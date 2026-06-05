package com.shopsphere.payment;

import com.shopsphere.common.Money;

import java.util.UUID;

/**
 * The Payment context's boundary to "actually charge an instrument". A provider takes a tokenized
 * payment method (never a raw PAN — the token is the only handle that crosses into here) and an
 * amount, and reports whether the charge succeeded. {@code Money} carries both the amount and its
 * currency, so it stands in for the "amount + currency" pair the charge needs.
 * <p>
 * Two implementations exist — {@link SimulatedProvider} (deterministic, offline) and a Stripe
 * Test-Mode provider — selected at boot by {@code shopsphere.payment.provider}. The consumer that
 * reacts to {@code OrderPlaced} depends only on this interface, so swapping providers changes no
 * caller and no event shape.
 */
public interface PaymentProvider {

    ChargeOutcome charge(UUID paymentMethodToken, Money amount);

    sealed interface ChargeOutcome {
        static ChargeOutcome succeeded(Money amount) {
            return new Succeeded(amount);
        }

        static ChargeOutcome failed(PaymentFailed.Reason reason) {
            return new Failed(reason);
        }

        record Succeeded(Money amount) implements ChargeOutcome {
        }

        record Failed(PaymentFailed.Reason reason) implements ChargeOutcome {
        }
    }
}
