package com.shopsphere.catalog;

import com.shopsphere.common.OrderLog;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
class CatalogImpl implements Catalog {

    private static final Logger log = LoggerFactory.getLogger(CatalogImpl.class);

    static final String PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";
    static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";

    private final ProductRepository products;
    private final StockReservationRepository reservations;
    private final Clock clock;
    private final MeterRegistry meters;

    CatalogImpl(ProductRepository products, StockReservationRepository reservations, Clock clock, MeterRegistry meters) {
        this.products = products;
        this.reservations = reservations;
        this.clock = clock;
        this.meters = meters;
    }

    private void countReservations(String status, long n) {
        if (n > 0) {
            meters.counter("reservations_total", "status", status).increment(n);
        }
    }

    @Override
    @Transactional
    public ReservationOutcome reserve(UUID orderId, List<ReservationItem> items) {
        List<Decision> decisions = new ArrayList<>(items.size());
        boolean anyDenied = false;
        for (ReservationItem item : items) {
            Optional<Product> productOpt = products.findByIdForUpdate(item.productId());
            if (productOpt.isEmpty()) {
                decisions.add(Decision.denied(item, PRODUCT_NOT_FOUND));
                anyDenied = true;
                continue;
            }
            Product product = productOpt.get();
            if (product.getAvailableQty() < item.qty()) {
                decisions.add(Decision.denied(item, INSUFFICIENT_STOCK));
                anyDenied = true;
                continue;
            }
            decisions.add(Decision.granted(item, product));
        }

        boolean allGranted = !anyDenied;
        if (allGranted) {
            Instant now = clock.instant();
            for (Decision d : decisions) {
                d.product().decreaseAvailable(d.item().qty());
                reservations.save(new StockReservation(
                        UUID.randomUUID(), orderId, d.item().productId(), d.item().qty(), now));
            }
            countReservations("held", decisions.size());
        }

        // Reservation's leg of the order's journey — stamped with orderId so a Loki orderId query
        // returns the stock decision alongside Ordering's and Payment's lines.
        boolean granted = allGranted;
        OrderLog.withOrder(orderId, () ->
                log.info("Reservation {} for {} item(s)", granted ? "granted" : "denied", decisions.size()));

        List<ReservationLine> lines = decisions.stream()
                .map(Decision::toLine)
                .toList();
        return new ReservationOutcome(orderId, lines, allGranted);
    }

    @Override
    @Transactional
    public void confirm(UUID orderId) {
        List<StockReservation> held = reservations.findAllByOrderIdAndStatus(orderId, StockReservation.Status.HELD);
        for (StockReservation r : held) {
            r.confirm();
        }
        countReservations("confirmed", held.size());
        OrderLog.withOrder(orderId, () -> log.info("Reservation confirmed for {} item(s)", held.size()));
    }

    @Override
    @Transactional
    public void release(UUID orderId) {
        List<StockReservation> held = reservations.findAllByOrderIdAndStatus(orderId, StockReservation.Status.HELD);
        for (StockReservation r : held) {
            Product product = products.findByIdForUpdate(r.getProductId())
                    .orElseThrow(() -> new IllegalStateException("missing product " + r.getProductId()));
            product.increaseAvailable(r.getQty());
            r.release();
        }
        countReservations("released", held.size());
        OrderLog.withOrder(orderId, () -> log.info("Reservation released for {} item(s)", held.size()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HeldReservation> findHeldForOrder(UUID orderId) {
        return reservations.findAllByOrderIdAndStatus(orderId, StockReservation.Status.HELD).stream()
                .map(r -> new HeldReservation(r.getProductId(), r.getQty()))
                .toList();
    }

    private record Decision(ReservationItem item, Product product, LineStatus status, String reason) {
        static Decision granted(ReservationItem item, Product product) {
            return new Decision(item, product, LineStatus.GRANTED, null);
        }

        static Decision denied(ReservationItem item, String reason) {
            return new Decision(item, null, LineStatus.DENIED, reason);
        }

        ReservationLine toLine() {
            return status == LineStatus.GRANTED
                    ? ReservationLine.granted(item.productId(), item.qty())
                    : ReservationLine.denied(item.productId(), item.qty(), reason);
        }
    }
}
