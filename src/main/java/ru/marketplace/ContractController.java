package ru.marketplace;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ContractController {
    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    public ResponseEntity<Resource> contract() {
        return ResponseEntity.ok(new ClassPathResource("openapi/marketplace.yaml"));
    }
}
