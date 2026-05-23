package com.shopsphere.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.catalog.Catalog;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CheckoutFlowIT {

    private static final UUID KEYBOARD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MOUSE    = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    Catalog catalog;

    @Test
    void successfulCheckoutReturns202AndPublishesOrderPlaced() throws Exception {
        String token = registerAndLogin();
        addToCart(token, KEYBOARD, 2);
        addToCart(token, MOUSE, 1);

        MvcResult posted = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"221B Baker Street\",\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn();

        UUID orderId = UUID.fromString(
                json.readTree(posted.getResponse().getContentAsString()).get("orderId").asText());

        // Stock reserved (held)
        assertThat(catalog.findHeldForOrder(orderId)).hasSize(2);

        // Cart empty after checkout
        mockMvc.perform(get("/api/v1/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        // Order detail user-scoped, snapshot intact
        mockMvc.perform(get("/api/v1/orders/" + orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.shippingAddress").value("221B Baker Street"))
                .andExpect(jsonPath("$.items.length()").value(2));

        // OrderPlaced event was published to ordering.events
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of("ordering.events"));
            JsonNode event = pollForOrderId(consumer, orderId);
            assertThat(event.get("orderId").asText()).isEqualTo(orderId.toString());
            assertThat(event.get("total").get("currency").asText()).isEqualTo("INR");
            assertThat(event.get("lines").isArray()).isTrue();
            assertThat(event.get("lines").size()).isEqualTo(2);
        }
    }

    @Test
    void emptyCartCheckoutReturns400() throws Exception {
        String token = registerAndLogin();
        // No cart items added.
        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"X\",\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void insufficientStockReturns409AndDoesNotMutateState() throws Exception {
        String token = registerAndLogin();
        addToCart(token, KEYBOARD, 9999);

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"X\",\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isConflict());

        // Cart still has the item; no order created
        mockMvc.perform(get("/api/v1/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void crossCustomerOrderDetailReturns404() throws Exception {
        String alice = registerAndLogin();
        String bob = registerAndLogin();
        addToCart(alice, MOUSE, 1);

        MvcResult posted = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"X\",\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID orderId = UUID.fromString(
                json.readTree(posted.getResponse().getContentAsString()).get("orderId").asText());

        mockMvc.perform(get("/api/v1/orders/" + orderId)
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound());
    }

    private JsonNode pollForOrderId(KafkaConsumer<String, String> consumer, UUID orderId) throws Exception {
        java.util.concurrent.atomic.AtomicReference<JsonNode> match = new java.util.concurrent.atomic.AtomicReference<>();
        await().atMost(30, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500)).until(() -> {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> r : records) {
                JsonNode tree = json.readTree(r.value());
                if (tree.has("orderId") && tree.get("orderId").asText().equals(orderId.toString())) {
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
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "checkout-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private void addToCart(String token, UUID productId, int qty) throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"%s\",\"qty\":%d}".formatted(productId, qty)))
                .andExpect(status().isOk());
    }

    private String registerAndLogin() throws Exception {
        String email = "ck+" + UUID.randomUUID() + "@example.com";
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
