package com.hybrid.blockchain;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hardware Abstraction Layer (HAL) for IoT peripherals.
 * In a real environment, this would interface with JNI/OS drivers.
 * Here it provides a deterministic mock interface for simulation.
 */
public class HardwareManager {
    private static final Logger log = LoggerFactory.getLogger(HardwareManager.class);
    private final Map<Long, Long> sensorValues = new ConcurrentHashMap<>();
    private final Map<Long, Long> actuatorStates = new ConcurrentHashMap<>();
    private final List<DeferredAction> deferredQueue = Collections.synchronizedList(new ArrayList<>());

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
        log.info("[HAL] Queued deferred action for block {}: ID={} VAL={}", blockHash, id, value);
    }

    public void commitDeferredActions(String blockHash) {
        log.info("[HAL] Attempting to commit for block: {}", blockHash);
        synchronized (deferredQueue) {
            java.util.Iterator<DeferredAction> it = deferredQueue.iterator();
            while (it.hasNext()) {
                DeferredAction action = it.next();
                log.debug("[HAL] Checking queued action for: {}", action.getBlockHash());
                if (action.getBlockHash().equals(blockHash)) {
                    actuatorStates.put(action.getDeviceId(), action.getValue());
                    log.info("[HAL] EXECUTED deferred action: {}", action);
                    it.remove();
                }
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

    /**
     * HSM (Hardware Security Module) integration for key management.
     */
    public byte[] signWithHSM(String keyAlias, byte[] data) throws Exception {
        // In production, this would use PKCS#11 or JAAS to talk to HSM
        log.info("[HSM] Signing with key: {}", keyAlias);
        return Crypto.hash(data); // Mock signature
    }

    /**
     * TEE (Trusted Execution Environment) for secure computation.
     */
    public byte[] executeSecureEnclave(byte[] encryptedCode, byte[] encryptedData) throws Exception {
        // In production, this would use Intel SGX or ARM TrustZone
        log.info("[TEE] Executing secure enclave computation");
        return new byte[0]; // Mock result
    }
}
