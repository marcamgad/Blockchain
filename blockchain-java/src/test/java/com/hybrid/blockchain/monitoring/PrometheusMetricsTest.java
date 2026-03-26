package com.hybrid.blockchain.monitoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
public class PrometheusMetricsTest {

    @Test
    @DisplayName("Metrics: Verify Prometheus exporter formatting")
    public void testPrometheusExportFormat() {
        BlockchainMonitor monitor = new BlockchainMonitor("node1");
        monitor.recordMetric("blocks.validated", 5);
        monitor.recordMetric("tx.processed", 100);

        PrometheusBridge bridge = new PrometheusBridge(monitor);
        String metricsOutput = bridge.buildMetrics();

        assertThat(metricsOutput)
            .contains("# HELP blocks_validated")
            .contains("blocks_validated_total 5\n")
            .contains("# HELP tx_processed")
            .contains("tx_processed_total 100\n")
            .contains("node_uptime_seconds")
            .contains("node_healthy 1");
    }
}
