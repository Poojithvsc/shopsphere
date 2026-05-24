package com.shopsphere.ordering;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.fieldnames.LogstashFieldNames;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the JSON shape of the {@code Order placed} log line. Mirrors the {@code LogstashEncoder}
 * configuration from {@code logback-spring.xml} (custom {@code app} field + renamed defaults) so the
 * shape this test pins is the shape production emits.
 */
class StructuredLogShapeTests {

    @Test
    void orderPlacedLogLineIsValidJsonWithContextualFields() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setCustomFields("{\"app\":\"shopsphere\"}");
        LogstashFieldNames names = new LogstashFieldNames();
        names.setTimestamp("timestamp");
        names.setLogger("logger");
        names.setThread("thread");
        encoder.setFieldNames(names);
        encoder.setContext(context);
        encoder.start();

        Logger logger = context.getLogger(CheckoutService.class);
        LoggingEvent event = new LoggingEvent(
                Logger.FQCN, logger, Level.INFO,
                "Order placed with {} line(s), total {} {}",
                null, new Object[]{2, "1999.0000", "INR"});
        event.setMDCPropertyMap(Map.of(
                "orderId", "11111111-1111-1111-1111-111111111111",
                "customerId", "22222222-2222-2222-2222-222222222222"));

        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
        JsonNode line = new ObjectMapper().readTree(json);

        assertThat(line.hasNonNull("timestamp")).isTrue();
        assertThat(line.get("level").asText()).isEqualTo("INFO");
        assertThat(line.get("logger").asText()).isEqualTo(CheckoutService.class.getName());
        assertThat(line.get("message").asText()).contains("Order placed");
        assertThat(line.get("app").asText()).isEqualTo("shopsphere");
        assertThat(line.get("orderId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(line.get("customerId").asText()).isEqualTo("22222222-2222-2222-2222-222222222222");
    }
}
