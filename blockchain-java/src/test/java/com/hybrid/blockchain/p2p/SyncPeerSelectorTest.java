package com.hybrid.blockchain.p2p;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-096] Found by the independent audit (docs/audit/independent-2026-10-24, C-2). Catch-up always
 * asked the single peer with the highest self-reported height, and a claimed height could only go
 * up. One Byzantine validator advertising a huge height and never delivering blocks kept every
 * lagging node from catching up.
 */
class SyncPeerSelectorTest {

    private static PeerManager.PeerInfo peer(String id, long claimedHeight) {
        PeerManager.PeerInfo p = new PeerManager.PeerInfo(id, "10.0.0." + id.length(), 6001);
        p.setBlockHeight(claimedHeight);
        return p;
    }

    @Test
    @DisplayName("a lagging node catches up although one peer claims a huge height and never delivers")
    void lyingPeerCannotStallCatchUp() {
        PeerManager.PeerInfo liar = peer("liar", 1L << 62);
        PeerManager.PeerInfo honestA = peer("honestA", 10);
        PeerManager.PeerInfo honestB = peer("honestB", 10);
        List<PeerManager.PeerInfo> peers = List.of(liar, honestA, honestB);

        SyncPeerSelector selector = new SyncPeerSelector(new Random(1));
        long local = 0, now = 0;
        for (int round = 0; round < 40 && local < 10; round++, now += 30_000) {
            selector.beforeRound(local, now);
            PeerManager.PeerInfo chosen = selector.choose(peers, local, now);
            if (chosen == null) continue;
            selector.onRequested(chosen.getId(), local + 1);
            if (!chosen.getId().equals("liar")) local = Math.min(10, local + 5);   // honest peers deliver
            liar.setBlockHeight(1L << 62);   // the liar keeps re-advertising (every peer-list broadcast)
        }
        assertThat(local).as("caught up to the honest height").isEqualTo(10);
    }

    @Test
    @DisplayName("a peer whose blocks were rejected is not asked again during its back-off")
    void rejectedPeerBackedOff() {
        PeerManager.PeerInfo bad = peer("bad", 100);
        SyncPeerSelector selector = new SyncPeerSelector(new Random(2));
        selector.onRejected("bad", 0);
        assertThat(selector.choose(List.of(bad), 0, 1_000)).isNull();
        assertThat(selector.choose(List.of(bad), 0, SyncPeerSelector.BACKOFF_MS + 1)).isSameAs(bad);
    }

    @Test
    @DisplayName("only peers that claim to be ahead are chosen")
    void onlyPeersAhead() {
        SyncPeerSelector selector = new SyncPeerSelector(new Random(3));
        assertThat(selector.choose(List.of(peer("level", 5), peer("behind", 3)), 5, 0)).isNull();
    }
}
