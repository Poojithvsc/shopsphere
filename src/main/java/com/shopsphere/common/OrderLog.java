package com.shopsphere.common;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * The one place that knows how an order is correlated across log lines: it stamps {@code orderId}
 * (and {@code customerId}, when known) into the SLF4J {@link MDC} for the duration of a log
 * statement, then always clears them again.
 *
 * <p>Every module that touches an order — Ordering, Payment, Catalog/Reservation — logs through this
 * helper so the {@code LogstashEncoder} lifts those fields to top-level JSON. A Loki query of the
 * form {@code {container="shopsphere-app"} |= "<orderId>"} then returns the whole journey of one
 * order across every module, which is the headline acceptance check for the logging stack.
 *
 * <p>Scope is deliberately tight: wrap only the {@code log.info(...)} call, not downstream work, so
 * the MDC never leaks onto an unrelated thread or nests with another order's context.
 */
public final class OrderLog {

    private static final String ORDER_ID = "orderId";
    private static final String CUSTOMER_ID = "customerId";

    private OrderLog() {
    }

    /** Run {@code body} with {@code orderId} stamped onto the MDC; clears it afterwards. */
    public static void withOrder(UUID orderId, Runnable body) {
        withOrder(orderId, null, body);
    }

    /**
     * Run {@code body} with {@code orderId} (and {@code customerId}, if non-null) stamped onto the
     * MDC; clears both afterwards, even if {@code body} throws.
     */
    public static void withOrder(UUID orderId, UUID customerId, Runnable body) {
        MDC.put(ORDER_ID, orderId.toString());
        if (customerId != null) {
            MDC.put(CUSTOMER_ID, customerId.toString());
        }
        try {
            body.run();
        } finally {
            MDC.remove(ORDER_ID);
            MDC.remove(CUSTOMER_ID);
        }
    }
}
