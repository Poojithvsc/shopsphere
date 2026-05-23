package com.shopsphere.ordering;

import com.shopsphere.catalog.Catalog;
import com.shopsphere.catalog.ProductPriceLookup;
import com.shopsphere.common.Money;
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

    private final CartRepository carts;
    private final OrderRepository orders;
    private final Catalog catalog;
    private final ProductPriceLookup catalogPrices;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    CheckoutService(CartRepository carts,
                    OrderRepository orders,
                    Catalog catalog,
                    ProductPriceLookup catalogPrices,
                    ApplicationEventPublisher events,
                    Clock clock) {
        this.carts = carts;
        this.orders = orders;
        this.catalog = catalog;
        this.catalogPrices = catalogPrices;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    PlacedOrder checkout(UUID customerId, String shippingAddress, String cardNumber) {
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

        OrderPlaced placed = new OrderPlaced(
                UUID.randomUUID(),
                now,
                order.getId(),
                customerId,
                runningTotal,
                cardNumber,
                List.copyOf(eventLines));
        events.publishEvent(placed);

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
