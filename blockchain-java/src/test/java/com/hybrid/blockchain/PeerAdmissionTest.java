package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStream;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** [HC-011] validator-only admission, [HC-012] bounded unauthenticated phase. */
class PeerAdmissionTest {

    private static PBFTConsensus pbft(TestKeyPair local, TestKeyPair... others) {
        Map<String, byte[]> vs = new LinkedHashMap<>();
        vs.put(local.getAddress(), local.getPublicKey());
        for (TestKeyPair k : others) vs.put(k.getAddress(), k.getPublicKey());
        return new PBFTConsensus(vs, local.getAddress(), local.getPrivateKey());
    }

    @Test
    @DisplayName("HC-011: under PBFT only validators are admitted unless observers are explicitly allowed")
    void onlyValidatorsAdmitted() {
        TestKeyPair a = new TestKeyPair(5101), b = new TestKeyPair(5102), c = new TestKeyPair(5103), d = new TestKeyPair(5104);
        PBFTConsensus consensus = pbft(a, b, c, d);
        try {
            PeerNode node = new PeerNode(0, Mockito.mock(Blockchain.class), consensus, a.getPrivateKey());
            assertThat(node.isAuthorisedPeer(b.getAddress())).isTrue();
            assertThat(node.isAuthorisedPeer(new TestKeyPair(5199).getAddress())).as("stranger").isFalse();
            System.setProperty("ALLOW_OBSERVER_PEERS", "true");
            try {
                assertThat(node.isAuthorisedPeer(new TestKeyPair(5199).getAddress())).as("observer opt-in").isTrue();
            } finally {
                System.clearProperty("ALLOW_OBSERVER_PEERS");
            }
        } finally {
            consensus.shutdown();
        }
    }

    @Test
    @DisplayName("HC-045: discovered peers are bounded candidates, never gossip targets")
    void discoveryDoesNotPolluteGossipTable() throws Exception {
        TestKeyPair a = new TestKeyPair(5301), b = new TestKeyPair(5302), c = new TestKeyPair(5303), d = new TestKeyPair(5304);
        PBFTConsensus consensus = pbft(a, b, c, d);
        try {
            PeerNode node = new PeerNode(0, Mockito.mock(Blockchain.class), consensus, a.getPrivateKey());
            java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) list.add(Map.of("id", "fake-" + i, "address", "10.9.0." + (i % 250), "port", 6001));
            byte[] payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(list);
            node.getGossipEngine().onMessageReceived(com.hybrid.blockchain.p2p.P2PMessage.create(
                    b.getAddress(), b.getPrivateKey(), com.hybrid.blockchain.p2p.P2PMessage.Type.PEER_DISCOVERY, payload), b.getAddress());
            assertThat(node.getPeerManager().getPeerCount()).as("gossip table untouched").isZero();
            assertThat(node.getDiscoveredPeers()).hasSizeLessThanOrEqualTo(PeerNode.MAX_DISCOVERED_PEERS).isNotEmpty();
        } finally {
            consensus.shutdown();
        }
    }

    @Test
    @DisplayName("HC-008: a peer with different consensus-rule flags is refused at HELLO")
    void ruleFlagMismatchRefused() throws Exception {
        java.lang.reflect.Method check = PeerNode.class.getDeclaredMethod("checkRuleFlags", int.class);
        check.setAccessible(true);
        check.invoke(null, PeerNode.ruleFlags());                       // same rules: accepted
        Throwable t = null;
        try { check.invoke(null, PeerNode.ruleFlags() ^ 1); }           // REQUIRE_QUANTUM_SIG differs
        catch (java.lang.reflect.InvocationTargetException e) { t = e.getCause(); }
        assertThat(t).isInstanceOf(java.io.IOException.class).hasMessageContaining("REQUIRE_QUANTUM_SIG");
    }

    @Test
    @DisplayName("HC-012: a peer that connects and sends nothing is dropped after the handshake timeout")
    void silentPeerIsDropped() throws Exception {
        TestKeyPair a = new TestKeyPair(5201), b = new TestKeyPair(5202), c = new TestKeyPair(5203), d = new TestKeyPair(5204);
        PBFTConsensus consensus = pbft(a, b, c, d);
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) { port = probe.getLocalPort(); }
        PeerNode node = new PeerNode(port, Mockito.mock(Blockchain.class), consensus, a.getPrivateKey());
        node.start();
        try (Socket silent = new Socket("127.0.0.1", port)) {
            silent.setSoTimeout(PeerNode.HANDSHAKE_TIMEOUT_MS + 5_000);
            long t0 = System.currentTimeMillis();
            int r;
            InputStream in = silent.getInputStream();
            // the server may send a TLS alert record (content type 21) before closing: drain to EOF
            try { do { r = in.read(); } while (r != -1); } catch (java.io.IOException closed) { r = -1; }
            long waited = System.currentTimeMillis() - t0;
            assertThat(r).as("server should close the silent connection").isEqualTo(-1);
            assertThat(waited).as("closed by the handshake timeout, not held forever")
                    .isLessThan(PeerNode.HANDSHAKE_TIMEOUT_MS + 4_000L);
        } finally {
            node.stop();
        }
    }
}
