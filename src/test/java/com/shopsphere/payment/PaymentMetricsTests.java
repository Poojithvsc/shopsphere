package com.shopsphere.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.common.Money;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-level proof that {@code payments_total} is tagged by outcome and incremented exactly once per
 * processed event. The full Kafka flow is not exercised — counting lives in {@code process(...)},
 * which {@code onOrderingEvent} only calls downstream of the {@code markProcessed} dedupe gate, so a
 * redelivered event never reaches it (the dedupe itself is verified in PaymentFlowIT against the DB).
 */
class PaymentMetricsTests {

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final PaymentMethods paymentMethods = mock(PaymentMethods.class);
    private final PaymentOrderingConsumer consumer = new PaymentOrderingConsumer(
            new ObjectMapper(),
            new SimulatedProvider(paymentMethods, new PaymentSimulator()),
            mock(JdbcTemplate.class),
            event -> { },
            Clock.systemUTC(),
            meters);

    @Test
    void successCardCountsAsSucceeded() {
        consumer.process(placedWith(PaymentSimulator.LAST_FOUR_SUCCESS));
        assertThat(meters.counter("payments_total", "outcome", "succeeded").count()).isEqualTo(1.0);
    }

    @Test
    void declinedCardCountsAsDeclined() {
        consumer.process(placedWith("0002"));
        assertThat(meters.counter("payments_total", "outcome", "declined").count()).isEqualTo(1.0);
    }

    @Test
    void insufficientFundsCardCountsAsInsufficientFunds() {
        consumer.process(placedWith(PaymentSimulator.LAST_FOUR_INSUFFICIENT_FUNDS));
        assertThat(meters.counter("payments_total", "outcome", "insufficient_funds").count()).isEqualTo(1.0);
    }

    private PaymentOrderingConsumer.OrderPlacedView placedWith(String lastFour) {
        UUID token = UUID.randomUUID();
        when(paymentMethods.lookup(any(UUID.class)))
                .thenReturn(Optional.of(new PaymentMethods.PaymentMethodView(token, lastFour)));
        return new PaymentOrderingConsumer.OrderPlacedView(
                PaymentOrderingConsumer.OrderPlacedView.EVENT_TYPE,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Money.of(new BigDecimal("100.0000"), "INR"),
                token);
    }
}
