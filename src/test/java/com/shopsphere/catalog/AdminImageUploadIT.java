package com.shopsphere.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.SharedContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Image upload is admin-only — the same {@code hasRole('ADMIN')} guard as the rest of the admin
 * product API (closing the guard deferred in Phase 17 / ADR-0017). Covers USER→403, admin→200,
 * an unsupported content type→400, and an unknown product→404.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminImageUploadIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    private MockMultipartFile png() {
        return new MockMultipartFile("file", "pic.png", "image/png", "fake-png".getBytes());
    }

    @Test
    void normalUserCannotUploadImage() throws Exception {
        UUID productId = createProduct();
        mockMvc.perform(multipart("/api/v1/admin/products/" + productId + "/image")
                        .file(png())
                        .header("Authorization", "Bearer " + registerAndLogin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanUploadImage() throws Exception {
        UUID productId = createProduct();
        mockMvc.perform(multipart("/api/v1/admin/products/" + productId + "/image")
                        .file(png())
                        .header("Authorization", "Bearer " + loginAsAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void uploadedImageIsServedAsAWorkingPresignedUrlOnProductRead() throws Exception {
        String admin = loginAsAdmin();
        UUID productId = createProduct();
        byte[] bytes = "the-real-png-bytes".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/api/v1/admin/products/" + productId + "/image")
                        .file(new MockMultipartFile("file", "pic.png", "image/png", bytes))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        MvcResult read = mockMvc.perform(get("/api/v1/products/" + productId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        String imageUrl = json.readTree(read.getResponse().getContentAsString()).get("imageUrl").asText();

        // The presigned URL the API handed back must actually resolve to the uploaded bytes.
        HttpResponse<byte[]> fetched = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(imageUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(bytes);
    }

    @Test
    void productWithoutImageHasNullImageUrl() throws Exception {
        String admin = loginAsAdmin();
        UUID productId = createProduct();

        mockMvc.perform(get("/api/v1/products/" + productId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(nullValue()));
    }

    @Test
    void unsupportedContentTypeIsRejected() throws Exception {
        UUID productId = createProduct();
        mockMvc.perform(multipart("/api/v1/admin/products/" + productId + "/image")
                        .file(new MockMultipartFile("file", "note.txt", "text/plain", "nope".getBytes()))
                        .header("Authorization", "Bearer " + loginAsAdmin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadingToUnknownProductReturns404() throws Exception {
        mockMvc.perform(multipart("/api/v1/admin/products/" + UUID.randomUUID() + "/image")
                        .file(png())
                        .header("Authorization", "Bearer " + loginAsAdmin()))
                .andExpect(status().isNotFound());
    }

    private UUID createProduct() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/products")
                        .header("Authorization", "Bearer " + loginAsAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Imaged Widget","description":"d","unitPrice":{"amount":"500.0000","currency":"INR"},"availableQty":1}
                                """))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }

    private String loginAsAdmin() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@shopsphere.local","password":"admin12345admin"}
                                """))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String registerAndLogin() throws Exception {
        String body = """
                {"email":"img-it+%s@example.com","password":"hunter2hunter2"}
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
