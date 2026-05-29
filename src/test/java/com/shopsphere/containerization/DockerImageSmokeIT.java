package com.shopsphere.containerization;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10 smoke test: builds the production Dockerfile, boots the resulting image
 * against real Postgres + Kafka on a shared Docker network, and asserts the composite
 * health endpoint returns {@code UP} within the startup timeout.
 *
 * <p>This is the "the image actually runs" CI gate. It complements the in-JVM
 * {@code @SpringBootTest} suite — those exercise the application code; this one
 * exercises the {@code Dockerfile} itself: layered jars assemble correctly, the
 * non-root user can read everything it needs, the JVM ergonomics resolve, and the
 * env-var substitution in {@code application.yml} maps cleanly onto the compose
 * service contract ({@code DB_HOST}, {@code KAFKA_BOOTSTRAP_SERVERS}, …).
 *
 * <p>Kafka is run as a {@link GenericContainer} (not Testcontainers'
 * {@code KafkaContainer}) because the latter rewrites advertised listeners using the
 * container's dynamic IP rather than the static network alias — which works for
 * host-side test clients but breaks containers reaching the broker through the alias.
 * The KRaft config below mirrors {@code docker-compose.yml} so the smoke test
 * exercises the same listener topology a developer hits with
 * {@code docker compose --profile full up}.
 */
class DockerImageSmokeIT {

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withDatabaseName("shopsphere")
            .withUsername("shopsphere")
            .withPassword("shopsphere");

    // Listener topology mirrors docker-compose.yml verbatim — proven config. The
    // single-listener variant (only PLAINTEXT://kafka:9092) passed on Docker Desktop
    // for Windows but failed on the Linux GitHub Actions runner: the cp-kafka image's
    // entrypoint appears to override the advertised listener with localhost:<mapped>
    // when only one PLAINTEXT listener is declared. Replicating compose's dual
    // PLAINTEXT + PLAINTEXT_HOST shape side-steps that auto-detection entirely.
    private static final GenericContainer<?> KAFKA = new GenericContainer<>("confluentinc/cp-kafka:7.6.1")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withEnv("KAFKA_NODE_ID", "1")
            .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
            .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@kafka:9093")
            .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
            .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:29092,CONTROLLER://0.0.0.0:9093,PLAINTEXT_HOST://0.0.0.0:9092")
            .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092")
            .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT")
            .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
            .withEnv("CLUSTER_ID", "MkU3OEVBNTcwNTJENDM2Qk")
            .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static final String IMAGE_TAG = "shopsphere-smoke:latest";

    private static GenericContainer<?> app;

    @BeforeAll
    static void buildAndStart() throws Exception {
        POSTGRES.start();
        KAFKA.start();

        buildImage();

        // DefaultPullPolicy is local-cache-first: the image we just built is present,
        // so this never reaches a registry (the tag has no registry counterpart anyway).
        app = new GenericContainer<>(DockerImageName.parse(IMAGE_TAG))
                .withNetwork(NETWORK)
                .withExposedPorts(8080)
                .withEnv("DB_HOST", "postgres")
                .withEnv("DB_PORT", "5432")
                .withEnv("DB_NAME", "shopsphere")
                .withEnv("DB_USER", "shopsphere")
                .withEnv("DB_PASSWORD", "shopsphere")
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092")
                .withEnv("JWT_SECRET", "dev-only-secret-change-me-32-bytes-minimum-aaaa")
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(180)));

        app.start();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.stop();
        }
        KAFKA.stop();
        POSTGRES.stop();
        NETWORK.close();
    }

    /**
     * Builds the production image via the Docker CLI rather than Testcontainers'
     * {@code ImageFromDockerfile}. The latter builds through the docker-java API, which
     * only drives Docker's <em>legacy</em> builder — and the legacy builder fails to
     * export this multi-stage, layered-jar image on the Linux CI runner with
     * {@code failed to get layer …: layer does not exist} (a known moby bug). The
     * {@code docker build} CLI uses BuildKit by default, which assembles multi-stage
     * layers correctly, so the build behaves identically on Windows Docker Desktop and
     * the GitHub Actions Linux runner. See ADR-0010.
     */
    private static void buildImage() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("docker", "build", "-t", IMAGE_TAG, ".")
                .directory(new File("."))
                .redirectErrorStream(true);
        pb.environment().put("DOCKER_BUILDKIT", "1");

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("docker build timed out after 10 minutes:\n" + output);
        }
        int exit = process.exitValue();
        if (exit != 0) {
            throw new IllegalStateException("docker build failed (exit " + exit + "):\n" + output);
        }
    }

    @Test
    void healthIsUpAfterImageBootsAgainstRealDependencies() throws Exception {
        URI uri = URI.create("http://" + app.getHost() + ":" + app.getMappedPort(8080) + "/actuator/health");
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);

        int status = conn.getResponseCode();
        String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(status).isEqualTo(200);
        assertThat(body).contains("\"status\":\"UP\"");
        assertThat(body).contains("\"db\":{\"status\":\"UP\"");
        assertThat(body).contains("\"kafka\":{\"status\":\"UP\"");
    }
}
