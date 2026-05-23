package com.shopsphere.ordering;

import com.shopsphere.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "order_line_items", schema = "ordering")
class OrderLineItem {

    @Id
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPriceAmount;

    @Column(name = "unit_price_currency", nullable = false, length = 3)
    private String unitPriceCurrency;

    @Column(nullable = false)
    private int qty;

    @Column(name = "line_total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotalAmount;

    @Column(name = "line_total_currency", nullable = false, length = 3)
    private String lineTotalCurrency;

    protected OrderLineItem() {
    }

    OrderLineItem(UUID id, Order order, UUID productId, String productName, Money unitPrice, int qty) {
        this.id = id;
        this.order = order;
        this.productId = productId;
        this.productName = productName;
        this.unitPriceAmount = unitPrice.amount();
        this.unitPriceCurrency = unitPrice.currency();
        this.qty = qty;
        Money lineTotal = unitPrice.multiply(qty);
        this.lineTotalAmount = lineTotal.amount();
        this.lineTotalCurrency = lineTotal.currency();
    }

    UUID getProductId() {
        return productId;
    }

    String getProductName() {
        return productName;
    }

    Money getUnitPrice() {
        return Money.of(unitPriceAmount, unitPriceCurrency);
    }

    int getQty() {
        return qty;
    }

    Money getLineTotal() {
        return Money.of(lineTotalAmount, lineTotalCurrency);
    }
}
