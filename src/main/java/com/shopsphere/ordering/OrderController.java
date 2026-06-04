package com.shopsphere.ordering;

import com.shopsphere.common.Money;
import com.shopsphere.identity.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    private final CheckoutService checkout;

    OrderController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    @PostMapping
    ResponseEntity<PlacedOrderResponse> place(
            @Valid @RequestBody PlaceOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // A blank header is treated as absent — only a real key opts into dedupe.
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
        CheckoutService.PlacedOrder placed = checkout.checkout(
                currentCustomerId(), request.shippingAddress(), request.cardNumber(), key);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new PlacedOrderResponse(placed.orderId(), placed.status()));
    }

    @GetMapping
    List<OrderResponse> list() {
        return checkout.listForCustomer(currentCustomerId()).stream()
                .map(OrderController::toResponse)
                .toList();
    }

    @GetMapping("/{orderId}")
    OrderResponse get(@PathVariable UUID orderId) {
        return toResponse(checkout.findForCustomer(currentCustomerId(), orderId));
    }

    @ExceptionHandler(CheckoutService.EmptyCartException.class)
    ResponseEntity<Void> emptyCart() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    @ExceptionHandler(CheckoutService.InsufficientStockException.class)
    ResponseEntity<Void> insufficientStock() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(CheckoutService.OrderNotFoundException.class)
    ResponseEntity<Void> orderNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(CheckoutService.IdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("idempotency_key_reuse",
                        "Idempotency-Key already used with a different request payload"));
    }

    private static UUID currentCustomerId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthenticatedPrincipal ap) {
            return ap.customerId();
        }
        throw new IllegalStateException("no authenticated principal");
    }

    private static OrderResponse toResponse(CheckoutService.OrderView view) {
        List<OrderLineResponse> lines = view.items().stream()
                .map(line -> new OrderLineResponse(
                        line.productId(),
                        line.name(),
                        line.unitPrice(),
                        line.qty(),
                        line.lineTotal()))
                .toList();
        return new OrderResponse(view.id(), view.status(), view.total(), view.shippingAddress(), lines);
    }

    record PlaceOrderRequest(@NotBlank String shippingAddress, @NotBlank String cardNumber) {
    }

    record PlacedOrderResponse(UUID orderId, OrderStatus status) {
    }

    record OrderResponse(UUID id, OrderStatus status, Money total, String shippingAddress, List<OrderLineResponse> items) {
    }

    record OrderLineResponse(UUID productId, String name, Money unitPrice, int qty, Money lineTotal) {
    }

    record ApiError(String error, String message) {
    }
}
