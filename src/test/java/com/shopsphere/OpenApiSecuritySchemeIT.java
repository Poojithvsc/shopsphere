package com.shopsphere;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The published OpenAPI contract must advertise a bearer-JWT security scheme so Swagger UI
 * renders the "Authorize" button — without it the browser cannot attach the token to the
 * protected endpoints, leaving the catalog/cart/checkout flow undrivable from the UI.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSecuritySchemeIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void apiDocsAdvertiseBearerJwtSecurityScheme() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
    }

    @Test
    void protectedEndpointRequiresBearerAuthInContract() throws Exception {
        // A representative protected operation carries the security requirement, so Swagger
        // sends the token (and shows the lock icon) for it.
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/products'].get.security[0].bearerAuth").exists());
    }
}
