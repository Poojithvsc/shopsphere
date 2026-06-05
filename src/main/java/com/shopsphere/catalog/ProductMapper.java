package com.shopsphere.catalog;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Maps a {@link Product} to its API DTO, minting a short-lived presigned read URL for the image when
 * the product has one (null otherwise). Presigning is a local signature computation — no S3 round-trip
 * — so doing it per row while paging a product list is cheap. The bucket itself stays private; the
 * 5-minute URL is the only way a client reaches the bytes.
 */
@Component
class ProductMapper {

    private static final Duration IMAGE_URL_TTL = Duration.ofMinutes(5);

    private final ProductImageStorage images;

    ProductMapper(ProductImageStorage images) {
        this.images = images;
    }

    ProductDto toDto(Product product) {
        String imageUrl = product.getImageKey() == null
                ? null
                : images.presignedRead(product.getImageKey(), IMAGE_URL_TTL);
        return new ProductDto(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getUnitPrice(),
                product.getAvailableQty(),
                imageUrl);
    }
}
