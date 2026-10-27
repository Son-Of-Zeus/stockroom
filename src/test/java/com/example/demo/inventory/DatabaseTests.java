package com.example.demo.inventory;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INVENTORY_TEST_MONGO_URI", matches = ".+")
class DatabaseTests {
    static MongoClient client;
    static InventoryRepository store;
    static CatalogService catalog;
    static StockService stock;
    static String database;

    @BeforeAll
    static void start() {
        database = "stockroom_test_" + UUID.randomUUID().toString().replace("-", "");
        client = MongoClients.create(System.getenv("INVENTORY_TEST_MONGO_URI"));
        store = new InventoryRepository(new MongoTemplate(client, database), client);
        store.indexes();
        catalog = new CatalogService(store);
        stock = new StockService(store);
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.getDatabase(database).drop();
            client.close();
        }
    }

    List<String> fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String supplier = catalog.supplier(null, new Requests.Supplier("Supplier " + suffix, "", "")).getString("id");
        String product = catalog.product(null, new Requests.Product("SKU-" + suffix, "Part " + suffix, "Parts", supplier, 3L)).getString("id");
        String source = catalog.warehouse(null, new Requests.Warehouse("A-" + suffix, "Source", "Local")).getString("id");
        String target = catalog.warehouse(null, new Requests.Warehouse("B-" + suffix, "Target", "Local")).getString("id");
        return List.of(product, source, target, supplier);
    }

    long quantity(String product, String warehouse) {
        Document row = store.collection("balances").find(and(eq("productId", product), eq("warehouseId", warehouse))).first();
        return row == null ? 0 : ((Number) row.get("quantity")).longValue();
    }

    void receive(List<String> ids, long quantity) {
        stock.apply("RECEIPT", UUID.randomUUID().toString(), new Requests.Stock(ids.get(0), ids.get(1), null, quantity, ""));
    }

    @Test
    void concurrentIssuesNeverOversell() throws Exception {
        var ids = fixture();
        receive(ids, 8);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) tasks.add(() -> {
                try {
                    stock.apply("ISSUE", UUID.randomUUID().toString(), new Requests.Stock(ids.get(0), ids.get(1), null, 1L, ""));
                    return true;
                } catch (ApiException error) {
                    assertEquals(409, error.status());
                    return false;
                }
            });
            int successes = 0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) successes++;
            assertEquals(8, successes);
            assertEquals(0, quantity(ids.get(0), ids.get(1)));
            assertEquals(9, store.collection("movements").countDocuments(eq("productId", ids.get(0))));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedDestinationCreditRollsBackDebitAndHistory() {
        var ids = fixture();
        receive(ids, 10);
        stock.apply("RECEIPT", UUID.randomUUID().toString(), new Requests.Stock(ids.get(0), ids.get(2), null, 1L, ""));
        store.collection("balances").updateOne(and(eq("productId", ids.get(0)), eq("warehouseId", ids.get(2))), set("quantity", 1000000000000L));
        long before = store.collection("movements").countDocuments(eq("productId", ids.get(0)));
        assertThrows(ApiException.class, () -> stock.apply("TRANSFER", UUID.randomUUID().toString(), new Requests.Stock(ids.get(0), ids.get(1), ids.get(2), 2L, "")));
        assertEquals(10, quantity(ids.get(0), ids.get(1)));
        assertEquals(1000000000000L, quantity(ids.get(0), ids.get(2)));
        assertEquals(before, store.collection("movements").countDocuments(eq("productId", ids.get(0))));
    }

    @Test
    void repeatedOperationsKeepOneMovement() throws Exception {
        var ids = fixture();
        String key = UUID.randomUUID().toString();
        var request = new Requests.Stock(ids.get(0), ids.get(1), null, 5L, "");
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Document>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(() -> stock.apply("RECEIPT", key, request));
            var results = executor.invokeAll(tasks);
            String id = results.get(0).get().getString("id");
            for (var result : results) assertEquals(id, result.get().getString("id"));
            assertEquals(5, quantity(ids.get(0), ids.get(1)));
            assertEquals(1, store.collection("movements").countDocuments(eq("productId", ids.get(0))));
            assertThrows(ApiException.class, () -> stock.apply("RECEIPT", key, new Requests.Stock(ids.get(0), ids.get(1), null, 6L, "")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void archivesRespectStockAndSupplierReferences() {
        var ids = fixture();
        receive(ids, 1);
        assertThrows(ApiException.class, () -> catalog.archive("products", ids.get(0)));
        assertThrows(ApiException.class, () -> catalog.archive("warehouses", ids.get(1)));
        assertThrows(ApiException.class, () -> catalog.archive("suppliers", ids.get(3)));
        stock.apply("ISSUE", UUID.randomUUID().toString(), new Requests.Stock(ids.get(0), ids.get(1), null, 1L, ""));
        assertFalse(catalog.archive("products", ids.get(0)).getBoolean("active"));
        assertFalse(catalog.archive("suppliers", ids.get(3)).getBoolean("active"));
        assertEquals(2, store.collection("movements").countDocuments(eq("productId", ids.get(0))));
    }

    @Test
    void idAllocationIsUniqueAcrossRepositories() throws Exception {
        InventoryRepository second = new InventoryRepository(new MongoTemplate(client, database), client);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                InventoryRepository repository = i % 2 == 0 ? store : second;
                tasks.add(repository::nextId);
            }
            var ids = new java.util.HashSet<String>();
            for (var result : executor.invokeAll(tasks)) ids.add(result.get());
            assertEquals(40, ids.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shareLinksReuseTheOriginalUrlAndKeepRevocation() throws Exception {
        var ids = fixture();
        var settings = new Settings(new MockEnvironment()
                .withProperty("app.owner-key", "test-owner-key-at-least-24-characters")
                .withProperty("app.base-url", "https://example.com"));
        var shares = new ShareService(store, stock, settings);
        var request = new Requests.Link("North / manager # தமிழ்");
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<java.util.Map<String, Object>>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(() -> shares.create(ids.get(1), request));
            var results = executor.invokeAll(tasks);
            var first = results.get(0).get();
            for (var result : results) assertEquals(first, result.get());
            assertEquals(1, store.collection("links").countDocuments(eq("warehouseId", ids.get(1))));
            String original = (String) first.get("originalUrl");
            String code = ((String) first.get("url")).substring("https://example.com/s/".length());
            String fragment = original.substring(original.indexOf('#') + 1);
            assertEquals(ids.get(1), shares.resolve(code).getString("id"));
            assertEquals(ids.get(1), shares.resolve(fragment).getString("id"));
            assertEquals(original, shares.destination(code));
            assertNotEquals(first.get("url"), shares.create(ids.get(2), request).get("url"));
            assertNotEquals(first.get("url"), shares.create(ids.get(1), new Requests.Link("Another manager")).get("url"));
            assertFalse(shares.list(ids.get(1), 0, 20).toString().contains("originalUrl"));
            shares.revoke(ids.get(1), ((Document) first.get("link")).getString("id"));
            assertEquals(404, assertThrows(ApiException.class, () -> shares.resolve(code)).status());
            assertEquals(404, assertThrows(ApiException.class, () -> shares.resolve(fragment)).status());
            assertEquals(409, assertThrows(ApiException.class, () -> shares.create(ids.get(1), request)).status());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void existingRandomLinksStillResolve() {
        var ids = fixture();
        String code = "A".repeat(22);
        store.collection("links").insertOne(new Document("_id", store.nextId())
                .append("warehouseId", ids.get(1)).append("codeHash", Checks.hash(code)).append("active", true));
        var settings = new Settings(new MockEnvironment().withProperty("app.owner-key", "test-owner-key-at-least-24-characters"));
        var shares = new ShareService(store, stock, settings);
        assertEquals(ids.get(1), shares.resolve(code).getString("id"));
        assertEquals("/shared.html#" + code, shares.destination(code));
    }

    @Test
    void lowStockAndIdsAreAccurate() {
        var ids = fixture();
        receive(ids, 2);
        var balances = stock.balances(ids.get(1), null, true, 0, 20);
        assertEquals(1L, balances.get("total"));
        assertTrue(Long.parseLong(store.nextId()) < Long.parseLong(store.nextId()));
        assertThrows(ApiException.class, () -> stock.balances(null, null, false, -1, 20));
    }
}
