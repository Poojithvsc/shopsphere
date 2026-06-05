package com.shopsphere.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration seam for product-image storage. The single knob that flips dev↔cloud is
 * {@code endpoint}: set to a LocalStack URL in dev, left blank in cloud so the SDK resolves real S3.
 * Credentials follow the same idea — explicit keys for LocalStack, otherwise the default AWS provider
 * chain (instance profile). The same binary serves both; only configuration changes.
 */
@ConfigurationProperties(prefix = "shopsphere.storage.s3")
record S3StorageProperties(String endpoint, String bucket, String region, String accessKey, String secretKey) {
}
