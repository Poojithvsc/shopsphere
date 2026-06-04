package com.shopsphere.ordering;

import com.shopsphere.common.Money;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
class CheckoutService {

    private final OrderPlacement placement;
    private final OrderRepository orders;
    private final MeterRegistry meters;

    CheckoutService(OrderPlacement placement, OrderRepository orders, MeterRegistry meters) {
        this.placement = placement;
        this.orders = orders;
        this.meters = meters;
    }

    /**
     * Places an order, optionally deduplicated by {@code idempotencyKey}. With a key: a prior
     * request for the same (customer, key) returns its cached order when the payload matches and is
     * rejected with {@link IdempotencyConflictException} when it differs; concurrent same-key
     * requests resolve to exactly one order via the DB unique constraint (the loser rolls back and
     * returns the winner's order).
     */
    PlacedOrder checkout(UUID customerId, String shippingAddress, String cardNumber, String idempotencyKey) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "placed";
        try {
            String requestHash = idempotencyKey == null ? null : requestHash(shippingAddress, cardNumber);
            if (idempotencyKey != null) {
                Optional<IdempotencyKeys.PriorRequest> prior = placement.findPrior(customerId, idempotencyKey);
                if (prior.isPresent()) {
                    outcome = "idempotent_replay";
                    return resolvePrior(prior.get(), requestHash);
                }
            }
            try {
                return placement.place(customerId, shippingAddress, cardNumber, idempotencyKey, requestHash);
            } catch (DuplicateKeyException raced) {
                // A concurrent same-key request won the INSERT and has committed by now; return it.
                outcome = "idempotent_replay";
                IdempotencyKeys.PriorRequest winner = placement.findPrior(customerId, idempotencyKey)
                        .orElseThrow(() -> raced);
                return resolvePrior(winner, requestHash);
            }
        } catch (EmptyCartException e) {
            outcome = "empty_cart";
            throw e;
        } catch (InsufficientStockException e) {
            outcome = "insufficient_stock";
            throw e;
        } catch (IdempotencyConflictException e) {
            outcome = "idempotency_conflict";
            throw e;
        } finally {
            sample.stop(Timer.builder("checkout_latency_seconds")
                    .description("Checkout endpoint latency")
                    .publishPercentileHistogram()
                    .register(meters));
            meters.counter("orders_placed_total", "outcome", outcome).increment();
        }
    }

    private PlacedOrder resolvePrior(IdempotencyKeys.PriorRequest prior, String requestHash) {
        if (!prior.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        return placement.loadPlaced(prior.orderId());
    }

    private static String requestHash(String shippingAddress, String cardNumber) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // The card is hashed into the fingerprint, never stored raw — no PAN reaches the DB.
            String payload = shippingAddress + "\n" + cardNumber;
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Transactional(readOnly = true)
    List<OrderView> listForCustomer(UUID customerId) {
        return orders.findAllByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    OrderView findForCustomer(UUID customerId, UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow(OrderNotFoundException::new);
        if (!order.getCustomerId().equals(customerId)) {
            throw new OrderNotFoundException();
        }
        return toView(order);
    }

    private OrderView toView(Order order) {
        List<OrderLineView> lines = order.getItems().stream()
                .map(line -> new OrderLineView(
                        line.getProductId(),
                        line.getProductName(),
                        line.getUnitPrice(),
                        line.getQty(),
                        line.getLineTotal()))
                .toList();
        return new OrderView(order.getId(), order.getStatus(), order.getTotal(), order.getShippingAddress(), lines);
    }

    record PlacedOrder(UUID orderId, OrderStatus status) {
    }

    record OrderView(UUID id, OrderStatus status, Money total, String shippingAddress, List<OrderLineView> items) {
    }

    record OrderLineView(UUID productId, String name, Money unitPrice, int qty, Money lineTotal) {
    }

    static final class EmptyCartException extends RuntimeException {
    }

    static final class InsufficientStockException extends RuntimeException {
    }

    static final class OrderNotFoundException extends RuntimeException {
    }

    static final class IdempotencyConflictException extends RuntimeException {
    }
}
