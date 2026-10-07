package ru.marketplace;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "debug=false")
class MarketplaceTest {
    static final String SECRET = "test-secret-with-at-least-thirty-two-bytes-long";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("marketplace.jwt-secret", () -> SECRET);
        r.add("marketplace.order-interval", () -> "PT1M");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder passwords;
    @Autowired Tokens tokens;
    final HttpClient http = HttpClient.newHttpClient();
    String user, other, seller, seller2, admin;
    UUID userId;

    @BeforeEach void reset() {
        db.execute("TRUNCATE refresh_tokens,user_operations,order_items,orders,promo_codes,products,users CASCADE");
        userId = UUID.randomUUID();
        user = identity(userId, "USER"); other = identity(UUID.randomUUID(), "USER");
        seller = identity(UUID.randomUUID(), "SELLER"); seller2 = identity(UUID.randomUUID(), "SELLER");
        admin = identity(UUID.randomUUID(), "ADMIN");
    }

    String identity(UUID id, String role) {
        db.update("INSERT INTO users(id,email,password_hash,role) VALUES (?,?,?,?::user_role)", id, id + "@test.local", "unused", role);
        return tokens.issue(id, role).getAccessToken();
    }

    HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(int status, String method, String path, Object body, String token) throws Exception {
        var response = call(method, path, body, token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        UUID.fromString(response.headers().firstValue("X-Request-Id").orElseThrow());
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }

    JsonNode error(int status, String code, String method, String path, Object body, String token) throws Exception {
        JsonNode node = ok(status, method, path, body, token);
        assertThat(node.path("error_code").asText()).isEqualTo(code);
        assertThat(node.path("message").asText()).isNotBlank();
        return node;
    }

    Map<String, Object> productBody(int stock, String status) {
        return new LinkedHashMap<>(Map.of("name", "Keyboard", "price", 100, "stock", stock, "category", "electronics", "status", status));
    }
    String product(int stock) throws Exception {
        return ok(201, "POST", "/products", productBody(stock, "ACTIVE"), seller).path("id").asText();
    }
    Map<String, Object> item(String id, int qty) { return Map.of("product_id", id, "quantity", qty); }
    Map<String, Object> order(String id, int qty) { return Map.of("items", List.of(item(id, qty))); }
    String createOrder(String product, int quantity, String token) throws Exception {
        return ok(201, "POST", "/orders", order(product, quantity), token).path("id").asText();
    }
    int stock(String id) { return db.queryForObject("SELECT stock FROM products WHERE id=?::uuid", Integer.class, id); }
    long count(String table) { return db.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    void age() { db.update("UPDATE user_operations SET created_at=created_at-interval '2 minutes'"); }
    Map<String, Object> promoBody(String code, String type, int value, int minimum, int uses) {
        return new LinkedHashMap<>(Map.of("code", code, "discount_type", type, "discount_value", value,
                "min_order_amount", minimum, "max_uses", uses, "valid_from", Instant.now().minusSeconds(60).toString(),
                "valid_until", Instant.now().plusSeconds(3600).toString(), "active", true));
    }
    void promo(String code, String type, int value, int minimum, int uses) throws Exception {
        ok(201, "POST", "/promo-codes", promoBody(code, type, value, minimum, uses), seller);
    }
    Map<String, Object> discounted(String id, int qty, String code) { return Map.of("items", List.of(item(id, qty)), "promo_code", code); }
    int uses(String code) { return db.queryForObject("SELECT current_uses FROM promo_codes WHERE code=?", Integer.class, code); }

    @Test void productCrudPaginationFiltersAndTimestamps() throws Exception {
        String id = product(10);
        JsonNode initial = ok(200, "GET", "/products/" + id, null, user);
        assertThat(initial.path("seller_id").asText()).isNotBlank();
        var body = productBody(7, "INACTIVE"); body.put("description", "Updated");
        var updated = ok(200, "PUT", "/products/" + id, body, seller);
        assertThat(updated.path("created_at")).isEqualTo(initial.path("created_at"));
        assertThat(updated.path("updated_at").asText()).isNotEqualTo(initial.path("updated_at").asText());
        product(2);
        var page = ok(200, "GET", "/products?status=INACTIVE&category=electronics&page=0&size=1", null, user);
        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        assertThat(page.path("content").size()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(1);
        assertThat(ok(200, "GET", "/products?category=Electronic", null, seller).path("totalElements").asInt()).isZero();
        assertThat(ok(200, "GET", "/products?page=99", null, user).path("content").size()).isZero();
        ok(204, "DELETE", "/products/" + id, null, seller);
        assertThat(count("products")).isEqualTo(2);
        assertThat(ok(200, "GET", "/products/" + id, null, user).path("status").asText()).isEqualTo("ARCHIVED");
        assertThat(db.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename='products' AND indexdef LIKE '%(status)%'", Integer.class)).isEqualTo(1);
    }

    @Test void validationRejectsInputsBeforeDatabaseMutation() throws Exception {
        for (var bad : List.of(Map.entry("name", ""), Map.entry("name", "x".repeat(256)), Map.entry("description", "x".repeat(4001)),
                Map.entry("price", 0), Map.entry("price", -1), Map.entry("price", 1.001), Map.entry("price", "100"),
                Map.entry("stock", -1), Map.entry("stock", 1.5), Map.entry("category", ""), Map.entry("category", "x".repeat(101)),
                Map.entry("status", "INVALID"), Map.entry("unexpected", true))) {
            var body = productBody(10, "ACTIVE"); body.put(bad.getKey(), bad.getValue());
            var result = error(400, "VALIDATION_ERROR", "POST", "/products", body, seller);
            assertThat(result.path("details").path("fields").size()).isGreaterThan(0);
        }
        var missing = productBody(10, "ACTIVE"); missing.remove("name");
        error(400, "VALIDATION_ERROR", "POST", "/products", missing, seller);
        assertThat(count("products")).isZero();
        for (String query : List.of("page=-1", "size=0", "size=101", "status=UNKNOWN", "category="))
            error(400, "VALIDATION_ERROR", "GET", "/products?" + query, null, user);
        error(400, "VALIDATION_ERROR", "GET", "/products/not-a-uuid", null, user);
        String id = product(10);
        for (Object body : List.of(Map.of("items", List.of()), Map.of("items", Collections.nCopies(51, item(id, 1))),
                order(id, 0), order(id, 1000), Map.of("items", List.of(item(id, 1), item(id, 1))),
                Map.of("items", Collections.singletonList(null)), discounted(id, 1, "bad")))
            error(400, "VALIDATION_ERROR", "POST", "/orders", body, user);
        assertThat(count("orders")).isZero(); assertThat(stock(id)).isEqualTo(10);
    }

    @Test void authRegisterLoginRefreshRotationAndTokenTypes() throws Exception {
        var credentials = Map.of("email", "Alice@example.com", "password", "Strong-password-123");
        var registered = ok(201, "POST", "/auth/register", credentials, null);
        assertThat(registered.path("role").asText()).isEqualTo("USER");
        assertThat(registered.has("password")).isFalse();
        String hash = db.queryForObject("SELECT password_hash FROM users WHERE email='alice@example.com'", String.class);
        assertThat(hash).startsWith("$2");
        error(409, "EMAIL_ALREADY_EXISTS", "POST", "/auth/register", credentials, null);
        error(400, "VALIDATION_ERROR", "POST", "/auth/register", Map.of("email", "admin@t.local", "password", "password123", "role", "ADMIN"), null);
        var pair = ok(200, "POST", "/auth/login", credentials, null);
        assertThat(pair.path("expires_in").asInt()).isEqualTo(1200);
        String access = pair.path("access_token").asText(), refresh = pair.path("refresh_token").asText();
        ok(200, "GET", "/products", null, access);
        error(401, "TOKEN_INVALID", "GET", "/products", null, refresh);
        error(401, "REFRESH_TOKEN_INVALID", "POST", "/auth/refresh", Map.of("refresh_token", access), null);
        var rotated = ok(200, "POST", "/auth/refresh", Map.of("refresh_token", refresh), null);
        assertThat(rotated.path("refresh_token").asText()).isNotEqualTo(refresh);
        error(401, "REFRESH_TOKEN_INVALID", "POST", "/auth/refresh", Map.of("refresh_token", refresh), null);
        error(401, "REFRESH_TOKEN_INVALID", "POST", "/auth/refresh", Map.of("refresh_token", "garbage"), null);
        error(401, "INVALID_CREDENTIALS", "POST", "/auth/login", Map.of("email", "alice@example.com", "password", "wrongpassword"), null);
        error(401, "TOKEN_INVALID", "GET", "/products", null, null);
        error(401, "TOKEN_INVALID", "GET", "/products", null, "tampered");
        String expired = Jwts.builder().issuer("marketplace").subject(userId.toString()).id(UUID.randomUUID().toString())
                .claim("role", "USER").claim("type", "access").issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60))).signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        error(401, "TOKEN_EXPIRED", "GET", "/products", null, expired);
        error(401, "REFRESH_TOKEN_INVALID", "POST", "/auth/refresh", Map.of("refresh_token", expired), null);
    }

    @Test void rolesAndOwnership() throws Exception {
        String id = product(10);
        error(403, "ACCESS_DENIED", "POST", "/products", productBody(1, "ACTIVE"), user);
        for (String token : List.of(user, seller2)) {
            error(403, "ACCESS_DENIED", "PUT", "/products/" + id, productBody(1, "ACTIVE"), token);
            error(403, "ACCESS_DENIED", "DELETE", "/products/" + id, null, token);
        }
        ok(200, "PUT", "/products/" + id, productBody(10, "ACTIVE"), admin);
        String order = createOrder(id, 1, user);
        error(403, "ACCESS_DENIED", "POST", "/orders", order(id, 1), seller);
        for (String method : List.of("GET", "PUT", "POST")) {
            String path = "/orders/" + order + (method.equals("POST") ? "/cancel" : "");
            Object body = method.equals("PUT") ? order(id, 1) : null;
            error(403, "ACCESS_DENIED", method, path, body, seller);
            error(403, "ORDER_OWNERSHIP_VIOLATION", method, path, body, other);
        }
        ok(200, "GET", "/orders/" + order, null, admin);
        ok(200, "PUT", "/orders/" + order, order(id, 2), admin);
        error(403, "ACCESS_DENIED", "POST", "/promo-codes", promoBody("SALE", "PERCENTAGE", 10, 0, 10), user);
        ok(200, "POST", "/orders/" + order + "/cancel", null, admin);
        ok(204, "DELETE", "/products/" + id, null, admin);
    }

    @Test void ratePrecedesActiveOrderAndOnlySuccessfulOperationsCount() throws Exception {
        String id = product(10);
        error(409, "INSUFFICIENT_STOCK", "POST", "/orders", order(id, 11), user);
        assertThat(count("user_operations")).isZero();
        String order = createOrder(id, 1, user);
        error(429, "ORDER_LIMIT_EXCEEDED", "POST", "/orders", order(id, 1), user);
        age();
        error(409, "ORDER_HAS_ACTIVE", "POST", "/orders", order(id, 1), user);
        ok(200, "PUT", "/orders/" + order, order(id, 2), user);
        error(429, "ORDER_LIMIT_EXCEEDED", "PUT", "/orders/" + order, order(id, 3), user);
        assertThat(stock(id)).isEqualTo(8);
        assertThat(count("user_operations")).isEqualTo(2);
    }

    @Test void catalogChecksPrecedeAllStockChecks() throws Exception {
        String id = product(0);
        String inactive = ok(201, "POST", "/products", productBody(10, "INACTIVE"), seller).path("id").asText();
        error(409, "PRODUCT_INACTIVE", "POST", "/orders", Map.of("items", List.of(item(id, 1), item(inactive, 1))), user);
        error(404, "PRODUCT_NOT_FOUND", "POST", "/orders", Map.of("items", List.of(item(id, 1), item(UUID.randomUUID().toString(), 1))), user);
        String second = product(0);
        var error = error(409, "INSUFFICIENT_STOCK", "POST", "/orders", Map.of("items", List.of(item(id, 2), item(second, 3))), user);
        assertThat(error.path("details").path("products").size()).isEqualTo(2);
        assertThat(count("orders")).isZero(); assertThat(count("user_operations")).isZero();
        error(404, "PRODUCT_NOT_FOUND", "GET", "/products/" + UUID.randomUUID(), null, user);
        error(404, "ORDER_NOT_FOUND", "GET", "/orders/" + UUID.randomUUID(), null, user);
    }

    @Test void failedCreateAndUpdateRollBackEveryWrite() throws Exception {
        String id = product(10), second = product(1);
        error(422, "PROMO_CODE_INVALID", "POST", "/orders", discounted(id, 2, "NOPE"), user);
        assertThat(stock(id)).isEqualTo(10); assertThat(count("orders")).isZero();
        String order = createOrder(id, 3, user);
        var before = ok(200, "GET", "/orders/" + order, null, user);
        error(409, "INSUFFICIENT_STOCK", "PUT", "/orders/" + order,
                Map.of("items", List.of(item(id, 1), item(second, 2))), user);
        assertThat(stock(id)).isEqualTo(7); assertThat(stock(second)).isEqualTo(1);
        assertThat(ok(200, "GET", "/orders/" + order, null, user)).isEqualTo(before);
        assertThat(count("user_operations")).isEqualTo(1);
        ok(200, "PUT", "/orders/" + order, order(second, 1), user);
        assertThat(stock(id)).isEqualTo(10); assertThat(stock(second)).isZero();
    }

    @Test void priceSnapshotSurvivesCatalogChangeAndOrderUpdate() throws Exception {
        String id = product(10), order = createOrder(id, 2, user);
        var body = productBody(8, "ACTIVE"); body.put("price", 900);
        ok(200, "PUT", "/products/" + id, body, seller);
        var existing = ok(200, "GET", "/orders/" + order, null, user);
        assertThat(existing.path("total_amount").decimalValue()).isEqualByComparingTo("200");
        var changed = ok(200, "PUT", "/orders/" + order, order(id, 3), user);
        assertThat(changed.path("total_amount").decimalValue()).isEqualByComparingTo("300");
        assertThat(changed.path("items").get(0).path("price_at_order").decimalValue()).isEqualByComparingTo("100");
        assertThat(stock(id)).isEqualTo(7);
    }

    @Test void promoMinimumRemovalAndCancelReleaseExactlyOnce() throws Exception {
        String id = product(10); promo("SALE", "PERCENTAGE", 20, 200, 1);
        error(422, "PROMO_CODE_MIN_AMOUNT", "POST", "/orders", discounted(id, 1, "SALE"), user);
        assertThat(stock(id)).isEqualTo(10); assertThat(uses("SALE")).isZero();
        var created = ok(201, "POST", "/orders", discounted(id, 3, "SALE"), user);
        assertThat(created.path("total_amount").decimalValue()).isEqualByComparingTo("240");
        String oid = created.path("id").asText();
        var kept = ok(200, "PUT", "/orders/" + oid, order(id, 2), user);
        assertThat(kept.path("discount_amount").decimalValue()).isEqualByComparingTo("40");
        assertThat(uses("SALE")).isEqualTo(1);
        age();
        var removed = ok(200, "PUT", "/orders/" + oid, order(id, 1), user);
        assertThat(removed.path("promo_code_id").isNull()).isTrue();
        assertThat(removed.path("discount_amount").decimalValue()).isZero();
        assertThat(uses("SALE")).isZero();
        ok(200, "POST", "/orders/" + oid + "/cancel", null, user);
        error(409, "INVALID_STATE_TRANSITION", "POST", "/orders/" + oid + "/cancel", null, user);
        assertThat(stock(id)).isEqualTo(10); assertThat(uses("SALE")).isZero();
    }

    @Test void promoFixedCapPercentageRuleAndRounding() throws Exception {
        String id = product(10);
        for (var example : List.of(new String[]{"FIXED", "FIXED_AMOUNT", "500", "0"}, new String[]{"HUGE", "PERCENTAGE", "71", "100"}, new String[]{"MAXI", "PERCENTAGE", "70", "30"})) {
            promo(example[0], example[1], Integer.parseInt(example[2]), 0, 1);
            var created = ok(201, "POST", "/orders", discounted(id, 1, example[0]), user);
            assertThat(created.path("total_amount").decimalValue()).isEqualByComparingTo(example[3]);
            ok(200, "POST", "/orders/" + created.path("id").asText() + "/cancel", null, user);
            assertThat(uses(example[0])).isZero(); age();
        }
        var body = productBody(10, "ACTIVE"); body.put("price", 0.05);
        ok(200, "PUT", "/products/" + id, body, seller);
        promo("ROUND", "PERCENTAGE", 10, 0, 1);
        var rounded = ok(201, "POST", "/orders", discounted(id, 1, "ROUND"), user);
        assertThat(rounded.path("discount_amount").decimalValue()).isEqualByComparingTo("0.01");
    }

    @Test void expiredInactiveFutureAndExhaustedPromosAreRejectedAndUpdateRollsBack() throws Exception {
        String id = product(10); promo("SALE", "PERCENTAGE", 10, 0, 1);
        var created = ok(201, "POST", "/orders", discounted(id, 2, "SALE"), user);
        error(422, "PROMO_CODE_INVALID", "POST", "/orders", discounted(id, 1, "SALE"), other);
        db.update("UPDATE promo_codes SET valid_from=now()-interval '2 hours', valid_until=now()-interval '1 hour' WHERE code='SALE'");
        error(422, "PROMO_CODE_INVALID", "PUT", "/orders/" + created.path("id").asText(), order(id, 3), user);
        assertThat(stock(id)).isEqualTo(8); assertThat(uses("SALE")).isEqualTo(1);
        ok(200, "POST", "/orders/" + created.path("id").asText() + "/cancel", null, user);
        assertThat(uses("SALE")).isZero(); assertThat(stock(id)).isEqualTo(10);
        error(422, "PROMO_CODE_INVALID", "POST", "/orders", discounted(id, 1, "SALE"), other);
        db.update("UPDATE promo_codes SET valid_from=now()+interval '1 hour', valid_until=now()+interval '2 hours'");
        error(422, "PROMO_CODE_INVALID", "POST", "/orders", discounted(id, 1, "SALE"), other);
        db.update("UPDATE promo_codes SET valid_from=now()-interval '1 hour',active=false");
        error(422, "PROMO_CODE_INVALID", "POST", "/orders", discounted(id, 1, "SALE"), other);
    }

    @Test void stateMachineAndPendingCancellation() throws Exception {
        String id = product(10), oid = createOrder(id, 2, user);
        error(403, "ACCESS_DENIED", "PATCH", "/orders/" + oid + "/status", Map.of("status", "PAID"), user);
        error(409, "INVALID_STATE_TRANSITION", "PATCH", "/orders/" + oid + "/status", Map.of("status", "COMPLETED"), admin);
        ok(200, "PATCH", "/orders/" + oid + "/status", Map.of("status", "PAYMENT_PENDING"), admin);
        error(409, "INVALID_STATE_TRANSITION", "PUT", "/orders/" + oid, order(id, 1), user);
        ok(200, "POST", "/orders/" + oid + "/cancel", null, user);
        assertThat(stock(id)).isEqualTo(10); age();
        oid = createOrder(id, 1, user);
        for (String status : List.of("PAYMENT_PENDING", "PAID", "SHIPPED", "COMPLETED")) {
            ok(200, "PATCH", "/orders/" + oid + "/status", Map.of("status", status), admin);
            if (!status.equals("PAYMENT_PENDING")) error(409, "INVALID_STATE_TRANSITION", "POST", "/orders/" + oid + "/cancel", null, user);
        }
        error(409, "INVALID_STATE_TRANSITION", "PATCH", "/orders/" + oid + "/status", Map.of("status", "CREATED"), admin);
        assertThat(stock(id)).isEqualTo(9);
    }

    @Test void concurrentRequestsCannotOversellOrOverusePromo() throws Exception {
        String id = product(1);
        String firstProduct = id;
        var responses = race(() -> call("POST", "/orders", order(firstProduct, 1), user), () -> call("POST", "/orders", order(firstProduct, 1), other));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 409);
        assertThat(stock(id)).isZero(); assertThat(count("orders")).isEqualTo(1);
        reset(); id = product(10); promo("LAST", "PERCENTAGE", 10, 0, 1);
        String pid = id;
        responses = race(() -> call("POST", "/orders", discounted(pid, 1, "LAST"), user), () -> call("POST", "/orders", discounted(pid, 1, "LAST"), other));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 422);
        assertThat(uses("LAST")).isEqualTo(1); assertThat(stock(id)).isEqualTo(9);
    }

    @Test void concurrentSameUserAndDoubleCancellationAreSerialized() throws Exception {
        String id = product(10);
        var responses = race(() -> call("POST", "/orders", order(id, 1), user), () -> call("POST", "/orders", order(id, 1), user));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 429);
        String oid = db.queryForObject("SELECT id::text FROM orders", String.class);
        responses = race(() -> call("POST", "/orders/" + oid + "/cancel", null, user), () -> call("POST", "/orders/" + oid + "/cancel", null, user));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 409);
        assertThat(stock(id)).isEqualTo(10);
    }

    @Test void jsonLogsContainRequestMetadataAndMaskCredentials() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger("API_REQUESTS");
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            ok(201, "POST", "/auth/register", Map.of("email", "log@test.local", "password", "super-secret-password"), null);
            ok(200, "GET", "/products", null, user);
            // The response can reach the client just before the outer filter emits its log.
            for (int i = 0; i < 100 && appender.list.size() < 2; i++) Thread.sleep(10);
            assertThat(appender.list).hasSize(2);
            var first = json.readTree(appender.list.getFirst().getFormattedMessage());
            assertThat(first.path("request_body").path("password").asText()).isEqualTo("***");
            assertThat(first.path("user_id").isNull()).isTrue();
            var second = json.readTree(appender.list.getLast().getFormattedMessage());
            for (String key : List.of("request_id", "method", "endpoint", "status_code", "duration_ms", "user_id", "timestamp"))
                assertThat(second.has(key)).isTrue();
            assertThat(second.path("user_id").asText()).isEqualTo(userId.toString());
            assertThat(second.path("status_code").asInt()).isEqualTo(200);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void optionalDefaultsRejectExplicitNullAndPromoDatesAreValidated() throws Exception {
        var registration = new LinkedHashMap<String, Object>(Map.of("email", "null@test.local", "password", "password123"));
        registration.put("role", null);
        error(400, "VALIDATION_ERROR", "POST", "/auth/register", registration, null);
        var promo = promoBody("SALE", "PERCENTAGE", 10, 0, 1);
        promo.put("active", null);
        error(400, "VALIDATION_ERROR", "POST", "/promo-codes", promo, seller);
        promo.put("active", true); promo.put("valid_until", promo.get("valid_from"));
        error(400, "VALIDATION_ERROR", "POST", "/promo-codes", promo, seller);
        promo.put("valid_until", Instant.now().plusSeconds(60).toString());
        promo.remove("active");
        assertThat(ok(201, "POST", "/promo-codes", promo, seller).path("active").asBoolean()).isTrue();
        error(409, "PROMO_CODE_EXISTS", "POST", "/promo-codes", promo, seller);
    }

    @Test void acceptedBoundaryValuesAndFiftyItems() throws Exception {
        var body = productBody(999, "ACTIVE");
        body.put("name", "n".repeat(255)); body.put("description", "d".repeat(4000));
        body.put("category", "c".repeat(100)); body.put("price", 0.01);
        String id = ok(201, "POST", "/products", body, seller).path("id").asText();
        String oid = createOrder(id, 999, user);
        assertThat(stock(id)).isZero();
        assertThat(ok(200, "GET", "/orders/" + oid, null, user).path("total_amount").decimalValue()).isEqualByComparingTo("9.99");
        ok(200, "POST", "/orders/" + oid + "/cancel", null, user);
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 50; i++) items.add(item(product(1), 1));
        var order = ok(201, "POST", "/orders", Map.of("items", items), other);
        assertThat(order.path("items").size()).isEqualTo(50);
        assertThat(order.path("total_amount").decimalValue()).isEqualByComparingTo("5000");
    }

    @Test void concurrentRefreshCanBeConsumedOnlyOnce() throws Exception {
        String refresh = tokens.issue(userId, "USER").getRefreshToken();
        var responses = race(() -> call("POST", "/auth/refresh", Map.of("refresh_token", refresh), null),
                () -> call("POST", "/auth/refresh", Map.of("refresh_token", refresh), null));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 401);
        assertThat(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked=true", Integer.class)).isEqualTo(1);
    }

    @Test void malformedJsonMissingBodyAndUnknownRoutesUseContractErrors() throws Exception {
        error(400, "VALIDATION_ERROR", "POST", "/products", null, seller);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/products"))
                .header("Authorization", "Bearer " + seller).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{broken")).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).path("error_code").asText()).isEqualTo("VALIDATION_ERROR");
        error(404, "RESOURCE_NOT_FOUND", "GET", "/not-an-endpoint", null, user);
        error(405, "METHOD_NOT_ALLOWED", "PATCH", "/products", Map.of(), seller);
        var wrongMedia = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/products"))
                .header("Authorization", "Bearer " + seller).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("text")).build();
        response = http.send(wrongMedia, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(json.readTree(response.body()).path("error_code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(count("products")).isZero();
    }

    private List<HttpResponse<String>> race(Callable<HttpResponse<String>> a, Callable<HttpResponse<String>> b) throws Exception {
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var fa = pool.submit(() -> { gate.await(); return a.call(); });
            var fb = pool.submit(() -> { gate.await(); return b.call(); });
            return List.of(fa.get(20, TimeUnit.SECONDS), fb.get(20, TimeUnit.SECONDS));
        }
    }
}
