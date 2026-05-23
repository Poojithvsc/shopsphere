package com.shopsphere.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.common.ProcessedEvents;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
class CatalogOrderingConsumer {

    private static final Logger log = LoggerFactory.getLogger(CatalogOrderingConsumer.class);
    static final String CONSUMER_ID = "catalog.ordering-terminal-events";

    private final ObjectMapper mapper;
    private final Catalog catalog;
    private final ProcessedEvents processedEvents;

    CatalogOrderingConsumer(ObjectMapper mapper, Catalog catalog, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.processedEvents = new ProcessedEvents(jdbc, "catalog");
    }

    @KafkaListener(topics = "ordering.events", groupId = CONSUMER_ID)
    @Transactional
    public void onOrderingEvent(ConsumerRecord<String, String> record) throws Exception {
        JsonNode tree = mapper.readTree(record.value());
        String eventType = textOrNull(tree, "eventType");
        if (!"OrderPaid".equals(eventType) && !"OrderCancelled".equals(eventType)) {
            return;
        }
        UUID eventId = UUID.fromString(tree.get("eventId").asText());
        UUID orderId = UUID.fromString(tree.get("orderId").asText());

        if (!processedEvents.markProcessed(CONSUMER_ID, eventId)) {
            log.info("Duplicate {} event {} — skipping", eventType, eventId);
            return;
        }

        switch (eventType) {
            case "OrderPaid" -> catalog.confirm(orderId);
            case "OrderCancelled" -> catalog.release(orderId);
            default -> { /* unreachable */ }
        }
    }

    private static String textOrNull(JsonNode tree, String field) {
        JsonNode n = tree.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
