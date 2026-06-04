package com.shopsphere.payment;

import java.util.Optional;
import java.util.UUID;

/**
 * The Payment context's exposed tokenization API. Other modules (Ordering) hold a raw card only
 * long enough to hand it to {@link #tokenize(String)} and keep the returned token; they never see
 * the PAN again. Within Payment, the consumer resolves a token back to its display data via
 * {@link #lookup(UUID)}.
 */
public interface PaymentMethods {

    /** Mints a fresh token for {@code rawCard}, persisting only redacted data. Same card → new token. */
    UUID tokenize(String rawCard);

    /** Resolves a token to its non-sensitive view, or empty if the token is unknown. */
    Optional<PaymentMethodView> lookup(UUID token);

    /** Non-sensitive projection of a tokenized instrument — carries no PAN. */
    record PaymentMethodView(UUID token, String lastFour) {
    }
}
