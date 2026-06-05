package com.shopsphere.payment;

import com.shopsphere.common.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SimulatedProvider} adapts the existing {@link PaymentSimulator} to the {@link PaymentProvider}
 * boundary: it resolves the token to its redacted last-four (the PAN is long gone) and decides from
 * that. These cover the approve/decline/insufficient branches plus an unknown token (no last-four).
 */
class SimulatedProviderTests {

    private final Money amount = Money.of(new BigDecimal("1000.0000"), "INR");

    private SimulatedProvider providerWith(Map<UUID, String> lastFourByToken) {
        PaymentMethods methods = new PaymentMethods() {
            @Override
            public UUID tokenize(String rawCard) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<PaymentMethodView> lookup(UUID token) {
                return Optional.ofNullable(lastFourByToken.get(token))
                        .map(lastFour -> new PaymentMethodView(token, lastFour));
            }
        };
        return new SimulatedProvider(methods, new PaymentSimulator());
    }

    @Test
    void successTokenChargesForTheFullAmount() {
        UUID token = UUID.randomUUID();
        SimulatedProvider provider = providerWith(Map.of(token, "4242"));

        PaymentProvider.ChargeOutcome outcome = provider.charge(token, amount);

        assertThat(outcome).isInstanceOfSatisfying(PaymentProvider.ChargeOutcome.Succeeded.class,
                s -> assertThat(s.amount()).isEqualTo(amount));
    }

    @Test
    void declinedTokenFailsWithDeclined() {
        UUID token = UUID.randomUUID();
        SimulatedProvider provider = providerWith(Map.of(token, "0002"));

        PaymentProvider.ChargeOutcome outcome = provider.charge(token, amount);

        assertThat(outcome).isInstanceOfSatisfying(PaymentProvider.ChargeOutcome.Failed.class,
                f -> assertThat(f.reason()).isEqualTo(PaymentFailed.Reason.DECLINED));
    }

    @Test
    void insufficientFundsTokenFailsWithInsufficientFunds() {
        UUID token = UUID.randomUUID();
        SimulatedProvider provider = providerWith(Map.of(token, "9995"));

        PaymentProvider.ChargeOutcome outcome = provider.charge(token, amount);

        assertThat(outcome).isInstanceOfSatisfying(PaymentProvider.ChargeOutcome.Failed.class,
                f -> assertThat(f.reason()).isEqualTo(PaymentFailed.Reason.INSUFFICIENT_FUNDS));
    }

    @Test
    void unknownTokenIsTreatedAsDeclined() {
        SimulatedProvider provider = providerWith(Map.of());

        PaymentProvider.ChargeOutcome outcome = provider.charge(UUID.randomUUID(), amount);

        assertThat(outcome).isInstanceOfSatisfying(PaymentProvider.ChargeOutcome.Failed.class,
                f -> assertThat(f.reason()).isEqualTo(PaymentFailed.Reason.DECLINED));
    }
}
