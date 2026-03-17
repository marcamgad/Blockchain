package com.hybrid.blockchain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents an event emitted by a smart contract during execution.
 * Events are captured by the {@code LOG} opcode and included in the
 * {@link TransactionReceipt} for the transaction that executed the contract.
 */
public class ContractEvent {

    private final String contractAddress;
    private final long topic;
    private final byte[] data;
    private final long timestamp;

    /**
     * Constructs a ContractEvent.
     *
     * @param contractAddress the address of the contract that emitted this event
     * @param topic           the event topic identifier (numerical)
     * @param data            the raw event data bytes
     * @param timestamp       the block timestamp when this event was emitted
     */
    @JsonCreator
    public ContractEvent(
            @JsonProperty("contractAddress") String contractAddress,
            @JsonProperty("topic") long topic,
            @JsonProperty("data") byte[] data,
            @JsonProperty("timestamp") long timestamp) {
        this.contractAddress = contractAddress;
        this.topic = topic;
        this.data = data == null ? new byte[0] : data;
        this.timestamp = timestamp;
    }

    /**
     * Returns the address of the contract that emitted this event.
     *
     * @return contract address
     */
    public String getContractAddress() {
        return contractAddress;
    }

    /**
     * Returns the event topic identifier.
     *
     * @return topic as a long
     */
    public long getTopic() {
        return topic;
    }

    /**
     * Returns the raw event data.
     *
     * @return event data bytes
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Returns the timestamp when this event was emitted.
     *
     * @return block timestamp in milliseconds
     */
    public long getTimestamp() {
        return timestamp;
    }
}
