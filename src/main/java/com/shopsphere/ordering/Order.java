package com.shopsphere.ordering;

import com.shopsphere.common.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders", schema = "ordering")
class Order {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "total_currency", nullable = false, length = 3)
    private String totalCurrency;

    @Column(name = "shipping_address", nullable = false)
    private String shippingAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderLineItem> items = new ArrayList<>();

    protected Order() {
    }

    Order(UUID id, UUID customerId, String shippingAddress, Money total, Instant now) {
        this.id = id;
        this.customerId = customerId;
        this.shippingAddress = shippingAddress;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.totalAmount = total.amount();
        this.totalCurrency = total.currency();
        this.createdAt = now;
        this.updatedAt = now;
    }

    void addLine(OrderLineItem line) {
        items.add(line);
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    OrderStatus getStatus() {
        return status;
    }

    Money getTotal() {
        return Money.of(totalAmount, totalCurrency);
    }

    String getShippingAddress() {
        return shippingAddress;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    List<OrderLineItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    void transitionTo(OrderStatus next, Instant now) {
        OrderStateMachine.assertTransition(this.status, next);
        this.status = next;
        this.updatedAt = now;
    }
}
