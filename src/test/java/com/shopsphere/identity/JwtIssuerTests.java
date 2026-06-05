package com.shopsphere.identity;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtIssuerTests {

    private static final String SECRET = "test-secret-test-secret-test-secret-32+";
    private static final String OTHER_SECRET = "another-secret-another-secret-32+chars!";

    private final Instant fixed = Instant.parse("2026-05-19T10:00:00Z");
    private final Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);
    private final JwtIssuer issuer = new JwtIssuer(SECRET, Duration.ofMinutes(15), clock);

    @Test
    void issueAndVerifyRoundTrip() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String token = issuer.issue(userId, customerId, List.of("USER", "ADMIN"));

        JwtIssuer.Verified verified = issuer.verify(token);

        assertThat(verified.userId()).isEqualTo(userId);
        assertThat(verified.customerId()).isEqualTo(customerId);
        assertThat(verified.roles()).containsExactly("USER", "ADMIN");
    }

    @Test
    void missingRolesClaimDefaultsToUser() {
        // Tokens minted before roles existed (or with no roles) must still authenticate as a plain user.
        UUID userId = UUID.randomUUID();
        String token = issuer.issue(userId, UUID.randomUUID(), List.of());

        assertThat(issuer.verify(token).roles()).containsExactly("USER");
    }

    @Test
    void expiredTokenIsRejected() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String token = issuer.issue(userId, customerId, List.of("USER"));

        Clock later = Clock.fixed(fixed.plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
        JwtIssuer laterIssuer = new JwtIssuer(SECRET, Duration.ofMinutes(15), later);

        assertThatThrownBy(() -> laterIssuer.verify(token))
                .isInstanceOf(JwtIssuer.InvalidTokenException.class);
    }

    @Test
    void tamperedSignatureIsRejected() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String token = issuer.issue(userId, customerId, List.of("USER"));

        JwtIssuer differentSigner = new JwtIssuer(OTHER_SECRET, Duration.ofMinutes(15), clock);

        assertThatThrownBy(() -> differentSigner.verify(token))
                .isInstanceOf(JwtIssuer.InvalidTokenException.class);
    }

    @Test
    void garbageTokenIsRejected() {
        assertThatThrownBy(() -> issuer.verify("not-a-real-token"))
                .isInstanceOf(JwtIssuer.InvalidTokenException.class);
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtIssuer("too-short", Duration.ofMinutes(15), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ttlSecondsReflectsConfiguredDuration() {
        assertThat(issuer.ttlSeconds()).isEqualTo(900);
    }
}
