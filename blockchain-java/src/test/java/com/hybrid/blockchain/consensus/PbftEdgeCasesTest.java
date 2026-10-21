package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [Item 9] Rejection paths of the PBFT engine that no test reached. */
class PbftEdgeCasesTest {

    private final TestKeyPair[] k = {new TestKeyPair(1), new TestKeyPair(2), new TestKeyPair(3), new TestKeyPair(4)};
    private PBFTConsensus pbft;

    @BeforeEach
    void setUp() {
        Map<String, byte[]> set = new HashMap<>();
        for (TestKeyPair t : k) set.put(t.getAddress(), t.getPublicKey());
        pbft = new PBFTConsensus(set, k[0].getAddress(), k[0].getPrivateKey());
        pbft.setTimeout(600_000);
    }

    @AfterEach
    void tearDown() {
        pbft.shutdown();
    }

    private Block block(TestKeyPair proposer, String tag, int index) {
        Block b = new Block(index, 1_000L, List.of(), "00".repeat(32), 1, HexUtils.encode(Crypto.hash(tag.getBytes())));
        b.setValidatorId(proposer.getAddress());
        b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), proposer.getPrivateKey()));
        return b;
    }

    private String sig(PBFTConsensus.Phase p, long view, long seq, String hash, TestKeyPair t) {
        PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(p, view, seq, hash, t.getAddress());
        m.sign(t.getPrivateKey());
        return HexUtils.encode(m.signature);
    }

    private PBFTConsensus.PreparedProof proof(Block b, long view, int voters) {
        List<Block.CommitSignature> prepares = new ArrayList<>();
        for (int i = 0; i < voters; i++) {
            prepares.add(new Block.CommitSignature(k[i].getAddress(), view, sig(PBFTConsensus.Phase.PREPARE, view, b.getIndex(), b.getHash(), k[i])));
        }
        return new PBFTConsensus.PreparedProof(b.getIndex(), b.getHash(), prepares, b);
    }

    @Test
    @DisplayName("prepared proofs: valid with 3 of 4; rejected when malformed, re-indexed, unsigned or under quorum")
    void preparedProofRules() {
        Block a = block(k[1], "A", 1);
        assertThat(pbft.verifyPreparedProof(proof(a, 0, 3))).isTrue();
        assertThat(pbft.verifyPreparedProof(null)).isFalse();
        assertThat(pbft.verifyPreparedProof(proof(a, 0, 2))).as("under quorum").isFalse();
        PBFTConsensus.PreparedProof wrongSeq = proof(a, 0, 3);
        wrongSeq.seq = 2;
        assertThat(pbft.verifyPreparedProof(wrongSeq)).as("seq differs from block index").isFalse();
        PBFTConsensus.PreparedProof wrongHash = proof(a, 0, 3);
        wrongHash.blockHash = "ab".repeat(32);
        assertThat(pbft.verifyPreparedProof(wrongHash)).as("hash differs from block").isFalse();
        Block unsigned = block(k[1], "U", 1);
        unsigned.setSignature(null);
        assertThat(pbft.verifyPreparedProof(proof(unsigned, 0, 3))).as("no proposer signature").isFalse();
        PBFTConsensus.PreparedProof dup = proof(a, 0, 2);
        dup.prepares.add(dup.prepares.get(0));
        assertThat(pbft.verifyPreparedProof(dup)).as("duplicate voter").isFalse();
        PBFTConsensus.PreparedProof garbage = proof(a, 0, 2);
        garbage.prepares.add(new Block.CommitSignature(k[2].getAddress(), 0, "zz-not-hex"));
        garbage.prepares.add(new Block.CommitSignature("hbunknown", 0, garbage.prepares.get(0).signature));
        garbage.prepares.add(null);
        assertThat(pbft.verifyPreparedProof(garbage)).as("garbage entries are not counted").isFalse();
    }

    @Test
    @DisplayName("view changes: a bad signature is refused; a bad attached proof is dropped but the vote counts")
    void viewChangeRules() {
        PBFTConsensus.ViewChangeProof forged = new PBFTConsensus.ViewChangeProof(1, 0, k[1].getAddress(),
                sig(PBFTConsensus.Phase.VIEW_CHANGE, 1, 0, "VIEW_CHANGE_PROOF", k[2]), null);
        assertThatThrownBy(() -> pbft.addViewChange(forged)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> pbft.addViewChange(null)).isInstanceOf(SecurityException.class);

        PBFTConsensus.ViewChangeProof withBadProof = new PBFTConsensus.ViewChangeProof(1, 0, k[1].getAddress(),
                sig(PBFTConsensus.Phase.VIEW_CHANGE, 1, 0, "VIEW_CHANGE_PROOF", k[1]), proof(block(k[1], "A", 1), 0, 1));
        pbft.addViewChange(withBadProof);
        assertThat(withBadProof.prepared).as("invalid proof dropped").isNull();
    }

    private PBFTConsensus.NewViewProof newView(long view, TestKeyPair leader, List<PBFTConsensus.ViewChangeProof> vcs, Block chosen) {
        return newView(view, leader, vcs, chosen, chosen == null ? "" : chosen.getHash());
    }

    /** {@code signedHash}: the hash the leader signs (what the receiver's own choice must be). */
    private PBFTConsensus.NewViewProof newView(long view, TestKeyPair leader, List<PBFTConsensus.ViewChangeProof> vcs,
            Block chosen, String signedHash) {
        PBFTConsensus.NewViewProof nv = new PBFTConsensus.NewViewProof();
        nv.newView = view;
        nv.leaderId = leader.getAddress();
        nv.viewChanges = vcs;
        nv.block = chosen;
        nv.signature = sig(PBFTConsensus.Phase.NEW_VIEW, view, 1, signedHash, leader);
        return nv;
    }

    private PBFTConsensus.ViewChangeProof vc(long view, TestKeyPair t, PBFTConsensus.PreparedProof p) {
        return new PBFTConsensus.ViewChangeProof(view, 0, t.getAddress(), sig(PBFTConsensus.Phase.VIEW_CHANGE, view, 0, "VIEW_CHANGE_PROOF", t), p);
    }

    private TestKeyPair leaderAfterViewChangeTo(long view) {
        // the receiver applies the view change (penalising the old leader) before electing
        PBFTConsensus probe = new PBFTConsensus(new HashMap<>(Map.of(k[0].getAddress(), k[0].getPublicKey(),
                k[1].getAddress(), k[1].getPublicKey(), k[2].getAddress(), k[2].getPublicKey(), k[3].getAddress(), k[3].getPublicKey())), null, null);
        probe.updateReputation(probe.selectLeader(0), PBFTConsensus.REP_MISSED_SLOT);
        String id = probe.selectLeader(view);
        probe.shutdown();
        for (TestKeyPair t : k) if (t.getAddress().equals(id)) return t;
        throw new IllegalStateException();
    }

    @Test
    @DisplayName("NEW_VIEW: malformed, wrong view tags, no block when one was prepared, and duplicates")
    void newViewRules() {
        assertThatThrownBy(() -> pbft.acceptNewView(null)).hasMessageContaining("Malformed");
        TestKeyPair leader = leaderAfterViewChangeTo(1);
        List<PBFTConsensus.ViewChangeProof> wrongView = List.of(vc(2, k[0], null), vc(2, k[1], null), vc(2, k[2], null));
        assertThatThrownBy(() -> pbft.acceptNewView(newView(1, leader, wrongView, null))).hasMessageContaining("view changes");

        Block a = block(k[1], "A", 1);
        List<PBFTConsensus.ViewChangeProof> withProof = List.of(vc(1, k[0], proof(a, 0, 3)), vc(1, k[1], null), vc(1, k[2], null));
        assertThatThrownBy(() -> pbft.acceptNewView(newView(1, leader, withProof, null, a.getHash())))
                .as("a prepared block exists but NEW_VIEW proposes nothing").hasMessageContaining("does not re-propose");

        List<PBFTConsensus.ViewChangeProof> empty = List.of(vc(1, k[0], null), vc(1, k[1], null), vc(1, k[2], null));
        assertThatThrownBy(() -> pbft.acceptNewView(newView(1, leader, empty, a, "")))
                .as("nothing prepared but NEW_VIEW proposes a block").hasMessageContaining("none was prepared");
    }

    @Test
    @DisplayName("NEW_VIEW: the higher-view prepared block wins, a duplicate delivery is ignored, an old view is stale")
    void newViewChoiceAndReplay() {
        TestKeyPair leader = leaderAfterViewChangeTo(1);
        Block older = block(k[1], "OLD", 1), newer = block(k[2], "NEW", 1);
        List<PBFTConsensus.ViewChangeProof> vcs = List.of(vc(1, k[0], proof(older, 0, 3)), vc(1, k[1], proof(newer, 1, 3)), vc(1, k[2], null));
        PBFTConsensus.NewViewProof nv = newView(1, leader, vcs, newer);
        assertThat(pbft.acceptNewView(nv).getHash()).isEqualTo(newer.getHash());
        assertThat(pbft.acceptNewView(nv)).as("duplicate delivery").isNull();
        PBFTConsensus.NewViewProof stale = newView(0, leader, vcs, newer);
        assertThatThrownBy(() -> pbft.acceptNewView(stale)).hasMessageContaining("Stale");
    }

    @Test
    @DisplayName("evidence: wrong phase, unknown validator, bad hex and null are not evidence")
    void evidenceRules() {
        PBFTConsensus.EquivocationEvidence ev = new PBFTConsensus.EquivocationEvidence();
        ev.validatorId = k[1].getAddress();
        ev.view = 0;
        ev.seq = 3;
        ev.hashA = "aa".repeat(32);
        ev.hashB = "bb".repeat(32);
        ev.signatureA = sig(PBFTConsensus.Phase.VIEW_CHANGE, 0, 3, ev.hashA, k[1]);
        ev.signatureB = sig(PBFTConsensus.Phase.VIEW_CHANGE, 0, 3, ev.hashB, k[1]);
        ev.phase = "VIEW_CHANGE";
        assertThat(pbft.verifyEvidence(ev)).as("only PREPARE/COMMIT equivocation counts").isFalse();
        ev.phase = "NOT_A_PHASE";
        assertThat(pbft.verifyEvidence(ev)).isFalse();
        ev.phase = "PREPARE";
        ev.signatureA = "zz";
        assertThat(pbft.verifyEvidence(ev)).isFalse();
        ev.validatorId = "hbunknown";
        assertThat(pbft.verifyEvidence(ev)).isFalse();
        assertThat(pbft.verifyEvidence(null)).isFalse();
    }

    @Test
    @DisplayName("block verification: missing proposer, unknown proposer or missing signature are rejected")
    void blockVerificationRules() {
        Block b = block(k[1], "B", 1);
        assertThat(pbft.verifyProposerSignature(b, null)).isTrue();
        Block noSig = block(k[1], "B", 1);
        noSig.setSignature(null);
        assertThat(pbft.verifyProposerSignature(noSig, null)).isFalse();
        Block noProposer = block(k[1], "B", 1);
        noProposer.setValidatorId(null);
        assertThat(pbft.verifyProposerSignature(noProposer, null)).isFalse();
        Block stranger = block(new TestKeyPair(77), "B", 1);
        assertThat(pbft.verifyProposerSignature(stranger, null)).isFalse();
        assertThat(pbft.verifyBlock(b, null)).as("signed but no certificate, 4 validators").isFalse();
        assertThat(pbft.verifyCommitCertificate(b)).isFalse();
    }
}
