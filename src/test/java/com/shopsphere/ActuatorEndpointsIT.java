package com.shopsphere;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ActuatorEndpointsIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void healthIsCompositeAndUpWithBothDependencies() throws Exception {
        // Permitted without auth; composite includes the auto-configured db plus our custom kafka indicator.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.kafka.status").value("UP"));
    }

    @Test
    void infoExposesGitShaAndBuildTimestamp() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.git.commit.id").exists())
                .andExpect(jsonPath("$.build.artifact").value("shopsphere"))
                .andExpect(jsonPath("$.build.time").exists());
    }

    @Test
    void metricsAndModulithEndpointsAreExposed() throws Exception {
        // The metrics endpoint is reachable; orders_placed_total's non-zero value is asserted in
        // CheckoutFlowIT against the registry (the meter only exists once a checkout has run).
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
        mockMvc.perform(get("/actuator/metrics/jvm.memory.used"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/modulith"))
                .andExpect(status().isOk());
    }
}
