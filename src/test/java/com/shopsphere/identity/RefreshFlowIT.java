package com.shopsphere.identity;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RefreshFlowIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Test
    void loginReturnsRefreshToken() throws Exception {
        String body = registerAndBody();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.refreshExpiresIn").value(7 * 24 * 60 * 60))
                .andReturn();
        assertThat(refreshToken(result)).contains(".");
    }

    @Test
    void refreshRotatesTokenAndInvalidatesOld() throws Exception {
        MvcResult login = loginFresh();
        String oldRefresh = refreshToken(login);

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(oldRefresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn();

        String newRefresh = refreshToken(refreshed);
        assertThat(newRefresh).isNotEqualTo(oldRefresh);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(oldRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replayingUsedTokenRevokesEntireFamily() throws Exception {
        MvcResult login = loginFresh();
        String first = refreshToken(login);

        MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(first)))
                .andExpect(status().isOk()).andReturn();
        String second = refreshToken(rotated);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(first)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(second)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        MvcResult login = loginFresh();
        String refresh = refreshToken(login);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refresh)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedRefreshTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not-a-real-token\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshedAccessTokenWorksOnProtectedEndpoint() throws Exception {
        MvcResult login = loginFresh();
        String oldRefresh = refreshToken(login);
        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(oldRefresh)))
                .andExpect(status().isOk()).andReturn();
        String newAccess = json.readTree(refreshed.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());
    }

    private MvcResult loginFresh() throws Exception {
        String body = registerAndBody();
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private String registerAndBody() throws Exception {
        String email = "refresh+" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"%s\",\"password\":\"hunter2hunter2\"}".formatted(email);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return body;
    }

    private String refreshToken(MvcResult result) throws Exception {
        JsonNode tree = json.readTree(result.getResponse().getContentAsString());
        return tree.get("refreshToken").asText();
    }
}
