package com.hybrid.blockchain.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedList;

/**
 * Predictive Threat Scorer for HybridChain consensus.
 *
 * <p><b>Algorithm:</b> Exponentially Weighted Moving Average (EWMA) sequence model.
 * <p><b>Time Complexity:</b> O(W) where W is the sliding window size (default 50).
 * <p><b>IoT Rationale:</b> Lightweight enough for consensus nodes to detect 
 * malicious validators in real-time without requiring a full training set.</p>
 *
 * <p>Uses a lightweight, pure-Java EWMA sequence model to predict Byzantine behavior 
 * based on validator reputation deltas, view numbers, and activity timing.</p>
 */
public class PredictiveThreatScorer {

    private static final Logger log = LoggerFactory.getLogger(PredictiveThreatScorer.class);
    private static final PredictiveThreatScorer INSTANCE = new PredictiveThreatScorer();

    public static final int WINDOW_SIZE = 50;
    public static final double DECAY_FACTOR = 0.88;

    public static class ValidatorActivity {
        public final double reputationDelta;
        public final long viewNumber;
        public final long timeSinceLastActivity;

        public ValidatorActivity(double reputationDelta, long viewNumber, long timeSinceLastActivity) {
            this.reputationDelta = reputationDelta;
            this.viewNumber = viewNumber;
            this.timeSinceLastActivity = timeSinceLastActivity;
        }
    }

    private final Map<String, LinkedList<ValidatorActivity>> validatorHistories = new ConcurrentHashMap<>();
    private final Map<String, Long> lastActivityTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Double> currentScores = new ConcurrentHashMap<>();

    private PredictiveThreatScorer() {
    }

    public static PredictiveThreatScorer getInstance() {
        return INSTANCE;
    }

    /**
     * Records a consensus activity for a validator.
     */
    public synchronized void recordActivity(String validatorId, double reputationDelta, long viewNumber) {
        long now = System.currentTimeMillis();
        long lastTime = lastActivityTimestamps.getOrDefault(validatorId, now);
        long timeSinceLast = now - lastTime;
        lastActivityTimestamps.put(validatorId, now);

        LinkedList<ValidatorActivity> history = validatorHistories.computeIfAbsent(validatorId,
                k -> new LinkedList<>());
        history.addFirst(new ValidatorActivity(reputationDelta, viewNumber, timeSinceLast));

        if (history.size() > WINDOW_SIZE) {
            history.removeLast();
        }

        updateScore(validatorId, history);
    }

    private void updateScore(String validatorId, LinkedList<ValidatorActivity> history) {
        double score = 0.0;
        double weight = 1.0;
        double sumWeights = 0.0;

        for (ValidatorActivity activity : history) {
            // Negative reputation delta heavily increases threat score
            // Positive reputation delta recovers (decreases) threat score
            double threatContribution = 0.0;
            if (activity.reputationDelta < 0) {
                // Large negative delta -> large threat (more aggressive penalty)
                threatContribution = Math.min(1.0, Math.abs(activity.reputationDelta) * 5.0);
            } else {
                // Positive delta -> negative threat (recovery)
                threatContribution = -activity.reputationDelta * 0.5;
            }

            // High time since last activity (missed slots) increases threat
            if (activity.timeSinceLastActivity > 30000) { // 30 seconds
                threatContribution += 0.1;
            }

            score += weight * threatContribution;
            sumWeights += weight;
            weight *= DECAY_FACTOR;
        }

        if (sumWeights > 0) {
            score = score / sumWeights; // Normalize to somewhat around [-0.5, 1.0]
        }

        // Clamp between 0.0 and 1.0
        score = Math.max(0.0, Math.min(1.0, score));
        currentScores.put(validatorId, score);
    }

    /**
     * Predicts the threat score of a validator.
     * 
     * @param validatorId The Validator ID.
     * @return threat score between 0.0 (benign) and 1.0 (Byzantine).
     */
    public double predictThreatScore(String validatorId) {
        return currentScores.getOrDefault(validatorId, 0.0);
    }

    public Map<String, Double> getAllScores() {
        return Collections.unmodifiableMap(currentScores);
    }

    // For testing
    public synchronized void reset() {
        validatorHistories.clear();
        lastActivityTimestamps.clear();
        currentScores.clear();
    }
}
