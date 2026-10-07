package ru.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogging extends OncePerRequestFilter {
    private final ObjectMapper json;
    public RequestLogging(ObjectMapper json) { this.json = json; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var req = new ContentCachingRequestWrapper(request, 65536);
        String id = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();
        long start = System.nanoTime();
        response.setHeader("X-Request-Id", id);
        try {
            chain.doFilter(req, response);
        } finally {
            var event = new LinkedHashMap<String, Object>();
            event.put("request_id", id);
            event.put("method", request.getMethod());
            event.put("endpoint", request.getRequestURI());
            event.put("status_code", response.getStatus());
            event.put("duration_ms", (System.nanoTime() - start) / 1_000_000.0);
            event.put("user_id", req.getAttribute("user_id"));
            event.put("timestamp", timestamp.toString());
            if (Set.of("POST", "PUT", "PATCH", "DELETE").contains(req.getMethod())) {
                try {
                    // Security may reject the request before MVC consumes the body.
                    if (req.getContentAsByteArray().length == 0) req.getInputStream().readNBytes(65536);
                    JsonNode body = json.readTree(req.getContentAsByteArray());
                    mask(body);
                    event.put("request_body", body);
                } catch (Exception e) {
                    event.put("request_body", "[unparseable or truncated body omitted]");
                }
            }
            LoggerFactory.getLogger("API_REQUESTS").info(json.writeValueAsString(event));
        }
    }

    private void mask(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.properties().forEach(entry -> {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (key.contains("password") || key.contains("token") || key.contains("secret") || key.equals("authorization"))
                    object.put(entry.getKey(), "***");
                else mask(entry.getValue());
            });
        } else if (node.isArray()) node.forEach(this::mask);
    }
}
