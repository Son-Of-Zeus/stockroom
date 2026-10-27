package com.example.demo.inventory;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoreTests {
    @Test
    void warehouseLabelsProduceStableSha256ShortCodes() {
        String original = ShareService.originalUrl("https://example.com", "42", "North manager");
        assertEquals("https://example.com/shared.html#warehouse/42/warehouse_link_for_North%20manager", original);
        assertEquals("GeQj7fGX_G75_tGV8wnXaP", ShareService.shortCode(original));
        assertNotEquals(ShareService.shortCode(original), ShareService.shortCode(
                ShareService.originalUrl("https://example.com", "43", "North manager")));
        String encoded = ShareService.originalUrl("https://example.com", "42", "A/B #+% தமிழ்");
        assertTrue(encoded.contains("A%2FB%20%23%2B%25%20"));
        assertDoesNotThrow(() -> java.net.URI.create(encoded));
    }

    @Test
    void stableRingIgnoresNodeOrder() {
        var first = new ConsistentHashRing(List.of("a:6379", "b:6379"));
        var second = new ConsistentHashRing(List.of("b:6379", "a:6379"));
        for (int i = 0; i < 1000; i++) assertEquals(first.node("key" + i), second.node("key" + i));
    }

    @Test
    void addingNodeOnlyMovesKeysToNewNode() {
        var oldRing = new ConsistentHashRing(List.of("a", "b"));
        var newRing = new ConsistentHashRing(List.of("a", "b", "c"));
        int moved = 0;
        for (int i = 0; i < 10000; i++) {
            String key = "key" + i;
            if (!oldRing.node(key).equals(newRing.node(key))) {
                moved++;
                assertEquals("c", newRing.node(key));
            }
        }
        assertTrue(moved > 2000 && moved < 4500);
    }

    @Test
    void removingNodeOnlyMovesItsKeys() {
        var oldRing = new ConsistentHashRing(List.of("a", "b", "c"));
        var newRing = new ConsistentHashRing(List.of("a", "c"));
        for (int i = 0; i < 1000; i++) {
            String key = "key" + i;
            if (!oldRing.node(key).equals("b")) assertEquals(oldRing.node(key), newRing.node(key));
        }
        assertThrows(IllegalArgumentException.class, () -> new ConsistentHashRing(List.of()));
    }

    @Test
    void ownerKeyAndOriginsAreRequired() {
        assertThrows(IllegalStateException.class, () -> new Settings(new MockEnvironment()));
        var environment = new MockEnvironment().withProperty("app.owner-key", "test-owner-key-at-least-24-characters");
        assertEquals(60, new Settings(environment).capacity);
        environment.withProperty("app.base-url", "https://example.com/path");
        assertThrows(IllegalStateException.class, () -> new Settings(environment));
    }

    @Test
    void invalidRateSettingsAreRejected() {
        var environment = new MockEnvironment().withProperty("app.owner-key", "test-owner-key-at-least-24-characters")
                .withProperty("app.rate-refill", "NaN");
        assertThrows(IllegalStateException.class, () -> new Settings(environment));
    }

    @Test
    void publicViewsRemoveSecretsWithoutChangingStorage() {
        var source = new Document("_id", "9007199254740993").append("codeHash", "secret").append("originalUrl", "private-url")
                .append("requestKey", "request").append("fingerprint", "body").append("stockRevision", 10)
                .append("createdAt", new Date(0));
        Document view = InventoryRepository.view(source);
        assertEquals("9007199254740993", view.getString("id"));
        assertEquals("1970-01-01T00:00:00Z", view.getString("createdAt"));
        assertFalse(view.containsKey("codeHash"));
        assertFalse(view.containsKey("originalUrl"));
        assertFalse(view.containsKey("requestKey"));
        assertFalse(view.containsKey("fingerprint"));
        assertTrue(source.containsKey("codeHash"));
    }

    @Test
    void validationBoundsAreEnforced() {
        assertThrows(ApiException.class, () -> Checks.text(" ", "Name", 40));
        assertThrows(ApiException.class, () -> Checks.size(101));
        assertThrows(ApiException.class, () -> Checks.page(-1));
        assertEquals("part", Checks.normalized(" PART "));
        assertEquals(64, Checks.hash("abc").length());
    }

    @Test
    void redisParserSupportsNestedValuesAndUtf8() throws Exception {
        String reply = "*4\r\n:1\r\n$4\r\ncafé\r\n$-1\r\n+OK\r\n";
        reply = reply.replace("$4", "$5");
        List<?> result = (List<?>) RedisClient.read(new ByteArrayInputStream(reply.getBytes(StandardCharsets.UTF_8)), 0);
        assertEquals(1L, result.get(0));
        assertEquals("café", result.get(1));
        assertNull(result.get(2));
        assertEquals("OK", result.get(3));
    }

    @Test
    void redisParserRejectsTruncatedAndOversizedReplies() {
        assertThrows(IOException.class, () -> RedisClient.read(new ByteArrayInputStream("$5\r\nab".getBytes()), 0));
        assertThrows(IOException.class, () -> RedisClient.read(new ByteArrayInputStream("$99999999\r\n".getBytes()), 0));
        assertThrows(IOException.class, () -> RedisClient.read(new ByteArrayInputStream("-ERR secret\r\n".getBytes()), 0));
    }

    @Test
    void bearerCredentialsAreNotReadFromQueryParameters() {
        var request = new MockHttpServletRequest();
        request.addParameter("token", "secret");
        assertEquals("", AccessFilter.bearer(request));
        request.addHeader("Authorization", "Bearer scoped-code");
        assertEquals("scoped-code", AccessFilter.bearer(request));
    }
}
