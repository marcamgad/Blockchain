package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * [BENCHMARK] Phase 1 step 6 — leader divergence across correct replicas.
 *
 * <p>Measures the quantity Theorem 1 predicts: how often two correct replicas compute
 * DIFFERENT leaders for the same view, as a function of timeout skew.
 *
 * <h3>Method</h3>
 * Each replica is an independent {@link PBFTConsensus} instance over the same validator
 * set. Per simulated round:
 * <ol>
 *   <li>A <b>consensus-ordered</b> event (block commit credit) is applied at
 *       <i>every</i> replica — this models a committed event and must not cause divergence.</li>
 *   <li>With probability {@code pTimeout} a random strict subset of replicas suffers a
 *       <b>local</b> view-change timeout and penalises its own view of the leader —
 *       exactly what {@code triggerViewChange()} does from the local timer.</li>
 *   <li>All replicas then compute the next view's leader; the round counts as divergent
 *       if they do not all agree.</li>
 * </ol>
 *
 * <h3>Honest limitations</h3>
 * <ul>
 *   <li>This is a <b>controlled simulation</b>, not a live networked deployment. It
 *       isolates the reputation channel; it does not measure real network throughput.</li>
 *   <li>{@code PredictiveThreatScorer} is a process-wide singleton, so all simulated
 *       replicas share one threat model. In a real deployment each node has its own,
 *       adding a second independent divergence channel (Remark 6.3). The numbers here are
 *       therefore a <b>lower bound</b> on real divergence.</li>
 *   <li>Fixed RNG seed for reproducibility.</li>
 * </ul>
 *
 * <p>Raw results are written to {@code docs/benchmarks/}.
 */
@Tag("benchmark")
public class LeaderDivergenceBenchmark {

    private static final long SEED   = 20260719L;
    private static final int  ROUNDS = 5_000;
    private static final int[] NODE_COUNTS = {4, 7};
    private static final double[] TIMEOUT_RATES = {0.0, 0.01, 0.05, 0.10, 0.25};

    /** Set by the build under test; recorded in the output so runs are comparable. */
    private static final String VARIANT = System.getProperty("bench.variant", "pre-fix");

    private static final class Replica {
        final PBFTConsensus consensus;
        Replica(Map<String, byte[]> vals, TestKeyPair local) {
            this.consensus = new PBFTConsensus(vals, local.getAddress(), local.getPrivateKey());
        }
    }

    @Test
    @DisplayName("[BENCHMARK] leader divergence vs timeout skew")
    void measureDivergence() throws IOException {
        StringBuilder csv = new StringBuilder("variant,nodes,timeout_rate,rounds,divergent_rounds,divergence_pct\n");
        StringBuilder human = new StringBuilder();
        human.append("Phase 1 step 6 — leader divergence benchmark\n")
             .append("variant=").append(VARIANT)
             .append("  seed=").append(SEED)
             .append("  rounds=").append(ROUNDS).append("\n\n")
             .append(String.format("%-6s %-14s %-18s %s%n", "nodes", "timeout_rate", "divergent_rounds", "divergence_%"));

        for (int n : NODE_COUNTS) {
            for (double rate : TIMEOUT_RATES) {
                int divergent = runOne(n, rate);
                double pct = 100.0 * divergent / ROUNDS;
                csv.append(String.format("%s,%d,%.2f,%d,%d,%.4f%n", VARIANT, n, rate, ROUNDS, divergent, pct));
                human.append(String.format("%-6d %-14.2f %-18d %.4f%n", n, rate, divergent, pct));
                System.out.printf("[BENCH:%s] N=%d timeout_rate=%.2f -> divergent %d/%d = %.4f%%%n",
                        VARIANT, n, rate, divergent, ROUNDS, pct);
            }
        }

        long opsPerSec = measureSelectLeaderThroughput();
        csv.append(String.format("%s,throughput,,,%d,selectLeader_ops_per_sec%n", VARIANT, opsPerSec));
        human.append("\nselectLeader throughput: ").append(opsPerSec).append(" ops/sec\n");
        System.out.printf("[BENCH:%s] selectLeader throughput: %d ops/sec%n", VARIANT, opsPerSec);

        Path dir = Paths.get("docs", "benchmarks");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("phase1_leader_divergence_" + VARIANT + ".csv"), csv.toString());
        Files.writeString(dir.resolve("phase1_leader_divergence_" + VARIANT + ".txt"), human.toString());
        System.out.println("[BENCH] raw results written to docs/benchmarks/");
    }

    /** One (nodeCount, timeoutRate) cell. Returns number of rounds with disagreement. */
    private int runOne(int nodeCount, double timeoutRate) {
        com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().reset();

        Map<String, byte[]> vals = new HashMap<>();
        List<TestKeyPair> kps = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            TestKeyPair kp = new TestKeyPair(41000 + nodeCount * 100 + i);
            kps.add(kp);
            vals.put(kp.getAddress(), kp.getPublicKey());
        }

        List<Replica> replicas = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) replicas.add(new Replica(vals, kps.get(i)));

        Random rng = new Random(SEED + nodeCount * 31L + (long) (timeoutRate * 1000));
        int divergent = 0;
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                long view = round;

                // (1) consensus-ordered: every replica credits the same committed proposer.
                String proposer = replicas.get(0).consensus.selectLeader(view);
                if (proposer != null) {
                    for (Replica r : replicas) {
                        r.consensus.updateReputation(proposer, PBFTConsensus.REP_BLOCK_PROPOSED);
                    }
                }

                // (2) locally-observed: a strict subset of replicas suffers a local
                // view-change timeout. We invoke the PRODUCTION path triggerViewChange()
                // — NOT updateReputation() directly — because the whole question is
                // whether that path applies a unilateral penalty. Calling
                // updateReputation() here would bypass the code E1 changes and make the
                // benchmark blind to the fix.
                if (rng.nextDouble() < timeoutRate) {
                    int howMany = 1 + rng.nextInt(Math.max(1, nodeCount - 1)); // strict subset
                    Set<Integer> chosen = new HashSet<>();
                    while (chosen.size() < howMany) chosen.add(rng.nextInt(nodeCount));
                    for (int idx : chosen) {
                        replicas.get(idx).consensus.triggerViewChange();
                    }
                }

                // (3) do all correct replicas agree on the next leader?
                Set<String> leaders = new HashSet<>();
                for (Replica r : replicas) leaders.add(String.valueOf(r.consensus.selectLeader(view + 1)));
                if (leaders.size() > 1) divergent++;
            }
        } finally {
            for (Replica r : replicas) r.consensus.shutdown();
            com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().reset();
        }
        return divergent;
    }

    /** Cost of the selection path itself, so the fix's overhead can be compared. */
    private long measureSelectLeaderThroughput() {
        Map<String, byte[]> vals = new HashMap<>();
        List<TestKeyPair> kps = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            TestKeyPair kp = new TestKeyPair(43000 + i);
            kps.add(kp);
            vals.put(kp.getAddress(), kp.getPublicKey());
        }
        PBFTConsensus c = new PBFTConsensus(vals, kps.get(0).getAddress(), kps.get(0).getPrivateKey());
        try {
            for (int i = 0; i < 50_000; i++) c.selectLeader(i);      // warm up JIT
            long t0 = System.nanoTime();
            int iters = 500_000;
            for (int i = 0; i < iters; i++) c.selectLeader(i);
            long elapsed = System.nanoTime() - t0;
            return (long) (iters / (elapsed / 1_000_000_000.0));
        } finally {
            c.shutdown();
        }
    }
}
