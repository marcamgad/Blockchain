package com.hybrid.blockchain.api;

/**
 * Common lifecycle for IoT transport adapters (MQTT, CoAP, …) that bridge device
 * messages onto the blockchain. Concrete adapters own their transport; the shared
 * ingress path (rate-limiting, payload validation, signed-transaction submission)
 * lives in {@link AbstractIoTMessageAdapter}.
 */
public interface IoTMessageAdapter {

    /** Start listening for device messages. */
    void start() throws Exception;

    /** Stop and release transport resources. Must be idempotent. */
    void stop() throws Exception;
}
