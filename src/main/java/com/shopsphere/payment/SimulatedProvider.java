package com.shopsphere.payment;

import com.shopsphere.common.Money;

import java.util.UUID;

/**
 * Offline {@link PaymentProvider} backing the default {@code simulator} mode. It resolves the token
 * to its redacted last-four — the PAN was tokenized away at checkout, so the last-four is all the
 * decision can see — and delegates to {@link PaymentSimulator}, which keys approve/decline/insufficient
 * off those four digits. An unknown token has no last-four and is treated as a decline.
 */
final class SimulatedProvider implements PaymentProvider {

    private final PaymentMethods paymentMethods;
    private final PaymentSimulator simulator;

    SimulatedProvider(PaymentMethods paymentMethods, PaymentSimulator simulator) {
        this.paymentMethods = paymentMethods;
        this.simulator = simulator;
    }

    @Override
    public ChargeOutcome charge(UUID paymentMethodToken, Money amount) {
        String lastFour = paymentMethods.lookup(paymentMethodToken)
                .map(PaymentMethods.PaymentMethodView::lastFour)
                .orElse("");
        return switch (simulator.process(lastFour, amount)) {
            case PaymentSimulator.PaymentOutcome.Succeeded s -> ChargeOutcome.succeeded(s.amount());
            case PaymentSimulator.PaymentOutcome.Failed f -> ChargeOutcome.failed(f.reason());
        };
    }
}
