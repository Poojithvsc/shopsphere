package com.shopsphere.catalog;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogRecordsTests {

    @Test
    void reservationItemRejectsNonPositiveQty() {
        UUID productId = UUID.randomUUID();
        assertThatThrownBy(() -> new Catalog.ReservationItem(productId, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Catalog.ReservationItem(productId, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grantedLineFactoryNullsReason() {
        UUID productId = UUID.randomUUID();
        Catalog.ReservationLine line = Catalog.ReservationLine.granted(productId, 3);
        assertThat(line.status()).isEqualTo(Catalog.LineStatus.GRANTED);
        assertThat(line.reason()).isNull();
        assertThat(line.requestedQty()).isEqualTo(3);
    }

    @Test
    void deniedLineFactoryCarriesReason() {
        UUID productId = UUID.randomUUID();
        Catalog.ReservationLine line = Catalog.ReservationLine.denied(productId, 5, "INSUFFICIENT_STOCK");
        assertThat(line.status()).isEqualTo(Catalog.LineStatus.DENIED);
        assertThat(line.reason()).isEqualTo("INSUFFICIENT_STOCK");
    }
}
