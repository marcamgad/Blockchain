package com.hybrid.blockchain.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

public class TelemetryAnomalyDetectorTest {

    private TelemetryAnomalyDetector detector;

    @BeforeEach
    public void setup() {
        // Since it's a singleton, state might leak between tests, but we will test it with unique device IDs.
        detector = TelemetryAnomalyDetector.getInstance();
    }

    @Test
    public void testBenignTelemetry() {
        String deviceId = "dev-" + System.currentTimeMillis();
        boolean anomaly = false;
        // Need MIN_SAMPLES for detection to start
        for (int i = 0; i < 15; i++) {
            anomaly = detector.check(deviceId, 20.0 + (i % 2));
            assertFalse(anomaly, "Stable telemetry should not trigger an anomaly");
        }
        
        TelemetryAnomalyDetector.AnomalyStats stats = detector.getStats(deviceId);
        assertNotNull(stats);
        assertEquals(0, stats.anomaliesDetected);
    }

    @Test
    public void testZScoreAnomaly() {
        String deviceId = "dev-spike-" + System.currentTimeMillis();
        // Warmup
        for (int i = 0; i < 20; i++) {
            detector.check(deviceId, 25.0);
        }
        
        // Sudden spike!
        boolean anomaly = detector.check(deviceId, 100.0);
        assertTrue(anomaly, "Sudden spike should be detected as an anomaly");
        
        TelemetryAnomalyDetector.AnomalyStats stats = detector.getStats(deviceId);
        assertTrue(stats.anomaliesDetected > 0);
    }
    
    @Test
    public void testArimaAnomaly() {
        String deviceId = "dev-arima-" + System.currentTimeMillis();
        
        // Predictable linear trend
        for (int i = 0; i < 15; i++) {
            detector.check(deviceId, 10 + i * 2); // 10, 12, 14, 16...
        }
        
        // Break the trend pattern but keep value close to mean so Z-score might not catch it instantly
        // Wait, ARIMA uses difference. The mean is moving up, stddev will have some value.
        // Let's drop it to 0 suddenly.
        // Break the trend pattern severely
        // Current trend is 10, 12, ... 38. Next should be 40.
        // Dropping to -100 should be detected even with high trend variance.
        boolean anom = detector.check(deviceId, -100);
        assertTrue(anom, "ARIMA should catch severe trend break");
    }

    @Test
    @DisplayName("Severe: Multiple devices must have isolated anomaly states")
    void testConcurrentDevices() {
        String dev1 = "dev-iso-1-" + System.currentTimeMillis();
        String dev2 = "dev-iso-2-" + System.currentTimeMillis();
        
        // dev1 stable but with some noise (to avoid zero stddev issues making it super sensitive)
        for (int i = 0; i < 20; i++) detector.check(dev1, 10.0 + (i % 2)); // mean 10.5, stddev ~0.5
        // dev2 spiking
        for (int i = 0; i < 20; i++) detector.check(dev2, 100.0);
        
        // 11.0 is very close to dev1's mean (10.5 +/- 0.5), so it should NOT be an anomaly
        assertFalse(detector.check(dev1, 11.0), "Dev1 should remain benign regardless of Dev2 noise");
        assertTrue(detector.check(dev2, 500.0), "Dev2 should track its own spike");
    }

    @Test
    @DisplayName("Severe: JSON telemetry with complex fields must be parsed correctly")
    void testJsonTelemetryParsing() {
        String deviceId = "dev-json-1";
        // Create an "anomaly" by first stabilizing
        for (int i = 0; i < 20; i++) detector.check(deviceId, 20.0);
        
        // Send a spiking value wrapped in complex JSON
        String json = "{\"timestamp\":1234567, \"device\":\"sensor-1\", \"value\":999.9, \"unit\":\"C\"}";
        // This requires using checkTransaction to test parseValue
        com.hybrid.blockchain.Transaction tx = new com.hybrid.blockchain.Transaction.Builder()
            .type(com.hybrid.blockchain.Transaction.Type.TELEMETRY)
            .from(deviceId)
            .data(json.getBytes())
            .build();
            
        int penalty = detector.checkTransaction(tx);
        assertThat(penalty).isEqualTo(10).as("Spiking JSON value must incur 10x penalty");
    }
}
