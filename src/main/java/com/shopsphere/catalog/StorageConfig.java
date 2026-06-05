package com.shopsphere.catalog;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Builds the S3 client and presigner from {@link S3StorageProperties}. Path-style access is forced so
 * the same configuration works against LocalStack (which is not virtual-host addressable) and real S3.
 * When {@code endpoint} is blank the SDK uses its normal AWS endpoint resolution — the dev↔cloud flip.
 */
@Configuration
@EnableConfigurationProperties(S3StorageProperties.class)
class StorageConfig {

    @Bean
    S3Client s3Client(S3StorageProperties props) {
        var builder = S3Client.builder()
                .region(Region.of(props.region()))
                .forcePathStyle(true)
                .credentialsProvider(credentials(props));
        if (StringUtils.hasText(props.endpoint())) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        return builder.build();
    }

    @Bean
    S3Presigner s3Presigner(S3StorageProperties props) {
        var builder = S3Presigner.builder()
                .region(Region.of(props.region()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(credentials(props));
        if (StringUtils.hasText(props.endpoint())) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credentials(S3StorageProperties props) {
        if (StringUtils.hasText(props.accessKey()) && StringUtils.hasText(props.secretKey())) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        }
        return DefaultCredentialsProvider.create();
    }
}
