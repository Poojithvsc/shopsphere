package com.shopsphere.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.SharedContainers;
import com.shopsphere.catalog.Catalog;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentFlowIT {

    private static final UUID KEYBOARD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SUCCESS_CARD = "4242424242424242";
    private static final String DECLINED_CARD = "4000000000000002";
    private static final String INSUFFICIENT_FUNDS_CARD = "4000000000009995";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    Catalog catalog;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void successCardLeadsToPaidAndConfirmedReservation() throws Exception {
        int qty = 1;
        Integer before = currentQty(KEYBOARD);
        String token = registerAndLogin();
        addToCart(token, KEYBOARD, qty);

        UUID orderId = placeOrder(token, SUCCESS_CARD);

        awaitOrderStatus(token, orderId, "PAID");

        // Reservation flipped to CONFIRMED, available qty reflects the sold units
        assertThat(catalog.findHeldForOrder(orderId)).isEmpty();
        assertThat(reservationStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(currentQty(KEYBOARD)).isEqualTo(before - qty);
    }

    @Test
    void declinedCardLeadsToCancelledAndReleasedReservation() throws Exception {
        int qty = 1;
        Integer before = currentQty(KEYBOARD);
        String token = registerAndLogin();
        addToCart(token, KEYBOARD, qty);

        UUID orderId = placeOrder(token, DECLINED_CARD);

        awaitOrderStatus(token, orderId, "CANCELLED");

        assertThat(catalog.findHeldForOrder(orderId)).isEmpty();
        assertThat(reservationStatus(orderId)).isEqualTo("RELEASED");
        assertThat(currentQty(KEYBOARD)).isEqualTo(before);
    }

    @Test
    void insufficientFundsCardCancelsWithInsufficientFundsReason() throws Exception {
        String token = registerAndLogin();
        addToCart(token, KEYBOARD, 1);

        UUID orderId = placeOrder(token, INSUFFICIENT_FUNDS_CARD);
        awaitOrderStatus(token, orderId, "CANCELLED");

        // Find the OrderCancelled event on ordering.events and confirm the reason
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of("ordering.events"));
            JsonNode event = pollForEvent(consumer, "OrderCancelled", orderId);
            assertThat(event.get("reason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        }
    }

    @Test
    void redeliveredPaymentEventIsDeduped() throws Exception {
        String token = registerAndLogin();
        addToCart(token, KEYBOARD, 1);
        UUID orderId = placeOrder(token, SUCCESS_CARD);
        awaitOrderStatus(token, orderId, "PAID");

        // Capture the PaymentSucceeded event off payment.events
        JsonNode captured;
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of("payment.events"));
            captured = pollForEvent(consumer, "PaymentSucceeded", orderId);
        }

        int rowsBefore = countProcessed("ordering.payment-events", captured.get("eventId").asText());
        assertThat(rowsBefore).isEqualTo(1);

        // Manually republish the same event
        KafkaTemplate<String, String> producer = newProducer();
        try {
            producer.send(new ProducerRecord<>("payment.events", json.writeValueAsString(captured))).get(5, TimeUnit.SECONDS);
        } finally {
            producer.destroy();
        }

        // Give the consumer time to receive + dedupe; status must remain PAID and processed_events row unchanged
        Thread.sleep(2000);
        awaitOrderStatus(token, orderId, "PAID");
        int rowsAfter = countProcessed("ordering.payment-events", captured.get("eventId").asText());
        assertThat(rowsAfter).isEqualTo(1);
    }

    private int countProcessed(String consumerId, String eventId) {
        // ordering.payment-events writes into ordering.processed_events
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ordering.processed_events WHERE consumer_id = ? AND event_id = ?::uuid",
                Integer.class, consumerId, eventId);
        return count == null ? 0 : count;
    }

    private String reservationStatus(UUID orderId) {
        return jdbc.queryForObject(
                "SELECT status FROM catalog.stock_reservations WHERE order_id = ?",
                String.class, orderId);
    }

    private Integer currentQty(UUID productId) {
        return jdbc.queryForObject(
                "SELECT available_qty FROM catalog.products WHERE id = ?",
                Integer.class, productId);
    }

    private UUID placeOrder(String token, String cardNumber) throws Exception {
        MvcResult posted = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"X\",\"cardNumber\":\"%s\"}".formatted(cardNumber)))
                .andExpect(status().isAccepted())
                .andReturn();
        return UUID.fromString(json.readTree(posted.getResponse().getContentAsString())
                .get("orderId").asText());
    }

    private void awaitOrderStatus(String token, UUID orderId, String terminalStatus) {
        await().atMost(30, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            MvcResult result = mockMvc.perform(get("/api/v1/orders/" + orderId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            String status = json.readTree(result.getResponse().getContentAsString())
                    .get("status").asText();
            assertThat(status).isEqualTo(terminalStatus);
        });
    }

    private JsonNode pollForEvent(KafkaConsumer<String, String> consumer, String eventType, UUID orderId) throws Exception {
        java.util.concurrent.atomic.AtomicReference<JsonNode> match = new java.util.concurrent.atomic.AtomicReference<>();
        await().atMost(30, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500)).until(() -> {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> r : records) {
                JsonNode tree = json.readTree(r.value());
                if (eventType.equals(tree.path("eventType").asText())
                        && orderId.toString().equals(tree.path("orderId").asText())) {
                    match.set(tree);
                    return true;
                }
            }
            return false;
        });
        return match.get();
    }

    private KafkaConsumer<String, String> newConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.kafkaBootstrap());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private KafkaTemplate<String, String> newProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.kafkaBootstrap());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    private void addToCart(String token, UUID productId, int qty) throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"%s\",\"qty\":%d}".formatted(productId, qty)))
                .andExpect(status().isOk());
    }

    private String registerAndLogin() throws Exception {
        String email = "pay+" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"%s\",\"password\":\"hunter2hunter2\"}".formatted(email);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
