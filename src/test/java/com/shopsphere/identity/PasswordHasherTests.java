package com.shopsphere.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTests {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashAndMatch() {
        String hash = hasher.hash("correct horse battery staple");
        assertThat(hasher.matches("correct horse battery staple", hash)).isTrue();
    }

    @Test
    void rejectsWrongPassword() {
        String hash = hasher.hash("hunter2");
        assertThat(hasher.matches("hunter3", hash)).isFalse();
    }

    @Test
    void hashIsSaltedSoCallsProduceDifferentOutputs() {
        String a = hasher.hash("same-password");
        String b = hasher.hash("same-password");
        assertThat(a).isNotEqualTo(b);
        assertThat(hasher.matches("same-password", a)).isTrue();
        assertThat(hasher.matches("same-password", b)).isTrue();
    }
}
