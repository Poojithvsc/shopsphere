package com.shopsphere.catalog;

import java.math.BigDecimal;
import java.util.UUID;

record ProductDto(
        UUID id,
        String name,
        String description,
        MoneyDto unitPrice,
        int availableQty
) {
    record MoneyDto(BigDecimal amount, String currency) {
    }
}
