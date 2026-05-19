package com.shopsphere.identity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

final class JwtIssuer {

    private final SecretKey key;
    private final Duration ttl;
    private final java.time.Clock clock;

    JwtIssuer(String secret, Duration ttl, java.time.Clock clock) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = ttl;
        this.clock = clock;
    }

    String issue(UUID userId, UUID customerId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("userId", userId.toString())
                .claim("customerId", customerId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    long ttlSeconds() {
        return ttl.toSeconds();
    }

    Verified verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            UUID userId = UUID.fromString(claims.get("userId", String.class));
            UUID customerId = UUID.fromString(claims.get("customerId", String.class));
            return new Verified(userId, customerId);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException(e);
        }
    }

    record Verified(UUID userId, UUID customerId) {
    }

    static final class InvalidTokenException extends RuntimeException {
        InvalidTokenException(Throwable cause) {
            super(cause);
        }
    }
}
