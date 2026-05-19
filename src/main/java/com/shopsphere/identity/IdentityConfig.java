package com.shopsphere.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
class IdentityConfig {

    @Bean
    PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }

    @Bean
    JwtIssuer jwtIssuer(@Value("${shopsphere.jwt.secret}") String secret,
                        @Value("${shopsphere.jwt.ttl:PT15M}") Duration ttl,
                        Clock clock) {
        return new JwtIssuer(secret, ttl, clock);
    }

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
