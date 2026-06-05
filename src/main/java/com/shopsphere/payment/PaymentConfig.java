package com.shopsphere.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the active {@link PaymentProvider}. Defaults to the offline {@link SimulatedProvider}; the
 * {@code shopsphere.payment.provider} toggle (added in the Stripe slice) selects an alternative at
 * boot without touching any caller.
 */
@Configuration
class PaymentConfig {

    @Bean
    PaymentProvider paymentProvider(PaymentMethods paymentMethods, PaymentSimulator simulator) {
        return new SimulatedProvider(paymentMethods, simulator);
    }
}
