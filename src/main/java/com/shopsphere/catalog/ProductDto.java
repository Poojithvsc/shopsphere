package com.shopsphere.catalog;

import com.shopsphere.common.Money;

import java.util.UUID;

record ProductDto(
        UUID id,
        String name,
        String description,
        Money unitPrice,
        int availableQty
) {
}
