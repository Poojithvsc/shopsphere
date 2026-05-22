package com.shopsphere.identity;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpaqueTokenGeneratorTests {

    private final OpaqueTokenGenerator generator = new OpaqueTokenGenerator();

    @Test
    void producesUniqueTokens() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(generator.generate())).isTrue();
        }
    }

    @Test
    void tokenIsUrlSafeBase64WithoutPadding() {
        String token = generator.generate();
        assertThat(token).matches("^[A-Za-z0-9_-]+$");
        assertThat(token).doesNotContain("=");
        assertThat(token.length()).isBetween(40, 64);
    }
}
