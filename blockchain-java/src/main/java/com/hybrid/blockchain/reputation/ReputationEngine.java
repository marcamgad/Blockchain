package com.hybrid.blockchain.reputation;

/**
 * Reputation Engine for HybridChain.
 * Calculates and manages device reputation scores based on blockchain activity.
 */
public class ReputationEngine {
    public static final double INITIAL_SCORE = 0.5;
    public static final double MAX_SCORE = 1.0;
    public static final double MIN_SCORE = 0.0;
    public static final double SUCCESS_INCREMENT = 0.01;
    public static final double FAILURE_DECREMENT = 0.05;

    /**
     * Calculates the new reputation score based on whether the activity was successful.
     * 
     * @param currentScore The current reputation score (0.0 - 1.0)
     * @param success Whether the device activity (e.g. telemetry submission) was successful
     * @return The updated reputation score
     */
    public static double calculateNewScore(double currentScore, boolean success) {
        if (success) {
            return Math.min(MAX_SCORE, currentScore + SUCCESS_INCREMENT);
        } else {
            return Math.max(MIN_SCORE, currentScore - FAILURE_DECREMENT);
        }
    }
    
    /**
     * Penalizes a device for inactivity.
     * 
     * @param currentScore The current reputation score
     * @param missedBlocks Number of blocks since last activity
     * @return The updated reputation score after inactivity penalty
     */
    public static double applyInactivityPenalty(double currentScore, long missedBlocks) {
        // Penalty: -0.001 per block of inactivity beyond a grace period of 100 blocks
        if (missedBlocks <= 100) return currentScore;
        double penalty = (missedBlocks - 100) * 0.001;
        return Math.max(MIN_SCORE, currentScore - penalty);
    }
}
