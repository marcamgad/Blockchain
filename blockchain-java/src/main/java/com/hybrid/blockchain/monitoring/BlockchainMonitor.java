package com.hybrid.blockchain.monitoring;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real-Time Monitoring and Metrics System
 * 
 * Provides comprehensive observability for production IoT blockchain:
 * - Performance metrics (TPS, latency, throughput)
 * - Health checks (node status, consensus health)
 * - Resource monitoring (memory, storage, network)
 * - Alert system for anomalies
 * - Historical data tracking
 * 
 * Use cases:
 * - Production monitoring and alerting
 * - Performance optimization
 * - Capacity planning
 * - Incident response
 */
public class BlockchainMonitor {

    private final String nodeId;
    private final Map<String, MetricCollector> metrics;
    private final List<Alert> alerts;
    private final long startTime;
    private final Map<String, HealthCheck> healthChecks;

    public BlockchainMonitor(String nodeId) {
        this.nodeId = nodeId;
        this.metrics = new ConcurrentHashMap<>();
        this.alerts = Collections.synchronizedList(new ArrayList<>());
        this.startTime = System.currentTimeMillis();
        this.healthChecks = new ConcurrentHashMap<>();

        initializeMetrics();
        initializeHealthChecks();
    }

    private void initializeMetrics() {
        // Transaction metrics
        metrics.put("transactions.submitted", new MetricCollector("Transactions Submitted"));
        metrics.put("transactions.validated", new MetricCollector("Transactions Validated"));
        metrics.put("transactions.rejected", new MetricCollector("Transactions Rejected"));
        metrics.put("transactions.latency", new MetricCollector("Transaction Latency (ms)"));

        // Block metrics
        metrics.put("blocks.created", new MetricCollector("Blocks Created"));
        metrics.put("blocks.validated", new MetricCollector("Blocks Validated"));
        metrics.put("blocks.size", new MetricCollector("Block Size (bytes)"));
        metrics.put("blocks.time", new MetricCollector("Block Time (ms)"));

        // Consensus metrics
        metrics.put("consensus.proposals", new MetricCollector("Consensus Proposals"));
        metrics.put("consensus.votes", new MetricCollector("Consensus Votes"));
        metrics.put("consensus.failures", new MetricCollector("Consensus Failures"));

        // Device metrics
        metrics.put("devices.active", new MetricCollector("Active Devices"));
        metrics.put("devices.provisioned", new MetricCollector("Provisioned Devices"));
        metrics.put("devices.revoked", new MetricCollector("Revoked Devices"));

        // Network metrics
        metrics.put("network.peers", new MetricCollector("Connected Peers"));
        metrics.put("network.bandwidth", new MetricCollector("Network Bandwidth (KB/s)"));

        // Storage metrics
        metrics.put("storage.size", new MetricCollector("Storage Size (MB)"));
        metrics.put("storage.growth", new MetricCollector("Storage Growth (MB/day)"));
    }

    private void initializeHealthChecks() {
        healthChecks.put("consensus", new HealthCheck("Consensus Health"));
        healthChecks.put("storage", new HealthCheck("Storage Health"));
        healthChecks.put("network", new HealthCheck("Network Health"));
        healthChecks.put("memory", new HealthCheck("Memory Health"));
    }

    /**
     * Record a metric value
     */
    public void recordMetric(String metricName, long value) {
        MetricCollector collector = metrics.get(metricName);
        if (collector != null) {
            collector.record(value);
        }
    }

    /**
     * Increment a counter metric
     */
    public void incrementMetric(String metricName) {
        recordMetric(metricName, 1);
    }

    /**
     * Update health check status
     */
    public void updateHealthCheck(String checkName, boolean healthy, String message) {
        HealthCheck check = healthChecks.get(checkName);
        if (check != null) {
            check.update(healthy, message);

            // Create alert if health check fails
            if (!healthy) {
                createAlert(AlertLevel.WARNING, "Health Check Failed",
                        checkName + ": " + message);
            }
        }
    }

    /**
     * Create an alert
     */
    public void createAlert(AlertLevel level, String title, String message) {
        Alert alert = new Alert(level, title, message);
        alerts.add(alert);

        // Keep only last 1000 alerts
        if (alerts.size() > 1000) {
            alerts.remove(0);
        }

        System.out.println("[ALERT] " + alert);
    }

    /**
     * Get dashboard snapshot
     */
    public Dashboard getDashboard() {
        return new Dashboard(
                nodeId,
                getUptime(),
                getMetricsSummary(),
                getHealthStatus(),
                getRecentAlerts(10),
                getPerformanceMetrics());
    }

    /**
     * Get uptime in milliseconds
     */
    public long getUptime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Get metrics summary
     */
    public Map<String, MetricSummary> getMetricsSummary() {
        Map<String, MetricSummary> summary = new HashMap<>();
        for (Map.Entry<String, MetricCollector> entry : metrics.entrySet()) {
            summary.put(entry.getKey(), entry.getValue().getSummary());
        }
        return summary;
    }

    /**
     * Get overall health status
     */
    public HealthStatus getHealthStatus() {
        boolean allHealthy = true;
        Map<String, Boolean> checks = new HashMap<>();

        for (Map.Entry<String, HealthCheck> entry : healthChecks.entrySet()) {
            boolean healthy = entry.getValue().isHealthy();
            checks.put(entry.getKey(), healthy);
            if (!healthy) {
                allHealthy = false;
            }
        }

        return new HealthStatus(allHealthy, checks);
    }

    /**
     * Get recent alerts
     */
    public List<Alert> getRecentAlerts(int count) {
        int size = alerts.size();
        int start = Math.max(0, size - count);
        return new ArrayList<>(alerts.subList(start, size));
    }

    /**
     * Get performance metrics
     */
    public PerformanceMetrics getPerformanceMetrics() {
        MetricCollector txSubmitted = metrics.get("transactions.submitted");
        MetricCollector txLatency = metrics.get("transactions.latency");
        MetricCollector blockTime = metrics.get("blocks.time");

        long uptimeSeconds = getUptime() / 1000;
        double tps = uptimeSeconds > 0 ? (double) txSubmitted.getTotal() / uptimeSeconds : 0;

        return new PerformanceMetrics(
                tps,
                txLatency.getAverage(),
                blockTime.getAverage(),
                txSubmitted.getTotal());
    }

    /**
     * Metric Collector - tracks statistics for a single metric
     */
    public static class MetricCollector {
        private final String name;
        private final AtomicLong count;
        private final AtomicLong total;
        private volatile long min;
        private volatile long max;
        private volatile long lastValue;
        private volatile long lastUpdate;

        public MetricCollector(String name) {
            this.name = name;
            this.count = new AtomicLong(0);
            this.total = new AtomicLong(0);
            this.min = Long.MAX_VALUE;
            this.max = Long.MIN_VALUE;
            this.lastValue = 0;
            this.lastUpdate = System.currentTimeMillis();
        }

        public synchronized void record(long value) {
            count.incrementAndGet();
            total.addAndGet(value);

            if (value < min)
                min = value;
            if (value > max)
                max = value;

            lastValue = value;
            lastUpdate = System.currentTimeMillis();
        }

        public MetricSummary getSummary() {
            long c = count.get();
            long t = total.get();
            double avg = c > 0 ? (double) t / c : 0;

            return new MetricSummary(
                    name,
                    c,
                    t,
                    avg,
                    min == Long.MAX_VALUE ? 0 : min,
                    max == Long.MIN_VALUE ? 0 : max,
                    lastValue,
                    lastUpdate);
        }

        public long getTotal() {
            return total.get();
        }

        public long getCount() {
            return count.get();
        }

        public double getAverage() {
            long c = count.get();
            return c > 0 ? (double) total.get() / c : 0;
        }
    }

    /**
     * Health Check
     */
    public static class HealthCheck {
        private final String name;
        private volatile boolean healthy;
        private volatile String message;
        private volatile long lastCheck;

        public HealthCheck(String name) {
            this.name = name;
            this.healthy = true;
            this.message = "OK";
            this.lastCheck = System.currentTimeMillis();
        }

        public void update(boolean healthy, String message) {
            this.healthy = healthy;
            this.message = message;
            this.lastCheck = System.currentTimeMillis();
        }

        public boolean isHealthy() {
            return healthy;
        }

        public String getMessage() {
            return message;
        }

        public long getLastCheck() {
            return lastCheck;
        }
    }

    /**
     * Alert
     */
    public static class Alert {
        private final long timestamp;
        private final AlertLevel level;
        private final String title;
        private final String message;

        public Alert(AlertLevel level, String title, String message) {
            this.timestamp = System.currentTimeMillis();
            this.level = level;
            this.title = title;
            this.message = message;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s: %s - %s",
                    new Date(timestamp), level, title, message);
        }

        public long getTimestamp() {
            return timestamp;
        }

        public AlertLevel getLevel() {
            return level;
        }

        public String getTitle() {
            return title;
        }

        public String getMessage() {
            return message;
        }
    }

    public enum AlertLevel {
        INFO, WARNING, ERROR, CRITICAL
    }

    /**
     * Dashboard - complete monitoring snapshot
     */
    public static class Dashboard {
        private final String nodeId;
        private final long uptime;
        private final Map<String, MetricSummary> metrics;
        private final HealthStatus health;
        private final List<Alert> recentAlerts;
        private final PerformanceMetrics performance;

        public Dashboard(
                String nodeId,
                long uptime,
                Map<String, MetricSummary> metrics,
                HealthStatus health,
                List<Alert> recentAlerts,
                PerformanceMetrics performance) {
            this.nodeId = nodeId;
            this.uptime = uptime;
            this.metrics = metrics;
            this.health = health;
            this.recentAlerts = recentAlerts;
            this.performance = performance;
        }

        public String toJSON() {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"nodeId\": \"").append(nodeId).append("\",\n");
            json.append("  \"uptime\": ").append(uptime).append(",\n");
            json.append("  \"healthy\": ").append(health.isHealthy()).append(",\n");
            json.append("  \"performance\": {\n");
            json.append("    \"tps\": ").append(String.format("%.2f", performance.getTps())).append(",\n");
            json.append("    \"avgLatency\": ").append(String.format("%.2f", performance.getAvgLatency()))
                    .append(",\n");
            json.append("    \"avgBlockTime\": ").append(String.format("%.2f", performance.getAvgBlockTime()))
                    .append("\n");
            json.append("  },\n");
            json.append("  \"alertCount\": ").append(recentAlerts.size()).append("\n");
            json.append("}");
            return json.toString();
        }

        // Getters
        public String getNodeId() {
            return nodeId;
        }

        public long getUptime() {
            return uptime;
        }

        public Map<String, MetricSummary> getMetrics() {
            return metrics;
        }

        public HealthStatus getHealth() {
            return health;
        }

        public List<Alert> getRecentAlerts() {
            return recentAlerts;
        }

        public PerformanceMetrics getPerformance() {
            return performance;
        }
    }

    /**
     * Metric Summary
     */
    public static class MetricSummary {
        private final String name;
        private final long count;
        private final long total;
        private final double average;
        private final long min;
        private final long max;
        private final long lastValue;
        private final long lastUpdate;

        public MetricSummary(String name, long count, long total, double average,
                long min, long max, long lastValue, long lastUpdate) {
            this.name = name;
            this.count = count;
            this.total = total;
            this.average = average;
            this.min = min;
            this.max = max;
            this.lastValue = lastValue;
            this.lastUpdate = lastUpdate;
        }

        // Getters
        public String getName() {
            return name;
        }

        public long getCount() {
            return count;
        }

        public long getTotal() {
            return total;
        }

        public double getAverage() {
            return average;
        }

        public long getMin() {
            return min;
        }

        public long getMax() {
            return max;
        }

        public long getLastValue() {
            return lastValue;
        }

        public long getLastUpdate() {
            return lastUpdate;
        }
    }

    /**
     * Health Status
     */
    public static class HealthStatus {
        private final boolean healthy;
        private final Map<String, Boolean> checks;

        public HealthStatus(boolean healthy, Map<String, Boolean> checks) {
            this.healthy = healthy;
            this.checks = checks;
        }

        public boolean isHealthy() {
            return healthy;
        }

        public Map<String, Boolean> getChecks() {
            return checks;
        }
    }

    /**
     * Performance Metrics
     */
    public static class PerformanceMetrics {
        private final double tps;
        private final double avgLatency;
        private final double avgBlockTime;
        private final long totalTransactions;

        public PerformanceMetrics(double tps, double avgLatency,
                double avgBlockTime, long totalTransactions) {
            this.tps = tps;
            this.avgLatency = avgLatency;
            this.avgBlockTime = avgBlockTime;
            this.totalTransactions = totalTransactions;
        }

        public double getTps() {
            return tps;
        }

        public double getAvgLatency() {
            return avgLatency;
        }

        public double getAvgBlockTime() {
            return avgBlockTime;
        }

        public long getTotalTransactions() {
            return totalTransactions;
        }
    }
}
