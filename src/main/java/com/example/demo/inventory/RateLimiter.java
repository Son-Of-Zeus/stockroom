package com.example.demo.inventory;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class RateLimiter {
    private final RedisClient redis;
    private final Settings settings;
    private final String script;

    public RateLimiter(RedisClient redis, Settings settings) throws IOException {
        this.redis = redis;
        this.settings = settings;
        try (var input = new ClassPathResource("redis/token-bucket.lua").getInputStream()) {
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public Decision acquire(String client) {
        List<?> result = (List<?>) redis.command(settings.rateRedis, "EVAL", script, "1", "bucket:" + Checks.hash(client),
                Integer.toString(settings.capacity), Double.toString(settings.refill));
        return new Decision(((Number) result.get(0)).intValue() == 1, ((Number) result.get(1)).longValue(),
                ((Number) result.get(2)).longValue());
    }

    public record Decision(boolean allowed, long remaining, long retryMillis) {}
}
