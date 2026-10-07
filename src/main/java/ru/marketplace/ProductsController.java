package ru.marketplace;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.api.ProductsApi;
import ru.marketplace.model.*;
import java.util.UUID;

@RestController
public class ProductsController implements ProductsApi {
    private final CatalogService catalog;
    public ProductsController(CatalogService catalog) { this.catalog = catalog; }
    @Override public ResponseEntity<ProductResponse> createProduct(ProductCreate request) {
        return ResponseEntity.status(201).body(catalog.create(request));
    }
    @Override public ResponseEntity<ProductResponse> getProduct(UUID id) { return ResponseEntity.ok(catalog.get(id, false)); }
    @Override public ResponseEntity<ProductPage> listProducts(Integer page, Integer size, ProductStatus status, String category) {
        return ResponseEntity.ok(catalog.list(page, size, status, category));
    }
    @Override public ResponseEntity<ProductResponse> updateProduct(UUID id, ProductUpdate request) {
        return ResponseEntity.ok(catalog.update(id, request));
    }
    @Override public ResponseEntity<Void> deleteProduct(UUID id) { catalog.archive(id); return ResponseEntity.noContent().build(); }
}
