package com.example.demo.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INVENTORY_TEST_REDIS", matches = ".+")
class RedisTests {
    Settings settings(int capacity, double refill) {
        return new Settings(new MockEnvironment().withProperty("app.owner-key", "redis-test-owner-key-with-24-characters")
                .withProperty("app.redis-rate", System.getenv("INVENTORY_TEST_REDIS"))
                .withProperty("app.rate-capacity", Integer.toString(capacity)).withProperty("app.rate-refill", Double.toString(refill)));
    }

    @Test
    void burstRefillAndExpiryAreCorrect() throws Exception {
        RedisClient redis = new RedisClient();
        RateLimiter limiter = new RateLimiter(redis, settings(3, 2));
        String client = UUID.randomUUID().toString();
        assertTrue(limiter.acquire(client).allowed());
        assertTrue(limiter.acquire(client).allowed());
        assertTrue(limiter.acquire(client).allowed());
        var denied = limiter.acquire(client);
        assertFalse(denied.allowed());
        assertTrue(denied.retryMillis() > 0 && denied.retryMillis() <= 500);
        Thread.sleep(600);
        assertTrue(limiter.acquire(client).allowed());
        assertFalse(limiter.acquire(client).allowed());
        long ttl = (long) redis.command(System.getenv("INVENTORY_TEST_REDIS"), "PTTL", "bucket:" + Checks.hash(client));
        assertTrue(ttl > 0 && ttl <= 2500);
        Thread.sleep(1700);
        assertTrue(limiter.acquire(client).allowed());
        assertTrue(limiter.acquire(client).allowed());
        assertTrue(limiter.acquire(client).allowed());
        assertFalse(limiter.acquire(client).allowed());
    }

    @Test
    void concurrentLimitersShareOneAtomicBucket() throws Exception {
        RedisClient redis = new RedisClient();
        RateLimiter first = new RateLimiter(redis, settings(5, .001));
        RateLimiter second = new RateLimiter(redis, settings(5, .001));
        String client = UUID.randomUUID().toString();
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                RateLimiter limiter = i % 2 == 0 ? first : second;
                tasks.add(() -> limiter.acquire(client).allowed());
            }
            int allowed = 0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) allowed++;
            assertEquals(5, allowed);
        } finally {
            executor.shutdownNow();
        }
    }
}
