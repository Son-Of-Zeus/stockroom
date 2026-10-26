package com.example.demo.inventory;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.*;
import static com.mongodb.client.model.Updates.*;

@Service
public class CatalogService {
    private final InventoryRepository store;

    public CatalogService(InventoryRepository store) {
        this.store = store;
    }

    public Map<String, Object> list(String type, String search, boolean archived, int page, int size) {
        String term = Checks.optional(search, 100);
        Bson filter = eq("active", !archived);
        if (!term.isBlank()) {
            Bson text = regex("name", Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE));
            filter = and(filter, type.equals("products") ? or(text, regex("sku", Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE))) : text);
        }
        return store.page(type, filter, orderBy(ascending("name"), ascending("_id")), page, size);
    }

    public Document product(String id, Requests.Product input) {
        String sku = Checks.text(input.sku(), "SKU", 64).toUpperCase(Locale.ROOT);
        String name = Checks.text(input.name(), "Name", 120);
        String category = Checks.text(input.category(), "Category", 80);
        String supplierId = Checks.text(input.supplierId(), "Supplier", 80);
        if (input.reorderLevel() == null || input.reorderLevel() < 0 || input.reorderLevel() > 1000000000L) {
            throw new ApiException(400, "Reorder level must be between 0 and 1000000000.");
        }
        return save("products", id, new Document("sku", sku).append("name", name).append("category", category)
                .append("supplierId", supplierId).append("reorderLevel", input.reorderLevel())
                .append("normalizedName", Checks.normalized(name)).append("normalizedSku", Checks.normalized(sku)), supplierId);
    }

    public Document warehouse(String id, Requests.Warehouse input) {
        return save("warehouses", id, new Document("code", Checks.text(input.code(), "Code", 40).toUpperCase(Locale.ROOT))
                .append("name", Checks.text(input.name(), "Name", 120))
                .append("location", Checks.text(input.location(), "Location", 200)), null);
    }

    public Document supplier(String id, Requests.Supplier input) {
        return save("suppliers", id, new Document("name", Checks.text(input.name(), "Name", 120))
                .append("email", Checks.optional(input.email(), 200)).append("phone", Checks.optional(input.phone(), 40)), null);
    }

    private Document save(String type, String id, Document fields, String supplierId) {
        String recordId = id == null ? store.nextId() : id;
        return store.transaction(session -> {
            if (supplierId != null) store.active(session, "suppliers", supplierId);
            if (id == null) {
                Document value = new Document(fields).append("_id", recordId).append("active", true)
                        .append("createdAt", new Date()).append("updatedAt", new Date());
                store.collection(type).insertOne(session, value);
                return InventoryRepository.view(value);
            }
            fields.put("updatedAt", new Date());
            List<Bson> changes = fields.entrySet().stream().map(entry -> set(entry.getKey(), entry.getValue())).toList();
            Document result = store.collection(type).findOneAndUpdate(session, and(eq("_id", id), eq("active", true)), combine(changes),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (result == null) throw new ApiException(404, "Active record was not found.");
            return InventoryRepository.view(result);
        });
    }

    public Document archive(String type, String id) {
        return store.transaction(session -> {
            Document record = store.collection(type).findOneAndUpdate(session, and(eq("_id", id), eq("active", true)),
                    combine(set("active", false), set("updatedAt", new Date())),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (record == null) throw new ApiException(404, "Active record was not found.");
            if (type.equals("suppliers")) {
                if (store.collection("products").countDocuments(session, and(eq("supplierId", id), eq("active", true))) > 0) {
                    throw new ApiException(409, "Archive or reassign this supplier's active products first.");
                }
            } else {
                String field = type.equals("products") ? "productId" : "warehouseId";
                if (store.collection("balances").countDocuments(session, and(eq(field, id), gt("quantity", 0L))) > 0) {
                    throw new ApiException(409, "Move or issue the remaining stock before archiving.");
                }
            }
            return InventoryRepository.view(record);
        });
    }

    public List<Document> suggestions(String prefix) {
        String value = Checks.normalized(Checks.text(prefix, "Search prefix", 80));
        if (value.length() < 2) return List.of();
        Pattern pattern = Pattern.compile("^" + Pattern.quote(value));
        return store.collection("products").find(and(eq("active", true), or(regex("normalizedName", pattern), regex("normalizedSku", pattern))))
                .sort(orderBy(ascending("normalizedName"), ascending("_id"))).limit(10)
                .map(row -> new Document("id", row.getString("_id")).append("name", row.getString("name")).append("sku", row.getString("sku")))
                .into(new ArrayList<>());
    }
}
