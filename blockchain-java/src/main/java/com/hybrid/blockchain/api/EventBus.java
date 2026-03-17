package com.hybrid.blockchain.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-wide event bus for broadcasting blockchain events to WebSocket subscribers.
 * Topics: {@code blocks}, {@code transactions}, {@code consensus}, {@code mempool}.
 *
 * <p>Clients subscribe to one or more topics via the WebSocket handshake message.
 * The EventBus delivers JSON-serialized events to all sessions subscribed to the matching topic.
 * Session cleanup happens automatically when a WebSocket session is closed.
 */
@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** topic → set of WebSocket sessions subscribed to that topic */
    private final Map<String, Set<WebSocketSession>> subscribers = new ConcurrentHashMap<>();

    /**
     * Subscribes a WebSocket session to the given topic.
     *
     * @param topic   the topic name (e.g., "blocks", "transactions", "mempool", "consensus")
     * @param session the WebSocket session to subscribe
     */
    public void subscribe(String topic, WebSocketSession session) {
        subscribers.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("[EventBus] Session {} subscribed to '{}'", session.getId(), topic);
    }

    /**
     * Unsubscribes a WebSocket session from the given topic.
     *
     * @param topic   the topic name
     * @param session the WebSocket session to unsubscribe
     */
    public void unsubscribe(String topic, WebSocketSession session) {
        Set<WebSocketSession> sessions = subscribers.get(topic);
        if (sessions != null) {
            sessions.remove(session);
        }
    }

    /**
     * Removes a WebSocket session from all topics (called on close or timeout).
     *
     * @param session the session to remove
     */
    public void removeSession(WebSocketSession session) {
        subscribers.values().forEach(set -> set.remove(session));
        log.info("[EventBus] Session {} removed from all topics", session.getId());
    }

    /**
     * Publishes an event to all sessions subscribed to the given topic.
     * The payload is serialized to JSON using Jackson. Dead sessions are automatically removed.
     *
     * @param topic   the topic to publish to
     * @param payload the event object to serialize and broadcast
     */
    public void publish(String topic, Object payload) {
        Set<WebSocketSession> sessions = subscribers.get(topic);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("topic", topic);
            envelope.put("data", payload);
            envelope.put("timestamp", System.currentTimeMillis());
            json = MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("[EventBus] Failed to serialize event for topic '{}': {}", topic, e.getMessage());
            return;
        }

        TextMessage message = new TextMessage(json);
        List<WebSocketSession> dead = new ArrayList<>();

        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                dead.add(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                log.warn("[EventBus] Failed to send to session {}: {}", session.getId(), e.getMessage());
                dead.add(session);
            }
        }

        // Clean up dead sessions
        dead.forEach(s -> {
            sessions.remove(s);
            log.debug("[EventBus] Removed dead session {}", s.getId());
        });
    }

    /**
     * Returns the number of active subscribers for a given topic.
     *
     * @param topic the topic name
     * @return number of subscribed sessions
     */
    public int getSubscriberCount(String topic) {
        Set<WebSocketSession> sessions = subscribers.get(topic);
        return sessions != null ? sessions.size() : 0;
    }

    /**
     * Returns all active topics that have at least one subscriber.
     *
     * @return set of topic names
     */
    public Set<String> getActiveTopics() {
        Set<String> active = new HashSet<>();
        for (Map.Entry<String, Set<WebSocketSession>> entry : subscribers.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                active.add(entry.getKey());
            }
        }
        return active;
    }
}
