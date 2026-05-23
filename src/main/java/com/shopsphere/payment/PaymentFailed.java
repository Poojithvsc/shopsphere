package com.shopsphere.payment;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.UUID;

@Externalized("payment.events")
public record PaymentFailed(
        String eventType,
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        UUID customerId,
        Reason reason) {

    public static final String EVENT_TYPE = "PaymentFailed";

    public PaymentFailed(UUID eventId, Instant occurredAt, UUID orderId, UUID customerId, Reason reason) {
        this(EVENT_TYPE, eventId, occurredAt, orderId, customerId, reason);
    }

    public enum Reason {
        DECLINED,
        INSUFFICIENT_FUNDS
    }
}
