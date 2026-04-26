package com.hybrid.blockchain.ai;

import com.hybrid.blockchain.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Z-score anomaly detection for IoT telemetry transactions.
 *
 * <p><b>Algorithm:</b> Z-Score + ARIMA(1,1,1) approximation.
 * <p><b>Time Complexity:</b> O(W) where W is the sliding window size (default 50).
 * <p><b>IoT Rationale:</b> Lightweight enough for edge nodes; effectively detects 
 * sensor drift and compromised devices without requiring heavy neural networks.
 *
 * <p>Maintains a sliding window of the last 50 readings per device.
 * A reading is flagged as an anomaly when |z-score| > 3.0.</p>
 */
public class TelemetryAnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(TelemetryAnomalyDetector.class);

    public static final int    WINDOW_SIZE  = 50;
    public static final double Z_THRESHOLD  = 3.0;
    /** Minimum samples before detection is active. */
    private static final int   MIN_SAMPLES  = 10;

    // ── per-device state ────────────────────────────────────────────────────

    /** Sliding window of recent telemetry values per device. */
    private final Map<String, LinkedList<Double>> windows = new ConcurrentHashMap<>();

    /** Cumulative anomaly statistics per device. */
    private final Map<String, AnomalyStats> statsMap = new ConcurrentHashMap<>();

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * Statistics snapshot for one device.
     */
    public static class AnomalyStats {
        public final  String deviceId;
        public volatile long   totalChecked;
        public volatile long   anomaliesDetected;
        public volatile double lastValue;
        public volatile double lastZScore;
        public volatile long   lastDetectedTimestamp;

        // --- ARIMA(1,1,1) State ---
        public volatile double arimaPrevValue = Double.NaN;
        public volatile double arimaPrevDiff = 0.0;
        public volatile double arimaPrevError = 0.0;

        AnomalyStats(String deviceId) { this.deviceId = deviceId; }
    }

    /**
     * Check a raw numeric reading for a given device.
     *
     * @return {@code true} if an anomaly is detected.
     */
    public double checkValue(String deviceId, double value) {
        return check(deviceId, value) ? 10.0 : 1.0;
    }

    public boolean check(String deviceId, double value) {
        return check(deviceId, value, System.currentTimeMillis());
    }

    public boolean check(String deviceId, double value, long timestamp) {
        AnomalyStats stats = statsMap.computeIfAbsent(deviceId, AnomalyStats::new);
        stats.lastValue = value;
        stats.totalChecked++;

        LinkedList<Double> win = windows.computeIfAbsent(deviceId, k -> new LinkedList<>());
        synchronized (win) {
            win.addLast(value);
            if (win.size() > WINDOW_SIZE) win.removeFirst();

            int n = win.size();
            if (n < MIN_SAMPLES) return false;   // too few samples

            double mean = 0;
            for (double d : win) mean += d;
            mean /= n;

            double variance = 0;
            for (double d : win) variance += (d - mean) * (d - mean);
            variance /= n;
            double stddev = Math.sqrt(variance);

            if (stddev < 1e-10) return false;    // all values identical

            double zScore = Math.abs((value - mean) / stddev);
            stats.lastZScore = zScore;

            // [FEATURE B3] ARIMA(1,1,1) approximation
            // using fixed coefficients: phi_1=0.5, theta_1=0.5
            double predicted = Double.isNaN(stats.arimaPrevValue) ? value : stats.arimaPrevValue + 0.5 * stats.arimaPrevDiff + 0.5 * stats.arimaPrevError;
            double error = value - predicted;
            stats.arimaPrevDiff = Double.isNaN(stats.arimaPrevValue) ? 0 : value - stats.arimaPrevValue;
            stats.arimaPrevValue = value;
            stats.arimaPrevError = error;

            boolean arimaAnomaly = n >= MIN_SAMPLES && Math.abs(error) > (Z_THRESHOLD * 2.0) * stddev && Math.abs(error) > 2.0;
            boolean zScoreAnomaly = zScore > Z_THRESHOLD;

            if (zScoreAnomaly || arimaAnomaly) {
                stats.anomaliesDetected++;
                stats.lastDetectedTimestamp = timestamp;
                // [FIX-B1] Fix SLF4J format string by using String.format for floating points
                log.warn("[ANOMALY] Device {} value={} z={} (mean={} std={})",
                        deviceId, value,
                        String.format("%.2f", zScore),
                        String.format("%.4f", mean),
                        String.format("%.4f", stddev));
                return true;
            }
            return false;
        }
    }

    /**
     * Parse a TELEMETRY transaction's data payload and run anomaly detection.
     *
     * <p>Expected data formats (in {@code tx.getData()}):</p>
     * <ul>
     *   <li>Plain number: {@code "42.5"}</li>
     *   <li>JSON with value field: {@code {"value":42.5,...}}</li>
     *   <li>Comma-separated (first token used): {@code "42.5,temp,..."}</li>
     * </ul>
     *
     * @return {@code 10} if anomaly detected (10× fee penalty), {@code 1} otherwise.
     */
    public int checkTransaction(Transaction tx) {
        return checkTransaction(tx, System.currentTimeMillis());
    }

    public int checkTransaction(Transaction tx, long timestamp) {
        if (tx.getType() != Transaction.Type.TELEMETRY
                || tx.getData() == null || tx.getData().length == 0) {
            return 1;
        }
        try {
            String raw = new String(tx.getData(), StandardCharsets.UTF_8).trim();
            double value = parseValue(raw);
            return check(tx.getFrom(), value, timestamp) ? 10 : 1;
        } catch (Exception e) {
            log.debug("[ANOMALY] Cannot parse telemetry for device {}: {}", tx.getFrom(), e.getMessage());
            return 1;
        }
    }

    private static double parseValue(String raw) {
        if (raw.startsWith("{")) {
            // Minimal JSON extraction — no external library
            int idx = raw.indexOf("\"value\"");
            if (idx < 0) throw new IllegalArgumentException("No 'value' key");
            String rest = raw.substring(idx + 7);
            // strip whitespace and colon, then read the number
            rest = rest.replaceAll("[^0-9.\\-eE+]", " ").trim();
            return Double.parseDouble(rest.split("\\s+")[0]);
        }
        // comma-separated or plain number
        return Double.parseDouble(raw.split("[,\\s;]+")[0]);
    }

    // ── stats accessors ─────────────────────────────────────────────────────

    public AnomalyStats getStats(String deviceId) {
        return statsMap.get(deviceId);
    }

    public Map<String, AnomalyStats> getAllStats() {
        return Collections.unmodifiableMap(statsMap);
    }

    /** Number of values currently in the sliding window for a device. */
    public int getWindowSize(String deviceId) {
        LinkedList<Double> w = windows.get(deviceId);
        return w == null ? 0 : w.size();
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    public void reset() {
        windows.clear();
        statsMap.clear();
    }

    public void clearDevice(String deviceId) {
        windows.remove(deviceId);
        statsMap.remove(deviceId);
    }

    // ── singleton ────────────────────────────────────────────────────────────

    private static final TelemetryAnomalyDetector INSTANCE = new TelemetryAnomalyDetector();

    public static TelemetryAnomalyDetector getInstance() { return INSTANCE; }
}
