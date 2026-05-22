package com.shopsphere.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenHasherTests {

    private final RefreshTokenHasher hasher = new RefreshTokenHasher();

    @Test
    void hashAndMatchRoundTrip() {
        String raw = "opaque-refresh-token-abc";
        String hash = hasher.hash(raw);
        assertThat(hasher.matches(raw, hash)).isTrue();
    }

    @Test
    void rejectsWrongToken() {
        String hash = hasher.hash("expected");
        assertThat(hasher.matches("other", hash)).isFalse();
    }
}
