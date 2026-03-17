package com.hybrid.blockchain;

/**
 * Tokenomics engine for HybridChain.
 * Implements Bitcoin-style halving schedule with a maximum supply cap of 21,000,000 tokens.
 * The block reward starts at 50 and halves every 210,000 blocks, with a minimum reward of 1.
 * No new tokens are minted once the maximum supply is reached.
 */
public final class Tokenomics {

    /** Maximum token supply in smallest units. */
    public static final long MAX_SUPPLY = 21_000_000L;

    /** Initial block reward in smallest units. */
    public static final long INITIAL_REWARD = 50L;

    /** Number of blocks between each halving. */
    public static final int HALVING_INTERVAL = 210_000;

    private Tokenomics() {}

    /**
     * Returns the block reward at the given block height, applying the halving schedule.
     * The reward is halved every {@code HALVING_INTERVAL} blocks, with a minimum of 1.
     * Returns 0 if the total minted supply has reached {@code MAX_SUPPLY}.
     *
     * @param blockHeight the current block height (1-indexed for new block being created)
     * @param totalMinted the total number of tokens already minted across all blocks
     * @return the block reward, or 0 if maxSupply has been reached
     */
    public static long getCurrentReward(long blockHeight, long totalMinted) {
        if (totalMinted >= MAX_SUPPLY) {
            return 0L;
        }
        long halvings = blockHeight / HALVING_INTERVAL;
        long reward = INITIAL_REWARD >> halvings;
        if (reward < 1L) {
            reward = 1L;
        }
        // Do not mint past MAX_SUPPLY
        long remaining = MAX_SUPPLY - totalMinted;
        return Math.min(reward, remaining);
    }

    /**
     * Convenience overload using only block height (does not enforce supply cap against chain state).
     * Suitable for computing the theoretical reward schedule.
     *
     * @param blockHeight the block height
     * @return the theoretical reward based purely on the halving schedule
     */
    public static long getCurrentReward(long blockHeight) {
        long halvings = blockHeight / HALVING_INTERVAL;
        long reward = INITIAL_REWARD >> halvings;
        return Math.max(reward, 1L);
    }

    /**
     * Returns the total number of tokens minted so far.
     * Uses the O(1) cached counter from the blockchain.
     *
     * @param chain the blockchain instance
     * @return total minted token supply
     */
    public static long getTotalMinted(Blockchain chain) {
        return chain.getTotalMinted();
    }

    /**
     * Returns the remaining token supply that can still be minted.
     *
     * @param chain the blockchain instance
     * @return remaining supply = MAX_SUPPLY - totalMinted
     */
    public static long getRemainingSupply(Blockchain chain) {
        return Math.max(0L, MAX_SUPPLY - chain.getTotalMinted());
    }

    /**
     * Returns the block height at which the next halving will occur.
     *
     * @param blockHeight the current block height
     * @return the block height of the next halving event
     */
    public static long getNextHalvingBlock(long blockHeight) {
        long halvingsDone = blockHeight / HALVING_INTERVAL;
        return (halvingsDone + 1) * HALVING_INTERVAL;
    }

    /**
     * Returns the number of halvings that have already been completed at the given block height.
     *
     * @param blockHeight the current block height
     * @return number of halvings completed
     */
    public static long getHalvingsCompleted(long blockHeight) {
        return blockHeight / HALVING_INTERVAL;
    }
}
