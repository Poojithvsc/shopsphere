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
    private final Clock clock;

    AuthService(UserRepository users,
                CustomerRepository customers,
                PasswordHasher hasher,
                JwtIssuer jwt,
                Clock clock) {
        this.users = users;
        this.customers = customers;
        this.hasher = hasher;
        this.jwt = jwt;
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

    @Transactional(readOnly = true)
    Token login(String email, String rawPassword) {
        User user = users.findByEmail(email).orElseThrow(BadCredentialsException::new);
        if (!hasher.matches(rawPassword, user.getPasswordHash())) {
            throw new BadCredentialsException();
        }
        String token = jwt.issue(user.getId(), user.getCustomerId());
        return new Token(token, jwt.ttlSeconds());
    }

    record Registered(UUID userId, UUID customerId) {
    }

    record Token(String accessToken, long expiresIn) {
    }

    static final class DuplicateEmailException extends RuntimeException {
    }

    static final class BadCredentialsException extends RuntimeException {
    }
}
