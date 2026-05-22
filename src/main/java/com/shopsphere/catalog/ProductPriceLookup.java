package com.shopsphere.catalog;

import com.shopsphere.common.Money;

import java.util.Optional;
import java.util.UUID;

public interface ProductPriceLookup {

    Optional<Money> findUnitPrice(UUID productId);

    Optional<ProductSummary> findSummary(UUID productId);

    record ProductSummary(UUID id, String name, Money unitPrice) {
    }
}
