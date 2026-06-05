package com.shopsphere.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrderLog} is the one place that knows the MDC field names ({@code orderId}/{@code customerId})
 * the structured logs and the Loki dashboards key on. These tests pin that contract and the
 * always-clean-up guarantee, so every module can stamp an order onto a log line the same way without
 * re-deriving the field names. Mirrors the field names asserted in
 * {@code com.shopsphere.ordering.StructuredLogShapeTests}.
 */
class OrderLogTests {

    private static final UUID ORDER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void stampsOrderAndCustomerOntoTheLogLinesMdc() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        Logger log = (Logger) LoggerFactory.getLogger(OrderLogTests.class);

        OrderLog.withOrder(ORDER, CUSTOMER, () -> log.info("did a thing"));

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMDCPropertyMap()).containsEntry("orderId", ORDER.toString());
        assertThat(event.getMDCPropertyMap()).containsEntry("customerId", CUSTOMER.toString());
    }

    @Test
    void stampsOrderOnlyWhenNoCustomerIsKnown() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        Logger log = (Logger) LoggerFactory.getLogger(OrderLogTests.class);

        OrderLog.withOrder(ORDER, () -> log.info("downstream consumer"));

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMDCPropertyMap()).containsEntry("orderId", ORDER.toString());
        assertThat(event.getMDCPropertyMap()).doesNotContainKey("customerId");
    }

    @Test
    void clearsMdcAfterTheBodyEvenWhenItThrows() {
        try {
            OrderLog.withOrder(ORDER, CUSTOMER, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // swallow — we only care that the MDC is clean afterwards
        }

        assertThat(MDC.get("orderId")).isNull();
        assertThat(MDC.get("customerId")).isNull();
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        ((Logger) LoggerFactory.getLogger(OrderLogTests.class)).addAppender(appender);
        return appender;
    }
}
