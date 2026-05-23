package com.shopsphere.ordering;

import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.UUID;

@Externalized("ordering.events")
public record OrderPaid(
        String eventType,
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        UUID customerId) {

    public static final String EVENT_TYPE = "OrderPaid";

    public OrderPaid(UUID eventId, Instant occurredAt, UUID orderId, UUID customerId) {
        this(EVENT_TYPE, eventId, occurredAt, orderId, customerId);
    }
}
