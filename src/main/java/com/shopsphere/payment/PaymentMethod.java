package com.shopsphere.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A tokenized payment instrument — the Payment context's redaction boundary for card data.
 *
 * <p>{@link #tokenize(String, Instant)} is the only way to mint one: it derives a fresh opaque
 * {@code token}, keeps just the {@code lastFour} display digits, and stores a {@code vaultRef}
 * that is unrelated to the PAN. The raw card number is consumed inside the factory and never
 * retained on a field, so it cannot leak onto an event, a log line, or any column other than the
 * deliberately-opaque {@code vault_ref}. A given card produces a different token on every call.
 */
@Entity
@Table(name = "payment_methods", schema = "payment")
class PaymentMethod {

    @Id
    private UUID token;

    @Column(name = "vault_ref", nullable = false)
    private String vaultRef;

    @Column(name = "last_four", nullable = false)
    private String lastFour;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentMethod() {
    }

    private PaymentMethod(UUID token, String vaultRef, String lastFour, Instant createdAt) {
        this.token = token;
        this.vaultRef = vaultRef;
        this.lastFour = lastFour;
        this.createdAt = createdAt;
    }

    static PaymentMethod tokenize(String rawCard, Instant now) {
        String digits = rawCard == null ? "" : rawCard.replaceAll("\\D", "");
        String lastFour = digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
        // The vault reference is a fresh opaque handle, NOT derived from the PAN — there is no path
        // back to the card number from anything we persist.
        String vaultRef = "vault:" + UUID.randomUUID();
        return new PaymentMethod(UUID.randomUUID(), vaultRef, lastFour, now);
    }

    UUID token() {
        return token;
    }

    String lastFour() {
        return lastFour;
    }

    String vaultRef() {
        return vaultRef;
    }
}
