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
        if (message != null && isPointToPoint(message.getType())) return deliverDirect(message);   // [HC-106]
        boolean accepted = validateAndProcess(message);
        if (accepted) {
            relay(message, fromPeerId);
        }
        return accepted;
    }

    /**
     * [HC-106] Sync requests and responses are between two peers. Through the gossip path they were
     * de-duplicated by hash(sender, type, payload), so a node asking again for the same range got
     * no answer (and an identical response was dropped), and both were relayed to random peers.
     */
    public static boolean isPointToPoint(P2PMessage.Type type) {
        return type == P2PMessage.Type.REQUEST_BLOCKS || type == P2PMessage.Type.BLOCKS_RESPONSE;
    }

    /** [HC-106] Origin-checked delivery to the handler: no de-duplication, no relay. */
    private boolean deliverDirect(P2PMessage message) {
        // the socket reader already bounds a frame at 10 MB; a 50-block response can exceed the
        // 1 MB gossip limit
        if (message.getPayload() == null || message.getPayload().length > 10 * 1024 * 1024) return false;
        if (!message.verifyOrigin()) return false;   // [HC-044]
        Consumer<P2PMessage> handler = handlers.get(message.getType());
        if (handler != null) handler.accept(message);
        return true;
    }

    public boolean validateAndProcess(byte[] payload) {
        if (payload == null) return false;
        P2PMessage msg = new P2PMessage("SYSTEM", "dummy", P2PMessage.Type.GOSSIP, payload);
        return process(msg);   // local de-duplication utility: not a network message
    }

    public boolean validateAndProcess(P2PMessage message) {
        if (message == null) return false;
        // Cheap duplicate check first, WITHOUT recording: a flood of copies costs no signature
        // checks. Recording happens only after verification, so a forged copy (the id does not
        // cover the signature) cannot pre-empt the genuine message.
        synchronized (seenMessages) {
            if (seenMessages.containsKey(message.getMessageId())) return false;
        }
        // [HC-044] authenticate the ORIGIN before handlers or relay
        if (!message.verifyOrigin()) return false;
        return process(message);
    }

    private boolean process(P2PMessage message) {
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
        if (dispatcher == null) throw new IllegalStateException("Dispatcher not configured");
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
