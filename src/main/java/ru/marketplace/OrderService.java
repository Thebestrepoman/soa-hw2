package ru.marketplace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.marketplace.model.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrderService {
    private final JdbcTemplate db;
    private final CatalogService catalog;
    private final PromoService promos;
    private final Duration interval;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    public OrderService(JdbcTemplate db, CatalogService catalog, PromoService promos,
                        @Value("${marketplace.order-interval}") Duration interval) {
        if (interval.isNegative()) throw new IllegalArgumentException("Order interval must not be negative");
        this.db = db; this.catalog = catalog; this.promos = promos; this.interval = interval;
    }

    @Transactional
    public OrderResponse create(OrderCreate request) {
        Actor actor = Actor.current(); actor.allow("USER", "ADMIN");
        lockUser(actor.id());
        rate(actor.id(), "CREATE_ORDER");
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM orders WHERE user_id=? AND status IN ('CREATED','PAYMENT_PENDING'))", Boolean.class, actor.id())))
            throw new BusinessException(409, "ORDER_HAS_ACTIVE", "User already has an active order");
        var products = lockProducts(request.getItems().stream().map(OrderItemRequest::getProductId).toList());
        var items = reserve(request.getItems(), products, Map.of());
        BigDecimal subtotal = subtotal(items);
        PromoCodeResponse promo = null;
        BigDecimal discount = BigDecimal.ZERO.setScale(2);
        if (request.getPromoCode() != null) {
            promo = promos.byCode(request.getPromoCode());
            promos.validate(promo, false);
            if (subtotal.compareTo(promo.getMinOrderAmount()) < 0)
                throw new BusinessException(422, "PROMO_CODE_MIN_AMOUNT", "Order subtotal is below promo minimum");
            discount = promos.discount(promo, subtotal);
            promos.usage(promo.getId(), 1);
        }
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO orders(id,user_id,status,promo_code_id,total_amount,discount_amount) VALUES (?,?,'CREATED',?,?,?)",
                id, actor.id(), promo == null ? null : promo.getId(), subtotal.subtract(discount), discount);
        saveItems(id, items);
        operation(actor.id(), "CREATE_ORDER");
        return load(id, false);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public OrderResponse get(UUID id) {
        Actor actor = Actor.current(); actor.allow("USER", "ADMIN");
        OrderResponse order = load(id, false); own(order, actor); return order;
    }

    @Transactional
    public OrderResponse update(UUID id, OrderUpdate request) {
        OrderResponse order = ownedLocked(id);
        if (order.getStatus() != OrderStatus.CREATED) throw state();
        rate(order.getUserId(), "UPDATE_ORDER");
        Set<UUID> ids = order.getItems().stream().map(OrderItemResponse::getProductId).collect(Collectors.toSet());
        request.getItems().forEach(i -> ids.add(i.getProductId()));
        var products = lockProducts(ids);
        restore(order.getItems(), products);
        Map<UUID, BigDecimal> prices = order.getItems().stream().collect(Collectors.toMap(OrderItemResponse::getProductId, OrderItemResponse::getPriceAtOrder));
        var items = reserve(request.getItems(), products, prices);
        BigDecimal subtotal = subtotal(items);
        BigDecimal discount = BigDecimal.ZERO.setScale(2);
        UUID promoId = order.getPromoCodeId();
        if (promoId != null) {
            var promo = promos.byId(promoId);
            promos.validate(promo, true); // This order already owns one use, including the last available use.
            if (subtotal.compareTo(promo.getMinOrderAmount()) < 0) {
                promos.usage(promoId, -1);
                promoId = null;
            } else discount = promos.discount(promo, subtotal);
        }
        db.update("UPDATE orders SET promo_code_id=?,total_amount=?,discount_amount=? WHERE id=?", promoId, subtotal.subtract(discount), discount, id);
        db.update("DELETE FROM order_items WHERE order_id=?", id);
        saveItems(id, items);
        operation(order.getUserId(), "UPDATE_ORDER");
        return load(id, false);
    }

    @Transactional
    public OrderResponse cancel(UUID id) {
        OrderResponse order = ownedLocked(id);
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.PAYMENT_PENDING) throw state();
        var products = lockProducts(order.getItems().stream().map(OrderItemResponse::getProductId).toList());
        restore(order.getItems(), products);
        if (order.getPromoCodeId() != null) {
            promos.byId(order.getPromoCodeId());
            promos.usage(order.getPromoCodeId(), -1);
        }
        db.update("UPDATE orders SET status='CANCELED' WHERE id=?", id);
        return load(id, false);
    }

    @Transactional
    public OrderResponse transition(UUID id, OrderStatus target) {
        Actor.current().allow("ADMIN");
        OrderResponse order = ownedLocked(id);
        var transitions = Map.of(OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING,
                OrderStatus.PAYMENT_PENDING, OrderStatus.PAID, OrderStatus.PAID, OrderStatus.SHIPPED,
                OrderStatus.SHIPPED, OrderStatus.COMPLETED);
        if (transitions.get(order.getStatus()) != target) throw state();
        db.update("UPDATE orders SET status=?::order_status WHERE id=?", target.getValue(), id);
        return load(id, false);
    }

    private OrderResponse ownedLocked(UUID id) {
        Actor actor = Actor.current(); actor.allow("USER", "ADMIN");
        OrderResponse order = load(id, false);
        own(order, actor);
        // Global lock order: owner -> order -> sorted products -> promo. Shared by every mutation.
        lockUser(order.getUserId());
        return load(id, true);
    }

    private void own(OrderResponse order, Actor actor) {
        if (!actor.admin() && !order.getUserId().equals(actor.id()))
            throw new BusinessException(403, "ORDER_OWNERSHIP_VIOLATION", "Order belongs to another user");
    }

    private void lockUser(UUID id) { db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, id); }

    private void rate(UUID userId, String type) {
        var last = db.query("SELECT created_at FROM user_operations WHERE user_id=? AND operation_type=?::operation_type ORDER BY created_at DESC LIMIT 1",
                (rs, n) -> rs.getTimestamp(1).toInstant(), userId, type);
        if (!last.isEmpty() && Duration.between(last.getFirst(), java.time.Instant.now()).compareTo(interval) < 0)
            throw new BusinessException(429, "ORDER_LIMIT_EXCEEDED", "Order operation rate limit exceeded",
                    Map.of("interval_seconds", interval.toSeconds()));
    }

    private void operation(UUID userId, String type) {
        db.update("INSERT INTO user_operations(id,user_id,operation_type) VALUES (?,?,?::operation_type)", UUID.randomUUID(), userId, type);
    }

    private Map<UUID, ProductResponse> lockProducts(Collection<UUID> ids) {
        var products = new LinkedHashMap<UUID, ProductResponse>();
        // Lock all existing rows first; catalog validation below follows request order.
        for (UUID id : new TreeSet<>(ids)) {
            try { products.put(id, catalog.get(id, true)); }
            catch (BusinessException e) { if (!e.code.equals("PRODUCT_NOT_FOUND")) throw e; }
        }
        return products;
    }

    private List<OrderItemResponse> reserve(List<OrderItemRequest> requested, Map<UUID, ProductResponse> products,
                                           Map<UUID, BigDecimal> snapshots) {
        for (var item : requested) {
            var product = products.get(item.getProductId());
            if (product == null) throw new BusinessException(404, "PRODUCT_NOT_FOUND", "Product not found", Map.of("product_id", item.getProductId()));
            if (product.getStatus() != ProductStatus.ACTIVE)
                throw new BusinessException(409, "PRODUCT_INACTIVE", "Product is not active", Map.of("product_id", item.getProductId()));
        }
        var shortages = new ArrayList<Map<String, Object>>();
        for (var item : requested) {
            var product = products.get(item.getProductId());
            if (product.getStock() < item.getQuantity()) shortages.add(Map.of("product_id", product.getId(), "requested", item.getQuantity(), "available", product.getStock()));
        }
        if (!shortages.isEmpty()) throw new BusinessException(409, "INSUFFICIENT_STOCK", "Insufficient stock", Map.of("products", shortages));
        var items = new ArrayList<OrderItemResponse>();
        for (var item : requested) {
            var product = products.get(item.getProductId());
            db.update("UPDATE products SET stock=stock-? WHERE id=?", item.getQuantity(), product.getId());
            items.add(new OrderItemResponse().id(UUID.randomUUID()).productId(product.getId()).quantity(item.getQuantity())
                    .priceAtOrder(snapshots.getOrDefault(product.getId(), product.getPrice())));
        }
        return items;
    }

    private void restore(List<OrderItemResponse> items, Map<UUID, ProductResponse> products) {
        for (var item : items) {
            db.update("UPDATE products SET stock=stock+? WHERE id=?", item.getQuantity(), item.getProductId());
            var product = products.get(item.getProductId());
            product.setStock(Math.addExact(product.getStock(), item.getQuantity()));
        }
    }

    private BigDecimal subtotal(List<OrderItemResponse> items) {
        BigDecimal amount = items.stream().map(i -> i.getPriceAtOrder().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        if (amount.compareTo(MAX_AMOUNT) > 0) throw BusinessException.validation("items", "Order subtotal exceeds 9999999999.99");
        return amount;
    }

    private void saveItems(UUID orderId, List<OrderItemResponse> items) {
        for (var item : items) db.update("INSERT INTO order_items(id,order_id,product_id,quantity,price_at_order) VALUES (?,?,?,?,?)",
                item.getId(), orderId, item.getProductId(), item.getQuantity(), item.getPriceAtOrder());
    }

    private OrderResponse load(UUID id, boolean lock) {
        var rows = db.query("SELECT * FROM orders WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::order, id);
        if (rows.isEmpty()) throw new BusinessException(404, "ORDER_NOT_FOUND", "Order not found");
        var order = rows.getFirst();
        order.setItems(db.query("SELECT * FROM order_items WHERE order_id=? ORDER BY product_id", (rs, n) ->
                new OrderItemResponse().id(rs.getObject("id", UUID.class)).productId(rs.getObject("product_id", UUID.class))
                        .quantity(rs.getInt("quantity")).priceAtOrder(rs.getBigDecimal("price_at_order")), id));
        return order;
    }

    private OrderResponse order(ResultSet rs, int n) throws SQLException {
        return new OrderResponse().id(rs.getObject("id", UUID.class)).userId(rs.getObject("user_id", UUID.class))
                .status(OrderStatus.fromValue(rs.getString("status"))).promoCodeId(rs.getObject("promo_code_id", UUID.class))
                .totalAmount(rs.getBigDecimal("total_amount")).discountAmount(rs.getBigDecimal("discount_amount"))
                .createdAt(rs.getObject("created_at", OffsetDateTime.class)).updatedAt(rs.getObject("updated_at", OffsetDateTime.class));
    }

    private BusinessException state() { return new BusinessException(409, "INVALID_STATE_TRANSITION", "Operation not allowed in current order state"); }
}
