package com.shopsphere.ordering;

import com.shopsphere.common.Money;
import org.springframework.modulith.events.Externalized;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Externalized("ordering.events")
public record OrderPlaced(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        UUID customerId,
        Money total,
        List<Line> lines) {

    public static final String EVENT_TYPE = "OrderPlaced";

    public record Line(UUID productId, String name, Money unitPrice, int qty) {
    }
}
