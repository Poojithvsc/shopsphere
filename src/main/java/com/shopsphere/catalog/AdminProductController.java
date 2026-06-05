package com.shopsphere.catalog;

import com.shopsphere.common.Money;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operator-only product management. Every method is gated by {@code hasRole('ADMIN')}: an
 * authenticated non-admin is rejected 403 (anonymous is 401, handled upstream by the security chain).
 * The admin is a seeded operator (Flyway V14), not a shopper.
 */
@RestController
@RequestMapping("/api/v1/admin/products")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
class AdminProductController {

    private final ProductRepository products;

    AdminProductController(ProductRepository products) {
        this.products = products;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProductDto create(@Valid @RequestBody ProductRequest request) {
        Product product = new Product(
                UUID.randomUUID(),
                request.name(),
                request.description(),
                request.unitPrice().amount(),
                request.unitPrice().currency(),
                request.availableQty());
        return ProductMapper.toDto(products.save(product));
    }

    @PutMapping("/{id}")
    ResponseEntity<ProductDto> update(@PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        return products.findById(id)
                .map(existing -> {
                    Product replacement = new Product(
                            existing.getId(),
                            request.name(),
                            request.description(),
                            request.unitPrice().amount(),
                            request.unitPrice().currency(),
                            request.availableQty());
                    return ResponseEntity.ok(ProductMapper.toDto(products.save(replacement)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!products.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        products.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    record ProductRequest(
            @NotBlank String name,
            @NotBlank String description,
            @NotNull Money unitPrice,
            @PositiveOrZero int availableQty) {
    }
}
