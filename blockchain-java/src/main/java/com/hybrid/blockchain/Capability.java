package com.hybrid.blockchain;

import java.util.Objects;

/**
 * Represents a specific privilege granted to a contract to access IoT hardware.
 */
public class Capability {
    public enum Type {
        READ_SENSOR,
        WRITE_ACTUATOR
    }

    private final Type type;
    private final long deviceId;

    public Capability(Type type, long deviceId) {
        this.type = type;
        this.deviceId = deviceId;
    }

    public Type getType() {
        return type;
    }

    public long getDeviceId() {
        return deviceId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Capability that = (Capability) o;
        return deviceId == that.deviceId && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, deviceId);
    }

    @Override
    public String toString() {
        return type.name() + ":" + deviceId;
    }
}
