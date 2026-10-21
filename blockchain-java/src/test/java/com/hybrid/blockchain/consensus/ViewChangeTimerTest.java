package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.TestKeyPair;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-083] Found by the live partition scenario: after the partition healed, all four validators
 * agreed on view 6 and its leader, but nothing was committed and the network never moved on. The
 * view-change timer was restarted only on a commit, so after one failed view change no node ever
 * timed out again.
 */
class ViewChangeTimerTest {

    private static PBFTConsensus node(List<Long> sent) {
        TestKeyPair[] k = {new TestKeyPair(1), new TestKeyPair(2), new TestKeyPair(3), new TestKeyPair(4)};
        Map<String, byte[]> set = new HashMap<>();
        for (TestKeyPair t : k) set.put(t.getAddress(), t.getPublicKey());
        PBFTConsensus c = new PBFTConsensus(set, k[0].getAddress(), k[0].getPrivateKey());
        c.setMessenger(new PBFTConsensus.PBFTMessenger() {
            public void broadcastPrepare(long s, String h, String v, byte[] sig) { }
            public void broadcastCommit(long s, String h, String v, byte[] sig) { }
            public void broadcastViewChange(long nv, long ls, String v, byte[] sig) { }
            public void broadcastNewView(long nv, List<PBFTConsensus.PBFTMessage> p, List<Block> b, String v) { }
            @Override public void broadcastViewChangeProof(PBFTConsensus.ViewChangeProof vc) { sent.add(vc.newView); }
        });
        return c;
    }

    @Test
    @DisplayName("without commits, a node keeps timing out and re-sending VIEW_CHANGE (with back-off)")
    void timerKeepsFiring() {
        List<Long> sent = new CopyOnWriteArrayList<>();
        PBFTConsensus c = node(sent);
        try {
            c.setTimeout(100);                          // back-off: 100, 200, 400 ms ...
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(sent.size()).as("VIEW_CHANGEs sent").isGreaterThanOrEqualTo(3));
        } finally {
            c.shutdown();
        }
    }

    @Test
    @DisplayName("the default timeout is longer than the block interval, so a healthy network does not churn views")
    void defaultTimeoutCoversBlockInterval() {
        assertThat(PBFTConsensus.defaultTimeoutMs()).isGreaterThan(Config.TARGET_BLOCK_TIME_MS);
    }
}
