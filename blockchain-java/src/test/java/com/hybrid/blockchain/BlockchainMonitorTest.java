package com.hybrid.blockchain;

import com.hybrid.blockchain.monitoring.BlockchainMonitor;
import com.hybrid.blockchain.monitoring.BlockchainMonitor.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Real-Time Monitoring System
 */
public class BlockchainMonitorTest {

    private BlockchainMonitor monitor;

    @BeforeEach
    public void setUp() {
        monitor = new BlockchainMonitor("test-node-001");
    }

    @Test
    public void testMetricRecording() {
        monitor.recordMetric("transactions.submitted", 10);
        monitor.recordMetric("transactions.submitted", 20);
        monitor.recordMetric("transactions.submitted", 30);

        Map<String, MetricSummary> metrics = monitor.getMetricsSummary();
        MetricSummary txMetric = metrics.get("transactions.submitted");

        assertNotNull(txMetric);
        assertEquals(3, txMetric.getCount());
        assertEquals(60, txMetric.getTotal());
        assertEquals(20.0, txMetric.getAverage(), 0.01);
        assertEquals(10, txMetric.getMin());
        assertEquals(30, txMetric.getMax());
        assertEquals(30, txMetric.getLastValue());
    }

    @Test
    public void testIncrementMetric() {
        monitor.incrementMetric("blocks.created");
        monitor.incrementMetric("blocks.created");
        monitor.incrementMetric("blocks.created");

        Map<String, MetricSummary> metrics = monitor.getMetricsSummary();
        MetricSummary blockMetric = metrics.get("blocks.created");

        assertEquals(3, blockMetric.getCount());
        assertEquals(3, blockMetric.getTotal());
    }

    @Test
    public void testHealthChecks() {
        // Initially all healthy
        HealthStatus status = monitor.getHealthStatus();
        assertTrue(status.isHealthy());

        // Update consensus health to unhealthy
        monitor.updateHealthCheck("consensus", false, "Validator timeout");

        status = monitor.getHealthStatus();
        assertFalse(status.isHealthy());
        assertFalse(status.getChecks().get("consensus"));
        assertTrue(status.getChecks().get("storage"));
    }

    @Test
    public void testAlertCreation() {
        monitor.createAlert(AlertLevel.WARNING, "High Latency", "Transaction latency > 1000ms");
        monitor.createAlert(AlertLevel.ERROR, "Consensus Failure", "Failed to reach consensus");

        List<Alert> alerts = monitor.getRecentAlerts(10);
        assertEquals(2, alerts.size());

        Alert lastAlert = alerts.get(1);
        assertEquals(AlertLevel.ERROR, lastAlert.getLevel());
        assertEquals("Consensus Failure", lastAlert.getTitle());
    }

    @Test
    public void testHealthCheckCreatesAlert() {
        monitor.updateHealthCheck("network", false, "Peer disconnected");

        List<Alert> alerts = monitor.getRecentAlerts(10);
        assertTrue(alerts.size() > 0);

        Alert alert = alerts.get(0);
        assertEquals(AlertLevel.WARNING, alert.getLevel());
        assertTrue(alert.getMessage().contains("network"));
    }

    @Test
    public void testPerformanceMetrics() throws InterruptedException {
        // Record some transactions
        for (int i = 0; i < 100; i++) {
            monitor.incrementMetric("transactions.submitted");
            monitor.recordMetric("transactions.latency", 50 + i);
        }

        // Record some blocks
        monitor.recordMetric("blocks.time", 1000);
        monitor.recordMetric("blocks.time", 1200);
        monitor.recordMetric("blocks.time", 800);

        Thread.sleep(100); // Small delay for uptime

        PerformanceMetrics perf = monitor.getPerformanceMetrics();

        assertTrue(perf.getTps() > 0);
        assertEquals(99.5, perf.getAvgLatency(), 0.1);
        assertEquals(1000.0, perf.getAvgBlockTime(), 0.1);
        assertEquals(100, perf.getTotalTransactions());
    }

    @Test
    public void testDashboard() {
        // Record some metrics
        monitor.incrementMetric("transactions.submitted");
        monitor.incrementMetric("blocks.created");
        monitor.updateHealthCheck("consensus", true, "OK");
        monitor.createAlert(AlertLevel.INFO, "Test", "Test alert");

        Dashboard dashboard = monitor.getDashboard();

        assertNotNull(dashboard);
        assertEquals("test-node-001", dashboard.getNodeId());
        assertTrue(dashboard.getUptime() > 0);
        assertTrue(dashboard.getHealth().isHealthy());
        assertEquals(1, dashboard.getRecentAlerts().size());
        assertNotNull(dashboard.getPerformance());
    }

    @Test
    public void testDashboardJSON() {
        monitor.incrementMetric("transactions.submitted");

        Dashboard dashboard = monitor.getDashboard();
        String json = dashboard.toJSON();

        assertNotNull(json);
        assertTrue(json.contains("nodeId"));
        assertTrue(json.contains("uptime"));
        assertTrue(json.contains("healthy"));
        assertTrue(json.contains("performance"));
        assertTrue(json.contains("tps"));
    }

    @Test
    public void testUptime() throws InterruptedException {
        long uptime1 = monitor.getUptime();
        Thread.sleep(100);
        long uptime2 = monitor.getUptime();

        assertTrue(uptime2 > uptime1);
        assertTrue(uptime2 >= 100);
    }

    @Test
    public void testMultipleMetrics() {
        monitor.recordMetric("transactions.submitted", 10);
        monitor.recordMetric("transactions.validated", 9);
        monitor.recordMetric("transactions.rejected", 1);
        monitor.recordMetric("blocks.created", 1);

        Map<String, MetricSummary> metrics = monitor.getMetricsSummary();

        assertTrue(metrics.size() > 0);
        assertNotNull(metrics.get("transactions.submitted"));
        assertNotNull(metrics.get("transactions.validated"));
        assertNotNull(metrics.get("transactions.rejected"));
        assertNotNull(metrics.get("blocks.created"));
    }

    @Test
    public void testAlertLimit() {
        // Create more than 1000 alerts
        for (int i = 0; i < 1100; i++) {
            monitor.createAlert(AlertLevel.INFO, "Alert " + i, "Message " + i);
        }

        List<Alert> alerts = monitor.getRecentAlerts(2000);

        // Should be capped at 1000
        assertTrue(alerts.size() <= 1000);
    }

    @Test
    public void testRecentAlertsLimit() {
        for (int i = 0; i < 20; i++) {
            monitor.createAlert(AlertLevel.INFO, "Alert " + i, "Message");
        }

        List<Alert> recent = monitor.getRecentAlerts(5);
        assertEquals(5, recent.size());

        // Should be the most recent ones
        Alert last = recent.get(4);
        assertTrue(last.getTitle().contains("19"));
    }

    @Test
    public void testMetricSummaryDetails() {
        monitor.recordMetric("test.metric", 100);
        monitor.recordMetric("test.metric", 200);
        monitor.recordMetric("test.metric", 300);

        Map<String, MetricSummary> metrics = monitor.getMetricsSummary();
        MetricSummary summary = metrics.get("test.metric");

        assertNotNull(summary);
        assertEquals(3, summary.getCount());
        assertEquals(600, summary.getTotal());
        assertEquals(200.0, summary.getAverage(), 0.01);
        assertEquals(100, summary.getMin());
        assertEquals(300, summary.getMax());
        assertTrue(summary.getLastUpdate() > 0);
    }
}
