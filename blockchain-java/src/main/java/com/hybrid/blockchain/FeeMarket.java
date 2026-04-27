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

    public FeeMarket() {}

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
    public long calculateNextBaseFee(long currentBaseFee, int blockGasUsed, int targetGas) {
        if (currentBaseFee < 1L) {
            currentBaseFee = 1L;
        }
        if (targetGas <= 0) {
            return currentBaseFee;
        }

        long gasDelta = (long) blockGasUsed - targetGas;
        // EIP-1559: baseFee * gasDelta / targetGas / BASE_FEE_MAX_CHANGE_DENOMINATOR
        long baseFeeChange = currentBaseFee * gasDelta / targetGas / Config.BASE_FEE_MAX_CHANGE_DENOMINATOR;
        
        // Ensure minimum change of 1 if there is any delta (to avoid getting stuck at 1 due to integer division)
        if (baseFeeChange == 0 && gasDelta != 0) {
            baseFeeChange = (gasDelta > 0) ? 1 : -1;
        }

        long nextFee = currentBaseFee + baseFeeChange;
        log.debug("[FEEMARKET] current={} gasUsed={} target={} change={} next={}",
            currentBaseFee, blockGasUsed, targetGas, baseFeeChange, nextFee);
        return Math.max(0L, nextFee);
    }

    public long calculateNextBaseFee(long currentBaseFee, long blockGasUsed) {
        return calculateNextBaseFee(currentBaseFee, (int)blockGasUsed, Config.TARGET_GAS_PER_BLOCK);
    }

    /**
     * Loads the current base fee from storage. Returns {@code Config.BASE_FEE_INITIAL} if not yet persisted.
     *
     * @param storage the storage instance
     * @return current base fee
     */
    public long getCurrentBaseFee(Storage storage) {
        if (storage == null) return Config.BASE_FEE_INITIAL;
        try {
            Object val = storage.getMeta("baseFee");
            if (val != null) {
                log.debug("[FEEMARKET] LOAD baseFee={} type={}", val, val.getClass().getName());
            }
            if (val instanceof Number) {
                return ((Number) val).longValue();
            }
        } catch (Exception e) {
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
    public void saveBaseFee(Storage storage, long newBaseFee) {
        try {
            log.debug("[FEEMARKET] SAVE baseFee={}", newBaseFee);
            storage.putMeta("baseFee", newBaseFee);
        } catch (Exception e) {
            log.error("[FeeMarket] Failed to save baseFee to storage: {}", e.getMessage());
        }
    }

    /**
     * Convenience overload with reversed arguments — matches test call convention:
     * {@code feeMarket.saveBaseFee(250L, storage)}.
     *
     * @param newBaseFee  the new base fee value to persist
     * @param storage     the storage instance
     */
    public void saveBaseFee(long newBaseFee, Storage storage) {
        saveBaseFee(storage, newBaseFee);
    }

    // ── Feature 4: Fee Prediction (Polynomial regression) ────────────────────

    /** Rolling history of {@code [txCount, baseFee]} pairs for regression. */
    private final java.util.ArrayDeque<long[]> FEE_HISTORY = new java.util.ArrayDeque<>();
    private final int MAX_HISTORY = 100;

    // [FIX A6]
    public synchronized void resetHistory() {
        FEE_HISTORY.clear();
    }

    public synchronized void resetHistory(Storage storage) {
        resetHistory();
    }

    /**
     * Record a (txCount, baseFee) data point after each block is applied.
     * Called from {@code Blockchain.applyBlockInternal()}.
     */
    public synchronized void recordFeeDataPoint(long txCount, long baseFee) {
        if (FEE_HISTORY.size() >= MAX_HISTORY) FEE_HISTORY.pollFirst();
        FEE_HISTORY.addLast(new long[]{txCount, baseFee});
    }

    public synchronized void recordFeeDataPoint(long txCount, long baseFee, Storage storage) {
        recordFeeDataPoint(txCount, baseFee);
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
    public long predictOptimalFee(long currentTxCount, long avgBlockTimeMs, Storage storage, int activeValidatorCount) {
        long[] flat;
        int n;
        synchronized (this) {
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

        return Math.max(0L, Math.round(predicted));
    }
}
