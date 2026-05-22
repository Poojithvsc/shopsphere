package com.shopsphere.identity;

import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

class RefreshTokenService {

    private final RefreshTokenRepository tokens;
    private final OpaqueTokenGenerator generator;
    private final RefreshTokenHasher hasher;
    private final Duration ttl;
    private final Clock clock;

    RefreshTokenService(RefreshTokenRepository tokens,
                        OpaqueTokenGenerator generator,
                        RefreshTokenHasher hasher,
                        Duration refreshTtl,
                        Clock clock) {
        this.tokens = tokens;
        this.generator = generator;
        this.hasher = hasher;
        this.ttl = refreshTtl;
        this.clock = clock;
    }

    @Transactional
    Issued issueNew(UUID userId) {
        UUID id = UUID.randomUUID();
        return issue(userId, id, id, null);
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    Issued rotate(String presented) {
        ParsedToken parsed = ParsedToken.parse(presented);
        RefreshToken existing = tokens.findById(parsed.id())
                .orElseThrow(InvalidRefreshTokenException::new);
        if (!hasher.matches(parsed.secret(), existing.getTokenHash())) {
            throw new InvalidRefreshTokenException();
        }
        Instant now = clock.instant();
        if (existing.wasUsed()) {
            revokeFamily(existing.getFamilyId(), now);
            throw new InvalidRefreshTokenException();
        }
        if (!existing.isUsable(now)) {
            throw new InvalidRefreshTokenException();
        }
        existing.markUsed(now);
        UUID childId = UUID.randomUUID();
        return issue(existing.getUserId(), childId, existing.getFamilyId(), existing.getId());
    }

    @Transactional
    void revoke(String presented) {
        ParsedToken parsed;
        try {
            parsed = ParsedToken.parse(presented);
        } catch (InvalidRefreshTokenException e) {
            return;
        }
        tokens.findById(parsed.id()).ifPresent(token -> {
            if (hasher.matches(parsed.secret(), token.getTokenHash())) {
                token.revoke(clock.instant());
            }
        });
    }

    private Issued issue(UUID userId, UUID id, UUID familyId, UUID parentId) {
        String secret = generator.generate();
        Instant now = clock.instant();
        RefreshToken token = new RefreshToken(
                id,
                userId,
                hasher.hash(secret),
                familyId,
                parentId,
                now.plus(ttl),
                now);
        tokens.save(token);
        return new Issued(userId, id + "." + secret, ttl.toSeconds());
    }

    private void revokeFamily(UUID familyId, Instant when) {
        for (RefreshToken sibling : tokens.findAllByFamilyId(familyId)) {
            sibling.revoke(when);
        }
    }

    record Issued(UUID userId, String rawToken, long expiresInSeconds) {
    }

    private record ParsedToken(UUID id, String secret) {
        static ParsedToken parse(String raw) {
            if (raw == null) {
                throw new InvalidRefreshTokenException();
            }
            int dot = raw.indexOf('.');
            if (dot <= 0 || dot == raw.length() - 1) {
                throw new InvalidRefreshTokenException();
            }
            try {
                UUID id = UUID.fromString(raw.substring(0, dot));
                return new ParsedToken(id, raw.substring(dot + 1));
            } catch (IllegalArgumentException e) {
                throw new InvalidRefreshTokenException();
            }
        }
    }

    static final class InvalidRefreshTokenException extends RuntimeException {
    }
}
