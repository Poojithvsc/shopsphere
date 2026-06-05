package com.shopsphere.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.common.OrderLog;
import com.shopsphere.common.ProcessedEvents;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
class PaymentEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsConsumer.class);
    static final String CONSUMER_ID = "ordering.payment-events";

    private final ObjectMapper mapper;
    private final OrderRepository orders;
    private final ProcessedEvents processedEvents;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PaymentEventsConsumer(ObjectMapper mapper,
                          OrderRepository orders,
                          JdbcTemplate jdbc,
                          ApplicationEventPublisher events,
                          Clock clock) {
        this.mapper = mapper;
        this.orders = orders;
        this.processedEvents = new ProcessedEvents(jdbc, "ordering");
        this.events = events;
        this.clock = clock;
    }

    @KafkaListener(topics = "payment.events", groupId = CONSUMER_ID)
    @Transactional
    public void onPaymentEvent(ConsumerRecord<String, String> record) throws Exception {
        JsonNode tree = mapper.readTree(record.value());
        String eventType = textOrNull(tree, "eventType");
        UUID eventId = UUID.fromString(tree.get("eventId").asText());
        UUID orderId = UUID.fromString(tree.get("orderId").asText());
        UUID customerId = UUID.fromString(tree.get("customerId").asText());

        if (!processedEvents.markProcessed(CONSUMER_ID, eventId)) {
            log.info("Duplicate payment event {} — skipping", eventId);
            return;
        }

        Instant now = clock.instant();
        Order order = orders.findById(orderId).orElseThrow(() ->
                new IllegalStateException("payment event for unknown order " + orderId));

        switch (eventType) {
            case "PaymentSucceeded" -> {
                order.transitionTo(OrderStatus.PAID, now);
                events.publishEvent(new OrderPaid(UUID.randomUUID(), now, orderId, customerId));
                OrderLog.withOrder(orderId, customerId, () -> log.info("Order marked PAID"));
            }
            case "PaymentFailed" -> {
                String reason = textOrNull(tree, "reason");
                order.transitionTo(OrderStatus.CANCELLED, now);
                events.publishEvent(new OrderCancelled(UUID.randomUUID(), now, orderId, customerId, reason));
                OrderLog.withOrder(orderId, customerId, () -> log.info("Order CANCELLED: {}", reason));
            }
            default -> log.warn("Unknown payment event type: {}", eventType);
        }
    }

    private static String textOrNull(JsonNode tree, String field) {
        JsonNode n = tree.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
