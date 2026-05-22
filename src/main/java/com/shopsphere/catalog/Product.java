package com.shopsphere.catalog;

import com.shopsphere.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "products", schema = "catalog")
class Product {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPriceAmount;

    @Column(name = "unit_price_currency", nullable = false, length = 3)
    private String unitPriceCurrency;

    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    protected Product() {
    }

    Product(UUID id,
            String name,
            String description,
            BigDecimal unitPriceAmount,
            String unitPriceCurrency,
            int availableQty) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.unitPriceAmount = unitPriceAmount;
        this.unitPriceCurrency = unitPriceCurrency;
        this.availableQty = availableQty;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    Money getUnitPrice() {
        return Money.of(unitPriceAmount, unitPriceCurrency);
    }

    int getAvailableQty() {
        return availableQty;
    }

    void decreaseAvailable(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("decrement must be positive, got " + amount);
        }
        if (this.availableQty < amount) {
            throw new InsufficientStockException();
        }
        this.availableQty -= amount;
    }

    void increaseAvailable(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("increment must be positive, got " + amount);
        }
        this.availableQty += amount;
    }

    static final class InsufficientStockException extends RuntimeException {
    }
}
