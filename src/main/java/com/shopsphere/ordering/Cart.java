package com.shopsphere.ordering;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "carts", schema = "ordering")
class Cart {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, unique = true)
    private UUID customerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<CartLineItem> items = new ArrayList<>();

    protected Cart() {
    }

    Cart(UUID id, UUID customerId, Instant now) {
        this.id = id;
        this.customerId = customerId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    List<CartLineItem> getItems() {
        return items;
    }

    Optional<CartLineItem> findLine(UUID productId) {
        return items.stream().filter(i -> i.getProductId().equals(productId)).findFirst();
    }

    void addOrIncrement(UUID productId, int qty, Instant now) {
        findLine(productId).ifPresentOrElse(
                line -> line.changeQty(line.getQty() + qty),
                () -> items.add(new CartLineItem(UUID.randomUUID(), this, productId, qty, now)));
        this.updatedAt = now;
    }

    void setQty(UUID productId, int qty, Instant now) {
        CartLineItem line = findLine(productId)
                .orElseThrow(LineNotFoundException::new);
        if (qty == 0) {
            items.remove(line);
        } else {
            line.changeQty(qty);
        }
        this.updatedAt = now;
    }

    boolean removeLine(UUID productId, Instant now) {
        boolean removed = items.removeIf(i -> i.getProductId().equals(productId));
        if (removed) {
            this.updatedAt = now;
        }
        return removed;
    }

    static final class LineNotFoundException extends RuntimeException {
    }
}
