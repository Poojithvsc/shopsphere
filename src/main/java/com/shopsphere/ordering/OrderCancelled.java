package com.shopsphere.ordering;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.UUID;

@Externalized("ordering.events")
public record OrderCancelled(
        String eventType,
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        UUID customerId,
        String reason) {

    public static final String EVENT_TYPE = "OrderCancelled";

    public OrderCancelled(UUID eventId, Instant occurredAt, UUID orderId, UUID customerId, String reason) {
        this(EVENT_TYPE, eventId, occurredAt, orderId, customerId, reason);
    }
}
