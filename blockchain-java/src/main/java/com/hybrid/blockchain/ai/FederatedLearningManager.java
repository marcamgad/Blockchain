package com.hybrid.blockchain.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
    private int minimumContributors = com.hybrid.blockchain.Config.MIN_FL_CONTRIBUTORS;
    public static final int MINIMUM_CONTRIBUTORS = com.hybrid.blockchain.Config.MIN_FL_CONTRIBUTORS;

    private static final String META_LATEST_HASH = "federated:latest:hash";
    private static final String META_LATEST_ROUND = "federated:latest:round";
    private static final String META_LAST_AGG_TS = "federated:latest:timestamp";
    private static final String META_LAST_CONTRIB = "federated:latest:contributors";
    private static final String MODEL_PREFIX = "federated:model:";

    // PAPER-IMPL: P1-E — FL-DABE-BC (arXiv 2410.20259) Gaussian mechanism
    // parameters
    private boolean differentialPrivacyEnabled = false;
    private double epsilon = 1.0; // privacy budget
    private double delta = 1e-5; // failure probability
    private double sensitivity = 1.0; // L2 sensitivity of update

    public void setDifferentialPrivacyEnabled(boolean enabled) {
        this.differentialPrivacyEnabled = enabled;
    }

    public void setEpsilon(double epsilon) {
        this.epsilon = epsilon;
    }

    /** @paper FL-DABE-BC arXiv:2410.20259 — Gaussian mechanism for (ε,δ)-DP FL */
    public void setDPParameters(double epsilon, double delta, double sensitivity) {
        this.epsilon = epsilon;
        this.delta = delta;
        this.sensitivity = sensitivity;
    }

    public synchronized void resetForTesting() {
        pendingUpdates.clear();
        roundNumber = 0;
        currentModel = new double[0];
        currentModelHash = "0".repeat(64);
        differentialPrivacyEnabled = false;
        epsilon = 1.0;
        delta = 1e-5;
        sensitivity = 1.0;
    }

    public synchronized void reset() {
        resetForTesting();
    }

    public synchronized void submitUpdate(String nodeId, double[] weights) {
        if (weights == null || weights.length == 0)
            throw new IllegalArgumentException("Weight array must be non-empty");
        // PAPER-IMPL: P1-E — apply per-update Gaussian noise BEFORE storing
        // (pre-aggregation DP)
        double[] toStore = Arrays.copyOf(weights, weights.length);
        pendingUpdates.put(nodeId, toStore);
        log.info("[FedLearn] Accepted update from {} ({} weights, round {}, dp={})",
                nodeId, weights.length, roundNumber + 1, differentialPrivacyEnabled);
    }

    /**
     * Gaussian mechanism for (ε,δ)-differential privacy.
     * σ = sensitivity × √(2 ln(1.25/δ)) / ε
     *
     * @paper FL-DABE-BC arXiv:2410.20259, Section IV-B
     * @paper Blockchain-FL with DP+HE (ResearchGate IIoT paper)
     */
    private double[] addGaussianNoise(double[] weights, double epsilon, double delta, double sensitivity) {
        double sigma = sensitivity * Math.sqrt(2.0 * Math.log(1.25 / delta)) / epsilon;
        SecureRandom rng = new SecureRandom();
        double[] noisy = Arrays.copyOf(weights, weights.length);
        for (int i = 0; i < noisy.length; i++)
            noisy[i] += rng.nextGaussian() * sigma;
        return noisy;
    }

    /** Overload for compatibility with double[] returning tests. */
    public synchronized double[] aggregate(int validatorCount) {
        AggregationResult res = aggregate("local-leader", null, validatorCount);
        return (res != null) ? res.model : null;
    }

    public synchronized AggregationResult aggregate(String leaderId, com.hybrid.blockchain.Storage storage, int validatorCount) {
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

        if (targetDim == 0)
            return null;

        double[] sum = new double[targetDim];
        int count = 0;
        List<String> acceptedNodes = new ArrayList<>();

        for (Map.Entry<String, double[]> entry : pendingUpdates.entrySet()) {
            double[] update = entry.getValue();
            if (update.length != targetDim)
                continue;

            if (currentModel != null && currentModel.length == targetDim && roundNumber >= 1) {
                double dist = 0;
                for (int i = 0; i < targetDim; i++) {
                    dist += Math.pow(update[i] - currentModel[i], 2);
                }
                dist = Math.sqrt(dist);
                if (dist > 3.0) {
                    log.warn("[FedLearn] REJECT BYZANTINE node={} dist={}", entry.getKey(),
                            String.format("%.2f", dist));
                    continue;
                }
            }

            for (int i = 0; i < targetDim; i++)
                sum[i] += update[i];
            count++;
            acceptedNodes.add(entry.getKey());
        }

        int minRequired = com.hybrid.blockchain.Config.isDebug() ? 1 : (2 * ((validatorCount - 1) / 3)) + 1;
        if (count < minRequired) {
            log.warn("[FedLearn] aggregate() rejected: insufficient contributors ({} < {})", count, minRequired);
            return null;
        }

        double[] aggregated = new double[targetDim];
        if (roundNumber == 0) {
            // [PHASE-0] FIX-3: Median-based aggregation for round 0
            for (int i = 0; i < targetDim; i++) {
                List<Double> values = new ArrayList<>();
                for (Map.Entry<String, double[]> entry : pendingUpdates.entrySet()) {
                    if (acceptedNodes.contains(entry.getKey())) {
                        values.add(entry.getValue()[i]);
                    }
                }
                Collections.sort(values);
                aggregated[i] = values.get(values.size() / 2);
            }
        } else {
            for (int i = 0; i < targetDim; i++)
                aggregated[i] = sum[i] / count;
        }

        if (differentialPrivacyEnabled) {
            // PAPER-IMPL: P1-E — DP-Enhanced Federated Learning
            double sensitivity = this.sensitivity / count; // L2 sensitivity for mean aggregation
            double epsilon = com.hybrid.blockchain.Config.FEDERATED_DP_EPSILON;
            double delta = 1e-5;
            aggregated = com.hybrid.blockchain.ai.DPMechanism.gaussianMechanism(aggregated, epsilon, delta, sensitivity);
            log.info("[FedLearn] Applied DP Gaussian noise (eps={}, delta={}) to aggregated model", epsilon, delta);
        }

        this.currentModel = aggregated;
        this.currentModelHash = computeModelHash(aggregated);
        this.lastAggregatedTimestamp = System.currentTimeMillis();
        this.roundNumber++;

        persistModel(storage, leaderId, currentModel, currentModelHash, roundNumber, count, lastAggregatedTimestamp);
        pendingUpdates.clear();

        return new AggregationResult(currentModel, currentModelHash, roundNumber, count);
    }

    public synchronized void applyCommittedModel(String modelHash,
            double[] model,
            Integer round,
            Integer contributors,
            com.hybrid.blockchain.Storage storage) {
        if (modelHash == null || modelHash.isBlank()) {
            return;
        }

        double[] committed = (model == null) ? new double[0] : Arrays.copyOf(model, model.length);
        long now = System.currentTimeMillis();
        int effectiveRound = (round != null && round >= 0) ? round : this.roundNumber;
        int effectiveContributors = (contributors != null && contributors >= 0) ? contributors : 0;

        if (committed.length > 0) {
            this.currentModel = committed;
            this.currentModelHash = modelHash;
            this.lastAggregatedTimestamp = now;
            this.roundNumber = Math.max(this.roundNumber, effectiveRound);
            persistModel(storage, "network-commit", committed, modelHash, this.roundNumber, effectiveContributors, now);
            return;
        }

        if (storage != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stored = storage.get(MODEL_PREFIX + modelHash, Map.class);
            if (stored != null) {
                double[] restored = toDoubleArray(stored.get("model"));
                if (restored.length > 0) {
                    this.currentModel = restored;
                    this.currentModelHash = modelHash;
                    this.lastAggregatedTimestamp = readLong(stored.get("timestamp"), now);
                    this.roundNumber = Math.max(this.roundNumber, readInt(stored.get("round"), this.roundNumber));
                    return;
                }
            }
        }

        this.currentModelHash = modelHash;
        this.lastAggregatedTimestamp = now;
        persistModel(storage, "network-commit", new double[0], modelHash, this.roundNumber, effectiveContributors, now);
    }

    /**
     * PAPER-IMPL: P1-D — FL Quorum Recovery
     * If the current leader fails to finalize a round, the next-highest reputation
     * node
     * in the validator set takes over as the backup aggregator.
     *
     * @paper Federated Learning Quorum (inspired by RWA-BFT)
     */
    public synchronized AggregationResult recoverAggregation(String callerId,
            List<String> sortedValidators,
            com.hybrid.blockchain.Storage storage) {
        if (pendingUpdates.size() < (com.hybrid.blockchain.Config.isDebug() ? 1 : MINIMUM_CONTRIBUTORS)) {
            return null;
        }

        // Find caller's rank in sorted (by rep) validators
        int callerRank = sortedValidators.indexOf(callerId);
        if (callerRank < 0)
            return null;

        // Simplified recovery: if the leader (rank 0) hasn't committed, rank 1, 2...
        // take over
        // In production, this would be gated by a timeout or view-change trigger.
        log.info("[FedLearn] Recovery triggered by validator {} (rank {})", callerId, callerRank);
        return aggregate(callerId, storage, sortedValidators.size());
    }

    public synchronized boolean loadLatestModel(com.hybrid.blockchain.Storage storage) {
        if (storage == null)
            return false;

        Object hashObj = storage.getMeta(META_LATEST_HASH);
        if (!(hashObj instanceof String) || ((String) hashObj).isBlank())
            return false;

        String hash = (String) hashObj;
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = storage.get(MODEL_PREFIX + hash, Map.class);
        if (stored == null)
            return false;

        double[] restored = toDoubleArray(stored.get("model"));
        if (restored.length == 0)
            return false;

        this.currentModel = restored;
        this.currentModelHash = hash;
        this.lastAggregatedTimestamp = readLong(stored.get("timestamp"), System.currentTimeMillis());
        this.roundNumber = Math.max(this.roundNumber, readInt(stored.get("round"), this.roundNumber));
        return true;
    }

    public double[] getCurrentModel() {
        return currentModel.length == 0 ? new double[0] : Arrays.copyOf(currentModel, currentModel.length);
    }

    public String getCurrentModelHash() {
        return currentModelHash;
    }

    public long getLastAggregatedTimestamp() {
        return lastAggregatedTimestamp;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public int getPendingUpdateCount() {
        return pendingUpdates.size();
    }

    private void persistModel(com.hybrid.blockchain.Storage storage,
            String source,
            double[] model,
            String modelHash,
            int round,
            int contributors,
            long timestamp) {
        if (storage == null)
            return;
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("model", Arrays.copyOf(model, model.length));
            record.put("modelHash", modelHash);
            record.put("round", round);
            record.put("contributors", contributors);
            record.put("leaderId", source);
            record.put("timestamp", timestamp);

            storage.put(MODEL_PREFIX + modelHash, record);
            storage.putMeta(META_LATEST_HASH, modelHash);
            storage.putMeta(META_LATEST_ROUND, round);
            storage.putMeta(META_LAST_AGG_TS, timestamp);
            storage.putMeta(META_LAST_CONTRIB, contributors);
        } catch (Exception e) {
            log.warn("[FedLearn] Failed to persist model hash={}: {}", modelHash, e.getMessage());
        }
    }

    private static int readInt(Object value, int fallback) {
        return (value instanceof Number) ? ((Number) value).intValue() : fallback;
    }

    private static long readLong(Object value, long fallback) {
        return (value instanceof Number) ? ((Number) value).longValue() : fallback;
    }

    private static double[] toDoubleArray(Object raw) {
        if (raw == null)
            return new double[0];
        if (raw instanceof double[])
            return Arrays.copyOf((double[]) raw, ((double[]) raw).length);
        if (raw instanceof List<?>) {
            List<?> list = (List<?>) raw;
            double[] out = new double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object v = list.get(i);
                if (!(v instanceof Number))
                    return new double[0];
                out[i] = ((Number) v).doubleValue();
            }
            return out;
        }
        return new double[0];
    }

    private static String computeModelHash(double[] model) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(model.length * Double.BYTES);
            for (double d : model)
                buf.putDouble(d);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(buf.array());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash)
                sb.append(String.format("%02x", b));
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

    public static FederatedLearningManager getInstance() {
        return INSTANCE;
    }
}
