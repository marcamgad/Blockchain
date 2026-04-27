package com.hybrid.blockchain;

import com.hybrid.blockchain.p2p.PeerManager;
import com.hybrid.blockchain.p2p.GossipEngine;
import com.hybrid.blockchain.p2p.P2PMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
public class PeerNetworkTest {

    @Test
    @DisplayName("Invariant: Peer discovery must identify and maintain valid neighbors")
    void testPeerDiscovery() {
        PeerManager pm = new PeerManager();
        
        pm.addPeer("peer1", "127.0.0.1", 8081);
        pm.addPeer("peer2", "127.0.0.1", 8082);
        
        assertThat(pm.getPeerCount()).isEqualTo(2);
        
        pm.removePeer("peer1");
        assertThat(pm.getPeerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Compatibility: deprecated onPeerConnected bridge should not throw")
    void testDeprecatedOnPeerConnectedBridge() {
        PeerManager pm = new PeerManager();
        assertThatCode(() -> pm.onPeerConnected("legacy-peer")).doesNotThrowAnyException();
        assertThat(pm.getPeer("legacy-peer")).isNotNull();
    }

    @Test
    @DisplayName("Security: Malicious peers (sending garbage) must be blacklisted")
    void testPeerBlacklisting() {
        PeerManager pm = new PeerManager();
        String attackerIp = "1.2.3.4";
        String attackerId = "attacker-node";
        
        pm.addPeer(attackerId, attackerIp, 9999);
        
        // Simulate "malicious behavior" detection leading to ban
        pm.banIp(attackerIp);
        
        assertThat(pm.isBanned(attackerIp)).isTrue();
        
        // Should not be able to re-add banned peer
        pm.addPeer("new-attacker", attackerIp, 9998);
        assertThat(pm.getPeerCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Invariant: Gossip protocol must reach all peers eventually")
    void testGossipPropagation() {
        PeerManager pm = new PeerManager();
        pm.addPeer("p1", "127.0.0.1", 9001);
        pm.addPeer("p2", "127.0.0.1", 9002);
        
        // Boost scores to ensure they are eligible for gossip (PeerManager.selectGossipPeers requires score >= 30, baseline is 50)
        
        GossipEngine gossip = new GossipEngine(pm, 2);
        AtomicInteger sendCount = new AtomicInteger(0);
        gossip.setRelayDispatcher((target, message) -> sendCount.incrementAndGet());
        
        P2PMessage msg = P2PMessage.create("local", new java.math.BigInteger("1"), P2PMessage.Type.BLOCK, "data".getBytes());
        gossip.relay(msg, "local");
        
        assertThat(sendCount.get()).isEqualTo(2);
    }
}
