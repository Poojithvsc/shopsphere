package com.shopsphere.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users", schema = "identity")
class User {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, unique = true)
    private UUID customerId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    // Comma-separated role names (e.g. "USER" or "USER,ADMIN"). Deliberately denormalised — the role
    // set is fixed and tiny and there is no role-management UI, so a join table would be premature
    // (see ADR-0017). Spring authorities are derived by prefixing each with ROLE_.
    @Column(nullable = false)
    private String roles;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {
    }

    User(UUID id, UUID customerId, String email, String passwordHash, Instant createdAt) {
        this(id, customerId, email, passwordHash, "USER", createdAt);
    }

    User(UUID id, UUID customerId, String email, String passwordHash, String roles, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.roles = roles;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    String getEmail() {
        return email;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    List<String> getRoles() {
        return List.of(roles.split(","));
    }
}
