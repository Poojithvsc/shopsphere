package com.shopsphere.payment;

import com.shopsphere.common.Money;
import org.springframework.stereotype.Component;

@Component
final class PaymentSimulator {

    static final String LAST_FOUR_SUCCESS = "4242";
    static final String LAST_FOUR_INSUFFICIENT_FUNDS = "9995";

    /**
     * Decides the outcome from the card's last four digits. Accepts either a full PAN or a bare
     * last-four string — both reduce to the same key — so the simulator works whether it is handed
     * a raw card (legacy path) or only the redacted {@code lastFour} resolved from a token.
     */
    PaymentOutcome process(String cardOrLastFour, Money amount) {
        String digits = cardOrLastFour == null ? "" : cardOrLastFour.replaceAll("\\D", "");
        String lastFour = digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
        return switch (lastFour) {
            case LAST_FOUR_SUCCESS -> PaymentOutcome.succeeded(amount);
            case LAST_FOUR_INSUFFICIENT_FUNDS -> PaymentOutcome.failed(PaymentFailed.Reason.INSUFFICIENT_FUNDS);
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
