package com.hybrid.blockchain;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of executing a transaction, including gas accounting and contract output.
 */
public class ExecutionResult {
    private final long gasUsed;
    private final String contractAddress;
    private final byte[] returnData;
    private final List<ContractEvent> events;

    public ExecutionResult(long gasUsed, String contractAddress, byte[] returnData, List<ContractEvent> events) {
        this.gasUsed = gasUsed;
        this.contractAddress = contractAddress;
        this.returnData = returnData != null ? returnData : new byte[0];
        this.events = events != null ? events : new ArrayList<>();
    }

    public long getGasUsed() { return gasUsed; }
    public String getContractAddress() { return contractAddress; }
    public byte[] getReturnData() { return returnData; }
    public List<ContractEvent> getEvents() { return events; }
}
