package com.shopsphere.ordering;

import com.shopsphere.catalog.Catalog;
import com.shopsphere.catalog.ProductPriceLookup;
import com.shopsphere.common.Money;
import com.shopsphere.payment.PaymentMethods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional core of checkout, deliberately split from {@link CheckoutService} so the
 * transaction boundary sits on a Spring proxy. That lets the orchestrator catch a lost
 * idempotency-key race ({@code DuplicateKeyException}) <em>after</em> this transaction has fully
 * rolled back, then read the winner's order in a fresh transaction.
 */
@Component
class OrderPlacement {

    private static final String DEFAULT_CURRENCY = "INR";
    private static final Logger log = LoggerFactory.getLogger(OrderPlacement.class);

    private final CartRepository carts;
    private final OrderRepository orders;
    private final Catalog catalog;
    private final ProductPriceLookup catalogPrices;
    private final PaymentMethods paymentMethods;
    private final IdempotencyKeys idempotencyKeys;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OrderPlacement(CartRepository carts,
                   OrderRepository orders,
                   Catalog catalog,
                   ProductPriceLookup catalogPrices,
                   PaymentMethods paymentMethods,
                   IdempotencyKeys idempotencyKeys,
                   ApplicationEventPublisher events,
                   Clock clock) {
        this.carts = carts;
        this.orders = orders;
        this.catalog = catalog;
        this.catalogPrices = catalogPrices;
        this.paymentMethods = paymentMethods;
        this.idempotencyKeys = idempotencyKeys;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    CheckoutService.PlacedOrder place(UUID customerId,
                                      String shippingAddress,
                                      String cardNumber,
                                      String idempotencyKey,
                                      String requestHash) {
        Cart cart = carts.findByCustomerId(customerId).orElseThrow(CheckoutService.EmptyCartException::new);
        if (cart.getItems().isEmpty()) {
            throw new CheckoutService.EmptyCartException();
        }

        UUID orderId = UUID.randomUUID();
        // Claim the idempotency slot before doing any work, so a concurrent duplicate fails fast.
        // A lost race throws DuplicateKeyException, rolling this whole transaction back (no order,
        // no reservation, no token); the orchestrator then returns the winner's order.
        if (idempotencyKey != null) {
            idempotencyKeys.claim(customerId, idempotencyKey, requestHash, orderId);
        }

        List<Catalog.ReservationItem> reservationItems = cart.getItems().stream()
                .map(line -> new Catalog.ReservationItem(line.getProductId(), line.getQty()))
                .toList();

        Catalog.ReservationOutcome outcome = catalog.reserve(orderId, reservationItems);
        if (!outcome.allGranted()) {
            throw new CheckoutService.InsufficientStockException();
        }

        Instant now = clock.instant();
        List<OrderPlaced.Line> eventLines = new ArrayList<>(cart.getItems().size());
        Money runningTotal = Money.zero(DEFAULT_CURRENCY);
        List<SnapshotLine> snapshots = new ArrayList<>(cart.getItems().size());
        for (CartLineItem line : cart.getItems()) {
            ProductPriceLookup.ProductSummary product = catalogPrices.findSummary(line.getProductId())
                    .orElseThrow(CheckoutService.InsufficientStockException::new);
            Money lineTotal = product.unitPrice().multiply(line.getQty());
            runningTotal = runningTotal.add(lineTotal);
            snapshots.add(new SnapshotLine(product.id(), product.name(), product.unitPrice(), line.getQty()));
            eventLines.add(new OrderPlaced.Line(product.id(), product.name(), product.unitPrice(), line.getQty()));
        }

        Order order = new Order(orderId, customerId, shippingAddress, runningTotal, now);
        for (SnapshotLine s : snapshots) {
            order.addLine(new OrderLineItem(UUID.randomUUID(), order, s.productId, s.name, s.unitPrice, s.qty));
        }
        orders.save(order);

        cart.clearItems(now);

        // Redact the PAN at the context boundary: the raw card is exchanged for an opaque token and
        // never reaches the event, the log, or the ordering schema.
        UUID paymentMethodToken = paymentMethods.tokenize(cardNumber);

        OrderPlaced placed = new OrderPlaced(
                UUID.randomUUID(),
                now,
                order.getId(),
                customerId,
                runningTotal,
                paymentMethodToken,
                List.copyOf(eventLines));
        events.publishEvent(placed);

        // Structured, contextual log line — orderId/customerId land as top-level JSON fields via MDC.
        MDC.put("orderId", order.getId().toString());
        MDC.put("customerId", customerId.toString());
        try {
            log.info("Order placed with {} line(s), total {} {}",
                    eventLines.size(), runningTotal.amount(), runningTotal.currency());
        } finally {
            MDC.remove("orderId");
            MDC.remove("customerId");
        }

        return new CheckoutService.PlacedOrder(order.getId(), order.getStatus());
    }

    @Transactional(readOnly = true)
    Optional<IdempotencyKeys.PriorRequest> findPrior(UUID customerId, String idempotencyKey) {
        return idempotencyKeys.find(customerId, idempotencyKey);
    }

    @Transactional(readOnly = true)
    CheckoutService.PlacedOrder loadPlaced(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow(CheckoutService.OrderNotFoundException::new);
        return new CheckoutService.PlacedOrder(order.getId(), order.getStatus());
    }

    private record SnapshotLine(UUID productId, String name, Money unitPrice, int qty) {
    }
}
