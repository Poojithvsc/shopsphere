package com.shopsphere.identity;

import java.util.UUID;

public record AuthenticatedPrincipal(UUID userId, UUID customerId) {
}
