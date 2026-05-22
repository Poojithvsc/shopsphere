package com.shopsphere.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    List<StockReservation> findAllByOrderId(UUID orderId);

    List<StockReservation> findAllByOrderIdAndStatus(UUID orderId, StockReservation.Status status);
}
