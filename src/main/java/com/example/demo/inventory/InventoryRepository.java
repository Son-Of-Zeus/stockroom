package com.example.demo.inventory;

import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReturnDocument;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Indexes.*;
import static com.mongodb.client.model.Updates.*;

@Repository
public class InventoryRepository {
    private final MongoDatabase database;
    private final MongoClient client;
    private static final TransactionOptions TRANSACTIONS = TransactionOptions.builder()
            .readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY)
            .readPreference(ReadPreference.primary()).timeout(10000L, TimeUnit.MILLISECONDS).build();

    public InventoryRepository(MongoTemplate template, MongoClient client) {
        this.database = template.getDb();
        this.client = client;
    }

    @PostConstruct
    void indexes() {
        collection("products").createIndex(ascending("sku"), new IndexOptions().unique(true));
        collection("products").createIndex(compoundIndex(ascending("active"), ascending("normalizedName")));
        collection("products").createIndex(compoundIndex(ascending("active"), ascending("normalizedSku")));
        collection("warehouses").createIndex(ascending("code"), new IndexOptions().unique(true));
        collection("warehouses").createIndex(compoundIndex(ascending("active"), ascending("name")));
        collection("suppliers").createIndex(compoundIndex(ascending("active"), ascending("name")));
        collection("balances").createIndex(compoundIndex(ascending("warehouseId"), ascending("productId")), new IndexOptions().unique(true));
        collection("balances").createIndex(ascending("productId"));
        collection("movements").createIndex(ascending("requestKey"), new IndexOptions().unique(true));
        collection("movements").createIndex(compoundIndex(descending("createdAt"), ascending("_id")));
        collection("movements").createIndex(compoundIndex(ascending("warehouseId"), descending("createdAt")));
        collection("movements").createIndex(compoundIndex(ascending("destinationWarehouseId"), descending("createdAt")));
        collection("links").createIndex(ascending("codeHash"), new IndexOptions().unique(true));
        collection("links").createIndex(compoundIndex(ascending("warehouseId"), descending("createdAt")));
        collection("counters").updateOne(eq("_id", "global"), setOnInsert("value", 0L), new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    MongoCollection<Document> collection(String name) {
        return database.getCollection(name);
    }

    public String nextId() {
        Document result = collection("counters").findOneAndUpdate(eq("_id", "global"), inc("value", 1L),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return Long.toString(((Number) result.get("value")).longValue());
    }

    public <T> T transaction(Function<ClientSession, T> operation) {
        try (ClientSession session = client.startSession()) {
            return session.withTransaction(() -> operation.apply(session), TRANSACTIONS);
        }
    }

    public Document get(String name, String id) {
        Document value = collection(name).find(eq("_id", id)).first();
        if (value == null) throw new ApiException(404, "Record was not found.");
        return value;
    }

    public Document active(String name, String id) {
        Document value = get(name, Checks.text(id, "Record ID", 80));
        if (!Boolean.TRUE.equals(value.getBoolean("active"))) throw new ApiException(409, "Record is archived.");
        return value;
    }

    public Document active(ClientSession session, String name, String id) {
        Document value = collection(name).findOneAndUpdate(session, and(eq("_id", id), eq("active", true)),
                inc("stockRevision", 1L), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (value == null) throw new ApiException(409, "A required record is missing or archived.");
        return value;
    }

    public Map<String, Object> page(String name, Bson filter, Bson sort, int page, int size) {
        Checks.page(page);
        Checks.size(size);
        List<Document> rows = collection(name).find(filter).sort(sort).skip(page * size).limit(size).into(new ArrayList<>());
        return Map.of("items", rows.stream().map(InventoryRepository::view).toList(),
                "page", page, "size", size, "total", collection(name).countDocuments(filter));
    }

    public static Document view(Document original) {
        Document result = new Document(original);
        Object id = result.remove("_id");
        if (id != null) result.put("id", id.toString());
        result.remove("codeHash");
        result.remove("requestKey");
        result.remove("fingerprint");
        result.remove("stockRevision");
        result.remove("normalizedName");
        result.remove("normalizedSku");
        result.replaceAll((key, value) -> value instanceof Date date ? date.toInstant().toString() : value);
        return result;
    }

    public boolean healthy() {
        try {
            database.runCommand(new Document("ping", 1));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
