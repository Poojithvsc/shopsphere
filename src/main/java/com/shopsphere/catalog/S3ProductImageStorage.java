package com.shopsphere.catalog;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

/**
 * S3 implementation of {@link ProductImageStorage}. Objects are keyed {@code <productId>.<ext>}, where
 * the extension comes from a small allow-list of image content types — an unknown type is rejected
 * rather than stored. Reads are served by presigned URLs so the bucket itself stays private.
 */
@Component
class S3ProductImageStorage implements ProductImageStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    S3ProductImageStorage(S3Client s3, S3Presigner presigner, S3StorageProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = props.bucket();
    }

    @Override
    public String upload(UUID productId, byte[] bytes, String contentType) {
        String key = productId + "." + extensionFor(contentType);
        s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(bytes));
        return key;
    }

    @Override
    public String presignedRead(String key, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(get)
                        .build())
                .url()
                .toString();
    }

    private static String extensionFor(String contentType) {
        return switch (contentType == null ? "" : contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> throw new UnsupportedImageTypeException(contentType);
        };
    }

    static final class UnsupportedImageTypeException extends RuntimeException {
        UnsupportedImageTypeException(String contentType) {
            super("unsupported image content-type: " + contentType);
        }
    }
}
