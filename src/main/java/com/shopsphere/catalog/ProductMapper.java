package com.shopsphere.catalog;

final class ProductMapper {

    private ProductMapper() {
    }

    static ProductDto toDto(Product product) {
        return new ProductDto(
                product.getId(),
                product.getName(),
                product.getDescription(),
                new ProductDto.MoneyDto(product.getUnitPriceAmount(), product.getUnitPriceCurrency()),
                product.getAvailableQty()
        );
    }
}
