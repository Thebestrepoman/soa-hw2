package ru.marketplace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.marketplace.model.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;

@Service
public class CatalogService {
    private final JdbcTemplate db;
    public CatalogService(JdbcTemplate db) { this.db = db; }

    ProductResponse get(UUID id, boolean lock) {
        var rows = db.query("SELECT * FROM products WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::product, id);
        if (rows.isEmpty()) throw new BusinessException(404, "PRODUCT_NOT_FOUND", "Product not found");
        return rows.getFirst();
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ProductPage list(Integer page, Integer size, ProductStatus status, String category) {
        String where = " WHERE true";
        var args = new ArrayList<Object>();
        if (status != null) { where += " AND status=?::product_status"; args.add(status.getValue()); }
        if (category != null) { where += " AND category=?"; args.add(category); }
        Long total = db.queryForObject("SELECT count(*) FROM products" + where, Long.class, args.toArray());
        args.add(size); args.add((long) page * size);
        var products = db.query("SELECT * FROM products" + where + " ORDER BY created_at,id LIMIT ? OFFSET ?", this::product, args.toArray());
        return new ProductPage().content(products).totalElements(total).page(page).size(size);
    }

    @Transactional
    public ProductResponse create(ProductCreate request) {
        Actor actor = Actor.current(); actor.allow("SELLER", "ADMIN");
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO products(id,name,description,price,stock,category,status,seller_id) VALUES (?,?,?,?,?,?,?::product_status,?)",
                id, request.getName(), request.getDescription(), request.getPrice(), request.getStock(), request.getCategory(), request.getStatus().getValue(), actor.id());
        return get(id, false);
    }

    @Transactional
    public ProductResponse update(UUID id, ProductUpdate request) {
        Actor actor = Actor.current(); actor.allow("SELLER", "ADMIN");
        owned(get(id, true), actor);
        db.update("UPDATE products SET name=?,description=?,price=?,stock=?,category=?,status=?::product_status WHERE id=?",
                request.getName(), request.getDescription(), request.getPrice(), request.getStock(), request.getCategory(), request.getStatus().getValue(), id);
        return get(id, false);
    }

    @Transactional
    public void archive(UUID id) {
        Actor actor = Actor.current(); actor.allow("SELLER", "ADMIN");
        owned(get(id, true), actor);
        db.update("UPDATE products SET status='ARCHIVED' WHERE id=?", id);
    }

    private void owned(ProductResponse product, Actor actor) {
        if (!actor.admin() && !product.getSellerId().equals(actor.id()))
            throw new BusinessException(403, "ACCESS_DENIED", "Product belongs to another seller");
    }

    private ProductResponse product(ResultSet rs, int row) throws SQLException {
        return new ProductResponse().id(rs.getObject("id", UUID.class)).name(rs.getString("name"))
                .description(rs.getString("description")).price(rs.getBigDecimal("price")).stock(rs.getInt("stock"))
                .category(rs.getString("category")).status(ProductStatus.fromValue(rs.getString("status")))
                .sellerId(rs.getObject("seller_id", UUID.class)).createdAt(rs.getObject("created_at", OffsetDateTime.class))
                .updatedAt(rs.getObject("updated_at", OffsetDateTime.class));
    }
}
