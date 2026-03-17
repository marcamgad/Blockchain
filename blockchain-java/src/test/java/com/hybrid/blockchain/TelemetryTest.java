package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
public class TelemetryTest {

    @Test
    @DisplayName("Invariant: Telemetry data must be retrievable by device ID and block range")
    void testTelemetryIndexingAndRetrieval() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Storage storage = tb.getStorage();
            String deviceId = "temp-sensor-01";
            
            // 1. Save telemetry at different heights
            storage.saveTelemetry(deviceId, 10, "tx_a", "{\"tmp\": 22.5}".getBytes());
            storage.saveTelemetry(deviceId, 15, "tx_b", "{\"tmp\": 23.0}".getBytes());
            storage.saveTelemetry(deviceId, 20, "tx_c", "{\"tmp\": 22.8}".getBytes());
            
            // 2. Query range [10, 15]
            List<Map<String, Object>> range1 = storage.getTelemetry(deviceId, 10, 15);
            assertThat(range1).hasSize(2);
            assertThat(range1.get(0).get("txid")).isEqualTo("tx_a");
            assertThat(range1.get(1).get("txid")).isEqualTo("tx_b");
            
            // 3. Query range [16, 25]
            List<Map<String, Object>> range2 = storage.getTelemetry(deviceId, 16, 25);
            assertThat(range2).hasSize(1);
            assertThat(range2.get(0).get("txid")).isEqualTo("tx_c");
        }
    }

    @Test
    @DisplayName("Security: Telemetry from different devices must be isolated")
    void testTelemetryIsolation() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Storage storage = tb.getStorage();
            
            storage.saveTelemetry("dev1", 100, "tx1", "data1".getBytes());
            storage.saveTelemetry("dev2", 100, "tx2", "data2".getBytes());
            
            List<Map<String, Object>> res1 = storage.getTelemetry("dev1", 0, 200);
            assertThat(res1).hasSize(1);
            assertThat(res1.get(0).get("deviceId")).isEqualTo("dev1");
            
            List<Map<String, Object>> res2 = storage.getTelemetry("dev2", 0, 200);
            assertThat(res2).hasSize(1);
            assertThat(res2.get(0).get("deviceId")).isEqualTo("dev2");
        }
    }

    @Test
    @DisplayName("Adversarial: Querying telemetry for non-existent device or range must return empty")
    void testEmptyTelemetryQueries() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Storage storage = tb.getStorage();
            
            List<Map<String, Object>> res = storage.getTelemetry("ghost_dev", 0, 1000);
            assertThat(res).isEmpty();
        }
    }
}
