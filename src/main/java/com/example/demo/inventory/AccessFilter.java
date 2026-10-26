package com.example.demo.inventory;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessFilter extends OncePerRequestFilter {
    private final Settings settings;
    private final RateLimiter limiter;

    public AccessFilter(Settings settings, RateLimiter limiter) {
        this.settings = settings;
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        response.setHeader("X-Instance-Id", settings.instanceId);
        String path = request.getRequestURI();
        if (path.startsWith("/api/") || path.startsWith("/s/")) {
            try {
                String client = request.getRemoteAddr();
                if (!settings.trustedProxy.isBlank() && settings.trustedProxy.equals(client)) {
                    String forwarded = request.getHeader("X-Real-IP");
                    if (forwarded != null && forwarded.length() <= 64 && forwarded.matches("[0-9a-fA-F:.]+")) client = forwarded;
                }
                RateLimiter.Decision decision = limiter.acquire(client);
                response.setHeader("X-RateLimit-Limit", Integer.toString(settings.capacity));
                response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
                if (!decision.allowed()) {
                    response.setHeader("Retry-After", Long.toString(Math.max(1, (decision.retryMillis() + 999) / 1000)));
                    error(response, 429, "Request limit reached. Wait before trying again.");
                    return;
                }
            } catch (ApiException e) {
                error(response, 503, "Rate-limit service is unavailable.");
                return;
            }
        }
        if (path.startsWith("/api/shared/")) {
            if (!request.getMethod().equals("GET")) {
                error(response, 405, "Warehouse links provide read access only.");
                return;
            }
            if (!request.getParameterMap().keySet().stream().allMatch(key -> key.equals("page") || key.equals("size") || key.equals("low"))) {
                error(response, 400, "Unsupported warehouse view parameter.");
                return;
            }
        } else if (path.startsWith("/api/")) {
            String value = bearer(request);
            if (!MessageDigest.isEqual(Checks.hash(value).getBytes(StandardCharsets.US_ASCII), settings.ownerHash.getBytes(StandardCharsets.US_ASCII))) {
                error(response, 401, "A valid owner access key is required.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
    }

    private void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
