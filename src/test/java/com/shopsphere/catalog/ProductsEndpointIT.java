package com.shopsphere.catalog;

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

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductsEndpointIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Test
    void getProducts_returnsSeededCatalog() throws Exception {
        String token = authenticatedBearer();

        // Size is "at least 3" because other ITs may seed additional products against the shared
        // Postgres. The Flyway-seeded three must always be present.
        mockMvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$[*].name", hasItem("Aurora Mechanical Keyboard")))
                .andExpect(jsonPath("$[*].name", hasItem("Nimbus Wireless Mouse")))
                .andExpect(jsonPath("$[*].name", hasItem("Vertex 27-inch 4K Monitor")));
    }

    private String authenticatedBearer() throws Exception {
        String body = """
                {"email":"products-it+%s@example.com","password":"hunter2hunter2"}
                """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
