package com.shopsphere.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_reservations", schema = "catalog")
class StockReservation {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int qty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockReservation() {
    }

    StockReservation(UUID id, UUID orderId, UUID productId, int qty, Instant createdAt) {
        this.id = id;
        this.orderId = orderId;
        this.productId = productId;
        this.qty = qty;
        this.status = Status.HELD;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    UUID getOrderId() {
        return orderId;
    }

    UUID getProductId() {
        return productId;
    }

    int getQty() {
        return qty;
    }

    Status getStatus() {
        return status;
    }

    boolean isHeld() {
        return status == Status.HELD;
    }

    void confirm() {
        if (status == Status.HELD) {
            status = Status.CONFIRMED;
        }
    }

    void release() {
        if (status == Status.HELD) {
            status = Status.RELEASED;
        }
    }

    enum Status {
        HELD,
        CONFIRMED,
        RELEASED
    }
}
