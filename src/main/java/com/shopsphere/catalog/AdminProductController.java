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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
    private final ProductImageStorage images;
    private final ProductMapper mapper;

    AdminProductController(ProductRepository products, ProductImageStorage images, ProductMapper mapper) {
        this.products = products;
        this.images = images;
        this.mapper = mapper;
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
        return mapper.toDto(products.save(product));
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
                    // An edit replaces the product's fields but not its image — carry the key across.
                    replacement.setImageKey(existing.getImageKey());
                    return ResponseEntity.ok(mapper.toDto(products.save(replacement)));
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

    /**
     * Uploads (or replaces) the image for a product. The bytes go to S3 via {@link ProductImageStorage};
     * only the returned key is recorded on the product, so the read side can later mint a presigned URL.
     * An unsupported content type is rejected 400 (see the exception handler); an unknown product is 404.
     */
    @PostMapping("/{id}/image")
    ResponseEntity<ProductDto> uploadImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file)
            throws IOException {
        Product product = products.findById(id).orElse(null);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        String key = images.upload(id, file.getBytes(), file.getContentType());
        product.setImageKey(key);
        return ResponseEntity.ok(mapper.toDto(products.save(product)));
    }

    @ExceptionHandler(S3ProductImageStorage.UnsupportedImageTypeException.class)
    ResponseEntity<Void> unsupportedImageType() {
        return ResponseEntity.badRequest().build();
    }

    record ProductRequest(
            @NotBlank String name,
            @NotBlank String description,
            @NotNull Money unitPrice,
            @PositiveOrZero int availableQty) {
    }
}
