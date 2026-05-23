package com.shopsphere.ordering;

import com.shopsphere.common.Money;
import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Externalized("ordering.events")
public record OrderPlaced(
        String eventType,
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        UUID customerId,
        Money total,
        String cardNumber,
        List<Line> lines) {

    public static final String EVENT_TYPE = "OrderPlaced";

    public OrderPlaced(UUID eventId,
                       Instant occurredAt,
                       UUID orderId,
                       UUID customerId,
                       Money total,
                       String cardNumber,
                       List<Line> lines) {
        this(EVENT_TYPE, eventId, occurredAt, orderId, customerId, total, cardNumber, lines);
    }

    public record Line(UUID productId, String name, Money unitPrice, int qty) {
    }
}
