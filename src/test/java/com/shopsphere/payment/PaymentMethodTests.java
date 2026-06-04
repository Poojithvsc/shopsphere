package com.shopsphere.payment;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMethodTests {

    private static final String CARD = "4242424242424242";
    private static final Instant NOW = Instant.parse("2026-06-04T00:00:00Z");

    @Test
    void tokenizeMintsAFreshTokenForTheSameCardEveryTime() {
        PaymentMethod first = PaymentMethod.tokenize(CARD, NOW);
        PaymentMethod second = PaymentMethod.tokenize(CARD, NOW);

        assertThat(first.token()).isNotNull();
        assertThat(second.token()).isNotNull();
        assertThat(first.token()).isNotEqualTo(second.token());
    }

    @Test
    void tokenizeKeepsOnlyTheLastFourDigits() {
        PaymentMethod method = PaymentMethod.tokenize(CARD, NOW);

        assertThat(method.lastFour()).isEqualTo("4242");
    }

    @Test
    void tokenizeStripsFormattingBeforeTakingLastFour() {
        PaymentMethod method = PaymentMethod.tokenize("4000 0000 0000 9995", NOW);

        assertThat(method.lastFour()).isEqualTo("9995");
    }

    @Test
    void vaultRefNeverContainsThePan() {
        PaymentMethod method = PaymentMethod.tokenize(CARD, NOW);

        // The vault reference is an opaque handle, not anything derived from the card number.
        assertThat(method.vaultRef()).doesNotContain("4242424242424242");
        assertThat(method.vaultRef()).startsWith("vault:");
    }

    @Test
    void tokenizeToleratesNullCard() {
        PaymentMethod method = PaymentMethod.tokenize(null, NOW);

        assertThat(method.token()).isNotNull();
        assertThat(method.lastFour()).isEmpty();
    }
}
