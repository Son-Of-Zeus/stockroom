package com.example.demo.inventory;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Component
public class Settings {
    final String ownerHash;
    final String instanceId;
    final String baseUrl;
    final String trustedProxy;
    final String rateRedis;
    final List<String> cacheNodes;
    final int capacity;
    final double refill;

    public Settings(Environment environment) {
        String key = environment.getProperty("app.owner-key", "");
        if (key.length() < 24) throw new IllegalStateException("Set OWNER_ACCESS_KEY to at least 24 characters. There is no default key.");
        ownerHash = Checks.hash(key);
        instanceId = environment.getProperty("app.instance-id", "local");
        baseUrl = environment.getProperty("app.base-url", "http://localhost:8080").replaceAll("/+$", "");
        URI uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalStateException("PUBLIC_BASE_URL must be an HTTP origin without a path or credentials.");
        trustedProxy = environment.getProperty("app.trusted-proxy", "");
        rateRedis = environment.getProperty("app.redis-rate", "127.0.0.1:6379");
        cacheNodes = Arrays.stream(environment.getProperty("app.redis-caches", "127.0.0.1:6380,127.0.0.1:6381").split(","))
                .map(String::strip).filter(value -> !value.isBlank()).distinct().sorted().toList();
        if (cacheNodes.isEmpty()) throw new IllegalStateException("At least one cache node is required.");
        capacity = environment.getProperty("app.rate-capacity", Integer.class, 60);
        refill = environment.getProperty("app.rate-refill", Double.class, 2.0);
        if (capacity < 1 || capacity > 100000 || !Double.isFinite(refill) || refill <= 0 || refill > 100000) {
            throw new IllegalStateException("Invalid token bucket capacity or refill rate.");
        }
    }
}
