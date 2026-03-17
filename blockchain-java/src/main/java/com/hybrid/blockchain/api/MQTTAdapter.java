package com.hybrid.blockchain.api;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.Transaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * MQTT Adapter for IoT devices.
 * Bridges MQTT topics to Blockchain transactions.
 */
public class MQTTAdapter {
    private static final Logger log = LoggerFactory.getLogger(MQTTAdapter.class);
    private final Blockchain blockchain;
    private MqttClient client;
    private final String brokerUrl;
    private final String clientId;

    public MQTTAdapter(Blockchain blockchain) {
        this.blockchain = blockchain;
        this.brokerUrl = System.getenv().getOrDefault("MQTT_BROKER_URL", "tcp://localhost:1883");
        this.clientId = "BlockchainNode_" + Config.NODE_ID;
    }

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
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                handleMessage(topic, new String(message.getPayload()));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        try {
            client.connect(options);
            // Subscribe to management and telemetry topics
            // Topics: blockchain/iot/<deviceId>/mgmt, blockchain/iot/<deviceId>/telemetry
            client.subscribe("blockchain/iot/+/mgmt");
            client.subscribe("blockchain/iot/+/telemetry");

            log.info("MQTT Adapter started, connected to broker: {}", brokerUrl);
        } catch (MqttException e) {
            log.warn("MQTT Adapter failed to connect to broker at {}. Operating without MQTT: {}", brokerUrl, e.getMessage());
        }
    }

    private void handleMessage(String topic, String payload) {
        try {
            log.info("Received MQTT message on topic: {}", topic);
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = mapper.readValue(payload, Map.class);
            
            if (topic.endsWith("/mgmt")) {
                submitIOTTransaction(data);
            } else if (topic.endsWith("/telemetry")) {
                submitTelemetryTransaction(data);
            }
        } catch (Exception e) {
            log.error("Error handling MQTT message: {}", e.getMessage());
        }
    }

    private void submitIOTTransaction(Map<String, Object> data) throws Exception {
        // In a real implementation, we would construct a Transaction object
        // from the MQTT payload and add it to the blockchain.
        log.info("Processing IoT Management command from MQTT: {}", data.get("action"));
        
        //Construction of IOT_MANAGEMENT transaction would go here
    }

    private void submitTelemetryTransaction(Map<String, Object> data) throws Exception {
        // Logging telemetry to blockchain
        log.info("Processing Telemetry data from MQTT for device: {}", data.get("deviceId"));
    }

    public void stop() throws MqttException {
        if (client != null && client.isConnected()) {
            client.disconnect();
            log.info("MQTT Adapter stopped");
        }
    }
}
