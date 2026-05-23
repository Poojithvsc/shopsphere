package com.shopsphere.ordering;

final class OrderStateMachine {

    private OrderStateMachine() {
    }

    static void assertTransition(OrderStatus from, OrderStatus to) {
        if (!isLegal(from, to)) {
            throw new IllegalOrderTransitionException(from, to);
        }
    }

    static boolean isLegal(OrderStatus from, OrderStatus to) {
        return switch (from) {
            case PENDING_PAYMENT -> to == OrderStatus.PAID || to == OrderStatus.CANCELLED;
            case PAID, CANCELLED -> false;
        };
    }

    static final class IllegalOrderTransitionException extends RuntimeException {
        IllegalOrderTransitionException(OrderStatus from, OrderStatus to) {
            super("illegal order transition: " + from + " -> " + to);
        }
    }
}
