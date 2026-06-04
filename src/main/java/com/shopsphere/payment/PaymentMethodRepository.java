package com.shopsphere.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {
}
