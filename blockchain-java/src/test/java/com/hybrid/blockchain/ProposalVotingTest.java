package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.testutil.ChainFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-087] Found while writing docs/ARCHITECTURE_DEEP_DIVE.md. A validator PREPARE-voted on every
 * proposal it accepted, from ANY validator, and turned its own second vote at one (view, height)
 * into equivocation evidence against itself. One Byzantine validator could send a competing block,
 * collect every honest validator's two conflicting PREPAREs and get them all jailed on-chain. The
 * leader also re-proposed an uncommitted height every 60 s (view timeout 135 s), so ordinary delay
 * made honest validators sign two blocks for one height in one view.
 */
class ProposalVotingTest {

    private static String previousDebug, previousAes;

    @BeforeAll
    static void productionMode() {   // ChainFixture requires DEBUG=false (same setup as MeshFormationTest)
        previousDebug = System.getProperty("DEBUG");
        previousAes = System.getProperty("STORAGE_AES_KEY");
        System.setProperty("DEBUG", "false");
        byte[] aes = new byte[16];
        new java.security.SecureRandom().nextBytes(aes);
        System.setProperty("STORAGE_AES_KEY", HexUtils.encode(aes));
        System.setProperty("NODE_PRIVATE_KEY", "1111111111111111111111111111111111111111111111111111111111111111");
    }

    @AfterAll
    static void restoreMode() {
        if (previousDebug == null) System.clearProperty("DEBUG"); else System.setProperty("DEBUG", previousDebug);
        if (previousAes == null) System.clearProperty("STORAGE_AES_KEY"); else System.setProperty("STORAGE_AES_KEY", previousAes);
    }

    /** Records what the node broadcasts instead of sending it. */
    static final class Captured implements PBFTConsensus.PBFTMessenger {
        final List<String> prepares = new CopyOnWriteArrayList<>();
        public void broadcastPrepare(long s, String h, String v, byte[] sig) { prepares.add(s + ":" + h); }
        public void broadcastCommit(long s, String h, String v, byte[] sig) { }
        public void broadcastViewChange(long nv, long ls, String v, byte[] sig) { }
        public void broadcastNewView(long nv, List<PBFTConsensus.PBFTMessage> p, List<Block> b, String v) { }
    }

    private static Block proposal(Blockchain chain, TestKeyPair proposer, long timestamp) {
        Block tip = chain.getLatestBlock();
        Block b = new Block(tip.getIndex() + 1, timestamp, new ArrayList<>(), tip.getHash(), tip.getDifficulty(), tip.getStateRoot());
        b.setValidatorId(proposer.getAddress());
        b.setHash(b.calculateHash());
        b.setSignature(Crypto.sign(b.signingDigest(), proposer.getPrivateKey()));
        return b;
    }

    @Test
    @DisplayName("a validator votes once per (view, height), only for the view's leader, and never reports itself")
    void onePrepareOnlyForTheLeader() throws Exception {
        TestKeyPair[] k = {new TestKeyPair(8701), new TestKeyPair(8702), new TestKeyPair(8703), new TestKeyPair(8704)};
        Map<String, byte[]> set = new HashMap<>();
        for (TestKeyPair t : k) set.put(t.getAddress(), t.getPublicKey());

        // this node: a validator that is NOT the leader of view 0
        PBFTConsensus probe = new PBFTConsensus(set, k[0].getAddress(), k[0].getPrivateKey());
        String leaderId = probe.getCurrentLeader();
        probe.shutdown();
        TestKeyPair leader = null, local = null, other = null;
        for (TestKeyPair t : k) {
            if (t.getAddress().equals(leaderId)) leader = t;
            else if (local == null) local = t;
            else if (other == null) other = t;
        }

        try (ChainFixture fx = new ChainFixture()) {
            PBFTConsensus pbft = new PBFTConsensus(set, local.getAddress(), local.getPrivateKey());
            fx.getBlockchain().setConsensus(pbft);
            fx.getBlockchain().init();
            Captured out = new Captured();
            pbft.setMessenger(out);
            List<PBFTConsensus.EquivocationEvidence> evidence = new CopyOnWriteArrayList<>();
            pbft.setEvidenceListener(evidence::add);

            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC", "BC");
            g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            PeerNode node = new PeerNode(9071, fx.getBlockchain(), pbft, local.getPrivateKey(), g.generateKeyPair(),
                    new com.hybrid.blockchain.security.CertificateAuthority(Crypto.hash("test-network-ca".getBytes())));
            try {
                // proposals are made "now" (HC-103: a proposal far behind the validator's clock is ignored)
                long t0 = System.currentTimeMillis();

                // (a) a validly signed block from a validator that is not this view's leader: no vote
                node.onProposal(proposal(fx.getBlockchain(), other, t0));
                assertThat(out.prepares).as("no PREPARE for a non-leader's proposal").isEmpty();

                // (e) [HC-103] a leader's proposal timestamped far behind this validator's clock: no vote
                Block stale = proposal(fx.getBlockchain(), leader, t0 - 10 * Config.MAX_TIMESTAMP_DRIFT);
                java.util.List<String> before = new java.util.ArrayList<>(out.prepares);
                node.onProposal(stale);
                assertThat(out.prepares).as("no PREPARE for a stale proposal").isEqualTo(before);

                // (b) the leader's proposal: exactly one PREPARE
                Block a = proposal(fx.getBlockchain(), leader, t0 + 1);
                node.onProposal(a);
                node.onProposal(a);   // the same block again (gossip duplicate) is harmless
                assertThat(out.prepares).containsOnly("1:" + a.getHash());

                // (c) a second, different block from the leader at the same height and view
                Block b = proposal(fx.getBlockchain(), leader, t0 + 2);
                node.onProposal(b);
                assertThat(out.prepares).as("never a second PREPARE at one (view, height)").doesNotContain("1:" + b.getHash());
                assertThat(evidence).as("an honest node never produces evidence against itself").isEmpty();

                // (d) the proposer guard: this height already has our vote in this view
                assertThat(pbft.hasOwnVote(PBFTConsensus.Phase.PREPARE, pbft.getViewNumber(), 1)).isTrue();
                assertThat(pbft.hasOwnVote(PBFTConsensus.Phase.PREPARE, pbft.getViewNumber(), 2)).isFalse();
            } finally {
                node.shutdown();
                pbft.shutdown();
            }
        }
    }

    @Test
    @DisplayName("PBFTConsensus refuses to record a conflicting vote under its own id")
    void ownConflictingVoteRefused() {
        TestKeyPair[] k = {new TestKeyPair(8711), new TestKeyPair(8712), new TestKeyPair(8713), new TestKeyPair(8714)};
        Map<String, byte[]> set = new HashMap<>();
        for (TestKeyPair t : k) set.put(t.getAddress(), t.getPublicKey());
        PBFTConsensus me = new PBFTConsensus(set, k[0].getAddress(), k[0].getPrivateKey());
        List<PBFTConsensus.EquivocationEvidence> evidence = new ArrayList<>();
        me.setEvidenceListener(evidence::add);
        try {
            assertThat(me.reserveOwnVote(PBFTConsensus.Phase.PREPARE, 0, 5, "aa".repeat(32))).isTrue();
            assertThat(me.reserveOwnVote(PBFTConsensus.Phase.PREPARE, 0, 5, "aa".repeat(32))).as("same hash again").isTrue();
            assertThat(me.reserveOwnVote(PBFTConsensus.Phase.PREPARE, 0, 5, "bb".repeat(32))).as("different hash").isFalse();
            assertThat(me.reserveOwnVote(PBFTConsensus.Phase.PREPARE, 1, 5, "bb".repeat(32))).as("next view").isTrue();

            for (String hash : new String[]{"cc".repeat(32), "dd".repeat(32)}) {
                PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.PREPARE, me.getViewNumber(), 7, hash, k[0].getAddress());
                m.sign(k[0].getPrivateKey());
                try {
                    me.addPrepareVote(7, hash, k[0].getAddress(), m.signature);
                } catch (IllegalStateException refused) {
                    // expected for the second one
                }
            }
            assertThat(evidence).as("no self-evidence even if a caller skips the reservation").isEmpty();
        } finally {
            me.shutdown();
        }
    }
}
