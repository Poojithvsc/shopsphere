package com.shopsphere.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
class PaymentMethodService implements PaymentMethods {

    private final PaymentMethodRepository repository;
    private final Clock clock;

    PaymentMethodService(PaymentMethodRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID tokenize(String rawCard) {
        PaymentMethod method = PaymentMethod.tokenize(rawCard, clock.instant());
        repository.save(method);
        return method.token();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentMethodView> lookup(UUID token) {
        return repository.findById(token)
                .map(method -> new PaymentMethodView(method.token(), method.lastFour()));
    }
}
