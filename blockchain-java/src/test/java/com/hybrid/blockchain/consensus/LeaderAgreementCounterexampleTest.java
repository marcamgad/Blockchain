package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable corroboration of Theorem 1 in {@code docs/formal/pbft_leader_model.md}.
 *
 * <p>Theorem 1 claims: if a locally-observed event (here, a local view-change timeout
 * penalty) updates reputation, two CORRECT validators can compute different leaders for
 * the same view — with no Byzantine node, no clock drift, no message loss.
 *
 * <p>Two {@link PBFTConsensus} instances model two correct validators p1 and p2 holding
 * the same validator set. Only p1's local timer has fired, so only p1 applied
 * {@code REP_MISSED_SLOT} to the leader. We then scan views and count disagreement.
 *
 * <p>Corollary 6.1 predicts a divergence rate of ≈1.92% of views for N=4 with a single
 * penalty. This test measures the actual rate against the real implementation.
 */
public class LeaderAgreementCounterexampleTest {

    private PBFTConsensus p1, p2;
    private List<String> sortedIds;

    @BeforeEach
    void setup() {
        // Keep the shared threat-scorer singleton out of the experiment: Theorem 1's
        // construction uses only the reputation channel.
        com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().reset();

        Map<String, byte[]> validators = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            TestKeyPair kp = new TestKeyPair(9100 + i);
            validators.put(kp.getAddress(), kp.getPublicKey());
            ids.add(kp.getAddress());
        }
        Collections.sort(ids);
        this.sortedIds = ids;

        TestKeyPair local = new TestKeyPair(9100);
        p1 = new PBFTConsensus(validators, local.getAddress(), local.getPrivateKey());
        p2 = new PBFTConsensus(validators, local.getAddress(), local.getPrivateKey());
    }

    @AfterEach
    void teardown() {
        if (p1 != null) p1.shutdown();
        if (p2 != null) p2.shutdown();
        com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().reset();
    }

    @Test
    @DisplayName("HC-082: a purely local penalty no longer splits the leader choice (Theorem 1 described the old weighted scheme)")
    void localTimeoutBreaksLeaderAgreement() {
        // Sanity: with identical state the two validators agree everywhere.
        for (long v = 0; v < 2000; v++) {
            assertThat(p1.selectLeader(v))
                    .as("baseline: identical reputation must give identical leaders at view %d", v)
                    .isEqualTo(p2.selectLeader(v));
        }

        // p1's local timer fires; p2's does not. Only p1 penalises the leader.
        // (This is exactly what triggerViewChange() does at PBFTConsensus.java:643.)
        String penalised = sortedIds.get(0);
        p1.updateReputation(penalised, PBFTConsensus.REP_MISSED_SLOT);

        int views = 200_000;
        int divergent = 0;
        Long firstDivergentView = null;
        for (long v = 0; v < views; v++) {
            String a = p1.selectLeader(v);
            String b = p2.selectLeader(v);
            if (a != null && !a.equals(b)) {
                divergent++;
                if (firstDivergentView == null) firstDivergentView = v;
            }
        }

        double rate = 100.0 * divergent / views;
        double predicted = predictedDivergencePct(4, -PBFTConsensus.REP_MISSED_SLOT);
        System.out.printf("[THEOREM-1] N=4 divergent views: %d/%d = %.4f%%  (first at v=%s)%n",
                divergent, views, rate, firstDivergentView);
        System.out.printf("[THEOREM-1] N=4 Corollary 6.1 (corrected) prediction: %.4f%%%n", predicted);

        // [HC-082] Theorem 1 proved that the reputation-WEIGHTED election let a local penalty split
        // the leader choice (Corollary 6.1 predicted ~4.3% of views for N=4), and the first live
        // fault-injection run hit exactly that: a permanent stall after a leader crash. The
        // election is now round-robin and ignores reputation, so the split rate must be zero.
        assertThat(divergent)
                .as("round-robin election: a locally-observed penalty must not split the leader choice")
                .isZero();
    }

    /** Corollary 6.1 (corrected): Pr[divergence] = δ(N−1) / (2(N−δ)), as a percentage. */
    private static double predictedDivergencePct(int n, double delta) {
        return 100.0 * (delta * (n - 1)) / (2.0 * (n - delta));
    }

    @Test
    @DisplayName("HC-082: no split for N=7 either (Corollary 6.1 applied to the old weighted scheme)")
    void divergenceRateClosedFormGeneralises() {
        com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().reset();
        Map<String, byte[]> validators = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            TestKeyPair kp = new TestKeyPair(9200 + i);
            validators.put(kp.getAddress(), kp.getPublicKey());
            ids.add(kp.getAddress());
        }
        Collections.sort(ids);
        TestKeyPair local = new TestKeyPair(9200);
        PBFTConsensus q1 = new PBFTConsensus(validators, local.getAddress(), local.getPrivateKey());
        PBFTConsensus q2 = new PBFTConsensus(validators, local.getAddress(), local.getPrivateKey());
        try {
            q1.updateReputation(ids.get(0), PBFTConsensus.REP_MISSED_SLOT);

            int views = 200_000, divergent = 0;
            for (long v = 0; v < views; v++) {
                String a = q1.selectLeader(v);
                String b = q2.selectLeader(v);
                if (a != null && !a.equals(b)) divergent++;
            }
            double rate = 100.0 * divergent / views;
            double predicted = predictedDivergencePct(7, -PBFTConsensus.REP_MISSED_SLOT);
            System.out.printf("[THEOREM-1] N=7 measured %.4f%%  predicted %.4f%%%n", rate, predicted);

            assertThat(divergent)
                    .as("round-robin election, N=7: a local penalty must not split the leader choice "
                            + "(the old weighted scheme split %.4f%% of views)", predicted)
                    .isZero();
        } finally {
            q1.shutdown();
            q2.shutdown();
            com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().reset();
        }
    }

    @Test
    @DisplayName("Theorem 2 (C1): with no local-event penalty, the two validators never disagree")
    void consensusOrderedOnlyKeepsAgreement() {
        // Both validators apply the SAME consensus-ordered event (a commit credit).
        String proposer = sortedIds.get(1);
        p1.updateReputation(proposer, PBFTConsensus.REP_BLOCK_PROPOSED);
        p2.updateReputation(proposer, PBFTConsensus.REP_BLOCK_PROPOSED);

        int divergent = 0;
        for (long v = 0; v < 200_000; v++) {
            String a = p1.selectLeader(v);
            String b = p2.selectLeader(v);
            if (a != null && !a.equals(b)) divergent++;
        }
        assertThat(divergent)
                .as("identical consensus-ordered event sets must yield identical leaders")
                .isZero();
    }
}
