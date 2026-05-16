package com.shopsphere.catalog;

import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
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
}
