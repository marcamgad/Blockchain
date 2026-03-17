package com.hybrid.blockchain;

/**
 * Exception thrown during pre-validation of a block received from a peer.
 * Carries a specific message indicating which validation rule was violated.
 * Used by {@link Blockchain#preValidateBlock(Block)} before {@code applyBlock()} is called.
 */
public class BlockValidationException extends Exception {

    private final String blockHash;
    private final int blockHeight;

    public BlockValidationException(String message, String hash, int height) {
        super(message);
        this.blockHash = hash;
        this.blockHeight = height;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public int getBlockHeight() {
        return blockHeight;
    }
}
