package com.hybrid.blockchain.monitoring;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.util.Collections;

/**
 * Unit and integration tests for Blockchain Monitoring and Alerting.
 */
@Tag("monitoring")
public class BlockchainMonitorCompleteTest {

    private BlockchainMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = BlockchainMonitor.getInstance();
        monitor.resetMetrics();
    }

    @Test
    @DisplayName("MON1.1 — Metric recording")
    void testMetrics() {
        monitor.recordMetric("custom_metric", 10.0);
        monitor.recordMetric("custom_metric", 5.0);
        assertThat(monitor.getMetricSum("custom_metric")).isEqualTo(15.0);
    }

    @Test
    @DisplayName("MON1.2 — Integration: blocks.validated")
    void testBlockValidationMetric() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            for(int i=0; i<5; i++) BlockApplier.createAndApplyBlock(tb, Collections.emptyList());
            // metric should be updated in Blockchain.applyBlock
            assertThat(monitor.getMetricSum("blocks.validated")).isEqualTo(5.0);
        }
    }

    @Test
    @DisplayName("MON1.3-1.4 — Alerting")
    void testAlerts() {
        monitor.setThreshold("high_load", 100.0);
        monitor.recordMetric("high_load", 101.0);
        
        assertThat(monitor.getAlerts()).anyMatch(a -> a.getMetric().equals("high_load") && a.isTriggered());
    }

    @Test
    @DisplayName("MON1.5 — Dashboard")
    void testDashboard() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            BlockchainMonitor.Dashboard dash = monitor.getDashboard(tb.getBlockchain());
            assertThat(dash.getBlockHeight()).isEqualTo(0);
            assertThat(dash.getMempoolSize()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("MON1.6 — Health check")
    void testHealthCheck() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            assertThat(monitor.checkHealth(tb.getBlockchain()).isHealthy()).isTrue();
            tb.getStorage().close();
            assertThat(monitor.checkHealth(tb.getBlockchain()).isHealthy()).as("Health check should fail when storage closed").isFalse();
        }
    }

    @Test
    @DisplayName("MON1.7 — Prometheus Bridge")
    void testPrometheusBridge() {
        monitor.recordMetric("blockchain_blocks_total", 5);
        String response = PrometheusBridge.buildMetricResponse();
        assertThat(response).contains("blockchain_blocks_total 5");
    }
}
