package com.shopsphere.ordering;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineTests {

    @Test
    void pendingPaymentToPaidIsLegal() {
        assertThat(OrderStateMachine.isLegal(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID)).isTrue();
    }

    @Test
    void pendingPaymentToCancelledIsLegal() {
        assertThat(OrderStateMachine.isLegal(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED)).isTrue();
    }

    @Test
    void paidIsTerminal() {
        assertThat(OrderStateMachine.isLegal(OrderStatus.PAID, OrderStatus.PENDING_PAYMENT)).isFalse();
        assertThat(OrderStateMachine.isLegal(OrderStatus.PAID, OrderStatus.CANCELLED)).isFalse();
    }

    @Test
    void cancelledIsTerminal() {
        assertThat(OrderStateMachine.isLegal(OrderStatus.CANCELLED, OrderStatus.PAID)).isFalse();
        assertThat(OrderStateMachine.isLegal(OrderStatus.CANCELLED, OrderStatus.PENDING_PAYMENT)).isFalse();
    }

    @Test
    void assertTransitionThrowsOnIllegalMove() {
        assertThatThrownBy(() -> OrderStateMachine.assertTransition(OrderStatus.PAID, OrderStatus.CANCELLED))
                .isInstanceOf(OrderStateMachine.IllegalOrderTransitionException.class);
    }
}
