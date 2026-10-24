package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-102] Found by the independent audit (docs/audit/independent-2026-10-24, C-9). Votes were
 * counted per (height, hash) without the view, and a commit certificate or prepared proof could mix
 * signatures from different views. Classical PBFT safety arguments count quorums within one view.
 * Worse, a prepared proof's view was the HIGHEST view among its signatures, so a Byzantine validator
 * could add one PREPARE of its own for a much later view to q - 1 honest PREPAREs and make the proof
 * win the NEW_VIEW choice. A quorum is now q votes for one hash in one view.
 */
class SameViewQuorumTest {

    private static final TestKeyPair[] K = {new TestKeyPair(10201), new TestKeyPair(10202), new TestKeyPair(10203), new TestKeyPair(10204)};
    private static final String H = "ab".repeat(32);
    private final List<PBFTConsensus> started = new ArrayList<>();

    private PBFTConsensus node() {
        Map<String, byte[]> set = new HashMap<>();
        for (TestKeyPair t : K) set.put(t.getAddress(), t.getPublicKey());
        PBFTConsensus c = new PBFTConsensus(set, K[0].getAddress(), K[0].getPrivateKey());
        started.add(c);
        return c;
    }

    @AfterEach
    void stop() {
        started.forEach(PBFTConsensus::shutdown);
    }

    private static void moveToView(PBFTConsensus c, long view) {
        for (int i = 1; i < K.length; i++) {
            PBFTConsensus.PBFTMessage vc = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.VIEW_CHANGE, view, 0, "VIEW_CHANGE_PROOF", K[i].getAddress());
            vc.sign(K[i].getPrivateKey());
            c.addViewChangeVote(view, 0, K[i].getAddress(), vc.signature);
        }
        assertThat(c.getViewNumber()).isEqualTo(view);
    }

    private static Block.CommitSignature sig(PBFTConsensus.Phase phase, long view, long seq, String hash, TestKeyPair k) {
        PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(phase, view, seq, hash, k.getAddress());
        m.sign(k.getPrivateKey());
        return new Block.CommitSignature(k.getAddress(), view, HexUtils.encode(m.signature));
    }

    @Test
    @DisplayName("live counting: COMMITs for one hash from two different views are not a quorum")
    void liveQuorumIsPerView() {
        PBFTConsensus c = node();
        for (int i : new int[]{1, 2}) {   // two COMMITs in view 0
            PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.COMMIT, 0, 5, H, K[i].getAddress());
            m.sign(K[i].getPrivateKey());
            c.addCommitVote(5, H, K[i].getAddress(), m.signature);
        }
        moveToView(c, 1);
        PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.COMMIT, 1, 5, H, K[3].getAddress());
        m.sign(K[3].getPrivateKey());
        c.addCommitVote(5, H, K[3].getAddress(), m.signature);   // one COMMIT in view 1
        assertThat(c.getQuorumHash(5, PBFTConsensus.Phase.COMMIT)).as("2 votes in view 0 + 1 in view 1 (q = 3)").isNull();
    }

    @Test
    @DisplayName("a commit certificate must hold q signatures from one view")
    void certificateIsPerView() {
        PBFTConsensus c = node();
        Block b = new Block(5, 1, new ArrayList<>(), "00".repeat(32), 4, "11".repeat(32));
        b.setValidatorId(K[0].getAddress());
        b.setHash(b.calculateHash());
        String hash = b.getHash();
        b.setCommitCertificate(List.of(sig(PBFTConsensus.Phase.COMMIT, 0, 5, hash, K[1]), sig(PBFTConsensus.Phase.COMMIT, 0, 5, hash, K[2]),
                sig(PBFTConsensus.Phase.COMMIT, 1, 5, hash, K[3])));
        assertThat(c.verifyCommitCertificate(b)).as("mixed views").isFalse();
        b.setCommitCertificate(List.of(sig(PBFTConsensus.Phase.COMMIT, 1, 5, hash, K[1]), sig(PBFTConsensus.Phase.COMMIT, 1, 5, hash, K[2]),
                sig(PBFTConsensus.Phase.COMMIT, 1, 5, hash, K[3])));
        assertThat(c.verifyCommitCertificate(b)).as("one view").isTrue();
    }

    @Test
    @DisplayName("a prepared proof cannot claim a later view with one extra signature")
    void preparedProofIsPerView() {
        PBFTConsensus c = node();
        Block b = new Block(5, 1, new ArrayList<>(), "00".repeat(32), 4, "11".repeat(32));
        b.setValidatorId(K[0].getAddress());
        b.setHash(b.calculateHash());
        b.sign(K[0].getPrivateKey());
        String hash = b.getHash();
        // two honest PREPAREs in view 0 plus a Byzantine PREPARE "in view 9"
        PBFTConsensus.PreparedProof forged = new PBFTConsensus.PreparedProof(5, hash, List.of(
                sig(PBFTConsensus.Phase.PREPARE, 0, 5, hash, K[1]), sig(PBFTConsensus.Phase.PREPARE, 0, 5, hash, K[2]),
                sig(PBFTConsensus.Phase.PREPARE, 9, 5, hash, K[3])), b);
        assertThat(c.verifyPreparedProof(forged)).as("mixed-view prepared proof").isFalse();
        PBFTConsensus.PreparedProof genuine = new PBFTConsensus.PreparedProof(5, hash, List.of(
                sig(PBFTConsensus.Phase.PREPARE, 0, 5, hash, K[1]), sig(PBFTConsensus.Phase.PREPARE, 0, 5, hash, K[2]),
                sig(PBFTConsensus.Phase.PREPARE, 0, 5, hash, K[3])), b);
        assertThat(c.verifyPreparedProof(genuine)).isTrue();
        assertThat(genuine.view()).isEqualTo(0);
    }
}
