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
}
