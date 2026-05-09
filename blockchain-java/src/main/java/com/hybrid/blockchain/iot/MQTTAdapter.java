package com.hybrid.blockchain.iot;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.Transaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * PAPER-IMPL: P1-B — Maftei et al. Sensors 2025
 * MQTT Adapter for IoT telemetry batching.
 */
public class MQTTAdapter {
    private static final Logger log = LoggerFactory.getLogger(MQTTAdapter.class);
    private final Blockchain blockchain;
    private final byte[] privateKey;
    private final String address;
    private MqttClient client;
    
    private final List<Map<String, Object>> buffer = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ObjectMapper msgpackMapper = new ObjectMapper(new MessagePackFactory());

    public MQTTAdapter(Blockchain blockchain, byte[] privateKey, String brokerUrl) throws MqttException {
        this.blockchain = blockchain;
        this.privateKey = privateKey;
        this.address = Crypto.deriveAddress(Crypto.derivePublicKey(new java.math.BigInteger(1, privateKey)));
        
        this.client = new MqttClient(brokerUrl, MqttClient.generateClientId());
        this.client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.warn("[MQTT] Connection lost: {}", cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> data = mapper.readValue(message.getPayload(), Map.class);
                    buffer.add(data);
                    if (buffer.size() >= 50) {
                        flush();
                    }
                } catch (Exception e) {
                    log.warn("[MQTT] Failed to process message: {}", e.getMessage());
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        this.client.connect();
        this.client.subscribe("telemetry/#");
        
        scheduler.scheduleAtFixedRate(this::flush, 10, 10, TimeUnit.SECONDS);
    }

    private synchronized void flush() {
        if (buffer.isEmpty()) return;
        
        List<Map<String, Object>> batch = new ArrayList<>(buffer);
        buffer.clear();
        
        try {
            byte[] packedData = msgpackMapper.writeValueAsBytes(batch);
            
            Transaction tx = new Transaction.Builder()
                    .type(Transaction.Type.TELEMETRY_BATCH)
                    .from(address)
                    .data(packedData)
                    .fee(10)
                    .nonce(blockchain.getNonce(address) + 1)
                    .build();
            
            tx.sign(new java.math.BigInteger(1, privateKey));
            blockchain.addTransaction(tx);
            log.info("[MQTT] Flushed batch of {} readings ({} bytes)", batch.size(), packedData.length);
        } catch (Exception e) {
            log.error("[MQTT] Failed to flush batch: {}", e.getMessage());
        }
    }

    public void stop() throws MqttException {
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
        scheduler.shutdown();
    }
}
