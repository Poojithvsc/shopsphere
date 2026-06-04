package com.shopsphere.ordering;

import com.shopsphere.common.Money;
import com.shopsphere.identity.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cart")
@SecurityRequirement(name = "bearerAuth")
class CartController {

    private final CartService carts;

    CartController(CartService carts) {
        this.carts = carts;
    }

    @GetMapping
    CartResponse getCart() {
        return toResponse(carts.viewOrCreate(currentCustomerId()));
    }

    @PostMapping("/items")
    CartResponse addItem(@Valid @RequestBody AddItemRequest request) {
        return toResponse(carts.addItem(currentCustomerId(), request.productId(), request.qty()));
    }

    @PatchMapping("/items/{productId}")
    CartResponse patchItem(@PathVariable UUID productId, @Valid @RequestBody PatchItemRequest request) {
        return toResponse(carts.setQty(currentCustomerId(), productId, request.qty()));
    }

    @DeleteMapping("/items/{productId}")
    CartResponse removeItem(@PathVariable UUID productId) {
        return toResponse(carts.removeItem(currentCustomerId(), productId));
    }

    @ExceptionHandler(CartService.ProductNotFoundException.class)
    ResponseEntity<Void> productNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(CartService.InvalidQuantityException.class)
    ResponseEntity<Void> invalidQuantity() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    private static UUID currentCustomerId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthenticatedPrincipal ap) {
            return ap.customerId();
        }
        throw new IllegalStateException("no authenticated principal");
    }

    private static CartResponse toResponse(CartService.CartView view) {
        List<CartLineResponse> lines = view.items().stream()
                .map(line -> new CartLineResponse(
                        line.productId(),
                        line.name(),
                        line.unitPrice(),
                        line.qty(),
                        line.lineTotal()))
                .toList();
        return new CartResponse(view.id(), lines, view.grandTotal());
    }

    record AddItemRequest(@NotNull UUID productId, @Min(1) int qty) {
    }

    record PatchItemRequest(@Min(0) int qty) {
    }

    record CartResponse(UUID id, List<CartLineResponse> items, Money grandTotal) {
    }

    record CartLineResponse(UUID productId, String name, Money unitPrice, int qty, Money lineTotal) {
    }
}
