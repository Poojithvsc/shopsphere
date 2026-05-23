package com.shopsphere.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.SharedContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CartFlowIT {

    private static final UUID KEYBOARD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MOUSE    = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final BigDecimal KEYBOARD_PRICE = new BigDecimal("8499.0000");
    private static final BigDecimal MOUSE_PRICE    = new BigDecimal("5999.0000");

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Test
    void getCartAutoCreatesAndStartsEmpty() throws Exception {
        String token = registerAndLogin();
        mockMvc.perform(get("/api/v1/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.grandTotal.amount").value(0))
                .andExpect(jsonPath("$.grandTotal.currency").value("INR"));
    }

    @Test
    void addPatchDeleteLifecycleWithTotals() throws Exception {
        String token = registerAndLogin();

        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"%s\",\"qty\":2}".formatted(KEYBOARD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(KEYBOARD.toString()))
                .andExpect(jsonPath("$.items[0].qty").value(2))
                .andExpect(jsonPath("$.items[0].lineTotal.amount").value(KEYBOARD_PRICE.multiply(BigDecimal.valueOf(2)).doubleValue()))
                .andExpect(jsonPath("$.grandTotal.amount").value(KEYBOARD_PRICE.multiply(BigDecimal.valueOf(2)).doubleValue()))

                .andExpect(jsonPath("$.grandTotal.currency").value("INR"));

        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"%s\",\"qty\":1}".formatted(MOUSE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(patch("/api/v1/cart/items/" + KEYBOARD)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grandTotal.amount").value(
                        KEYBOARD_PRICE.multiply(BigDecimal.valueOf(3)).add(MOUSE_PRICE).doubleValue()));

        mockMvc.perform(patch("/api/v1/cart/items/" + MOUSE)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.grandTotal.amount").value(
                        KEYBOARD_PRICE.multiply(BigDecimal.valueOf(3)).doubleValue()));

        mockMvc.perform(delete("/api/v1/cart/items/" + KEYBOARD)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.grandTotal.amount").value(0));
    }

    @Test
    void postSameProductTwiceIncrementsQty() throws Exception {
        String token = registerAndLogin();
        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"%s\",\"qty\":1}".formatted(KEYBOARD)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"%s\",\"qty\":2}".formatted(KEYBOARD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].qty").value(3));
    }

    @Test
    void postUnknownProductReturns404() throws Exception {
        String token = registerAndLogin();
        UUID nope = UUID.fromString("99999999-9999-9999-9999-999999999999");
        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"%s\",\"qty\":1}".formatted(nope)))
                .andExpect(status().isNotFound());
    }

    @Test
    void postZeroQtyReturns400() throws Exception {
        String token = registerAndLogin();
        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"%s\",\"qty\":0}".formatted(KEYBOARD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cartIsIsolatedPerCustomer() throws Exception {
        String aliceToken = registerAndLogin();
        String bobToken = registerAndLogin();

        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"%s\",\"qty\":2}".formatted(KEYBOARD)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cart").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void cartPersistsAcrossRefresh() throws Exception {
        String email = "cart+" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"%s\",\"password\":\"hunter2hunter2\"}".formatted(email);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode loginTree = json.readTree(login.getResponse().getContentAsString());
        String access1 = loginTree.get("accessToken").asText();
        String refresh = loginTree.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + access1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"%s\",\"qty\":1}".formatted(KEYBOARD)))
                .andExpect(status().isOk());

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refresh)))
                .andExpect(status().isOk()).andReturn();
        String access2 = json.readTree(refreshed.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get("/api/v1/cart").header("Authorization", "Bearer " + access2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].qty").value(1));
    }

    private String registerAndLogin() throws Exception {
        String email = "cart+" + UUID.randomUUID() + "@example.com";
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
