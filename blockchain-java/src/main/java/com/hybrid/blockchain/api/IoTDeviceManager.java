package com.hybrid.blockchain.api;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class IoTDeviceManager {

    private final Map<String, byte[]> devices = new ConcurrentHashMap<>();

    public void registerDevice(String deviceId, byte[] publicKey) {
        devices.put(deviceId, publicKey);
    }

    public boolean exists(String deviceId) {
        return devices.containsKey(deviceId);
    }

    public byte[] getPublicKey(String deviceId) {
        return devices.get(deviceId);
    }

    public void removeDevice(String deviceId) {
        devices.remove(deviceId);
    }
}
