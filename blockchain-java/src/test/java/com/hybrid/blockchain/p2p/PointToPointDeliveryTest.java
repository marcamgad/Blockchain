package com.hybrid.blockchain.p2p;

import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [HC-106] Found by the live harness run of 2026-10-24: a node that fell behind asked for the same
 * range again and again, and got no answer after the first. Sync requests and responses went through
 * the gossip engine, whose message id is hash(sender, type, payload): a repeated REQUEST_BLOCKS for
 * the same range (and a repeated identical BLOCKS_RESPONSE) was a "duplicate" and silently dropped.
 * Both were also relayed to random peers. They are point-to-point: delivered once per receipt, after
 * the origin check, and never relayed.
 */
class PointToPointDeliveryTest {

    @Test
    @DisplayName("a repeated sync request is handled every time and never relayed; a forged one is not handled")
    void syncMessagesAreNotDeduplicatedOrRelayed() {
        PeerManager peers = mock(PeerManager.class);
        GossipEngine g = new GossipEngine(peers, 3);
        AtomicInteger handled = new AtomicInteger();
        g.registerHandler(P2PMessage.Type.REQUEST_BLOCKS, m -> handled.incrementAndGet());

        TestKeyPair k = new TestKeyPair(10601);
        byte[] payload = "{\"fromHeight\":21,\"toHeight\":70}".getBytes(StandardCharsets.UTF_8);
        assertThat(GossipEngine.isPointToPoint(P2PMessage.Type.REQUEST_BLOCKS)).isTrue();
        assertThat(GossipEngine.isPointToPoint(P2PMessage.Type.BLOCKS_RESPONSE)).isTrue();
        assertThat(GossipEngine.isPointToPoint(P2PMessage.Type.TRANSACTION)).isFalse();

        for (int i = 0; i < 3; i++) {
            assertThat(g.onMessageReceived(P2PMessage.create(k.getAddress(), k.getPrivateKey(), P2PMessage.Type.REQUEST_BLOCKS, payload), "peer"))
                    .isTrue();
        }
        assertThat(handled.get()).as("the same request three times").isEqualTo(3);
        verify(peers, never()).selectGossipPeers(anyInt(), any());

        TestKeyPair other = new TestKeyPair(10602);
        P2PMessage forged = P2PMessage.create(k.getAddress(), other.getPrivateKey(), P2PMessage.Type.REQUEST_BLOCKS, payload);
        assertThat(g.onMessageReceived(forged, "peer")).isFalse();
        assertThat(handled.get()).isEqualTo(3);
    }
}
