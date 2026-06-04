package com.shopsphere.catalog;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@SecurityRequirement(name = "bearerAuth")
class ProductController {

    private final ProductRepository products;

    ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    List<ProductDto> list() {
        return products.findAll(Sort.by("name")).stream()
                .map(ProductMapper::toDto)
                .toList();
    }

    @GetMapping("/{id}")
    ResponseEntity<ProductDto> getOne(@PathVariable UUID id) {
        return products.findById(id)
                .map(ProductMapper::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
