package com.shopsphere.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.common.Money;
import com.shopsphere.common.ProcessedEvents;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

@Component
class PaymentOrderingConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderingConsumer.class);
    static final String CONSUMER_ID = "payment.orderplaced";

    private final ObjectMapper mapper;
    private final PaymentSimulator simulator;
    private final PaymentMethods paymentMethods;
    private final ProcessedEvents processedEvents;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final MeterRegistry meters;

    PaymentOrderingConsumer(ObjectMapper mapper,
                            PaymentSimulator simulator,
                            PaymentMethods paymentMethods,
                            JdbcTemplate jdbc,
                            ApplicationEventPublisher events,
                            Clock clock,
                            MeterRegistry meters) {
        this.mapper = mapper;
        this.simulator = simulator;
        this.paymentMethods = paymentMethods;
        this.processedEvents = new ProcessedEvents(jdbc, "payment");
        this.events = events;
        this.clock = clock;
        this.meters = meters;
    }

    @KafkaListener(topics = "ordering.events", groupId = CONSUMER_ID)
    @Transactional
    public void onOrderingEvent(ConsumerRecord<String, String> record) throws Exception {
        JsonNode tree = mapper.readTree(record.value());
        String eventType = textOrNull(tree, "eventType");
        if (!OrderPlacedView.EVENT_TYPE.equals(eventType)) {
            return;
        }
        OrderPlacedView placed = mapper.treeToValue(tree, OrderPlacedView.class);
        if (!processedEvents.markProcessed(CONSUMER_ID, placed.eventId)) {
            log.info("Duplicate OrderPlaced event {} — skipping", placed.eventId);
            return;
        }
        process(placed);
    }

    void process(OrderPlacedView placed) {
        // Tolerant of either field while Ordering migrates from cardNumber to paymentMethodToken:
        // prefer the token (resolve its redacted last-four), fall back to the legacy raw card.
        String cardOrLastFour = placed.paymentMethodToken != null
                ? paymentMethods.lookup(placed.paymentMethodToken)
                        .map(PaymentMethods.PaymentMethodView::lastFour)
                        .orElse("")
                : placed.cardNumber;
        PaymentSimulator.PaymentOutcome outcome = simulator.process(cardOrLastFour, placed.total);
        String metricOutcome = switch (outcome) {
            case PaymentSimulator.PaymentOutcome.Succeeded s -> {
                events.publishEvent(new PaymentSucceeded(
                        UUID.randomUUID(), clock.instant(), placed.orderId, placed.customerId, s.amount()));
                yield "succeeded";
            }
            case PaymentSimulator.PaymentOutcome.Failed f -> {
                events.publishEvent(new PaymentFailed(
                        UUID.randomUUID(), clock.instant(), placed.orderId, placed.customerId, f.reason()));
                yield f.reason().name().toLowerCase(Locale.ROOT);
            }
        };
        // Counted only here, downstream of the markProcessed dedupe gate — redelivery never re-counts.
        meters.counter("payments_total", "outcome", metricOutcome).increment();
    }

    private static String textOrNull(JsonNode tree, String field) {
        JsonNode n = tree.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    /**
     * Local projection of OrderPlaced — Payment doesn't import the ordering module's record (module
     * boundary). Mirrors the JSON shape that Modulith externalises to ordering.events.
     */
    record OrderPlacedView(
            String eventType,
            UUID eventId,
            UUID orderId,
            UUID customerId,
            Money total,
            UUID paymentMethodToken,
            String cardNumber) {

        static final String EVENT_TYPE = "OrderPlaced";

        OrderPlacedView {
            if (total == null) {
                total = Money.zero("INR");
            }
            if (cardNumber == null) {
                cardNumber = "";
            }
        }
    }
}
