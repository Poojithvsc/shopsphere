package com.shopsphere.catalog;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@SecurityRequirement(name = "bearerAuth")
class ProductController {

    private final ProductRepository products;

    ProductController(ProductRepository products) {
        this.products = products;
    }

    /**
     * Lists products one page at a time. {@code ?page=&size=} are optional; with no params a caller
     * gets the first page of 20 sorted by name (backward-compatible default). {@code size} is capped
     * at 100 by {@code spring.data.web.pageable.max-page-size}, so an oversized request is clamped,
     * not rejected. The response is a {@link PagedResponse} envelope (content + page metadata).
     */
    @GetMapping
    PagedResponse<ProductDto> list(@PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return PagedResponse.of(products.findAll(pageable), ProductMapper::toDto);
    }

    @GetMapping("/{id}")
    ResponseEntity<ProductDto> getOne(@PathVariable UUID id) {
        return products.findById(id)
                .map(ProductMapper::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
