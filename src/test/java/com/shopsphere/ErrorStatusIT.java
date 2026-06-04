package com.shopsphere;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Runs against a REAL servlet container (RANDOM_PORT), not MockMvc, on purpose.
 * <p>
 * The bug these tests pin — Spring Security masking every error as 401 because the
 * container's ERROR dispatch to {@code /error} was not permitted — is invisible to MockMvc,
 * which never performs that dispatch. That is exactly why the MockMvc-based suites stayed
 * green while the running application returned 401 for malformed/4xx requests on otherwise
 * public ({@code permitAll}) paths. Each path below is permitAll, so the ONLY thing that can
 * turn its natural 4xx into a 401 is the error-dispatch gap.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class ErrorStatusIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void malformedJsonOnPublicEndpointReturns400NotMasked401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/auth/register",
                new HttpEntity<>("{not-valid-json", headers),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void invalidBodyOnPublicEndpointReturns400NotMasked401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Well-formed JSON, but password too short and email malformed -> bean validation 400.
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/auth/register",
                new HttpEntity<>("{\"email\":\"nope\",\"password\":\"short\"}", headers),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void wrongHttpMethodOnPublicEndpointReturns405NotMasked401() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/auth/register", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(405);
    }

    @Test
    void unknownPathUnderPublicPrefixReturns404NotMasked401() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/does-not-exist", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
