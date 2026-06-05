package com.shopsphere.ordering;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler so {@link IdempotencyRetention#scheduledSweep()} fires. Scoped to the
 * Ordering module rather than the application class to keep the concern next to its only user.
 */
@Configuration
@EnableScheduling
class OrderingConfig {
}
