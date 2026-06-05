package com.shopsphere;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Singleton Postgres + Kafka containers shared across every {@code @SpringBootTest}. Starting fresh
 * containers per test class would cost ~10 seconds each AND make the Spring context cache incoherent:
 * two test classes with identical configuration would share a context (good), but each class's
 * {@code @Container} field would launch its own container with a different dynamic port. Beans
 * cached from the first class then point at a port the second class's container doesn't own.
 * <p>
 * Centralising the lifecycle here gives every test the same broker URL and lets Spring's context
 * cache do its job.
 */
public final class SharedContainers {

    private SharedContainers() {
    }

    private static final String IMAGE_BUCKET = "shopsphere-product-images";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3)
            // Enforce presigned-URL signatures/expiry — LocalStack skips this by default, which would
            // let an expired URL still resolve and make the expiry test meaningless (and unlike real S3).
            .withEnv("S3_SKIP_SIGNATURE_VALIDATION", "0");

    static {
        POSTGRES.start();
        KAFKA.start();
        LOCALSTACK.start();
        createImageBucket();
    }

    private static void createImageBucket() {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(IMAGE_BUCKET).build());
        }
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("shopsphere.storage.s3.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("shopsphere.storage.s3.bucket", () -> IMAGE_BUCKET);
        registry.add("shopsphere.storage.s3.region", LOCALSTACK::getRegion);
        registry.add("shopsphere.storage.s3.access-key", LOCALSTACK::getAccessKey);
        registry.add("shopsphere.storage.s3.secret-key", LOCALSTACK::getSecretKey);
        // Spring caches one context (hence one Hikari pool) per distinct test configuration, and a
        // RANDOM_PORT IT is its own configuration. Several cached pools at the default size of 10
        // each exhaust Postgres's default max_connections (100) — failing late ITs with
        // "sorry, too many clients already". Tests don't need a deep pool, so cap it small.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
    }

    public static String kafkaBootstrap() {
        return KAFKA.getBootstrapServers();
    }
}
