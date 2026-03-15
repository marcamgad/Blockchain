package com.hybrid.blockchain.p2p;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Handles message propagation logic, duplicate suppression, and peer selection.
 */
public class GossipEngine {

    private final PeerManager peerManager;
    private final Map<String, Long> seenMessages = new LinkedHashMap<String, Long>(1000, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 5000; // Cache last 5000 message IDs
        }
    };

    private final Map<P2PMessage.Type, Consumer<P2PMessage>> handlers = new ConcurrentHashMap<>();
    private final int fanout;

    public GossipEngine(PeerManager peerManager, int fanout) {
        this.peerManager = peerManager;
        this.fanout = fanout;
    }

    public void registerHandler(P2PMessage.Type type, Consumer<P2PMessage> handler) {
        handlers.put(type, handler);
    }

    /**
     * Entry point for incoming messages from peers.
     */
    public boolean onMessageReceived(P2PMessage message, String fromPeerId) {
        boolean accepted = validateAndProcess(message);
        if (accepted) {
            relay(message, fromPeerId);
        }
        return accepted;
    }

    public boolean validateAndProcess(P2PMessage message) {
        if (message.getPayload() == null || message.getPayload().length > 1024 * 1024) return false;
        
        String msgId = message.getMessageId();
        synchronized (seenMessages) {
            if (seenMessages.containsKey(msgId)) {
                return false;
            }
            seenMessages.put(msgId, System.currentTimeMillis());
        }

        // Trigger local handler
        Consumer<P2PMessage> handler = handlers.get(message.getType());
        if (handler != null) {
            handler.accept(message);
        }

        return true;
    }

    /**
     * Relays a message to a random subset of peers.
     */
    public void relay(P2PMessage message, String excludePeerId) {
        List<PeerManager.PeerInfo> targets = peerManager.selectGossipPeers(fanout, excludePeerId);
        for (PeerManager.PeerInfo peer : targets) {
            // This would normally call PeerNode.sendMessage(peer, message)
            // We'll use a functional approach or event bus to keep GossipEngine decoupled
            dispatchRelay(peer, message);
        }
    }

    // Hook for PeerNode to perform actual socket write
    private RelayDispatcher dispatcher;
    public void setRelayDispatcher(RelayDispatcher dispatcher) { this.dispatcher = dispatcher; }

    public interface RelayDispatcher {
        void send(PeerManager.PeerInfo target, P2PMessage message);
    }

    private void dispatchRelay(PeerManager.PeerInfo target, P2PMessage message) {
        if (dispatcher != null) {
            dispatcher.send(target, message);
        }
    }
}
