package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Integration and logic tests for the PBFT consensus protocol.
 */
@Tag("consensus")
public class PBFTConsensusCompleteTest {

    private Map<String, byte[]> validators;
    private TestKeyPair leaderKey;
    private PBFTConsensus pbft;

    @BeforeEach
    void setUp() {
        com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().reset();
        validators = new HashMap<>();
        leaderKey = new TestKeyPair(1);
        validators.put(leaderKey.getAddress(), leaderKey.getPublicKey());
        for (int i = 2; i <= 4; i++) {
            TestKeyPair kp = new TestKeyPair(i);
            validators.put(kp.getAddress(), kp.getPublicKey());
        }
        pbft = new PBFTConsensus(validators, leaderKey.getAddress(), leaderKey.getPrivateKey());
    }

    @AfterEach
    void tearDown() {
        if (pbft != null) pbft.shutdown();
    }

    @Test
    @DisplayName("P1.1 — Constructor requires 4 validators")
    void testMinValidators() {
        Map<String, byte[]> small = new HashMap<>();
        small.put("a", new byte[32]);
        assertThatThrownBy(() -> new PBFTConsensus(small, "a", java.math.BigInteger.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("P1.2-1.4 — Leader selection logic")
    void testLeaderSelection() {
        String leaderView0 = pbft.getCurrentLeader();
        assertThat(leaderView0).isNotNull();
        assertThat(pbft.selectLeader(0)).isEqualTo(leaderView0);
        
        // Determinism check
        String leaderView1 = pbft.selectLeader(1);
        assertThat(pbft.selectLeader(1)).isEqualTo(leaderView1);
        
        // Over many views, multiple nodes should be chosen (distribution)
        java.util.Set<String> chosen = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) chosen.add(pbft.selectLeader(i));
        assertThat(chosen.size()).as("Multiple nodes should be picked over 20 views").isGreaterThan(1);
    }

    @Test
    @DisplayName("P1.4b — High-threat validator excluded from leader selection")
    void testThreatScoreAffectsLeaderSelection() {
        String risky = validators.keySet().iterator().next();
        com.hybrid.blockchain.ai.PredictiveThreatScorer scorer = com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance();

        for (int i = 0; i < 8; i++) {
            scorer.recordActivity(risky, -0.5, i);
        }
        assertThat(scorer.predictThreatScore(risky)).isGreaterThan(0.7);

        for (long view = 1; view <= 200; view++) {
            assertThat(pbft.selectLeader(view)).isNotEqualTo(risky);
        }
    }

    @Test
    @DisplayName("P1.5-1.6 — Prepare vote quorum")
    void testPrepareQuorum() {
        String hash = "block_hash";
        long seq = 1;
        
        // Add valid signatures from 3 validators (2f + 1 = 3)
        for (int i = 1; i <= 3; i++) {
            TestKeyPair kp = new TestKeyPair(i);
            PBFTConsensus.PBFTMessage msg = new PBFTConsensus.PBFTMessage(
                    PBFTConsensus.Phase.PREPARE, 0, seq, hash, kp.getAddress());
            msg.sign(kp.getPrivateKey());
            
            pbft.addPrepareVote(seq, hash, kp.getAddress(), msg.signature);
        }
        
        assertThat(pbft.hasQuorum(seq, PBFTConsensus.Phase.PREPARE)).isTrue();
        
        // P1.5: invalid signature check
        TestKeyPair intruder = new TestKeyPair(999);
        assertThatThrownBy(() -> pbft.addPrepareVote(seq, hash, intruder.getAddress(), new byte[64]))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("P1.7-1.8 — Commit vote and marking")
    void testCommitQuorum() {
        String hash = "hash";
        long seq = 1;
        
        for (int i = 1; i <= 3; i++) {
            TestKeyPair kp = new TestKeyPair(i);
            PBFTConsensus.PBFTMessage msg = new PBFTConsensus.PBFTMessage(
                    PBFTConsensus.Phase.COMMIT, 0, seq, hash, kp.getAddress());
            msg.sign(kp.getPrivateKey());
            pbft.addCommitVote(seq, hash, kp.getAddress(), msg.signature);
        }
        
        // Mark committed (idempotent)
        pbft.markCommitted(hash, seq, leaderKey.getAddress());
        pbft.markCommitted(hash, seq, leaderKey.getAddress());
        
        // Success if no exception and logic flows
    }

    @Test
    @DisplayName("P1.9-1.11 — validateBlock consistency")
    void testValidateBlock() {
        Block block = new Block(1, System.currentTimeMillis(), new ArrayList<>(), "prev", 1, "state");
        block.setValidatorId(leaderKey.getAddress());
        byte[] sig = Crypto.sign(Crypto.hash(block.serializeCanonical()), leaderKey.getPrivateKey());
        block.setSignature(sig);
        
        // P1.11: Valid
        assertThat(pbft.validateBlock(block, new ArrayList<>())).isTrue();
        
        // P1.10: Invalid signature
        block.setSignature(new byte[64]);
        assertThat(pbft.validateBlock(block, new ArrayList<>())).isFalse();
        
        // P1.9: Wrong leader
        block.setValidatorId(new TestKeyPair(2).getAddress());
        block.setSignature(sig); // Valid sig for wrong sender
        assertThat(pbft.validateBlock(block, new ArrayList<>())).isFalse();
    }

    @Test
    @DisplayName("P1.12 — View change timeout")
    void testViewChangeTimer() {
        pbft.setTimeout(100);
        long initialView = pbft.getViewNumber();
        long nextView = initialView + 1;
        
        // Timeout will call triggerViewChange, adding local vote.
        // We need 2 more votes for quorum in 4-node cluster (f=1, 2f+1=3).
        for (int i = 2; i <= 3; i++) {
            TestKeyPair kp = new TestKeyPair(i);
            PBFTConsensus.PBFTMessage msg = new PBFTConsensus.PBFTMessage(
                PBFTConsensus.Phase.VIEW_CHANGE, nextView, 0, "VIEW_CHANGE_PROOF", kp.getAddress());
            msg.sign(kp.getPrivateKey());
            pbft.addViewChangeVote(nextView, 0, kp.getAddress(), msg.signature);
        }
        
        await().atMost(Duration.ofSeconds(5)).until(() -> pbft.getViewNumber() > initialView);
    }

    @Test
    @DisplayName("P1.13 — View change quorum")
    void testViewChangeQuorum() {
        long nextView = pbft.getViewNumber() + 1;
        // 3 votes required
        for (int i = 1; i <= 3; i++) {
            TestKeyPair kp = new TestKeyPair(i);
            PBFTConsensus.PBFTMessage msgChange = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.VIEW_CHANGE, nextView, 0, "VIEW_CHANGE_PROOF", kp.getAddress());
            msgChange.sign(kp.getPrivateKey());
            pbft.addViewChangeVote(nextView, 0, kp.getAddress(), msgChange.signature);
        }
        
        assertThat(pbft.getViewNumber()).isEqualTo(nextView);
    }

    @Test
    @DisplayName("P1.14 — Double-signing slashing")
    void testSlashing() {
        String hashA = "hashA";
        String hashB = "hashB";
        long seq = 1;
        
        TestKeyPair validator = new TestKeyPair(2);
        
        PBFTConsensus.PBFTMessage msgA = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.PREPARE, 0, seq, hashA, validator.getAddress());
        msgA.sign(validator.getPrivateKey());
        pbft.addPrepareVote(seq, hashA, validator.getAddress(), msgA.signature);
        
        PBFTConsensus.PBFTMessage msgB = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.PREPARE, 0, seq, hashB, validator.getAddress());
        msgB.sign(validator.getPrivateKey());
        pbft.addPrepareVote(seq, hashB, validator.getAddress(), msgB.signature);
        
        assertThat(pbft.getSlashedValidators()).contains(validator.getAddress());
    }

    @Test
    @DisplayName("P1.15-1.16 — Reputation updates")
    void testReputationUpdates() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
             PBFTConsensus p = tb.getConsensus();
             String leader = p.getCurrentLeader();
             double repBefore = tb.getConsensus().getReputationMap().getOrDefault(leader, 100.0);
             
             // P1.15: Success
             BlockApplier.createAndApplyBlock(tb, List.of());
             assertThat(tb.getConsensus().getReputationMap().getOrDefault(leader, 100.0)).isGreaterThan(repBefore);
             
             // P1.16: View Change (Missed slot)
             double repAfterSuccess = tb.getConsensus().getReputationMap().getOrDefault(leader, 100.0);
             p.triggerViewChange();
             // Manually advance view to trigger penalty calculation logic if tied to consensus state
             for(int i=1; i<=3; i++) {
                 long nextV = p.getViewNumber() + 1;
                 PBFTConsensus.PBFTMessage msgChange = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.VIEW_CHANGE, nextV, 0, "VIEW_CHANGE_PROOF", new TestKeyPair(i).getAddress());
                 msgChange.sign(new TestKeyPair(i).getPrivateKey());
                 p.addViewChangeVote(nextV, 0, new TestKeyPair(i).getAddress(), msgChange.signature);
             }
             
             assertThat(tb.getConsensus().getReputationMap().getOrDefault(leader, 100.0)).as("Reputation should decrease after missed slot")
                     .isLessThan(repAfterSuccess);
        }
    }

    @Test
    @DisplayName("P1.20 — Concurrent vote adding")
    void testConcurrentVotes() throws InterruptedException {
        String hash = "concurrent";
        long seq = 10;
        ExecutorService service = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        
        for (int i = 1; i <= 4; i++) {
            final int id = i;
            service.submit(() -> {
                try {
                    TestKeyPair kp = new TestKeyPair(id);
                    PBFTConsensus.PBFTMessage msg = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.PREPARE, 0, seq, hash, kp.getAddress());
                    msg.sign(kp.getPrivateKey());
                    pbft.addPrepareVote(seq, hash, kp.getAddress(), msg.signature);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(5, TimeUnit.SECONDS);
        service.shutdown();
        assertThat(pbft.hasQuorum(seq, PBFTConsensus.Phase.PREPARE)).isTrue();
    }
}
