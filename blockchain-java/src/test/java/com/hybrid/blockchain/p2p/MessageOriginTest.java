package com.hybrid.blockchain.p2p;

import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** [HC-044] P2P messages are authenticated against the key that owns senderId. */
class MessageOriginTest {

    private static GossipEngine engineCounting(AtomicInteger handled) {
        GossipEngine e = new GossipEngine();
        e.registerHandler(P2PMessage.Type.BLOCK, m -> handled.incrementAndGet());
        return e;
    }

    @Test
    @DisplayName("a correctly signed message is accepted and handled")
    void genuineAccepted() {
        AtomicInteger handled = new AtomicInteger();
        TestKeyPair a = new TestKeyPair(7101);
        P2PMessage m = P2PMessage.create(a.getAddress(), a.getPrivateKey(), P2PMessage.Type.BLOCK,
                "x".getBytes(StandardCharsets.UTF_8));
        assertThat(m.verifyOrigin()).isTrue();
        assertThat(engineCounting(handled).validateAndProcess(m)).isTrue();
        assertThat(handled.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("spoofed sender, altered payload and missing key are rejected before any handler")
    void forgeriesRejected() {
        AtomicInteger handled = new AtomicInteger();
        GossipEngine engine = engineCounting(handled);
        TestKeyPair a = new TestKeyPair(7101), victim = new TestKeyPair(7102);
        byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
        P2PMessage real = P2PMessage.create(a.getAddress(), a.getPrivateKey(), P2PMessage.Type.BLOCK, payload);

        // signed by a, but claims to come from victim (with a's key / with victim's key)
        P2PMessage spoof1 = new P2PMessage(victim.getAddress(), P2PMessage.Type.BLOCK, payload,
                real.getSignature(), real.getSenderPubKey());
        P2PMessage spoof2 = new P2PMessage(victim.getAddress(), P2PMessage.Type.BLOCK, payload,
                real.getSignature(), victim.getPublicKey());
        // payload changed after signing
        P2PMessage altered = new P2PMessage(a.getAddress(), P2PMessage.Type.BLOCK, "y".getBytes(StandardCharsets.UTF_8),
                real.getSignature(), real.getSenderPubKey());
        // legacy form without a sender key
        P2PMessage noKey = new P2PMessage(a.getAddress(), P2PMessage.Type.BLOCK, payload, real.getSignature());

        for (P2PMessage m : new P2PMessage[]{spoof1, spoof2, altered, noKey}) {
            assertThat(engine.validateAndProcess(m)).isFalse();
        }
        assertThat(handled.get()).isZero();
        // and none of the forgeries blocked the genuine message (dedup records only verified ones)
        assertThat(engine.validateAndProcess(real)).isTrue();
        assertThat(handled.get()).isEqualTo(1);
    }
}
