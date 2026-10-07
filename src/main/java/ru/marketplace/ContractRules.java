package ru.marketplace;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import ru.marketplace.model.*;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Constraints the generator cannot express as Jakarta annotations (multipleOf, uniqueness, cross-field). */
@ControllerAdvice
public class ContractRules extends RequestBodyAdviceAdapter {
    @Override public boolean supports(MethodParameter p, Type t, Class<? extends HttpMessageConverter<?>> c) { return true; }

    @Override public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter p, Type t,
                                          Class<? extends HttpMessageConverter<?>> c) {
        if (body instanceof ProductCreate r) money("price", r.getPrice());
        if (body instanceof ProductUpdate r) money("price", r.getPrice());
        if (body instanceof PromoCodeCreate r) {
            if (r.getActive() == null) throw BusinessException.validation("active", "Must not be null");
            money("discount_value", r.getDiscountValue());
            money("min_order_amount", r.getMinOrderAmount());
            if (r.getValidFrom() != null && r.getValidUntil() != null && !r.getValidUntil().isAfter(r.getValidFrom()))
                throw BusinessException.validation("valid_until", "Must be after valid_from");
        }
        if (body instanceof OrderCreate r) items(r.getItems());
        if (body instanceof OrderUpdate r) items(r.getItems());
        if (body instanceof RegisterRequest r) {
            password(r.getPassword());
            if (r.getRole() == null) throw BusinessException.validation("role", "Must not be null");
        }
        if (body instanceof LoginRequest r) password(r.getPassword());
        return body;
    }

    private void money(String field, BigDecimal value) {
        if (value != null && value.stripTrailingZeros().scale() > 2)
            throw BusinessException.validation(field, "Must be a multiple of 0.01");
    }

    private void password(String value) {
        if (value != null && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw BusinessException.validation("password", "Must not exceed 72 UTF-8 bytes");
    }

    private void items(List<OrderItemRequest> items) {
        if (items == null) return;
        var ids = new HashSet<UUID>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == null) throw BusinessException.validation("items[" + i + "]", "Must not be null");
            if (!ids.add(items.get(i).getProductId()))
                throw BusinessException.validation("items[" + i + "].product_id", "Duplicate product ID");
        }
    }
}
