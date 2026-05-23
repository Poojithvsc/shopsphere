package com.shopsphere.payment;

import com.shopsphere.common.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSimulatorTests {

    private final PaymentSimulator simulator = new PaymentSimulator();
    private final Money amount = Money.of(new BigDecimal("1000.0000"), "INR");

    @Test
    void successCardSucceedsWithSameAmount() {
        PaymentSimulator.PaymentOutcome outcome = simulator.process("4242424242424242", amount);

        assertThat(outcome).isInstanceOfSatisfying(PaymentSimulator.PaymentOutcome.Succeeded.class,
                s -> assertThat(s.amount()).isEqualTo(amount));
    }

    @Test
    void declinedCardFailsWithDeclinedReason() {
        PaymentSimulator.PaymentOutcome outcome = simulator.process("4000000000000002", amount);

        assertThat(outcome).isInstanceOfSatisfying(PaymentSimulator.PaymentOutcome.Failed.class,
                f -> assertThat(f.reason()).isEqualTo(PaymentFailed.Reason.DECLINED));
    }

    @Test
    void insufficientFundsCardFailsWithInsufficientFundsReason() {
        PaymentSimulator.PaymentOutcome outcome = simulator.process("4000000000009995", amount);

        assertThat(outcome).isInstanceOfSatisfying(PaymentSimulator.PaymentOutcome.Failed.class,
                f -> assertThat(f.reason()).isEqualTo(PaymentFailed.Reason.INSUFFICIENT_FUNDS));
    }

    @Test
    void unknownCardIsTreatedAsDeclined() {
        PaymentSimulator.PaymentOutcome outcome = simulator.process("1111222233334444", amount);

        assertThat(outcome).isInstanceOfSatisfying(PaymentSimulator.PaymentOutcome.Failed.class,
                f -> assertThat(f.reason()).isEqualTo(PaymentFailed.Reason.DECLINED));
    }

    @Test
    void cardNumberWhitespaceIsIgnored() {
        PaymentSimulator.PaymentOutcome outcome = simulator.process("4242 4242 4242 4242", amount);

        assertThat(outcome).isInstanceOf(PaymentSimulator.PaymentOutcome.Succeeded.class);
    }

    @Test
    void nullCardNumberIsTreatedAsDeclined() {
        PaymentSimulator.PaymentOutcome outcome = simulator.process(null, amount);

        assertThat(outcome).isInstanceOfSatisfying(PaymentSimulator.PaymentOutcome.Failed.class,
                f -> assertThat(f.reason()).isEqualTo(PaymentFailed.Reason.DECLINED));
    }
}
