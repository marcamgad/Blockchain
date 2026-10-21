package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-082] Found by the live leader-crash scenario: after the leader was killed, the three
 * survivors all reached view 3 but disagreed on its leader, and the chain stalled for good.
 * Leader election used node-local reputation, which diverged because nodes took different
 * view-change paths, credited proposers only on their own commit path, and penalised double
 * votes only where they were observed.
 */
class LeaderAgreementTest {

    private final TestKeyPair[] k = {new TestKeyPair(1), new TestKeyPair(2), new TestKeyPair(3), new TestKeyPair(4)};

    private PBFTConsensus node(int self) {
        Map<String, byte[]> set = new HashMap<>();
        for (TestKeyPair t : k) set.put(t.getAddress(), t.getPublicKey());
        PBFTConsensus c = new PBFTConsensus(set, k[self].getAddress(), k[self].getPrivateKey());
        c.setTimeout(600_000);
        return c;
    }

    private void viewChangeQuorum(PBFTConsensus c, long newView) {
        for (int i = 1; i <= 3; i++) {
            PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(
                    PBFTConsensus.Phase.VIEW_CHANGE, newView, 0, "VIEW_CHANGE_PROOF", k[i].getAddress());
            m.sign(k[i].getPrivateKey());
            c.addViewChangeVote(newView, 0, k[i].getAddress(), m.signature);
        }
    }

    @Test
    @DisplayName("nodes that reach the same view by different paths elect the same leader")
    void differentPathsSameLeader() {
        PBFTConsensus stepwise = node(0), jumper = node(0);
        try {
            for (long v = 1; v <= 3; v++) viewChangeQuorum(stepwise, v);   // 0 -> 1 -> 2 -> 3
            viewChangeQuorum(jumper, 3);                                    // 0 -> 3 directly
            assertThat(stepwise.getViewNumber()).isEqualTo(3);
            assertThat(jumper.getViewNumber()).isEqualTo(3);
            assertThat(stepwise.getCurrentLeader()).isEqualTo(jumper.getCurrentLeader());
        } finally {
            stepwise.shutdown();
            jumper.shutdown();
        }
    }

    @Test
    @DisplayName("locally observed events (own commits, observed double votes) do not change the leader schedule")
    void localEventsDoNotMoveTheSchedule() {
        PBFTConsensus a = node(0), b = node(1);
        try {
            a.markCommitted("aa".repeat(32), 1, k[2].getAddress());          // only a saw this commit
            a.updateReputation(k[3].getAddress(), PBFTConsensus.REP_INVALID_BLOCK);   // only a saw a double vote
            for (long v = 0; v < 12; v++) assertThat(a.selectLeader(v)).as("view " + v).isEqualTo(b.selectLeader(v));
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }

    @Test
    @DisplayName("every non-jailed validator leads in turn; a jailed validator never leads")
    void roundRobinWithoutJailed() {
        PBFTConsensus c = node(0);
        try {
            java.util.Set<String> leaders = new java.util.HashSet<>();
            for (long v = 0; v < 4; v++) leaders.add(c.selectLeader(v));
            assertThat(leaders).hasSize(4);
            c.setJailedValidators(List.of(k[2].getAddress()));
            for (long v = 0; v < 9; v++) assertThat(c.selectLeader(v)).isNotEqualTo(k[2].getAddress());
        } finally {
            c.shutdown();
        }
    }
}
