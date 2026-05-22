package com.shopsphere.identity;

import org.springframework.security.crypto.bcrypt.BCrypt;

final class RefreshTokenHasher {

    private static final int COST = 10;

    String hash(String raw) {
        return BCrypt.hashpw(raw, BCrypt.gensalt(COST));
    }

    boolean matches(String raw, String hash) {
        return BCrypt.checkpw(raw, hash);
    }
}
