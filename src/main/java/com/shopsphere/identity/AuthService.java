package com.shopsphere.identity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
class AuthService {

    private final UserRepository users;
    private final CustomerRepository customers;
    private final PasswordHasher hasher;
    private final JwtIssuer jwt;
    private final RefreshTokenService refreshTokens;
    private final Clock clock;

    AuthService(UserRepository users,
                CustomerRepository customers,
                PasswordHasher hasher,
                JwtIssuer jwt,
                RefreshTokenService refreshTokens,
                Clock clock) {
        this.users = users;
        this.customers = customers;
        this.hasher = hasher;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional
    Registered register(String email, String rawPassword) {
        if (users.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }
        UUID customerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var now = clock.instant();
        customers.save(new Customer(customerId, now));
        users.save(new User(userId, customerId, email, hasher.hash(rawPassword), now));
        return new Registered(userId, customerId);
    }

    @Transactional
    Token login(String email, String rawPassword) {
        User user = users.findByEmail(email).orElseThrow(BadCredentialsException::new);
        if (!hasher.matches(rawPassword, user.getPasswordHash())) {
            throw new BadCredentialsException();
        }
        return issueTokens(user.getId(), user.getCustomerId());
    }

    @Transactional(noRollbackFor = RefreshTokenService.InvalidRefreshTokenException.class)
    Token refresh(String presentedRefreshToken) {
        RefreshTokenService.Issued issued = refreshTokens.rotate(presentedRefreshToken);
        User user = users.findById(issued.userId())
                .orElseThrow(RefreshTokenService.InvalidRefreshTokenException::new);
        String access = jwt.issue(user.getId(), user.getCustomerId());
        return new Token(access, jwt.ttlSeconds(), issued.rawToken(), issued.expiresInSeconds());
    }

    @Transactional
    void logout(String presentedRefreshToken) {
        refreshTokens.revoke(presentedRefreshToken);
    }

    private Token issueTokens(UUID userId, UUID customerId) {
        String access = jwt.issue(userId, customerId);
        RefreshTokenService.Issued issued = refreshTokens.issueNew(userId);
        return new Token(access, jwt.ttlSeconds(), issued.rawToken(), issued.expiresInSeconds());
    }

    record Registered(UUID userId, UUID customerId) {
    }

    record Token(String accessToken,
                 long expiresIn,
                 String refreshToken,
                 long refreshExpiresIn) {
    }

    static final class DuplicateEmailException extends RuntimeException {
    }

    static final class BadCredentialsException extends RuntimeException {
    }
}
