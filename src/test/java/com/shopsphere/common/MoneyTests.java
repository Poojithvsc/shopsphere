package com.shopsphere.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTests {

    @Test
    void addSameCurrency() {
        Money a = Money.of(new BigDecimal("100.0000"), "INR");
        Money b = Money.of(new BigDecimal("50.5000"), "INR");
        assertThat(a.add(b)).isEqualTo(Money.of(new BigDecimal("150.5000"), "INR"));
    }

    @Test
    void multiplyByQty() {
        Money price = Money.of(new BigDecimal("8499.0000"), "INR");
        assertThat(price.multiply(3))
                .isEqualTo(Money.of(new BigDecimal("25497.0000"), "INR"));
    }

    @Test
    void addRejectsCurrencyMismatch() {
        Money inr = Money.of(new BigDecimal("100.0000"), "INR");
        Money usd = Money.of(new BigDecimal("100.0000"), "USD");
        assertThatThrownBy(() -> inr.add(usd))
                .isInstanceOf(Money.CurrencyMismatchException.class)
                .hasMessageContaining("INR")
                .hasMessageContaining("USD");
    }

    @Test
    void structuralEquality() {
        assertThat(Money.of(new BigDecimal("100.0000"), "INR"))
                .isEqualTo(Money.of(new BigDecimal("100.0000"), "INR"));
    }

    @Test
    void scaleNormalisedToFour() {
        Money m = Money.of(new BigDecimal("100"), "INR");
        assertThat(m.amount().scale()).isEqualTo(4);
        assertThat(m).isEqualTo(Money.of(new BigDecimal("100.0000"), "INR"));
    }

    @Test
    void zeroFactory() {
        Money zero = Money.zero("INR");
        assertThat(zero.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zero.currency()).isEqualTo("INR");
    }

    @Test
    void rejectsNonIsoCurrency() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "INRX"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
