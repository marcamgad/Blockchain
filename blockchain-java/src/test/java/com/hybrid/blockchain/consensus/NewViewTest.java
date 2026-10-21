package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Crypto;
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

/**
 * [O-1] NEW_VIEW: a block prepared before the leader failed is carried into the next view, and a
 * new leader cannot replace it, hide behind too few view changes, or impersonate the leader.
 */
class NewViewTest {

    private final TestKeyPair[] keys = {new TestKeyPair(1), new TestKeyPair(2), new TestKeyPair(3), new TestKeyPair(4)};
    private final Map<String, TestKeyPair> byId = new HashMap<>();
    private final Map<String, PBFTConsensus> nodes = new HashMap<>();
    private final Map<String, PBFTConsensus.ViewChangeProof> sentViewChanges = new HashMap<>();
    private final Map<String, PBFTConsensus.NewViewProof> sentNewViews = new HashMap<>();

    @BeforeEach
    void setUp() {
        Map<String, byte[]> set = new HashMap<>();
        for (TestKeyPair k : keys) {
            set.put(k.getAddress(), k.getPublicKey());
            byId.put(k.getAddress(), k);
        }
        for (TestKeyPair k : keys) {
            PBFTConsensus c = new PBFTConsensus(set, k.getAddress(), k.getPrivateKey());
            c.setTimeout(600_000);
            String id = k.getAddress();
            c.setMessenger(new PBFTConsensus.PBFTMessenger() {
                public void broadcastPrepare(long s, String h, String v, byte[] sig) { }
                public void broadcastCommit(long s, String h, String v, byte[] sig) { }
                public void broadcastViewChange(long nv, long ls, String v, byte[] sig) { }
                public void broadcastNewView(long nv, List<PBFTConsensus.PBFTMessage> p, List<Block> b, String v) { }
                @Override public void broadcastViewChangeProof(PBFTConsensus.ViewChangeProof vc) { sentViewChanges.put(id, vc); }
                @Override public void broadcastNewViewProof(PBFTConsensus.NewViewProof nv) { sentNewViews.put(id, nv); }
            });
            nodes.put(id, c);
        }
    }

    @AfterEach
    void tearDown() {
        nodes.values().forEach(PBFTConsensus::shutdown);
    }

    private Block block(String proposer, String stateRoot) {
        Block b = new Block(1, 1_000L, List.of(), "00".repeat(32), 1, Crypto.bytesToHex(Crypto.hash(stateRoot.getBytes())));
        b.setValidatorId(proposer);
        b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), byId.get(proposer).getPrivateKey()));
        return b;
    }

    private byte[] vote(PBFTConsensus.Phase phase, long view, long seq, String hash, TestKeyPair k) {
        PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(phase, view, seq, hash, k.getAddress());
        m.sign(k.getPrivateKey());
        return m.signature;
    }

    /** Node {@code id} holds block {@code b} at seq 1 with PREPAREs from {@code voters} in view 0. */
    private void prepareOn(String id, Block b, int voters) {
        PBFTConsensus c = nodes.get(id);
        c.setPendingBlock(1, b);
        for (int i = 0; i < voters; i++) {
            c.addPrepareVote(1, b.getHash(), keys[i].getAddress(), vote(PBFTConsensus.Phase.PREPARE, 0, 1, b.getHash(), keys[i]));
        }
    }

    private String leaderOfView1() {
        // every node holds the same reputation, so every node elects the same leader; the view
        // change first penalises the leader of view 0, exactly as processViewChange does
        PBFTConsensus probe = nodes.get(keys[0].getAddress());
        PBFTConsensus copy = new PBFTConsensus(new HashMap<>(Map.of(
                keys[0].getAddress(), keys[0].getPublicKey(), keys[1].getAddress(), keys[1].getPublicKey(),
                keys[2].getAddress(), keys[2].getPublicKey(), keys[3].getAddress(), keys[3].getPublicKey())), null, null);
        copy.updateReputation(probe.selectLeader(0), PBFTConsensus.REP_MISSED_SLOT);
        String l = copy.selectLeader(1);
        copy.shutdown();
        return l;
    }

    /** Runs a view change 0 -> 1 in which {@code withVc} send VIEW_CHANGE; returns the leader's NEW_VIEW. */
    private PBFTConsensus.NewViewProof viewChange(String leader, List<String> withVc) {
        for (String id : withVc) nodes.get(id).triggerViewChange();
        PBFTConsensus l = nodes.get(leader);
        for (String id : withVc) if (!id.equals(leader)) l.addViewChange(sentViewChanges.get(id));
        return sentNewViews.get(leader);
    }

    private List<String> others(String leader) {
        List<String> out = new ArrayList<>();
        for (TestKeyPair k : keys) if (!k.getAddress().equals(leader)) out.add(k.getAddress());
        return out;
    }

    @Test
    @DisplayName("a block prepared in view 0 is re-proposed by the leader of view 1 and accepted by the others")
    void preparedBlockSurvivesViewChange() {
        String leader = leaderOfView1();
        String oldLeader = nodes.get(leader).selectLeader(0);
        Block a = block(oldLeader, "A");
        String witness = others(leader).get(0);
        prepareOn(witness, a, 3);                                   // 3 = quorum of 4

        List<String> vcs = new ArrayList<>(List.of(leader, witness, others(leader).get(1)));
        PBFTConsensus.NewViewProof nv = viewChange(leader, vcs);
        assertThat(nv).as("leader sends NEW_VIEW once it has 2f+1 view changes").isNotNull();
        assertThat(nv.block).isNotNull();
        assertThat(nv.block.getHash()).isEqualTo(a.getHash());

        PBFTConsensus follower = nodes.get(others(leader).get(2));  // saw none of it before
        Block reproposed = follower.acceptNewView(nv);
        assertThat(reproposed.getHash()).isEqualTo(a.getHash());
        assertThat(follower.getViewNumber()).isEqualTo(1);
        assertThat(follower.getJustifiedHash(1)).isEqualTo(a.getHash());
    }

    @Test
    @DisplayName("the new leader cannot replace the prepared block with another one")
    void leaderCannotSwapPreparedBlock() {
        String leader = leaderOfView1();
        Block a = block(nodes.get(leader).selectLeader(0), "A");
        String witness = others(leader).get(0);
        prepareOn(witness, a, 3);
        PBFTConsensus.NewViewProof nv = viewChange(leader, new ArrayList<>(List.of(leader, witness, others(leader).get(1))));

        Block b = block(leader, "B");
        nv.block = b;
        PBFTConsensus.PBFTMessage resigned = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.NEW_VIEW, 1, 1, b.getHash(), leader);
        resigned.sign(byId.get(leader).getPrivateKey());
        nv.signature = com.hybrid.blockchain.HexUtils.encode(resigned.signature);
        assertThatThrownBy(() -> nodes.get(others(leader).get(2)).acceptNewView(nv)).isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("a prepared proof with fewer than 2f+1 PREPAREs is ignored; then nothing is re-proposed")
    void underQuorumProofIsIgnored() {
        String leader = leaderOfView1();
        Block a = block(nodes.get(leader).selectLeader(0), "A");
        String witness = others(leader).get(0);
        prepareOn(witness, a, 2);                                   // only 2 PREPAREs: not prepared
        PBFTConsensus.NewViewProof nv = viewChange(leader, new ArrayList<>(List.of(leader, witness, others(leader).get(1))));
        assertThat(nv.block).isNull();
        assertThat(nodes.get(others(leader).get(2)).acceptNewView(nv)).isNull();
    }

    @Test
    @DisplayName("NEW_VIEW from a non-leader, or with fewer than 2f+1 view changes, is rejected")
    void forgedNewViewRejected() {
        String leader = leaderOfView1();
        PBFTConsensus.NewViewProof nv = viewChange(leader, new ArrayList<>(List.of(leader, others(leader).get(0), others(leader).get(1))));
        assertThat(nv).isNotNull();

        PBFTConsensus.NewViewProof few = new PBFTConsensus.NewViewProof();
        few.newView = 1;
        few.leaderId = leader;
        few.viewChanges = new ArrayList<>(nv.viewChanges.subList(0, 2));
        few.signature = nv.signature;
        assertThatThrownBy(() -> nodes.get(others(leader).get(2)).acceptNewView(few)).hasMessageContaining("view changes");

        String impostor = others(leader).get(1);
        PBFTConsensus.NewViewProof forged = new PBFTConsensus.NewViewProof();
        forged.newView = 1;
        forged.leaderId = impostor;
        forged.viewChanges = nv.viewChanges;
        PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.NEW_VIEW, 1, 1, "", impostor);
        m.sign(byId.get(impostor).getPrivateKey());
        forged.signature = com.hybrid.blockchain.HexUtils.encode(m.signature);
        assertThatThrownBy(() -> nodes.get(others(leader).get(2)).acceptNewView(forged)).hasMessageContaining("leader");
    }

    @Test
    @DisplayName("re-voting in a new view is not slashed; two different votes in one view still are")
    void noFalseSlashAcrossViews() {
        String leader = leaderOfView1();
        PBFTConsensus.NewViewProof nv = viewChange(leader, new ArrayList<>(List.of(leader, others(leader).get(0), others(leader).get(1))));
        PBFTConsensus c = nodes.get(others(leader).get(2));
        TestKeyPair v = keys[0];
        Block a = block(nodes.get(leader).selectLeader(0), "A");
        Block b = block(nodes.get(leader).selectLeader(0), "B");
        c.addPrepareVote(1, a.getHash(), v.getAddress(), vote(PBFTConsensus.Phase.PREPARE, 0, 1, a.getHash(), v));
        c.acceptNewView(nv);                                        // now in view 1
        c.addPrepareVote(1, b.getHash(), v.getAddress(), vote(PBFTConsensus.Phase.PREPARE, 1, 1, b.getHash(), v));
        assertThat(c.getSlashedValidators()).doesNotContain(v.getAddress());
        c.addPrepareVote(1, a.getHash(), v.getAddress(), vote(PBFTConsensus.Phase.PREPARE, 1, 1, a.getHash(), v));
        assertThat(c.getSlashedValidators()).contains(v.getAddress());
    }
}
