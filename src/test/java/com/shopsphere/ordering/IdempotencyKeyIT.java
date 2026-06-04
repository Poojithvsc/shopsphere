package com.shopsphere.ordering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.SharedContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Idempotency-Key dedupe on POST /api/v1/orders. Covers the three contractual scenarios: same
 * key + same payload returns the cached order, same key + different payload is rejected 422, and
 * concurrent same-key requests produce exactly one order (arbitrated by the DB unique constraint).
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyKeyIT {

    private static final UUID KEYBOARD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String CARD = "4242424242424242";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sameKeySamePayloadReturnsCachedOrder() throws Exception {
        String token = registerAndLogin();
        addToCart(token, KEYBOARD, 1);
        String key = UUID.randomUUID().toString();
        String body = body("221B Baker Street");

        UUID first = placeOrderId(token, body, key);
        // Cart is now empty, but the replay must short-circuit before touching it and return the
        // same order rather than failing with an empty-cart error.
        UUID second = placeOrderId(token, body, key);

        assertThat(second).isEqualTo(first);
        assertThat(orderCount(token, first)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentPayloadIsRejected() throws Exception {
        String token = registerAndLogin();
        addToCart(token, KEYBOARD, 1);
        String key = UUID.randomUUID().toString();

        placeOrderId(token, body("221B Baker Street"), key);

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("10 Downing Street")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void concurrentSameKeyRequestsProduceExactlyOneOrder() throws Exception {
        String token = registerAndLogin();
        addToCart(token, KEYBOARD, 1);
        String key = UUID.randomUUID().toString();
        String body = body("221B Baker Street");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReferenceArray<UUID> results = new AtomicReferenceArray<>(threads);

        try {
            for (int i = 0; i < threads; i++) {
                int slot = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        results.set(slot, placeOrderId(token, body, key));
                    } catch (Exception e) {
                        // leave slot null on failure
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        UUID a = results.get(0);
        UUID b = results.get(1);
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        // Both callers see the same order, and the DB holds exactly one.
        assertThat(a).isEqualTo(b);
        assertThat(orderCount(token, a)).isEqualTo(1);
        assertThat(idempotencyRowCount(key)).isEqualTo(1);
    }

    private int orderCount(String token, UUID orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ordering.orders WHERE id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private int idempotencyRowCount(String key) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ordering.idempotency_keys WHERE idempotency_key = ?", Integer.class, key);
        return count == null ? 0 : count;
    }

    private UUID placeOrderId(String token, String body, String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .get("orderId").asText());
    }

    private static String body(String shippingAddress) {
        return "{\"shippingAddress\":\"%s\",\"cardNumber\":\"%s\"}".formatted(shippingAddress, CARD);
    }

    private void addToCart(String token, UUID productId, int qty) throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"%s\",\"qty\":%d}".formatted(productId, qty)))
                .andExpect(status().isOk());
    }

    private String registerAndLogin() throws Exception {
        String email = "idem+" + UUID.randomUUID() + "@example.com";
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
