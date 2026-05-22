package com.shopsphere.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigDecimal amount, String currency) {

    private static final int SCALE = 4;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO code, got: " + currency);
        }
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), currency);
    }

    public Money multiply(int factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new CurrencyMismatchException(this.currency, other.currency);
        }
    }

    public static final class CurrencyMismatchException extends RuntimeException {
        public CurrencyMismatchException(String left, String right) {
            super("currency mismatch: " + left + " vs " + right);
        }
    }
}
