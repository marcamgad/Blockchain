package com.hybrid.blockchain;

/**
 * Represents a physical action that is queued until a block reaches finality.
 */
public class DeferredAction {
    private final String blockHash;
    private final long deviceId;
    private final long value;
    private final long timestamp;

    public DeferredAction(String blockHash, long deviceId, long value) {
        this.blockHash = blockHash;
        this.deviceId = deviceId;
        this.value = value;
        this.timestamp = System.currentTimeMillis();
    }

    public String getBlockHash() {
        return blockHash;
    }

    public long getDeviceId() {
        return deviceId;
    }

    public long getValue() {
        return value;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "DeferredAction{" +
                "blockHash='" + blockHash + '\'' +
                ", deviceId=" + deviceId +
                ", value=" + value +
                '}';
    }
}
