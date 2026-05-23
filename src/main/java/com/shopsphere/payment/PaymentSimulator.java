package com.shopsphere.payment;

import com.shopsphere.common.Money;
import org.springframework.stereotype.Component;

@Component
final class PaymentSimulator {

    static final String CARD_SUCCESS = "4242424242424242";
    static final String CARD_DECLINED = "4000000000000002";
    static final String CARD_INSUFFICIENT_FUNDS = "4000000000009995";

    PaymentOutcome process(String cardNumber, Money amount) {
        String normalised = cardNumber == null ? "" : cardNumber.replaceAll("\\s+", "");
        return switch (normalised) {
            case CARD_SUCCESS -> PaymentOutcome.succeeded(amount);
            case CARD_INSUFFICIENT_FUNDS -> PaymentOutcome.failed(PaymentFailed.Reason.INSUFFICIENT_FUNDS);
            default -> PaymentOutcome.failed(PaymentFailed.Reason.DECLINED);
        };
    }

    sealed interface PaymentOutcome {
        static PaymentOutcome succeeded(Money amount) {
            return new Succeeded(amount);
        }

        static PaymentOutcome failed(PaymentFailed.Reason reason) {
            return new Failed(reason);
        }

        record Succeeded(Money amount) implements PaymentOutcome {
        }

        record Failed(PaymentFailed.Reason reason) implements PaymentOutcome {
        }
    }
}
