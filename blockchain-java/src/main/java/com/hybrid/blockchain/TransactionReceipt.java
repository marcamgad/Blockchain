package com.hybrid.blockchain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable receipt produced for every transaction after it is finalized in a block.
 * The receipt captures execution outcome, gas used, any error message, and all events
 * emitted by smart contract execution via the {@code LOG} opcode.
 *
 * <p>Status values:
 * <ul>
 *   <li>{@code SUCCESS} — transaction executed without error</li>
 *   <li>{@code FAILED}  — transaction threw an unexpected exception</li>
 *   <li>{@code REVERTED}— contract execution used the {@code REVERT} opcode</li>
 * </ul>
 */
public class TransactionReceipt {

    /** Transaction was successfully executed. */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** Transaction failed with an unexpected exception. */
    public static final String STATUS_FAILED = "FAILED";
    /** Contract explicitly reverted all state changes. */
    public static final String STATUS_REVERTED = "REVERTED";

    private final String txid;
    private final String blockHash;
    private final int blockHeight;
    private final String status;
    private final long gasUsed;
    private final String errorMessage;
    private final List<ContractEvent> events;
    private final long timestamp;

    /**
     * Constructs a TransactionReceipt.
     *
     * @param txid         the transaction ID
     * @param blockHash    the hash of the block containing this transaction
     * @param blockHeight  the height of the block containing this transaction
     * @param status       one of SUCCESS, FAILED, or REVERTED
     * @param gasUsed      gas consumed during execution
     * @param errorMessage error description (null on success)
     * @param events       contract events emitted during execution
     * @param timestamp    the block timestamp in milliseconds
     */
    @JsonCreator
    public TransactionReceipt(
            @JsonProperty("txid") String txid,
            @JsonProperty("blockHash") String blockHash,
            @JsonProperty("blockHeight") int blockHeight,
            @JsonProperty("status") String status,
            @JsonProperty("gasUsed") long gasUsed,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("events") List<ContractEvent> events,
            @JsonProperty("timestamp") long timestamp) {
        this.txid = txid;
        this.blockHash = blockHash;
        this.blockHeight = blockHeight;
        this.status = status;
        this.gasUsed = gasUsed;
        this.errorMessage = errorMessage;
        this.events = events != null ? new ArrayList<>(events) : new ArrayList<>();
        this.timestamp = timestamp;
    }

    /** @return the transaction ID */
    public String getTxid() { return txid; }

    /** @return the block hash */
    public String getBlockHash() { return blockHash; }

    /** @return the block height */
    public int getBlockHeight() { return blockHeight; }

    /** @return execution status: SUCCESS, FAILED, or REVERTED */
    public String getStatus() { return status; }

    /** @return gas units consumed */
    public long getGasUsed() { return gasUsed; }

    /** @return error message, or null if status is SUCCESS */
    public String getErrorMessage() { return errorMessage; }

    /** @return immutable list of contract events emitted */
    public List<ContractEvent> getEvents() { return events; }

    /** @return block timestamp in milliseconds */
    public long getTimestamp() { return timestamp; }
}
