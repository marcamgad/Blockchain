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

    /** session → Map of filters (e.g., "address", "eventName") */
    private final Map<WebSocketSession, Map<String, Map<String, String>>> sessionFilters = new ConcurrentHashMap<>();

    /**
     * [HC-063] Only these topics exist. Any client used to be able to create an unbounded number
     * of topic entries (never removed), and a session had a single filter shared by all its
     * subscriptions, so subscribing to a second topic silently replaced the first filter.
     */
    public static final Set<String> TOPICS = Set.of("blocks", "transactions", "mempool", "contracts", "anomalies", "consensus");
    static final int MAX_FILTER_KEYS = 8;

    /**
     * Subscribes a WebSocket session to the given topic.
     *
     * @param topic   the topic name (e.g., "blocks", "transactions", "mempool", "consensus")
     * @param session the WebSocket session to subscribe
     */
    public void subscribe(String topic, WebSocketSession session) {
        subscribe(topic, session, Collections.emptyMap());
    }

    /**
     * Subscribes a WebSocket session to the given topic with optional filters.
     */
    public void subscribe(String topic, WebSocketSession session, Map<String, String> filter) {
        if (topic == null || !TOPICS.contains(topic)) {
            log.warn("[EventBus] Session {} tried to subscribe to unknown topic", session.getId());
            return;
        }
        if (filter != null && filter.size() > MAX_FILTER_KEYS) {
            log.warn("[EventBus] Session {} sent an oversized filter", session.getId());
            return;
        }
        subscribers.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(session);
        Map<String, Map<String, String>> perTopic = sessionFilters.computeIfAbsent(session, k -> new ConcurrentHashMap<>());
        if (filter != null && !filter.isEmpty()) {
            perTopic.put(topic, new HashMap<>(filter));
            log.info("[EventBus] Session {} subscribed to '{}' with filter {}", session.getId(), topic, filter);
        } else {
            perTopic.remove(topic);
            log.info("[EventBus] Session {} subscribed to '{}'", session.getId(), topic);
        }
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
        Map<String, Map<String, String>> perTopic = sessionFilters.get(session);
        if (perTopic != null) perTopic.remove(topic);
    }

    /**
     * Removes a WebSocket session from all topics (called on close or timeout).
     *
     * @param session the session to remove
     */
    public void removeSession(WebSocketSession session) {
        subscribers.values().forEach(set -> set.remove(session));
        sessionFilters.remove(session);
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

            // Apply filters if present
            Map<String, Map<String, String>> perTopic = sessionFilters.get(session);
            Map<String, String> filter = perTopic == null ? null : perTopic.get(topic);
            if (filter != null) {
                if (!matchesFilter(payload, filter)) {
                    continue;
                }
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
     * Dynamic filter matching. Checks if payload contains fields matching the filter.
     */
    private boolean matchesFilter(Object payload, Map<String, String> filter) {
        if (filter == null || filter.isEmpty()) return true;
        try {
            // Convert payload to map for easier filtering
            Map<String, Object> payloadMap;
            if (payload instanceof Map) {
                payloadMap = (Map<String, Object>) payload;
            } else {
                payloadMap = MAPPER.convertValue(payload, Map.class);
            }

            for (Map.Entry<String, String> f : filter.entrySet()) {
                Object val = payloadMap.get(f.getKey());
                if (val == null || !val.toString().equalsIgnoreCase(f.getValue())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false; // [HC-063] fail closed: an unfilterable event is not sent to a filtered subscriber
        }
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
