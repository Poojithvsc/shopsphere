package com.shopsphere.catalog;

import com.shopsphere.SharedContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StockReservationIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    Catalog catalog;

    @Autowired
    ProductRepository products;

    @Autowired
    StockReservationRepository reservations;

    @Test
    void reserveGrantedDecreasesAvailableQtyAndInsertsHeldRow() {
        UUID productId = seedProduct(5);
        UUID orderId = UUID.randomUUID();

        Catalog.ReservationOutcome outcome = catalog.reserve(orderId,
                List.of(new Catalog.ReservationItem(productId, 3)));

        assertThat(outcome.allGranted()).isTrue();
        assertThat(outcome.lines()).singleElement().satisfies(line -> {
            assertThat(line.status()).isEqualTo(Catalog.LineStatus.GRANTED);
            assertThat(line.reason()).isNull();
        });
        assertThat(products.findById(productId).orElseThrow().getAvailableQty()).isEqualTo(2);
        assertThat(reservations.findAllByOrderId(orderId)).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(StockReservation.Status.HELD);
            assertThat(r.getQty()).isEqualTo(3);
        });
    }

    @Test
    void reserveInsufficientStockDeniesWithoutMutation() {
        UUID productId = seedProduct(2);
        UUID orderId = UUID.randomUUID();

        Catalog.ReservationOutcome outcome = catalog.reserve(orderId,
                List.of(new Catalog.ReservationItem(productId, 5)));

        assertThat(outcome.allGranted()).isFalse();
        assertThat(outcome.lines()).singleElement().satisfies(line -> {
            assertThat(line.status()).isEqualTo(Catalog.LineStatus.DENIED);
            assertThat(line.reason()).isEqualTo(CatalogImpl.INSUFFICIENT_STOCK);
        });
        assertThat(products.findById(productId).orElseThrow().getAvailableQty()).isEqualTo(2);
        assertThat(reservations.findAllByOrderId(orderId)).isEmpty();
    }

    @Test
    void reserveUnknownProductDenies() {
        UUID nope = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Catalog.ReservationOutcome outcome = catalog.reserve(orderId,
                List.of(new Catalog.ReservationItem(nope, 1)));

        assertThat(outcome.allGranted()).isFalse();
        assertThat(outcome.lines()).singleElement().satisfies(line -> {
            assertThat(line.reason()).isEqualTo(CatalogImpl.PRODUCT_NOT_FOUND);
        });
    }

    @Test
    void reserveMixedItemsRollsBackWholeOutcome() {
        UUID prodA = seedProduct(5);
        UUID prodB = seedProduct(1);
        UUID orderId = UUID.randomUUID();

        Catalog.ReservationOutcome outcome = catalog.reserve(orderId, List.of(
                new Catalog.ReservationItem(prodA, 2),
                new Catalog.ReservationItem(prodB, 5)));

        assertThat(outcome.allGranted()).isFalse();
        // Neither product should have moved
        assertThat(products.findById(prodA).orElseThrow().getAvailableQty()).isEqualTo(5);
        assertThat(products.findById(prodB).orElseThrow().getAvailableQty()).isEqualTo(1);
        assertThat(reservations.findAllByOrderId(orderId)).isEmpty();
    }

    @Test
    void confirmFlipsHeldToConfirmedAndIsIdempotent() {
        UUID productId = seedProduct(4);
        UUID orderId = UUID.randomUUID();
        catalog.reserve(orderId, List.of(new Catalog.ReservationItem(productId, 2)));

        catalog.confirm(orderId);

        assertThat(reservations.findAllByOrderId(orderId)).singleElement().satisfies(r ->
                assertThat(r.getStatus()).isEqualTo(StockReservation.Status.CONFIRMED));
        assertThat(products.findById(productId).orElseThrow().getAvailableQty()).isEqualTo(2);

        // Idempotent: second call leaves CONFIRMED alone
        catalog.confirm(orderId);
        assertThat(reservations.findAllByOrderId(orderId)).singleElement().satisfies(r ->
                assertThat(r.getStatus()).isEqualTo(StockReservation.Status.CONFIRMED));
    }

    @Test
    void releaseFlipsHeldToReleasedAndRestoresAvailableQtyIdempotently() {
        UUID productId = seedProduct(4);
        UUID orderId = UUID.randomUUID();
        catalog.reserve(orderId, List.of(new Catalog.ReservationItem(productId, 3)));
        assertThat(products.findById(productId).orElseThrow().getAvailableQty()).isEqualTo(1);

        catalog.release(orderId);

        assertThat(reservations.findAllByOrderId(orderId)).singleElement().satisfies(r ->
                assertThat(r.getStatus()).isEqualTo(StockReservation.Status.RELEASED));
        assertThat(products.findById(productId).orElseThrow().getAvailableQty()).isEqualTo(4);

        // Idempotent: second release doesn't double-restore
        catalog.release(orderId);
        assertThat(products.findById(productId).orElseThrow().getAvailableQty()).isEqualTo(4);
        assertThat(reservations.findAllByOrderId(orderId)).singleElement().satisfies(r ->
                assertThat(r.getStatus()).isEqualTo(StockReservation.Status.RELEASED));
    }

    @Test
    void findHeldForOrderReturnsOnlyHeldReservations() {
        UUID prodA = seedProduct(3);
        UUID prodB = seedProduct(2);
        UUID orderId = UUID.randomUUID();
        catalog.reserve(orderId, List.of(
                new Catalog.ReservationItem(prodA, 1),
                new Catalog.ReservationItem(prodB, 1)));

        List<Catalog.HeldReservation> held = catalog.findHeldForOrder(orderId);
        assertThat(held).hasSize(2);

        catalog.confirm(orderId);
        assertThat(catalog.findHeldForOrder(orderId)).isEmpty();
    }

    @Test
    void concurrentReservesForLastUnit_exactlyOneWins() throws Exception {
        UUID productId = seedProduct(1);
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();

        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        Catalog.ReservationOutcome outcome = catalog.reserve(UUID.randomUUID(),
                                List.of(new Catalog.ReservationItem(productId, 1)));
                        if (outcome.allGranted()) {
                            granted.incrementAndGet();
                        } else {
                            denied.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(granted.get()).isEqualTo(1);
        assertThat(denied.get()).isEqualTo(threadCount - 1);
        assertThat(products.findById(productId).orElseThrow().getAvailableQty()).isZero();
    }

    private UUID seedProduct(int qty) {
        UUID id = UUID.randomUUID();
        products.save(new Product(
                id,
                "Test Product " + id,
                "Phase 6 test fixture",
                new BigDecimal("1.0000"),
                "INR",
                qty));
        return id;
    }
}
