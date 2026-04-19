package com.hybrid.blockchain.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Federated Learning aggregation manager for HybridChain nodes.
 *
 * <p><b>Algorithm:</b> FedAvg (Federated Averaging).
 * <p><b>Time Complexity:</b> O(N * D) where N is number of contributing nodes and D is model dimension.
 * <p><b>IoT Rationale:</b> Enables global model training without raw data leaving resource-constrained 
 * IoT devices, preserving privacy and reducing bandwidth consumption.</p>
 *
 * <p><b>Protocol:</b></p>
 * <ol>
 *   <li>Each node submits its local model weight update via
 *       {@link #submitUpdate(String, double[])}.</li>
 *   <li>The current PBFT leader calls {@link #aggregate(String)} to perform
 *       <em>FedAvg</em> (uniform-weight average across all submitted arrays).</li>
 *   <li>The aggregated model hash is stored on-chain as a
 *       {@code FEDERATED_COMMIT} transaction payload.</li>
 * </ol>
 *
 * <p>No external ML libraries are used — all math is plain {@code double[]} arithmetic.</p>
 */
public class FederatedLearningManager {

    private static final Logger log = LoggerFactory.getLogger(FederatedLearningManager.class);

    /** Pending per-node model weight updates for the current round. */
    private final Map<String, double[]> pendingUpdates = new ConcurrentHashMap<>();

    /** Latest aggregated model (result of last FedAvg). */
    private volatile double[] currentModel = new double[0];

    /** SHA-256 hex digest of {@link #currentModel}. */
    private volatile String currentModelHash = "0".repeat(64);

    private volatile long lastAggregatedTimestamp = 0;
    private volatile int  roundNumber             = 0;

    // [FIX A7]
    public synchronized void resetForTesting() {
        pendingUpdates.clear();
        roundNumber = 0;
        currentModel = new double[0];
        currentModelHash = "0".repeat(64);
    }

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * Submit a local model weight update from {@code nodeId}.
     * Replaces any previous submission from the same node in this round.
     *
     * @param nodeId  the submitting node's address / identifier
     * @param weights local gradient / weight array to aggregate
     */
    public synchronized void submitUpdate(String nodeId, double[] weights) {
        if (weights == null || weights.length == 0)
            throw new IllegalArgumentException("Weight array must be non-empty");
        pendingUpdates.put(nodeId, Arrays.copyOf(weights, weights.length));
        log.info("[FedLearn] Accepted update from {} ({} weights, round {})",
                nodeId, weights.length, roundNumber + 1);
    }

    /**
     * Perform FedAvg across all pending updates. Called by the PBFT leader.
     *
     * <p>Arrays of differing lengths are silently excluded; only arrays matching
     * the most-common dimension participate.</p>
     *
     * @param leaderId the current leader's identifier (for logging)
     * @return aggregation result, or {@code null} if no updates are pending
     */
    public synchronized AggregationResult aggregate(String leaderId, com.hybrid.blockchain.Storage storage) {
        if (pendingUpdates.isEmpty()) {
            log.debug("[FedLearn] aggregate() called by {} but no pending updates", leaderId);
            return null;
        }

        // ── find most-common vector dimension ──
        Map<Integer, Integer> dimFreq = new HashMap<>();
        for (double[] arr : pendingUpdates.values())
            dimFreq.merge(arr.length, 1, Integer::sum);

        int targetDim = dimFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);

        if (targetDim == 0) return null;

        // [FEATURE B5] Byzantine-robust aggregation (filtering out L2 > 2.0)
        double[] aggregated = new double[targetDim];
        double[] first = pendingUpdates.values().iterator().next();
        int dim = first.length;
        double[] sum = new double[dim];
        int count = 0;

        // Track the accepted node ids for the audit log
        List<String> acceptedNodes = new ArrayList<>();

        for (Map.Entry<String, double[]> entry : pendingUpdates.entrySet()) {
            double[] update = entry.getValue();
            if (update.length != dim) {
                log.warn("[FedLearn] Rejecting update from {} due to dimension mismatch ({} != {})", entry.getKey(), update.length, dim);
                continue;
            }
            
            // Byzantine Robustness: check L2 distance from currentModel
            if (currentModel != null && currentModel.length == dim && roundNumber >= 1) {
                double dist = 0;
                for (int i = 0; i < dim; i++) {
                    dist += Math.pow(update[i] - currentModel[i], 2);
                }
                dist = Math.sqrt(dist);
                if (dist > 3.0) { // Threshold for outlier rejection (Byzantine tolerance)
                    String audit = String.format("REJECT BYZANTINE node=%s dist=%.2f", entry.getKey(), dist);
                    log.warn("[FedLearn] {}", audit);
                    if (storage != null) {
                        try { storage.put("fedlearn:audit:byzantine:" + roundNumber + ":" + entry.getKey(), audit); } catch (Exception e) {}
                    }
                    continue;
                }
            }

            for (int i = 0; i < dim; i++) {
                sum[i] += update[i];
            }
            count++;
            acceptedNodes.add(entry.getKey());
        }
        if (count == 0) return null;

        for (int i = 0; i < targetDim; i++) aggregated[i] = sum[i] / count;

        this.currentModel            = aggregated;
        this.currentModelHash        = computeModelHash(aggregated);
        this.lastAggregatedTimestamp = System.currentTimeMillis();
        this.roundNumber++;

        int finalCount = count;
        pendingUpdates.clear();

        // Write Audit Log to storage
        if (storage != null) {
            try {
                String auditLog = String.format("{\"round\":%d,\"contributors\":%d,\"acceptedNodes\":%s,\"hash\":\"%s\"}", 
                        roundNumber, finalCount, "[\"" + String.join("\",\"", acceptedNodes) + "\"]", currentModelHash);
                storage.put("federated:audit:round:" + roundNumber, auditLog);
            } catch (Exception e) {
                log.error("[FedLearn] Failed to write audit log to storage", e);
            }
        }

        log.info("[FedLearn] Round {} aggregated by leader {} from {} contributors — hash={}",
                roundNumber, leaderId, finalCount, currentModelHash.substring(0, 16) + "…");

        return new AggregationResult(currentModel, currentModelHash, roundNumber, finalCount);
    }

    // ── accessors ────────────────────────────────────────────────────────────

    /** @return a defensive copy of the current aggregated model. */
    public double[] getCurrentModel() {
        return currentModel.length == 0 ? new double[0]
                                        : Arrays.copyOf(currentModel, currentModel.length);
    }

    public String  getCurrentModelHash()        { return currentModelHash; }
    public long    getLastAggregatedTimestamp() { return lastAggregatedTimestamp; }
    public int     getRoundNumber()             { return roundNumber; }
    public int     getPendingUpdateCount()      { return pendingUpdates.size(); }

    // ── helpers ──────────────────────────────────────────────────────────────

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
            // Fallback: Java array hash
            return String.format("%08x%08x%08x%08x",
                    Arrays.hashCode(model), model.length,
                    Double.doubleToLongBits(model[0]),
                    Double.doubleToLongBits(model[model.length - 1]));
        }
    }

    // ── result DTO ──────────────────────────────────────────────────────────

    public static class AggregationResult {
        public final double[] model;
        public final String   modelHash;
        public final int      round;
        public final int      contributors;

        AggregationResult(double[] model, String modelHash, int round, int contributors) {
            this.model        = Arrays.copyOf(model, model.length);
            this.modelHash    = modelHash;
            this.round        = round;
            this.contributors = contributors;
        }
    }

    // ── singleton ────────────────────────────────────────────────────────────

    private static final FederatedLearningManager INSTANCE = new FederatedLearningManager();

    public static FederatedLearningManager getInstance() { return INSTANCE; }
}
