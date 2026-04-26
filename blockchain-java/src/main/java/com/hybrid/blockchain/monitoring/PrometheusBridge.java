package com.hybrid.blockchain.monitoring;

import java.util.Map;

/**
 * Prometheus Bridge for HybridChain.
 * Exports BlockchainMonitor metrics in Prometheus text format.
 */
public class PrometheusBridge {
    private final BlockchainMonitor monitor;

    public PrometheusBridge(BlockchainMonitor monitor) {
        this.monitor = monitor;
    }

    public static String buildMetricResponse() {
        return new PrometheusBridge(BlockchainMonitor.getInstance()).buildMetrics();
    }

    /**
     * Builds the metrics response in Prometheus format.
     * 
     * @return String containing Prometheus formatted metrics
     */
    public String buildMetrics() {
        StringBuilder sb = new StringBuilder();
        Map<String, BlockchainMonitor.MetricSummary> summary = monitor.getMetricsSummary();

        for (Map.Entry<String, BlockchainMonitor.MetricSummary> entry : summary.entrySet()) {
            String rawName = entry.getKey();
            String prometheusName = rawName.replace(".", "_");
            String totalMetricName = prometheusName.endsWith("_total") ? prometheusName : prometheusName + "_total";
            BlockchainMonitor.MetricSummary s = entry.getValue();

            // Total Counter
            sb.append("# HELP ").append(totalMetricName).append(" ").append(s.getName()).append(" total\n");
            sb.append("# TYPE ").append(totalMetricName).append(" counter\n");
            sb.append(totalMetricName).append(" ").append(s.getTotal()).append("\n\n");

            // Count
            sb.append("# HELP ").append(prometheusName).append("_count ").append(s.getName()).append(" sample count\n");
            sb.append("# TYPE ").append(prometheusName).append("_count gauge\n");
            sb.append(prometheusName).append("_count ").append(s.getCount()).append("\n\n");

            // Last Value
            sb.append("# HELP ").append(prometheusName).append("_last ").append(s.getName()).append(" last recorded value\n");
            sb.append("# TYPE ").append(prometheusName).append("_last gauge\n");
            sb.append(prometheusName).append("_last ").append(s.getLastValue()).append("\n\n");

            // Average
            sb.append("# HELP ").append(prometheusName).append("_avg ").append(s.getName()).append(" running average\n");
            sb.append("# TYPE ").append(prometheusName).append("_avg gauge\n");
            sb.append(prometheusName).append("_avg ").append(String.format("%.4f", s.getAverage())).append("\n\n");
        }

        // Uptime
        sb.append("# HELP node_uptime_seconds Node uptime in seconds\n");
        sb.append("# TYPE node_uptime_seconds counter\n");
        sb.append("node_uptime_seconds ").append(String.format("%.2f", monitor.getUptime() / 1000.0)).append("\n\n");

        // Health Status
        BlockchainMonitor.HealthStatus health = monitor.getHealthStatus();
        sb.append("# HELP node_healthy 1 if all health checks pass, 0 otherwise\n");
        sb.append("# TYPE node_healthy gauge\n");
        sb.append("node_healthy ").append(health.isHealthy() ? 1 : 0).append("\n\n");

        for (Map.Entry<String, Boolean> check : health.getChecks().entrySet()) {
            String checkName = check.getKey();
            sb.append("# HELP node_health_").append(checkName).append(" 1 if ").append(checkName).append(" health check passes\n");
            sb.append("# TYPE node_health_").append(checkName).append(" gauge\n");
            sb.append("node_health_").append(checkName).append(" ").append(check.getValue() ? 1 : 0).append("\n\n");
        }

        return sb.toString();
    }
}
