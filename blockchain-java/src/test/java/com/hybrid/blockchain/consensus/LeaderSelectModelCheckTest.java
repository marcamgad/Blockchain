package com.hybrid.blockchain.consensus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive bounded state-space enumeration of the leader-selection model in
 * {@code docs/formal/LeaderSelect.tla}.
 *
 * <p><b>What this is:</b> a hand-rolled exhaustive explorer over exactly the same finite
 * model the TLA+ spec defines (scaled-integer reputation, fraction quantised to
 * {@code kk/Denom}, penalty events bounded by {@code MaxSteps}). It enumerates every
 * reachable state and checks the LeaderAgreement invariant at every one.
 *
 * <p><b>What this is NOT:</b> it is not TLC/Apalache. It is an independent implementation
 * of the same semantics, so it corroborates the spec rather than verifying the spec
 * itself. A bounded no-violation result is evidence, not proof of unbounded correctness.
 *
 * <p>Mirrors {@code docs/formal/pbft_leader_model.md} §3 (selection) and §4 (property).
 */
public class LeaderSelectModelCheckTest {

    // ── Model parameters (kept in sync with LeaderSelect_*.cfg) ──────────────
    private static final int NODES     = 2;
    private static final int INIT_REP  = 100;  // 1.00 scaled
    private static final int PENALTY   = 10;   // REP_MISSED_SLOT = -0.10 scaled

    /** A model state: rep[node][validator], scaled integers. */
    private static final class State {
        final int[][] rep;
        final int steps;
        State(int[][] rep, int steps) { this.rep = rep; this.steps = steps; }

        String key() {
            StringBuilder sb = new StringBuilder();
            for (int[] row : rep) { for (int r : row) sb.append(r).append(','); sb.append('|'); }
            return sb.toString();
        }
        State copy(int newSteps) {
            int[][] c = new int[rep.length][];
            for (int i = 0; i < rep.length; i++) c[i] = rep[i].clone();
            return new State(c, newSteps);
        }
    }

    /** selectLeader abstraction: first index whose cumulative weight reaches kk/Denom of total. */
    private static int leader(int[] weights, int kk, int denom) {
        long total = 0;
        for (int w : weights) total += w;
        long cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (cumulative * (long) denom >= (long) kk * total) return i;
        }
        return weights.length - 1;
    }

    /** LeaderAgreement (Def 4.1): all nodes agree for every fraction bucket. */
    private static Integer firstDisagreeingBucket(State s, int denom) {
        for (int kk = 0; kk < denom; kk++) {
            int ref = leader(s.rep[0], kk, denom);
            for (int n = 1; n < s.rep.length; n++) {
                if (leader(s.rep[n], kk, denom) != ref) return kk;
            }
        }
        return null;
    }

    /** Exhaustive BFS over reachable states. Returns first violating state, or null. */
    private static State explore(boolean broken, int numVals, int maxSteps, int denom, int[] visitedOut) {
        int[][] init = new int[NODES][numVals];
        for (int[] row : init) java.util.Arrays.fill(row, INIT_REP);
        State start = new State(init, 0);

        Set<String> seen = new HashSet<>();
        Deque<State> queue = new ArrayDeque<>();
        seen.add(start.key());
        queue.add(start);

        State violation = null;
        while (!queue.isEmpty()) {
            State cur = queue.poll();
            if (firstDisagreeingBucket(cur, denom) != null && violation == null) {
                violation = cur; // record but keep exploring to report full state count
            }
            if (cur.steps >= maxSteps) continue;

            List<State> succs = new ArrayList<>();
            if (broken) {
                // LocalPenalty(n, i): one node penalises on its own.
                for (int n = 0; n < NODES; n++) {
                    for (int i = 0; i < numVals; i++) {
                        if (cur.rep[n][i] > PENALTY) {
                            State nx = cur.copy(cur.steps + 1);
                            nx.rep[n][i] -= PENALTY;
                            succs.add(nx);
                        }
                    }
                }
            } else {
                // CommitPenalty(i): every node applies it atomically.
                for (int i = 0; i < numVals; i++) {
                    boolean ok = true;
                    for (int n = 0; n < NODES; n++) if (cur.rep[n][i] <= PENALTY) ok = false;
                    if (ok) {
                        State nx = cur.copy(cur.steps + 1);
                        for (int n = 0; n < NODES; n++) nx.rep[n][i] -= PENALTY;
                        succs.add(nx);
                    }
                }
            }
            for (State nx : succs) {
                if (seen.add(nx.key())) queue.add(nx);
            }
        }
        visitedOut[0] = seen.size();
        return violation;
    }

    @Test
    @DisplayName("Model check BROKEN (local per-node penalty): invariant MUST be violated")
    void brokenModelViolatesLeaderAgreement() {
        int[] visited = new int[1];
        State v = explore(true, 4, 2, 40, visited);

        System.out.printf("[MODEL-CHECK] Broken  N=4 maxSteps=2 denom=40 : states=%d violation=%s%n",
                visited[0], v != null);
        if (v != null) {
            System.out.printf("[MODEL-CHECK]   counterexample rep p1=%s p2=%s (bucket kk=%d)%n",
                    java.util.Arrays.toString(v.rep[0]),
                    java.util.Arrays.toString(v.rep[1]),
                    firstDisagreeingBucket(v, 40));
        }

        assertThat(v)
                .as("Theorem 1: the broken model must exhibit a LeaderAgreement violation")
                .isNotNull();
    }

    @Test
    @DisplayName("Model check FIXED (consensus-ordered penalty): no violation in bounded space")
    void fixedModelPreservesLeaderAgreement() {
        int[] visited = new int[1];
        State v = explore(false, 4, 3, 40, visited);

        System.out.printf("[MODEL-CHECK] Fixed   N=4 maxSteps=3 denom=40 : states=%d violation=%s%n",
                visited[0], v != null);

        assertThat(v)
                .as("Theorem 2: consensus-ordered updates must preserve LeaderAgreement")
                .isNull();
    }

    @Test
    @DisplayName("Fixed model holds at larger bounds (N=7, deeper, finer fraction resolution)")
    void fixedModelHoldsAtLargerBounds() {
        int[] visited = new int[1];
        State v = explore(false, 7, 4, 100, visited);

        System.out.printf("[MODEL-CHECK] Fixed   N=7 maxSteps=4 denom=100: states=%d violation=%s%n",
                visited[0], v != null);

        assertThat(v).isNull();
    }

    @Test
    @DisplayName("Broken model violates at N=7 too (defect is not an artifact of N=4)")
    void brokenModelViolatesAtN7() {
        int[] visited = new int[1];
        State v = explore(true, 7, 2, 100, visited);

        System.out.printf("[MODEL-CHECK] Broken  N=7 maxSteps=2 denom=100: states=%d violation=%s%n",
                visited[0], v != null);

        assertThat(v).isNotNull();
    }
}
