package com.shopsphere.ordering;

import com.shopsphere.catalog.ProductPriceLookup;
import com.shopsphere.common.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
class CartService {

    private static final String DEFAULT_CURRENCY = "INR";

    private final CartRepository carts;
    private final ProductPriceLookup catalog;
    private final Clock clock;

    CartService(CartRepository carts, ProductPriceLookup catalog, Clock clock) {
        this.carts = carts;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional
    CartView viewOrCreate(UUID customerId) {
        return toView(loadOrCreate(customerId));
    }

    @Transactional
    CartView addItem(UUID customerId, UUID productId, int qty) {
        if (qty <= 0) {
            throw new InvalidQuantityException();
        }
        ProductPriceLookup.ProductSummary product = catalog.findSummary(productId)
                .orElseThrow(ProductNotFoundException::new);
        Cart cart = loadOrCreate(customerId);
        cart.addOrIncrement(product.id(), qty, clock.instant());
        return toView(cart);
    }

    @Transactional
    CartView setQty(UUID customerId, UUID productId, int qty) {
        if (qty < 0) {
            throw new InvalidQuantityException();
        }
        catalog.findSummary(productId).orElseThrow(ProductNotFoundException::new);
        Cart cart = loadOrCreate(customerId);
        try {
            cart.setQty(productId, qty, clock.instant());
        } catch (Cart.LineNotFoundException e) {
            throw new ProductNotFoundException();
        }
        return toView(cart);
    }

    @Transactional
    CartView removeItem(UUID customerId, UUID productId) {
        Cart cart = loadOrCreate(customerId);
        if (!cart.removeLine(productId, clock.instant())) {
            throw new ProductNotFoundException();
        }
        return toView(cart);
    }

    private Cart loadOrCreate(UUID customerId) {
        return carts.findByCustomerId(customerId).orElseGet(() -> {
            Cart cart = new Cart(UUID.randomUUID(), customerId, clock.instant());
            return carts.save(cart);
        });
    }

    private CartView toView(Cart cart) {
        List<LineView> lines = cart.getItems().stream()
                .map(this::toLineView)
                .toList();
        Money grandTotal = lines.stream()
                .map(LineView::lineTotal)
                .reduce(Money.zero(DEFAULT_CURRENCY), Money::add);
        return new CartView(cart.getId(), lines, grandTotal);
    }

    private LineView toLineView(CartLineItem item) {
        ProductPriceLookup.ProductSummary summary = catalog.findSummary(item.getProductId())
                .orElseThrow(ProductNotFoundException::new);
        Money lineTotal = summary.unitPrice().multiply(item.getQty());
        return new LineView(summary.id(), summary.name(), summary.unitPrice(), item.getQty(), lineTotal);
    }

    record CartView(UUID id, List<LineView> items, Money grandTotal) {
    }

    record LineView(UUID productId, String name, Money unitPrice, int qty, Money lineTotal) {
    }

    static final class ProductNotFoundException extends RuntimeException {
    }

    static final class InvalidQuantityException extends RuntimeException {
    }
}
