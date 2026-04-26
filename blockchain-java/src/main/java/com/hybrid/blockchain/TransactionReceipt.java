package com.hybrid.blockchain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable receipt produced for every transaction after it is finalized in a block.
 */
public class TransactionReceipt {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_REVERTED = "REVERTED";

    private final String txid;
    private final String blockHash;
    private final int blockHeight;
    private final String status;
    private final long gasUsed;
    private final String errorMessage;
    private final List<ContractEvent> events;
    private final long timestamp;
    private final String contractAddress;
    private final byte[] returnData;

    @JsonCreator
    public TransactionReceipt(
            @JsonProperty("txid") String txid,
            @JsonProperty("blockHash") String blockHash,
            @JsonProperty("blockHeight") int blockHeight,
            @JsonProperty("status") String status,
            @JsonProperty("gasUsed") long gasUsed,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("events") List<ContractEvent> events,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("contractAddress") String contractAddress,
            @JsonProperty("returnData") byte[] returnData) {
        this.txid = txid;
        this.blockHash = blockHash;
        this.blockHeight = blockHeight;
        this.status = status;
        this.gasUsed = gasUsed;
        this.errorMessage = errorMessage;
        this.events = events != null ? new ArrayList<>(events) : new ArrayList<>();
        this.timestamp = timestamp;
        this.contractAddress = contractAddress;
        this.returnData = returnData != null ? returnData : new byte[0];
    }

    /**
     * Compact constructor used in tests and lightweight contexts.
     *
     * @param txid            transaction ID
     * @param status          result status (SUCCESS / FAILED / REVERTED)
     * @param gasUsed         gas consumed
     * @param contractAddress address of deployed contract (may be null)
     * @param events          initial event list (may be null)
     */
    public TransactionReceipt(String txid, String status, long gasUsed,
                              String contractAddress, List<ContractEvent> events) {
        this(txid, null, 0, status, gasUsed, null,
             events, System.currentTimeMillis(), contractAddress, null);
    }

    public String getTxid() { return txid; }
    public String getBlockHash() { return blockHash; }
    public int getBlockHeight() { return blockHeight; }
    public String getStatus() { return status; }
    public long getGasUsed() { return gasUsed; }
    public String getErrorMessage() { return errorMessage; }
    public String getError() { return errorMessage; } // Alias used in some tests
    public List<ContractEvent> getEvents() { return events; }
    public long getTimestamp() { return timestamp; }
    public String getContractAddress() { return contractAddress; }
    public byte[] getReturnData() { return returnData; }

    /**
     * Adds a contract event to this receipt (used in tests that build receipts incrementally).
     *
     * @param topic the event topic / name
     * @param data  the event data payload
     */
    public void addEvent(String topic, String data) {
        events.add(new ContractEvent(this.contractAddress != null ? this.contractAddress : "test-contract", topic.hashCode(), data != null ? data.getBytes() : new byte[0], this.timestamp));
    }
}
