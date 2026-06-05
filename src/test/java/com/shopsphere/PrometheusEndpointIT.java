package com.shopsphere;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 18a — the app exposes a Prometheus scrape endpoint. Asserts {@code /actuator/prometheus}
 * serves the exposition format and that the four existing business meters appear once recorded
 * (Micrometer registers a meter lazily on first use). No new meters are introduced.
 * <p>
 * {@link AutoConfigureObservability} is required because {@code @SpringBootTest} otherwise disables
 * metrics export in tests; production export is on by default, so this only re-enables it here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
class PrometheusEndpointIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MeterRegistry meters;

    @Test
    void prometheusEndpointServesTheBusinessMeters() throws Exception {
        // Touch each meter with its production name + tag key so it is registered for the scrape.
        meters.counter("orders_placed_total", "outcome", "placed").increment();
        meters.counter("payments_total", "outcome", "succeeded").increment();
        meters.counter("reservations_total", "status", "granted").increment();
        Timer.builder("checkout_latency_seconds").register(meters).record(Duration.ofMillis(5));

        MvcResult result = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(body).contains("jvm_memory_used_bytes");
        // Micrometer suffixes counters with _total; the timer becomes _seconds_count/_sum/_bucket.
        assertThat(body).contains("orders_placed_total");
        assertThat(body).contains("payments_total");
        assertThat(body).contains("reservations_total");
        assertThat(body).contains("checkout_latency_seconds");
    }
}
