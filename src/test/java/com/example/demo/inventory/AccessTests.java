package com.example.demo.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AccessTests {
    static final String KEY = "owner-access-test-key-with-24-characters";

    Settings settings() {
        return new Settings(new MockEnvironment().withProperty("app.owner-key", KEY).withProperty("app.trusted-proxy", "172.30.78.10"));
    }

    AccessFilter filter(AtomicReference<String> client, RateLimiter.Decision decision) throws Exception {
        Settings configuration = settings();
        return new AccessFilter(configuration, new RateLimiter(new RedisClient(), configuration) {
            @Override
            public Decision acquire(String address) {
                client.set(address);
                return decision;
            }
        });
    }

    @Test
    void onlyTrustedProxyCanSupplyClientAddress() throws Exception {
        var address = new AtomicReference<String>();
        AccessFilter filter = filter(address, new RateLimiter.Decision(true, 59, 0));
        var request = new MockHttpServletRequest("GET", "/api/v1/dashboard");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", "192.0.2.44");
        request.addHeader("Authorization", "Bearer " + KEY);
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertEquals("127.0.0.1", address.get());
        assertNotNull(chain.getRequest());
        request = new MockHttpServletRequest("GET", "/api/v1/dashboard");
        request.setRemoteAddr("172.30.78.10");
        request.addHeader("X-Real-IP", "192.0.2.44");
        request.addHeader("Authorization", "Bearer " + KEY);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertEquals("192.0.2.44", address.get());
    }

    @Test
    void deniedBucketsIncludeRetryAfter() throws Exception {
        AccessFilter filter = filter(new AtomicReference<>(), new RateLimiter.Decision(false, 0, 1200));
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/dashboard"), response, new MockFilterChain());
        assertEquals(429, response.getStatus());
        assertEquals("2", response.getHeader("Retry-After"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void ownerCredentialsCannotBeOmittedOrReplacedByManagerCode() throws Exception {
        AccessFilter filter = filter(new AtomicReference<>(), new RateLimiter.Decision(true, 59, 0));
        var request = new MockHttpServletRequest("GET", "/api/v1/warehouses");
        request.addHeader("Authorization", "Bearer AAAAAAAAAAAAAAAAAAAAAA");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    @Test
    void sharedWritesAndScopeOverridesAreRejected() throws Exception {
        AccessFilter filter = filter(new AtomicReference<>(), new RateLimiter.Decision(true, 59, 0));
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/shared/stock"), response, new MockFilterChain());
        assertEquals(405, response.getStatus());
        var request = new MockHttpServletRequest("GET", "/api/shared/stock");
        request.addParameter("warehouseId", "another-warehouse");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(400, response.getStatus());
    }
}
