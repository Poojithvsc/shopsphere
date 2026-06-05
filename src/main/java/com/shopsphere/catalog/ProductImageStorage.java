package com.shopsphere.catalog;

import java.time.Duration;
import java.util.UUID;

/**
 * Deep module over product-image object storage. Catalog code depends only on these two operations
 * and never sees the S3 SDK, the bucket, the key scheme, or how a URL is signed. The implementation
 * is swappable by configuration (LocalStack in dev, real S3 in cloud) — see {@link StorageConfig}.
 */
interface ProductImageStorage {

    /** Stores {@code bytes} for {@code productId} and returns the storage key. */
    String upload(UUID productId, byte[] bytes, String contentType);

    /** Mints a time-limited read URL for a stored key. The URL stops working once {@code ttl} elapses. */
    String presignedRead(String key, Duration ttl);
}
