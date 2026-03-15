package com.hybrid.blockchain.p2p;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages peer connections, health tracking, and reputation scoring.
 */
public class PeerManager {

    public static class PeerInfo {
        private final String id;
        private final String address;
        private final int port;
        private double score;
        private long lastSeen;
        private long latency;
        private final Map<P2PMessage.Type, AtomicInteger> messageCounts = new ConcurrentHashMap<>();

        public PeerInfo(String id, String address, int port) {
            this.id = id;
            this.address = address;
            this.port = port;
            this.score = 50.0; // Initial neutral score
            this.lastSeen = System.currentTimeMillis();
        }

        public void updateScore(double delta) {
            this.score = Math.max(0, Math.min(100, this.score + delta));
        }

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
    }

    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();
    private final Set<String> bannedIps = ConcurrentHashMap.newKeySet();

    public void addPeer(String id, String address, int port) {
        if (bannedIps.contains(address)) return;
        peers.putIfAbsent(id, new PeerInfo(id, address, port));
    }

    public void onPeerConnected(String id) {
        addPeer(id, "127.0.0.1", 6001);
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
            if (info.getScore() < 10) {
                System.err.println("[PeerManager] Peer " + id + " banned due to low score");
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
