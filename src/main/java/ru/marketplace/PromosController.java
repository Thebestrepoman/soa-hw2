package ru.marketplace;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.api.PromosApi;
import ru.marketplace.model.*;

@RestController
public class PromosController implements PromosApi {
    private final PromoService promos;
    public PromosController(PromoService promos) { this.promos = promos; }
    @Override public ResponseEntity<PromoCodeResponse> createPromoCode(PromoCodeCreate request) {
        return ResponseEntity.status(201).body(promos.create(request));
    }
}
