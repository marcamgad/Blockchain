package com.hybrid.blockchain;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * Hardware Abstraction Layer (HAL) for IoT peripherals.
 * In a real environment, this would interface with JNI/OS drivers.
 * Here it provides a deterministic mock interface for simulation.
 */
public class HardwareManager {
    private final Map<Long, Long> sensorValues = new HashMap<>();
    private final Map<Long, Long> actuatorStates = new HashMap<>();
    private final List<DeferredAction> deferredQueue = new java.util.ArrayList<>();

    public HardwareManager() {
        // Default mock hardware
        sensorValues.put(1L, 25L); // Temp Sensor 1
        sensorValues.put(2L, 60L); // Humidity Sensor 2
        actuatorStates.put(100L, 0L); // Relay 100
    }

    public long readSensor(long id) throws Exception {
        if (!sensorValues.containsKey(id)) {
            throw new Exception("Sensor ID " + id + " not found");
        }
        return sensorValues.get(id);
    }

    public void queueActuator(String blockHash, long id, long value) throws Exception {
        if (!actuatorStates.containsKey(id)) {
            throw new Exception("Actuator ID " + id + " not found");
        }
        deferredQueue.add(new DeferredAction(blockHash, id, value));
        System.out.println("[HAL] Queued deferred action for block " + blockHash + ": ID=" + id + " VAL=" + value);
    }

    public void commitDeferredActions(String blockHash) {
        System.out.println("[HAL] Attempting to commit for block: " + blockHash);
        java.util.Iterator<DeferredAction> it = deferredQueue.iterator();
        while (it.hasNext()) {
            DeferredAction action = it.next();
            System.out.println("[HAL] Checking queued action for: " + action.getBlockHash());
            if (action.getBlockHash().equals(blockHash)) {
                actuatorStates.put(action.getDeviceId(), action.getValue());
                System.out.println("[HAL] EXECUTED deferred action: " + action);
                it.remove();
            }
        }
    }

    // Direct write for emergency/non-blockchain use
    public void writeActuator(long id, long value) throws Exception {
        if (!actuatorStates.containsKey(id)) {
            throw new Exception("Actuator ID " + id + " not found");
        }
        actuatorStates.put(id, value);
    }

    // Network-wide sync of sensor states (simplified simulation)
    public void setMockSensorValue(long id, long value) {
        sensorValues.put(id, value);
    }

    public long getActuatorState(long id) {
        return actuatorStates.getOrDefault(id, -1L);
    }
}
