package com.hybrid.blockchain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;

/**
 * EIP-1559-style fee market for HybridChain.
 * The base fee adjusts by up to 12.5% per block depending on whether the block
 * was above or below the target gas (transaction count). The base fee is persisted
 * to storage under the meta key {@code "baseFee"} after each block.
 *
 * <p>All transactions must pay at least the current base fee; fees below the base fee
 * are rejected at validation time.</p>
 */
public final class FeeMarket {

    private static final Logger log = LoggerFactory.getLogger(FeeMarket.class);

    private FeeMarket() {}

    /**
     * Calculates the next base fee using the EIP-1559 adjustment formula.
     *
     * <p>If {@code blockGasUsed > targetGas}: fee increases by up to 12.5%.<br>
     * If {@code blockGasUsed < targetGas}: fee decreases by up to 12.5%.<br>
     * If {@code blockGasUsed == targetGas}: fee remains unchanged.<br>
     * The fee is never allowed to drop below 1.
     *
     * @param currentBaseFee the current base fee
     * @param blockGasUsed   number of transactions included in the last block
     * @param targetGas      target number of transactions per block
     * @return the new base fee to use for the next block
     */
    public static long calculateNextBaseFee(long currentBaseFee, int blockGasUsed, int targetGas) {
        if (currentBaseFee < 1L) {
            currentBaseFee = 1L;
        }
        if (targetGas <= 0) {
            return currentBaseFee;
        }

        long gasDelta = (long) blockGasUsed - targetGas;
        // EIP-1559: baseFee * gasDelta / targetGas / BASE_FEE_MAX_CHANGE_DENOMINATOR
        long baseFeeChange = currentBaseFee * gasDelta / targetGas / Config.BASE_FEE_MAX_CHANGE_DENOMINATOR;

        long nextFee = currentBaseFee + baseFeeChange;
        return Math.max(1L, nextFee);
    }

    /**
     * Loads the current base fee from storage. Returns {@code Config.BASE_FEE_INITIAL} if not yet persisted.
     *
     * @param storage the storage instance
     * @return current base fee
     */
    public static long getCurrentBaseFee(Storage storage) {
        if (storage == null) return Config.BASE_FEE_INITIAL;
        try {
            Object val = storage.getMeta("baseFee");
            if (val instanceof Number) {
                return ((Number) val).longValue();
            }
        } catch (IOException e) {
            log.warn("[FeeMarket] Failed to load baseFee from storage: {}", e.getMessage());
        }
        return Config.BASE_FEE_INITIAL;
    }

    /**
     * Saves the new base fee to storage.
     *
     * @param storage     the storage instance
     * @param newBaseFee  the new base fee value to persist
     */
    public static void saveBaseFee(Storage storage, long newBaseFee) {
        try {
            storage.putMeta("baseFee", newBaseFee);
        } catch (IOException e) {
            log.error("[FeeMarket] Failed to save baseFee to storage: {}", e.getMessage());
        }
    }

    // ── Feature 4: Fee Prediction (Polynomial regression) ────────────────────

    /** Rolling history of {@code [txCount, baseFee]} pairs for regression. */
    private static final java.util.ArrayDeque<long[]> FEE_HISTORY = new java.util.ArrayDeque<>();
    private static final int MAX_HISTORY = 100;

    // [FIX A6]
    public static synchronized void resetHistory() {
        FEE_HISTORY.clear();
    }

    /**
     * Record a (txCount, baseFee) data point after each block is applied.
     * Called from {@code Blockchain.applyBlockInternal()}.
     */
    public static synchronized void recordFeeDataPoint(long txCount, long baseFee) {
        if (FEE_HISTORY.size() >= MAX_HISTORY) FEE_HISTORY.pollFirst();
        FEE_HISTORY.addLast(new long[]{txCount, baseFee});
    }

    /**
     * Predict the optimal fee using OLS linear regression over the collected history.
     * Falls back to the current stored base fee if fewer than 2 data points exist.
     *
     * @param currentTxCount  number of pending transactions competing for the next block
     * @param avgBlockTimeMs  recent average block time in ms (demand pressure)
     * @param storage         storage for current base-fee fallback
     * @return predicted optimal fee (always &ge; 1)
     */
    public static long predictOptimalFee(long currentTxCount, long avgBlockTimeMs, Storage storage, int activeValidatorCount) {
        long[] flat;
        int n;
        synchronized (FeeMarket.class) {
            n = FEE_HISTORY.size();
            if (n < 3) return Math.max(1L, getCurrentBaseFee(storage)); // Need at least 3 points for deg 2
            flat = new long[n * 2];
            int idx = 0;
            for (long[] pt : FEE_HISTORY) { flat[idx++] = pt[0]; flat[idx++] = pt[1]; }
        }

        // Degree 2 Polynomial Regression: y = c0 + c1*x + c2*x^2
        // Normal equations: (X^T * X) * C = X^T * Y
        double sum1 = n, sumX = 0, sumX2 = 0, sumX3 = 0, sumX4 = 0;
        double sumY = 0, sumXY = 0, sumX2Y = 0;
        
        for (int i = 0; i < n; i++) {
            double xi = flat[i * 2], yi = flat[i * 2 + 1];
            double xi2 = xi * xi;
            sumX += xi; sumX2 += xi2; sumX3 += xi2 * xi; sumX4 += xi2 * xi2;
            sumY += yi; sumXY += xi * yi; sumX2Y += xi2 * yi;
        }

        // Direct 3x3 matrix inverse using Cramer's rule
        double d = sum1 * (sumX2 * sumX4 - sumX3 * sumX3) 
                 - sumX * (sumX * sumX4 - sumX2 * sumX3) 
                 + sumX2 * (sumX * sumX3 - sumX2 * sumX2);

        double c0 = 0, c1 = 0, c2 = 0;
        if (Math.abs(d) > 1e-10) {
            c0 = (sumY * (sumX2 * sumX4 - sumX3 * sumX3) 
                - sumX * (sumXY * sumX4 - sumX2Y * sumX3) 
                + sumX2 * (sumXY * sumX3 - sumX2Y * sumX2)) / d;
            c1 = (sum1 * (sumXY * sumX4 - sumX2Y * sumX3) 
                - sumY * (sumX * sumX4 - sumX2 * sumX3) 
                + sumX2 * (sumX * sumX2Y - sumX2 * sumXY)) / d;
            c2 = (sum1 * (sumX2 * sumX2Y - sumX3 * sumXY) 
                - sumX * (sumX * sumX2Y - sumX2 * sumXY) 
                + sumY * (sumX * sumX3 - sumX2 * sumX2)) / d;
        } else {
            // Fallback to simple average if singular
            c0 = sumY / n;
        }

        double predicted = c0 + c1 * currentTxCount + c2 * currentTxCount * currentTxCount;

        // Scarcity premium: > 15 active validators adds a 1.25x premium factor
        if (activeValidatorCount > 15) {
            predicted *= 1.25;
        }

        // Faster blocks → higher demand pressure (capped at 2×)
        if (avgBlockTimeMs > 0 && avgBlockTimeMs < Config.TARGET_BLOCK_TIME_MS) {
            double pressure = (double) Config.TARGET_BLOCK_TIME_MS / avgBlockTimeMs;
            predicted *= Math.min(pressure, 2.0);
        }

        return Math.max(1L, Math.round(predicted));
    }
}
