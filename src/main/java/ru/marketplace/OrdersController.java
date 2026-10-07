package ru.marketplace;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.api.OrdersApi;
import ru.marketplace.model.*;
import java.util.UUID;

@RestController
public class OrdersController implements OrdersApi {
    private final OrderService orders;
    public OrdersController(OrderService orders) { this.orders = orders; }
    @Override public ResponseEntity<OrderResponse> createOrder(OrderCreate r) { return ResponseEntity.status(201).body(orders.create(r)); }
    @Override public ResponseEntity<OrderResponse> getOrder(UUID id) { return ResponseEntity.ok(orders.get(id)); }
    @Override public ResponseEntity<OrderResponse> updateOrder(UUID id, OrderUpdate r) { return ResponseEntity.ok(orders.update(id, r)); }
    @Override public ResponseEntity<OrderResponse> cancelOrder(UUID id) { return ResponseEntity.ok(orders.cancel(id)); }
    @Override public ResponseEntity<OrderResponse> transitionOrder(UUID id, OrderStatusUpdate r) { return ResponseEntity.ok(orders.transition(id, r.getStatus())); }
}
