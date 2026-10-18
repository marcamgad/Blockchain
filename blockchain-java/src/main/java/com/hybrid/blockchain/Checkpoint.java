package com.hybrid.blockchain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;

/**
 * A finalized blockchain checkpoint used for fast synchronization.
 * A checkpoint is created every 1,000 blocks and requires {@code 2f+1} validator signatures
 * to be considered valid. New nodes lagging by more than 1,000 blocks can load the latest
 * checkpoint and skip replaying blocks from genesis.
 *
 * <p>The {@code stateRoot} and {@code utxoRoot} allow a joining node to verify that it has
 * loaded the correct state without replaying the full history.
 */
public class Checkpoint {

    private final int blockHeight;
    private final String blockHash;
    private final String stateRoot;
    private final String utxoRoot;
    private final long timestamp;
    private final Map<String, String> validatorSignatures; // validatorId -> hex-encoded signature

    /**
     * Constructs a Checkpoint.
     *
     * @param blockHeight         the block height at which this checkpoint was created
     * @param blockHash           the hash of the checkpoint block
     * @param stateRoot           the MPT state root at this height
     * @param utxoRoot            SHA-256 of the serialized UTXO set at this height
     * @param timestamp           the block timestamp in milliseconds
     * @param validatorSignatures map of validatorId to hex-encoded signature over the checkpoint hash
     */
    @JsonCreator
    public Checkpoint(
            @JsonProperty("blockHeight") int blockHeight,
            @JsonProperty("blockHash") String blockHash,
            @JsonProperty("stateRoot") String stateRoot,
            @JsonProperty("utxoRoot") String utxoRoot,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("validatorSignatures") Map<String, String> validatorSignatures) {
        this.blockHeight = blockHeight;
        this.blockHash = blockHash;
        this.stateRoot = stateRoot;
        this.utxoRoot = utxoRoot;
        this.timestamp = timestamp;
        this.validatorSignatures = validatorSignatures != null ? new HashMap<>(validatorSignatures) : new HashMap<>();
    }

    /** @return block height of this checkpoint */
    public int getBlockHeight() { return blockHeight; }

    /** @return hash of the checkpoint block */
    public String getBlockHash() { return blockHash; }

    /** @return state root of the account MPT at this height */
    public String getStateRoot() { return stateRoot; }

    /** @return SHA-256 of the serialized UTXO set at this height */
    public String getUtxoRoot() { return utxoRoot; }

    /** @return block timestamp in milliseconds */
    public long getTimestamp() { return timestamp; }

    /** @return map of validator signatures (validatorId → hex signature) */
    public Map<String, String> getValidatorSignatures() { return validatorSignatures; }

    /**
     * Computes the canonical hash of this checkpoint for signing.
     * Concatenates height, blockHash, stateRoot, and utxoRoot.
     *
     * @return SHA-256 hash as hex string
     */
    /**
     * [HC-069] Domain-separated, length-prefixed. It was the bare concatenation
     * height+blockHash+stateRoot+utxoRoot, signed with the same validator key as blocks and
     * votes and without any field boundaries.
     */
    public String computeCheckpointHash() {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
            out.write("HYBRIDCHAIN-CHECKPOINT-v2\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.writeInt(blockHeight);
            for (String f : new String[]{blockHash, stateRoot, utxoRoot}) {
                byte[] b = f == null ? new byte[0] : f.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.writeInt(f == null ? -1 : b.length);
                out.write(b);
            }
            out.flush();
            return Crypto.bytesToHex(Crypto.hash(bos.toByteArray()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
