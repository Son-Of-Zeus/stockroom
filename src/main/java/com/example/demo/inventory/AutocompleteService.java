package com.example.demo.inventory;

import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

@Service
public class AutocompleteService {
    private final RedisClient redis;
    private final CatalogService catalog;
    private final ConsistentHashRing ring;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder failures = new LongAdder();

    public AutocompleteService(RedisClient redis, CatalogService catalog, Settings settings) {
        this.redis = redis;
        this.catalog = catalog;
        this.ring = new ConsistentHashRing(settings.cacheNodes);
    }

    public List<Document> search(String prefix) {
        String normalized = Checks.normalized(Checks.text(prefix, "Search prefix", 80));
        if (normalized.length() < 2) return List.of();
        String key = "autocomplete:v1:" + Checks.hash(normalized);
        String node = ring.node(key);
        try {
            String cached = (String) redis.command(node, "GET", key);
            if (cached != null) {
                hits.increment();
                return Document.parse(cached).getList("items", Document.class);
            }
        } catch (RuntimeException e) {
            failures.increment();
        }
        misses.increment();
        List<Document> result = catalog.suggestions(normalized);
        try {
            redis.command(node, "SET", key, new Document("items", result).toJson(), "EX", "15");
        } catch (RuntimeException e) {
            failures.increment();
        }
        return result;
    }

    public Map<String, Object> diagnostics() {
        return Map.of("hits", hits.sum(), "misses", misses.sum(), "failures", failures.sum(), "metricsScope", "current instance",
                "virtualNodesPerNode", 128, "ttlSeconds", 15,
                "routing", List.of("bearing", "cable", "motor", "valve", "sensor", "pump").stream()
                        .map(term -> Map.of("prefix", term, "node", ring.node("autocomplete:v1:" + Checks.hash(term)))).toList());
    }
}
