package com.shopsphere.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers", schema = "identity")
class Customer {

    @Id
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Customer() {
    }

    Customer(UUID id, Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }
}
