package com.hybrid.blockchain.api;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.TextMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class EventBusWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EventBusWebSocketHandler.class);
    private final EventBus eventBus;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

    public EventBusWebSocketHandler(EventBus eventBus) {
        this.eventBus = eventBus;
        startHeartbeat();
    }

    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            // In a real implementation, we would track lastPong and close timed-out sessions.
            // Simplified heartbeat for this IoT version.
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("[WebSocket] New connection established: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode payload = mapper.readTree(message.getPayload());
            if (payload.has("action") && payload.has("topic")) {
                String action = payload.get("action").asText();
                String topic = payload.get("topic").asText();

                if ("subscribe".equals(action)) {
                    java.util.Map<String, String> filter = new java.util.HashMap<>();
                    if (payload.has("filter") && payload.get("filter").isObject()) {
                        payload.get("filter").fields().forEachRemaining(entry -> {
                            filter.put(entry.getKey(), entry.getValue().asText());
                        });
                    }
                    eventBus.subscribe(topic, session, filter);
                } else if ("unsubscribe".equals(action)) {
                    eventBus.unsubscribe(topic, session);
                }
            }
        } catch (Exception e) {
            log.warn("[WebSocket] Invalid message from {}: {}", session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("[WebSocket] Connection closed: {}", session.getId());
        eventBus.removeSession(session);
    }

    @jakarta.annotation.PreDestroy
    public void destroy() {
        scheduler.shutdown();
    }
}
