package com.example.demo.inventory;

import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.*;
import static com.mongodb.client.model.Updates.*;

@Service
public class StockService {
    private final InventoryRepository store;
    private static final long MAX_STOCK = 1000000000000L;

    public StockService(InventoryRepository store) {
        this.store = store;
    }

    public Document apply(String type, String key, Requests.Stock input) {
        if (!List.of("RECEIPT", "ISSUE", "TRANSFER").contains(type)) throw new ApiException(400, "Unknown stock operation.");
        String requestKey = Checks.hash(Checks.text(key, "Idempotency-Key", 128));
        String productId = Checks.text(input.productId(), "Product", 80);
        String warehouseId = Checks.text(input.warehouseId(), "Warehouse", 80);
        String destination = type.equals("TRANSFER") ? Checks.text(input.destinationWarehouseId(), "Destination warehouse", 80) : "";
        String note = Checks.optional(input.note(), 400);
        if (!type.equals("TRANSFER") && input.destinationWarehouseId() != null && !input.destinationWarehouseId().isBlank()) {
            throw new ApiException(400, "Only transfers accept a destination warehouse.");
        }
        if (warehouseId.equals(destination)) throw new ApiException(400, "Choose two different warehouses.");
        if (input.quantity() == null || input.quantity() < 1 || input.quantity() > 1000000000L) {
            throw new ApiException(400, "Quantity must be a whole number between 1 and 1000000000.");
        }
        long quantity = input.quantity();
        String fingerprint = Checks.hash(new Document("type", type).append("product", productId).append("warehouse", warehouseId)
                .append("destination", destination).append("quantity", quantity).append("note", note).toJson());
        Document previous = store.collection("movements").find(eq("requestKey", requestKey)).first();
        if (previous != null) return replay(previous, fingerprint);
        String movementId = store.nextId();
        try {
            return store.transaction(session -> {
                Document existing = store.collection("movements").find(session, eq("requestKey", requestKey)).first();
                if (existing != null) return replay(existing, fingerprint);
                Document product = store.active(session, "products", productId);
                Document warehouse = store.active(session, "warehouses", warehouseId);
                Document destinationWarehouse = destination.isEmpty() ? null : store.active(session, "warehouses", destination);
                if (type.equals("RECEIPT")) credit(session, productId, warehouseId, quantity);
                else debit(session, productId, warehouseId, quantity);
                if (type.equals("TRANSFER")) credit(session, productId, destination, quantity);
                Document movement = new Document("_id", movementId).append("type", type).append("productId", productId)
                        .append("productName", product.getString("name")).append("sku", product.getString("sku"))
                        .append("warehouseId", warehouseId).append("warehouseName", warehouse.getString("name"))
                        .append("destinationWarehouseId", destination)
                        .append("destinationWarehouseName", destinationWarehouse == null ? "" : destinationWarehouse.getString("name"))
                        .append("quantity", quantity).append("note", note).append("createdAt", new Date())
                        .append("requestKey", requestKey).append("fingerprint", fingerprint);
                store.collection("movements").insertOne(session, movement);
                return InventoryRepository.view(movement);
            });
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) {
                Document existing = store.collection("movements").find(eq("requestKey", requestKey)).first();
                if (existing != null) return replay(existing, fingerprint);
            }
            throw e;
        }
    }

    private Document replay(Document movement, String fingerprint) {
        if (!fingerprint.equals(movement.getString("fingerprint"))) throw new ApiException(409, "This Idempotency-Key was already used for different data.");
        return InventoryRepository.view(movement);
    }

    private void debit(ClientSession session, String productId, String warehouseId, long quantity) {
        Document result = store.collection("balances").findOneAndUpdate(session,
                and(eq("productId", productId), eq("warehouseId", warehouseId), gte("quantity", quantity)),
                combine(inc("quantity", -quantity), set("updatedAt", new Date())), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (result == null) throw new ApiException(409, "There is not enough stock in the source warehouse.");
    }

    private void credit(ClientSession session, String productId, String warehouseId, long quantity) {
        String balanceId = warehouseId + ":" + productId;
        Document balance = store.collection("balances").find(session, eq("_id", balanceId)).first();
        if (balance == null) {
            store.collection("balances").insertOne(session, new Document("_id", balanceId).append("productId", productId)
                    .append("warehouseId", warehouseId).append("quantity", quantity).append("updatedAt", new Date()));
        } else {
            long current = ((Number) balance.get("quantity")).longValue();
            if (current > MAX_STOCK - quantity) throw new ApiException(409, "The stock quantity exceeds the supported limit.");
            store.collection("balances").updateOne(session, eq("_id", balanceId), combine(inc("quantity", quantity), set("updatedAt", new Date())));
        }
    }

    private List<Bson> balancePipeline(String warehouseId, String productId, boolean low) {
        List<Bson> steps = new ArrayList<>();
        Document filter = new Document();
        if (warehouseId != null && !warehouseId.isBlank()) filter.put("warehouseId", warehouseId);
        if (productId != null && !productId.isBlank()) filter.put("productId", productId);
        steps.add(new Document("$match", filter));
        steps.add(new Document("$lookup", new Document("from", "products").append("localField", "productId").append("foreignField", "_id").append("as", "product")));
        steps.add(new Document("$unwind", "$product"));
        steps.add(new Document("$lookup", new Document("from", "warehouses").append("localField", "warehouseId").append("foreignField", "_id").append("as", "warehouse")));
        steps.add(new Document("$unwind", "$warehouse"));
        steps.add(new Document("$match", new Document("product.active", true).append("warehouse.active", true)));
        steps.add(new Document("$addFields", new Document("lowStock", new Document("$lte", List.of("$quantity", "$product.reorderLevel")))));
        if (low) steps.add(new Document("$match", new Document("lowStock", true)));
        return steps;
    }

    public Map<String, Object> balances(String warehouseId, String productId, boolean low, int page, int size) {
        Checks.page(page);
        Checks.size(size);
        List<Bson> steps = balancePipeline(warehouseId, productId, low);
        List<Bson> data = List.of(new Document("$sort", new Document("product.name", 1).append("warehouse.name", 1).append("_id", 1)),
                new Document("$skip", page * size), new Document("$limit", size),
                new Document("$project", new Document("productId", 1).append("warehouseId", 1).append("quantity", 1).append("lowStock", 1)
                        .append("productName", "$product.name").append("sku", "$product.sku").append("category", "$product.category")
                        .append("reorderLevel", "$product.reorderLevel").append("warehouseName", "$warehouse.name")));
        steps.add(new Document("$facet", new Document("items", data).append("count", List.of(new Document("$count", "total")))));
        Document result = store.collection("balances").aggregate(steps).first();
        List<Document> counts = result.getList("count", Document.class);
        return Map.of("items", result.getList("items", Document.class).stream().map(InventoryRepository::view).toList(),
                "page", page, "size", size, "total", counts.isEmpty() ? 0 : ((Number) counts.get(0).get("total")).longValue());
    }

    public Map<String, Object> movements(String warehouseId, int page, int size) {
        Bson filter = warehouseId == null || warehouseId.isBlank() ? new Document() : or(eq("warehouseId", warehouseId), eq("destinationWarehouseId", warehouseId));
        return store.page("movements", filter, orderBy(descending("createdAt"), ascending("_id")), page, size);
    }

    public Map<String, Object> dashboard() {
        List<Bson> steps = balancePipeline(null, null, false);
        steps.add(new Document("$group", new Document("_id", null).append("units", new Document("$sum", "$quantity"))
                .append("lowStock", new Document("$sum", new Document("$cond", List.of("$lowStock", 1, 0))))));
        Document counts = store.collection("balances").aggregate(steps).first();
        return Map.of("products", store.collection("products").countDocuments(eq("active", true)),
                "warehouses", store.collection("warehouses").countDocuments(eq("active", true)),
                "units", counts == null ? 0 : counts.get("units"), "lowStock", counts == null ? 0 : counts.get("lowStock"),
                "recentMovements", movements(null, 0, 6).get("items"));
    }
}
