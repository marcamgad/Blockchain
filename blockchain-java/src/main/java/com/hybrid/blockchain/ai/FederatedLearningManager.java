package com.hybrid.blockchain.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Federated Learning aggregation manager for HybridChain nodes.
 */
public class FederatedLearningManager {

    private static final Logger log = LoggerFactory.getLogger(FederatedLearningManager.class);

    private final Map<String, double[]> pendingUpdates = new ConcurrentHashMap<>();
    private volatile double[] currentModel = new double[0];
    private volatile String currentModelHash = "0".repeat(64);
    private volatile long lastAggregatedTimestamp = 0;
    private volatile int roundNumber = 0;
    private boolean differentialPrivacyEnabled = false;
    private double epsilon = 1.0;

    public void setDifferentialPrivacyEnabled(boolean enabled) { this.differentialPrivacyEnabled = enabled; }
    public void setEpsilon(double epsilon) { this.epsilon = epsilon; }

    public synchronized void resetForTesting() {
        pendingUpdates.clear();
        roundNumber = 0;
        currentModel = new double[0];
        currentModelHash = "0".repeat(64);
        differentialPrivacyEnabled = false;
        epsilon = 1.0;
    }

    public synchronized void reset() {
        resetForTesting();
    }

    public synchronized void submitUpdate(String nodeId, double[] weights) {
        if (weights == null || weights.length == 0)
            throw new IllegalArgumentException("Weight array must be non-empty");
        pendingUpdates.put(nodeId, Arrays.copyOf(weights, weights.length));
        log.info("[FedLearn] Accepted update from {} ({} weights, round {})",
                nodeId, weights.length, roundNumber + 1);
    }

    /** Overload for compatibility with double[] returning tests. */
    public synchronized double[] aggregate() {
        AggregationResult res = aggregate("local-leader", null);
        return (res != null) ? res.model : null;
    }

    public synchronized AggregationResult aggregate(String leaderId, com.hybrid.blockchain.Storage storage) {
        if (pendingUpdates.isEmpty()) {
            log.debug("[FedLearn] aggregate() called by {} but no pending updates", leaderId);
            return null;
        }

        Map<Integer, Integer> dimFreq = new HashMap<>();
        for (double[] arr : pendingUpdates.values())
            dimFreq.merge(arr.length, 1, Integer::sum);

        int targetDim = dimFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);

        if (targetDim == 0) return null;

        double[] sum = new double[targetDim];
        int count = 0;
        List<String> acceptedNodes = new ArrayList<>();

        for (Map.Entry<String, double[]> entry : pendingUpdates.entrySet()) {
            double[] update = entry.getValue();
            if (update.length != targetDim) continue;

            if (currentModel != null && currentModel.length == targetDim && roundNumber >= 1) {
                double dist = 0;
                for (int i = 0; i < targetDim; i++) {
                    dist += Math.pow(update[i] - currentModel[i], 2);
                }
                dist = Math.sqrt(dist);
                if (dist > 3.0) {
                    log.warn("[FedLearn] REJECT BYZANTINE node={} dist=%.2f", entry.getKey(), dist);
                    continue;
                }
            }

            for (int i = 0; i < targetDim; i++) sum[i] += update[i];
            count++;
            acceptedNodes.add(entry.getKey());
        }

        if (count == 0) return null;

        double[] aggregated = new double[targetDim];
        for (int i = 0; i < targetDim; i++) aggregated[i] = sum[i] / count;

        if (differentialPrivacyEnabled) {
            Random rand = new Random();
            for (int i = 0; i < targetDim; i++) {
                // Add noise proportional to 1/epsilon
                double noise = (rand.nextGaussian() * 0.1) / epsilon;
                aggregated[i] += noise;
            }
        }

        this.currentModel            = aggregated;
        this.currentModelHash        = computeModelHash(aggregated);
        this.lastAggregatedTimestamp = System.currentTimeMillis();
        this.roundNumber++;
        pendingUpdates.clear();

        return new AggregationResult(currentModel, currentModelHash, roundNumber, count);
    }

    public double[] getCurrentModel() {
        return currentModel.length == 0 ? new double[0] : Arrays.copyOf(currentModel, currentModel.length);
    }

    public String getCurrentModelHash() { return currentModelHash; }
    public long getLastAggregatedTimestamp() { return lastAggregatedTimestamp; }
    public int getRoundNumber() { return roundNumber; }
    public int getPendingUpdateCount() { return pendingUpdates.size(); }

    private static String computeModelHash(double[] model) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(model.length * Double.BYTES);
            for (double d : model) buf.putDouble(d);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(buf.array());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Arrays.hashCode(model));
        }
    }

    public static class AggregationResult {
        public final double[] model;
        public final String modelHash;
        public final int round;
        public final int contributors;

        AggregationResult(double[] model, String modelHash, int round, int contributors) {
            this.model = Arrays.copyOf(model, model.length);
            this.modelHash = modelHash;
            this.round = round;
            this.contributors = contributors;
        }
    }

    private static final FederatedLearningManager INSTANCE = new FederatedLearningManager();
    public static FederatedLearningManager getInstance() { return INSTANCE; }
}
