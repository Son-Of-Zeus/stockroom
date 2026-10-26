package com.example.demo.inventory;

import org.bson.Document;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.*;
import static com.mongodb.client.model.Updates.*;

@Service
public class ShareService {
    private final InventoryRepository store;
    private final StockService stock;
    private final Settings settings;
    private final SecureRandom random = new SecureRandom();

    public ShareService(InventoryRepository store, StockService stock, Settings settings) {
        this.store = store;
        this.stock = stock;
        this.settings = settings;
    }

    public Map<String, Object> create(String warehouseId, Requests.Link input) {
        String label = Checks.text(input.label(), "Link label", 80);
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = store.nextId();
        Document link = store.transaction(session -> {
            store.active(session, "warehouses", warehouseId);
            Document value = new Document("_id", id).append("codeHash", Checks.hash(code)).append("warehouseId", warehouseId)
                    .append("label", label).append("active", true).append("createdAt", new Date());
            store.collection("links").insertOne(session, value);
            return InventoryRepository.view(value);
        });
        return Map.of("link", link, "url", settings.baseUrl + "/s/" + code);
    }

    public Map<String, Object> list(String warehouseId, int page, int size) {
        store.get("warehouses", warehouseId);
        return store.page("links", eq("warehouseId", warehouseId), orderBy(descending("createdAt"), ascending("_id")), page, size);
    }

    public void revoke(String warehouseId, String id) {
        var result = store.collection("links").updateOne(and(eq("_id", id), eq("warehouseId", warehouseId)),
                combine(set("active", false), set("revokedAt", new Date())));
        if (result.getMatchedCount() == 0) throw new ApiException(404, "Link was not found.");
    }

    public Document resolve(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{22}")) throw new ApiException(404, "This warehouse link is invalid or revoked.");
        Document link = store.collection("links").find(and(eq("codeHash", Checks.hash(code)), eq("active", true))).first();
        if (link == null) throw new ApiException(404, "This warehouse link is invalid or revoked.");
        Document warehouse = store.collection("warehouses").find(and(eq("_id", link.getString("warehouseId")), eq("active", true))).first();
        if (warehouse == null) throw new ApiException(404, "This warehouse link is invalid or revoked.");
        return InventoryRepository.view(warehouse);
    }

    public Map<String, Object> overview(String code) {
        Document warehouse = resolve(code);
        String id = warehouse.getString("id");
        return Map.of("warehouse", warehouse, "stock", stock.balances(id, null, false, 0, 20),
                "lowStock", stock.balances(id, null, true, 0, 20), "movements", sharedMovements(id, 0, 10));
    }

    public Map<String, Object> balances(String code, boolean low, int page, int size) {
        String id = resolve(code).getString("id");
        return stock.balances(id, null, low, page, size);
    }

    public Map<String, Object> movements(String code, int page, int size) {
        return sharedMovements(resolve(code).getString("id"), page, size);
    }

    private Map<String, Object> sharedMovements(String warehouseId, int page, int size) {
        Map<String, Object> result = stock.movements(warehouseId, page, size);
        List<Document> safe = new ArrayList<>();
        for (Object value : (List<?>) result.get("items")) {
            Document row = (Document) value;
            String type = row.getString("type");
            if (type.equals("TRANSFER")) type = warehouseId.equals(row.getString("warehouseId")) ? "TRANSFER_OUT" : "TRANSFER_IN";
            safe.add(new Document("id", row.getString("id")).append("type", type).append("productId", row.getString("productId"))
                    .append("productName", row.getString("productName")).append("sku", row.getString("sku"))
                    .append("quantity", row.get("quantity")).append("createdAt", row.get("createdAt")));
        }
        return Map.of("items", safe, "total", result.get("total"), "page", page, "size", size);
    }
}
