package com.shopsphere.catalog;

import com.shopsphere.SharedContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProductImageStorage} against a real S3 API (LocalStack via Testcontainers, so the dev/test
 * path exercises the same code as cloud). An uploaded object is fetchable through its presigned URL;
 * once the URL's TTL elapses the same URL is rejected.
 */
@SpringBootTest
class ProductImageStorageIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    ProductImageStorage storage;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void uploadedImageIsFetchableViaPresignedUrl() throws Exception {
        UUID productId = UUID.randomUUID();
        byte[] bytes = "the-image-bytes".getBytes(StandardCharsets.UTF_8);

        String key = storage.upload(productId, bytes, "image/png");
        String url = storage.presignedRead(key, Duration.ofMinutes(5));

        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(bytes);
    }

    @Test
    void presignedUrlIsRejectedAfterItExpires() throws Exception {
        UUID productId = UUID.randomUUID();
        String key = storage.upload(productId, "x".getBytes(StandardCharsets.UTF_8), "image/png");

        String url = storage.presignedRead(key, Duration.ofSeconds(2));
        Thread.sleep(3_000);

        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(403);
    }
}
