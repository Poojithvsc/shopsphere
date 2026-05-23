package com.shopsphere.payment;

import com.shopsphere.common.Money;
import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.UUID;

@Externalized("payment.events")
public record PaymentSucceeded(
        String eventType,
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        UUID customerId,
        Money amount) {

    public static final String EVENT_TYPE = "PaymentSucceeded";

    public PaymentSucceeded(UUID eventId, Instant occurredAt, UUID orderId, UUID customerId, Money amount) {
        this(EVENT_TYPE, eventId, occurredAt, orderId, customerId, amount);
    }
}
