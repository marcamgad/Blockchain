package com.hybrid.blockchain.api;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.Transaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * MQTT Adapter for IoT devices. Bridges MQTT topics to Blockchain transactions
 * through the shared {@link AbstractIoTMessageAdapter} ingress path (rate-limit,
 * validate, sign, submit).
 */
public class MQTTAdapter extends AbstractIoTMessageAdapter {
    private MqttClient client;
    private final String brokerUrl;
    private final String clientId;

    public MQTTAdapter(Blockchain blockchain) {
        super(blockchain);
        this.brokerUrl = System.getProperty("MQTT_BROKER_URL",
                System.getenv().getOrDefault("MQTT_BROKER_URL", "tcp://localhost:1883"));
        this.clientId = "BlockchainNode_" + Config.NODE_ID;
    }

    @Override
    public void start() throws MqttException {
        client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);

        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MQTT connection lost: {}", cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                handleMessage(topic, new String(message.getPayload(), StandardCharsets.UTF_8));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        try {
            client.connect(options);
            // Topics: blockchain/iot/<deviceId>/mgmt, blockchain/iot/<deviceId>/telemetry
            client.subscribe("blockchain/iot/+/mgmt");
            client.subscribe("blockchain/iot/+/telemetry");
            log.info("MQTT Adapter started, connected to broker: {}", brokerUrl);
        } catch (MqttException e) {
            log.warn("MQTT Adapter failed to connect to broker at {}. Operating without MQTT: {}",
                    brokerUrl, e.getMessage());
        }
    }

    private void handleMessage(String topic, String payload) {
        try {
            log.info("Received MQTT message on topic: {}", topic);
            ObjectMapper mapper = new ObjectMapper();
            byte[] rawPayload = payload.getBytes(StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = mapper.readValue(payload, Map.class);

            String deviceId = extractDeviceId(topic, data);
            if (topic.endsWith("/mgmt")) {
                submitDeviceTx(Transaction.Type.IOT_MANAGEMENT, deviceId, rawPayload);
            } else if (topic.endsWith("/telemetry")) {
                submitDeviceTx(Transaction.Type.TELEMETRY, deviceId, rawPayload);
            }
        } catch (Exception e) {
            // Never propagate: a bad/rate-limited/oversized message must not kill the
            // MQTT callback thread.
            log.warn("Error handling MQTT message on {}: {}", topic, e.getMessage());
        }
    }

    /** Prefer the device id embedded in the topic (blockchain/iot/&lt;deviceId&gt;/...), fall back to the payload. */
    private String extractDeviceId(String topic, Map<String, Object> data) {
        String[] parts = topic.split("/");
        if (parts.length >= 3 && !parts[2].isEmpty()) {
            return parts[2];
        }
        Object d = data.get("deviceId");
        return d != null ? String.valueOf(d) : null;
    }

    @Override
    public void stop() throws MqttException {
        if (client != null && client.isConnected()) {
            client.disconnect();
            log.info("MQTT Adapter stopped");
        }
    }
}
