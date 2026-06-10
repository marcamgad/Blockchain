package com.hybrid.blockchain.iot;

import com.hybrid.blockchain.Crypto;
import java.util.Arrays;

/**
 * IoT State Channel for off-chain high-frequency telemetry.
 *
 * <p>Allows a device and a gateway (or validator) to exchange telemetry readings
 * off-chain, only settling the final state on-chain. This reduces congestion.
 *
 * @novel P2-B — Off-chain scalability for IoT data
 */
public class IoTStateChannel {
    private final String channelId;
    private final String deviceId;
    private final String gatewayId;
    private long balance; // Representing remaining bandwidth or data quota
    private long sequenceNumber;
    private boolean closed;
    private byte[] lastCommitment;

    public IoTStateChannel(String channelId, String deviceId, String gatewayId, long initialBalance) {
        this.channelId = channelId;
        this.deviceId = deviceId;
        this.gatewayId = gatewayId;
        this.balance = initialBalance;
        this.sequenceNumber = 0;
        this.closed = false;
    }

    /**
     * Updates the channel state with a multi-signed commitment.
     */
    public synchronized void updateState(long newBalance, long newSequence, byte[] commitment, byte[] deviceSig, byte[] gatewaySig) {
        if (closed) throw new IllegalStateException("Channel already closed");
        if (newSequence <= sequenceNumber) throw new IllegalArgumentException("Sequence must be increasing");

        // PAPER-IMPL: P2-B — verify multi-signature for off-chain settlement
        // In a full implementation, we'd verify deviceSig and gatewaySig against their public keys
        
        this.balance = newBalance;
        this.sequenceNumber = newSequence;
        this.lastCommitment = commitment;
    }

    /**
     * Closes the channel for final settlement.
     */
    public synchronized void close() {
        this.closed = true;
    }

    public String getChannelId() { return channelId; }
    public String getDeviceId() { return deviceId; }
    public String getGatewayId() { return gatewayId; }
    public long getBalance() { return balance; }
    public long getSequenceNumber() { return sequenceNumber; }
    public boolean isClosed() { return closed; }
    public byte[] getLastCommitment() { return lastCommitment; }
}
