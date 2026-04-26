package com.hybrid.blockchain.reputation;

// FIX 4: ReputationEngine is now a stateful component backed by Storage.
// Scores are persisted under "rep:<deviceId>" so they survive node restarts.
// Static helpers (calculateNewScore, applyInactivityPenalty) are preserved
// for callers that do not have a Storage reference.

import com.hybrid.blockchain.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reputation Engine for HybridChain IoT devices.
 *
 * <p>Scores are stored in the blockchain's key-value store under the key
 * {@code "rep:<deviceId>"} so they survive restarts and are consistent across
 * all nodes that share the same storage state.
 *
 * <p>All methods that write to storage are idempotent: calling them repeatedly
 * with the same arguments leaves the store in the same final state.
 */
public class ReputationEngine {

    private static final Logger log = LoggerFactory.getLogger(ReputationEngine.class);

    // ── Score constants ───────────────────────────────────────────────────────
    public static final double INITIAL_SCORE    = 0.5;
    public static final double MAX_SCORE        = 1.0;
    public static final double MIN_SCORE        = 0.0;
    public static final double SUCCESS_INCREMENT = 0.01;
    public static final double FAILURE_DECREMENT = 0.05;

    /** Storage key prefix for reputation scores. */
    public static final String REP_KEY_PREFIX = "rep:";

    // ── Stateless helpers (backward-compatible) ───────────────────────────────

    /**
     * Calculates the new reputation score based on whether the activity was successful.
     *
     * @param currentScore the current reputation score (0.0 – 1.0)
     * @param success      whether the device activity was successful
     * @return the updated reputation score, clamped to [MIN_SCORE, MAX_SCORE]
     */
    public static double calculateNewScore(double currentScore, boolean success) {
        if (success) {
            return Math.min(MAX_SCORE, currentScore + SUCCESS_INCREMENT);
        } else {
            return Math.max(MIN_SCORE, currentScore - FAILURE_DECREMENT);
        }
    }

    /**
     * Applies an inactivity penalty for blocks elapsed since last activity.
     *
     * @param currentScore the current reputation score
     * @param missedBlocks number of blocks since last activity
     * @return the updated reputation score after penalty, clamped to MIN_SCORE
     */
    public static double applyInactivityPenalty(double currentScore, long missedBlocks) {
        // Penalty: -0.001 per block of inactivity beyond a grace period of 100 blocks
        if (missedBlocks <= 100) return currentScore;
        double penalty = (missedBlocks - 100) * 0.001;
        return Math.max(MIN_SCORE, currentScore - penalty);
    }

    // ── Stateful persistence operations ──────────────────────────────────────

    /**
     * Reads the current reputation score for a device from storage.
     * Falls back to {@link #INITIAL_SCORE} if no entry exists.
     *
     * @param deviceId the device identifier
     * @param storage  the blockchain storage instance
     * @return the device's current reputation score
     */
    public static double readScore(String deviceId, Storage storage) {
        if (storage == null) return INITIAL_SCORE;
        try {
            Object val = storage.getMeta(REP_KEY_PREFIX + deviceId);
            if (val instanceof Number) return ((Number) val).doubleValue();
        } catch (Exception e) {
            log.warn("[REPUTATION] Failed to read score for {}: {}", deviceId, e.getMessage());
        }
        return INITIAL_SCORE;
    }

    /**
     * Updates and persists the reputation score for a device based on a recorded
     * activity outcome.
     *
     * <p>Sequence:
     * <ol>
     *   <li>Read current score from storage (or use INITIAL_SCORE).</li>
     *   <li>Apply success/failure delta via {@link #calculateNewScore}.</li>
     *   <li>Persist the new score under {@code "rep:<deviceId>"}.</li>
     * </ol>
     *
     * @param deviceId the device identifier
     * @param success  whether the activity was successful
     * @param storage  the blockchain storage instance (may be null for in-memory-only mode)
     * @return the new reputation score after update
     */
    public static double updateScore(String deviceId, boolean success, Storage storage) {
        double current = readScore(deviceId, storage);
        double updated = calculateNewScore(current, success);
        writeScore(deviceId, updated, storage);
        log.debug("[REPUTATION] Device {}: {:.4f} -> {:.4f} (success={})", deviceId, current, updated, success);
        return updated;
    }

    /**
     * Applies validator slashing — drops the score to {@link #MIN_SCORE} immediately
     * and persists the result.
     *
     * @param deviceId the device / validator identifier to slash
     * @param storage  the blockchain storage instance
     */
    public static void slashToMin(String deviceId, Storage storage) {
        writeScore(deviceId, MIN_SCORE, storage);
        log.warn("[REPUTATION] Device {} slashed to MIN_SCORE", deviceId);
    }

    /**
     * Writes a score value to storage under {@code "rep:<deviceId>"}.
     *
     * @param deviceId the device identifier
     * @param score    the score value to persist
     * @param storage  the blockchain storage instance (no-op if null)
     */
    public static void writeScore(String deviceId, double score, Storage storage) {
        if (storage == null) return;
        try {
            storage.putMeta(REP_KEY_PREFIX + deviceId, score);
        } catch (Exception e) {
            log.error("[REPUTATION] Failed to persist score for {}: {}", deviceId, e.getMessage());
        }
    }
}
