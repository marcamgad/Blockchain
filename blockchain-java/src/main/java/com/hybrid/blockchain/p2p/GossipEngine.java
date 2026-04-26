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

    public GossipEngine() {
        this.peerManager = null;
        this.fanout = 3;
    }

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

    public boolean validateAndProcess(byte[] payload) {
        if (payload == null) return false;
        P2PMessage msg = new P2PMessage("SYSTEM", "dummy", P2PMessage.Type.GOSSIP, payload);
        return validateAndProcess(msg);
    }

    public boolean validateAndProcess(P2PMessage message) {
        if (message == null) return false;
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
        if (peerManager == null) return;
        List<PeerManager.PeerInfo> targets = peerManager.selectGossipPeers(fanout, excludePeerId);
        for (PeerManager.PeerInfo peer : targets) {
            dispatchRelay(peer, message);
        }
    }

    public void relay(byte[] payload, String excludePeerId, List<String> availablePeers, int fanout) {
        P2PMessage msg = new P2PMessage("SYSTEM", "dummy", P2PMessage.Type.GOSSIP, payload);
        String msgId = msg.getMessageId();
        synchronized (seenMessages) {
            seenMessages.put(msgId, System.currentTimeMillis());
        }

        List<String> targets = new ArrayList<>(availablePeers);
        targets.remove(excludePeerId);
        Collections.shuffle(targets);
        List<String> selected = targets.subList(0, Math.min(fanout, targets.size()));

        if (dispatcher != null) {
            dispatcher.onDispatch(selected, payload);
        }
    }

    // Hook for PeerNode to perform actual socket write
    public interface Dispatcher {
        void onDispatch(List<String> targets, byte[] payload);
    }

    private Dispatcher dispatcher;
    public void setDispatcher(Dispatcher dispatcher) { this.dispatcher = dispatcher; }

    public void setRelayDispatcher(RelayDispatcher dispatcher) { this.relayDispatcher = dispatcher; }

    public interface RelayDispatcher {
        void send(PeerManager.PeerInfo target, P2PMessage message);
    }

    private RelayDispatcher relayDispatcher;

    private void dispatchRelay(PeerManager.PeerInfo target, P2PMessage message) {
        if (relayDispatcher != null) {
            relayDispatcher.send(target, message);
        }
    }
}
