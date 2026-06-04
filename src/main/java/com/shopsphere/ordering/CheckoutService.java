package com.shopsphere.ordering;

import com.shopsphere.catalog.Catalog;
import com.shopsphere.catalog.ProductPriceLookup;
import com.shopsphere.common.Money;
import com.shopsphere.payment.PaymentMethods;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
class CheckoutService {

    private static final String DEFAULT_CURRENCY = "INR";
    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final CartRepository carts;
    private final OrderRepository orders;
    private final Catalog catalog;
    private final ProductPriceLookup catalogPrices;
    private final PaymentMethods paymentMethods;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final MeterRegistry meters;

    CheckoutService(CartRepository carts,
                    OrderRepository orders,
                    Catalog catalog,
                    ProductPriceLookup catalogPrices,
                    PaymentMethods paymentMethods,
                    ApplicationEventPublisher events,
                    Clock clock,
                    MeterRegistry meters) {
        this.carts = carts;
        this.orders = orders;
        this.catalog = catalog;
        this.catalogPrices = catalogPrices;
        this.paymentMethods = paymentMethods;
        this.events = events;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional
    PlacedOrder checkout(UUID customerId, String shippingAddress, String cardNumber) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "placed";
        try {
            return doCheckout(customerId, shippingAddress, cardNumber);
        } catch (EmptyCartException e) {
            outcome = "empty_cart";
            throw e;
        } catch (InsufficientStockException e) {
            outcome = "insufficient_stock";
            throw e;
        } finally {
            sample.stop(Timer.builder("checkout_latency_seconds")
                    .description("Checkout endpoint latency")
                    .publishPercentileHistogram()
                    .register(meters));
            meters.counter("orders_placed_total", "outcome", outcome).increment();
        }
    }

    private PlacedOrder doCheckout(UUID customerId, String shippingAddress, String cardNumber) {
        Cart cart = carts.findByCustomerId(customerId).orElseThrow(EmptyCartException::new);
        if (cart.getItems().isEmpty()) {
            throw new EmptyCartException();
        }

        UUID orderId = UUID.randomUUID();
        List<Catalog.ReservationItem> reservationItems = cart.getItems().stream()
                .map(line -> new Catalog.ReservationItem(line.getProductId(), line.getQty()))
                .toList();

        Catalog.ReservationOutcome outcome = catalog.reserve(orderId, reservationItems);
        if (!outcome.allGranted()) {
            throw new InsufficientStockException();
        }

        Instant now = clock.instant();
        List<OrderPlaced.Line> eventLines = new ArrayList<>(cart.getItems().size());
        Money runningTotal = Money.zero(DEFAULT_CURRENCY);
        List<SnapshotLine> snapshots = new ArrayList<>(cart.getItems().size());
        for (CartLineItem line : cart.getItems()) {
            ProductPriceLookup.ProductSummary product = catalogPrices.findSummary(line.getProductId())
                    .orElseThrow(InsufficientStockException::new);
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

        return new PlacedOrder(order.getId(), order.getStatus());
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

    private record SnapshotLine(UUID productId, String name, Money unitPrice, int qty) {
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
}
