package com.hybrid.blockchain.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages peer connections, health tracking, and reputation scoring.
 */
public class PeerManager {

    private static final Logger log = LoggerFactory.getLogger(PeerManager.class);

    public static class PeerInfo {
        private final String id;
        private final String address;
        private final int port;
        private double score;
        private long lastSeen;
        private long latency;
        private long blockHeight;
        private long slashedUntil; // 0 if not slashed
        private final Map<P2PMessage.Type, AtomicInteger> messageCounts = new ConcurrentHashMap<>();

        public PeerInfo(String id, String address, int port) {
            this.id = id;
            this.address = address;
            this.port = port;
            this.score = 50.0; // Initial neutral score
            this.lastSeen = System.currentTimeMillis();
            this.blockHeight = 0;
        }

        public void updateScore(double delta) {
            this.score = Math.max(0, Math.min(100, this.score + delta));
            if (this.score <= 0.1) {
                // Slashed for 24 hours
                this.slashedUntil = System.currentTimeMillis() + (24 * 60 * 60 * 1000);
                log.warn("[SECURITY] Peer {} SLASHED for 24 hours due to zero reputation.", id);
            }
        }

        public boolean isSlashed() {
            return slashedUntil > System.currentTimeMillis();
        }

        public long getSlashedUntil() { return slashedUntil; }

        public synchronized void recordLatency(long ms) {
            // Moving average
            this.latency = (this.latency == 0) ? ms : (this.latency * 7 + ms * 3) / 10;
        }

        public void recordMessage(P2PMessage.Type type) {
            messageCounts.computeIfAbsent(type, k -> new AtomicInteger(0)).incrementAndGet();
            this.lastSeen = System.currentTimeMillis();
        }

        public String getId() { return id; }
        public String getAddress() { return address; }
        public int getPort() { return port; }
        public double getScore() { return score; }
        public long getLastSeen() { return lastSeen; }
        public long getLatency() { return latency; }
        public long getBlockHeight() { return blockHeight; }
        public void setBlockHeight(long height) { this.blockHeight = height; }
    }

    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();
    private final Set<String> bannedIps = ConcurrentHashMap.newKeySet();
    private static final int MAX_PEERS = 50;
    public static final int MIN_PEERS = Integer.parseInt(System.getProperty("MIN_PEERS", "4"));

    private final Map<String, Long> slashedNodes = new ConcurrentHashMap<>(); // ID -> slashedUntil

    public void addPeer(String id, String address, int port) {
        if (bannedIps.contains(address)) return;
        
        Long slashedUntil = slashedNodes.get(id);
        if (slashedUntil != null) {
            if (slashedUntil > System.currentTimeMillis()) {
                log.debug("[P2P] Rejecting connection: Node {} is slashed until {}", id, new java.util.Date(slashedUntil));
                return;
            } else {
                slashedNodes.remove(id); // Slashing expired
            }
        }

        if (peers.size() >= MAX_PEERS) return;
        peers.putIfAbsent(id, new PeerInfo(id, address, port));
    }

    /**
     * @deprecated BUG FIX: Do NOT use this method — it hardcoded "127.0.0.1" which is
     * always wrong in multi-node deployments. Call
     * {@link #addPeer(String, String, int)} directly with the real socket address
     * obtained from {@code socket.getInetAddress().getHostAddress()}.
     */
    @Deprecated(forRemoval = true)
    public void onPeerConnected(String id) {
        // Backward-safe bridge: keep running while directing callers to the correct API.
        log.warn("[PeerManager] Deprecated onPeerConnected(id) used for {}. " +
            "Call addPeer(id, realIp, realPort) with actual socket address.", id);
        addPeer(id, "127.0.0.1", 0);
    }

    public void removePeer(String id) {
        peers.remove(id);
    }

    public PeerInfo getPeer(String id) {
        return peers.get(id);
    }

    public Collection<PeerInfo> getAllPeers() {
        return peers.values();
    }

    public int getPeerCount() {
        return peers.size();
    }

    public List<PeerInfo> getTopPeers(int count) {
        List<PeerInfo> list = new ArrayList<>(peers.values());
        list.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return list.subList(0, Math.min(count, list.size()));
    }

    public void updatePeerScore(String id, double delta) {
        PeerInfo info = peers.get(id);
        if (info != null) {
            info.updateScore(delta);
            if (info.isSlashed()) {
                slashedNodes.put(id, info.getSlashedUntil());
                log.warn("[PeerManager] Node {} slashed and removed", id);
                removePeer(id);
            } else if (info.getScore() < 10) {
                log.warn("[PeerManager] Peer {} banned due to low score", id);
                bannedIps.add(info.getAddress());
                removePeer(id);
            }
        }
    }

    public void banIp(String ip) {
        bannedIps.add(ip);
        peers.values().removeIf(p -> p.getAddress().equals(ip));
    }

    public boolean isBanned(String ip) {
        return bannedIps.contains(ip);
    }

    /**
     * Selects a random subset of peers for gossip.
     */
    public List<PeerInfo> selectGossipPeers(int fanout, String excludeId) {
        if (peers.size() < MIN_PEERS) {
            log.warn("[PeerManager] Eclipse protection: waiting for at least {} peers (current: {})", MIN_PEERS, peers.size());
            return Collections.emptyList();
        }
        List<PeerInfo> eligible = new ArrayList<>();
        for (PeerInfo p : peers.values()) {
            if (!p.getId().equals(excludeId) && p.getScore() >= 30) {
                eligible.add(p);
            }
        }
        Collections.shuffle(eligible);
        return eligible.subList(0, Math.min(fanout, eligible.size()));
    }
}
