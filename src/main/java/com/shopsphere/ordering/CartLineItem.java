package com.shopsphere.ordering;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cart_line_items", schema = "ordering")
class CartLineItem {

    @Id
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CartLineItem() {
    }

    CartLineItem(UUID id, Cart cart, UUID productId, int qty, Instant createdAt) {
        this.id = id;
        this.cart = cart;
        this.productId = productId;
        this.qty = qty;
        this.createdAt = createdAt;
    }

    UUID getProductId() {
        return productId;
    }

    int getQty() {
        return qty;
    }

    void changeQty(int newQty) {
        if (newQty <= 0) {
            throw new IllegalArgumentException("qty must be positive (use removeLine for 0)");
        }
        this.qty = newQty;
    }
}
