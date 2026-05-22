package com.shopsphere.catalog;

final class ProductMapper {

    private ProductMapper() {
    }

    static ProductDto toDto(Product product) {
        return new ProductDto(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getUnitPrice(),
                product.getAvailableQty()
        );
    }
}
