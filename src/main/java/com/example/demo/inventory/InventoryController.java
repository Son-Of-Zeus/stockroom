package com.example.demo.inventory;

import org.bson.Document;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class InventoryController {
    private final CatalogService catalog;
    private final StockService stock;
    private final ShareService shares;
    private final AutocompleteService autocomplete;
    private final InventoryRepository store;
    private final RedisClient redis;
    private final Settings settings;

    public InventoryController(CatalogService catalog, StockService stock, ShareService shares, AutocompleteService autocomplete,
                               InventoryRepository store, RedisClient redis, Settings settings) {
        this.catalog = catalog;
        this.stock = stock;
        this.shares = shares;
        this.autocomplete = autocomplete;
        this.store = store;
        this.redis = redis;
        this.settings = settings;
    }

    @GetMapping("/{type:products|warehouses|suppliers}")
    public Map<String, Object> list(@PathVariable String type, @RequestParam(defaultValue = "") String search,
                                   @RequestParam(defaultValue = "false") boolean archived, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return catalog.list(type, search, archived, page, size);
    }

    @PostMapping("/products")
    public Document product(@RequestBody Requests.Product input) { return catalog.product(null, input); }

    @PutMapping("/products/{id}")
    public Document product(@PathVariable String id, @RequestBody Requests.Product input) { return catalog.product(id, input); }

    @PostMapping("/warehouses")
    public Document warehouse(@RequestBody Requests.Warehouse input) { return catalog.warehouse(null, input); }

    @PutMapping("/warehouses/{id}")
    public Document warehouse(@PathVariable String id, @RequestBody Requests.Warehouse input) { return catalog.warehouse(id, input); }

    @PostMapping("/suppliers")
    public Document supplier(@RequestBody Requests.Supplier input) { return catalog.supplier(null, input); }

    @PutMapping("/suppliers/{id}")
    public Document supplier(@PathVariable String id, @RequestBody Requests.Supplier input) { return catalog.supplier(id, input); }

    @PostMapping("/{type:products|warehouses|suppliers}/{id}/archive")
    public Document archive(@PathVariable String type, @PathVariable String id) { return catalog.archive(type, id); }

    @PostMapping("/stock/{operation:receipts|issues|transfers}")
    public Document operation(@PathVariable String operation, @RequestHeader(name = "Idempotency-Key", defaultValue = "") String key,
                              @RequestBody Requests.Stock input) {
        String type = Map.of("receipts", "RECEIPT", "issues", "ISSUE", "transfers", "TRANSFER").get(operation);
        return stock.apply(type, key, input);
    }

    @GetMapping("/stock")
    public Map<String, Object> balances(@RequestParam(required = false) String warehouseId, @RequestParam(required = false) String productId,
                                       @RequestParam(defaultValue = "false") boolean low, @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return stock.balances(warehouseId, productId, low, page, size);
    }

    @GetMapping("/movements")
    public Map<String, Object> movements(@RequestParam(required = false) String warehouseId,
                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return stock.movements(warehouseId, page, size);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() { return stock.dashboard(); }

    @GetMapping("/autocomplete")
    public List<Document> autocomplete(@RequestParam String prefix) { return autocomplete.search(prefix); }

    @GetMapping("/warehouses/{warehouseId}/links")
    public Map<String, Object> links(@PathVariable String warehouseId, @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) { return shares.list(warehouseId, page, size); }

    @PostMapping("/warehouses/{warehouseId}/links")
    public Map<String, Object> create(@PathVariable String warehouseId, @RequestBody Requests.Link input) { return shares.create(warehouseId, input); }

    @DeleteMapping("/warehouses/{warehouseId}/links/{id}")
    public Map<String, Object> revoke(@PathVariable String warehouseId, @PathVariable String id) {
        shares.revoke(warehouseId, id);
        return Map.of("revoked", true);
    }

    @GetMapping("/system")
    public Map<String, Object> system() {
        return Map.of("instanceId", settings.instanceId, "mongo", store.healthy(),
                "rateLimiter", Map.of("algorithm", "token bucket", "capacity", settings.capacity, "refillPerSecond", settings.refill,
                        "healthy", redis.healthy(settings.rateRedis)),
                "cacheNodes", settings.cacheNodes.stream().map(node -> Map.of("address", node, "healthy", redis.healthy(node))).toList(),
                "cache", autocomplete.diagnostics(), "baseUrl", settings.baseUrl);
    }
}
