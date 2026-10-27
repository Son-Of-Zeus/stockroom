package com.example.demo.inventory;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@RestController
public class SharedController {
    private final ShareService shares;
    private final InventoryRepository store;
    private final RedisClient redis;
    private final Settings settings;

    public SharedController(ShareService shares, InventoryRepository store, RedisClient redis, Settings settings) {
        this.shares = shares;
        this.store = store;
        this.redis = redis;
        this.settings = settings;
    }

    @GetMapping("/s/{code}")
    public ResponseEntity<Void> shortLink(@PathVariable String code) {
        return ResponseEntity.status(303).location(URI.create(shares.destination(code))).build();
    }

    @GetMapping("/api/shared/warehouse")
    public Map<String, Object> overview(HttpServletRequest request) { return shares.overview(AccessFilter.bearer(request)); }

    @GetMapping("/api/shared/stock")
    public Map<String, Object> stock(HttpServletRequest request, @RequestParam(defaultValue = "false") boolean low,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return shares.balances(AccessFilter.bearer(request), low, page, size);
    }

    @GetMapping("/api/shared/movements")
    public Map<String, Object> movements(HttpServletRequest request, @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return shares.movements(AccessFilter.bearer(request), page, size);
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        boolean healthy = store.healthy() && redis.healthy(settings.rateRedis);
        return ResponseEntity.status(healthy ? 200 : 503).body(Map.of("status", healthy ? "UP" : "DOWN"));
    }
}
