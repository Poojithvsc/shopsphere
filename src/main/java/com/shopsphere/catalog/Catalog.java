package com.shopsphere.catalog;

import java.util.List;
import java.util.UUID;

public interface Catalog {

    ReservationOutcome reserve(UUID orderId, List<ReservationItem> items);

    void confirm(UUID orderId);

    void release(UUID orderId);

    List<HeldReservation> findHeldForOrder(UUID orderId);

    record ReservationItem(UUID productId, int qty) {
        public ReservationItem {
            if (qty <= 0) {
                throw new IllegalArgumentException("qty must be positive, got " + qty);
            }
        }
    }

    record ReservationOutcome(UUID orderId, List<ReservationLine> lines, boolean allGranted) {
    }

    record ReservationLine(UUID productId, int requestedQty, LineStatus status, String reason) {
        public static ReservationLine granted(UUID productId, int qty) {
            return new ReservationLine(productId, qty, LineStatus.GRANTED, null);
        }

        public static ReservationLine denied(UUID productId, int qty, String reason) {
            return new ReservationLine(productId, qty, LineStatus.DENIED, reason);
        }
    }

    enum LineStatus {
        GRANTED,
        DENIED
    }

    record HeldReservation(UUID productId, int qty) {
    }
}
