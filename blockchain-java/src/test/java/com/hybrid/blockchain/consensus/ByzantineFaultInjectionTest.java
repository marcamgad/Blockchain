package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 step 7 — randomized fault injection against PBFT consensus.
 *
 * <p><b>Approach (adapted, not built from scratch).</b> Phase 0 identified ByzzFuzz
 * (Winter et al., "Randomized Testing of Byzantine Fault Tolerant Algorithms") as the
 * model to follow: inject <i>randomly generated network and process faults</i> into
 * executions and check protocol invariants. ByzzFuzz itself targets other codebases and
 * runtimes, so what is adapted here is its <b>method</b>, not its code:
 * <ul>
 *   <li><b>Network faults:</b> message drop, partition into two groups, delay/reorder,
 *       duplication.</li>
 *   <li><b>Process faults:</b> node pause (delivers nothing) and later resume.</li>
 *   <li><b>Small-scope mutation:</b> a Byzantine node equivocates — sends a different
 *       block hash to different peers for the same sequence.</li>
 * </ul>
 *
 * <p>The injection seam is {@link PBFTConsensus.PBFTMessenger}, so real consensus code
 * runs; only message delivery is controlled. Every run is seeded and printable, so a
 * failure is reproducible.
 *
 * <h3>Properties checked</h3>
 * <ul>
 *   <li><b>SAFETY:</b> no two correct nodes commit different block hashes at the same
 *       sequence number. This must hold under <i>every</i> fault schedule.</li>
 *   <li><b>LIVENESS:</b> once faults are healed, the cluster can still reach a commit
 *       quorum (it is not permanently wedged).</li>
 * </ul>
 *
 * <h3>Honest scope</h3>
 * This drives the consensus layer in-process. It does <b>not</b> exercise sockets, TLS,
 * disk persistence, or {@code PeerNode.applyBlockAtSequence}. A failure here is a real
 * consensus bug; a pass here does <b>not</b> clear the networking or storage layers.
 */
public class ByzantineFaultInjectionTest {

    // ── controllable in-process network ──────────────────────────────────────

    /** One delivered message. */
    private record Msg(String from, String to, PBFTConsensus.Phase phase,
                       long seq, String blockHash, byte[] sig) {}

    /**
     * Routes consensus messages between in-process replicas, applying injected faults.
     */
    private static final class FaultyNetwork {
        final Map<String, PBFTConsensus> nodes = new LinkedHashMap<>();
        final Map<String, TestKeyPair> keys = new LinkedHashMap<>();
        final Set<String> paused = new HashSet<>();
        /** partition[x] != partition[y] => messages between x and y are dropped. */
        final Map<String, Integer> partition = new HashMap<>();
        final List<Msg> deferred = new ArrayList<>();
        final Random rng;
        double dropRate = 0.0;
        double delayRate = 0.0;
        double dupRate = 0.0;

        FaultyNetwork(Random rng) { this.rng = rng; }

        boolean canDeliver(String from, String to) {
            if (paused.contains(from) || paused.contains(to)) return false;
            Integer a = partition.get(from), b = partition.get(to);
            if (a != null && b != null && !a.equals(b)) return false;
            return true;
        }

        void send(Msg m) {
            if (!canDeliver(m.from(), m.to())) return;            // partition / pause
            if (rng.nextDouble() < dropRate) return;               // random drop
            if (rng.nextDouble() < delayRate) { deferred.add(m); return; } // delay
            deliver(m);
            if (rng.nextDouble() < dupRate) deliver(m);            // duplicate
        }

        void deliver(Msg m) {
            PBFTConsensus target = nodes.get(m.to());
            if (target == null) return;
            try {
                if (m.phase() == PBFTConsensus.Phase.PREPARE) {
                    target.addPrepareVote(m.seq(), m.blockHash(), m.from(), m.sig());
                } else if (m.phase() == PBFTConsensus.Phase.COMMIT) {
                    target.addCommitVote(m.seq(), m.blockHash(), m.from(), m.sig());
                }
            } catch (RuntimeException expected) {
                // Consensus legitimately rejects bad/duplicate/equivocating votes.
                // That is the protocol defending itself, not a harness failure.
            }
        }

        /** Heal all faults and flush anything that was delayed. */
        void healAndFlush() {
            paused.clear();
            partition.clear();
            dropRate = delayRate = dupRate = 0.0;
            List<Msg> pending = new ArrayList<>(deferred);
            deferred.clear();
            for (Msg m : pending) if (canDeliver(m.from(), m.to())) deliver(m);
        }
    }

    /** Builds a signed vote from {@code from} for (phase, view, seq, hash). */
    private static byte[] sign(FaultyNetwork net, String from, PBFTConsensus.Phase phase,
                               long view, long seq, String hash) {
        PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(phase, view, seq, hash, from);
        m.sign(net.keys.get(from).getPrivateKey());
        return m.signature;
    }

    private static FaultyNetwork cluster(int n, long seed) {
        FaultyNetwork net = new FaultyNetwork(new Random(seed));
        Map<String, byte[]> validators = new LinkedHashMap<>();
        List<TestKeyPair> kps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TestKeyPair kp = new TestKeyPair(70000 + i);
            kps.add(kp);
            validators.put(kp.getAddress(), kp.getPublicKey());
        }
        for (int i = 0; i < n; i++) {
            TestKeyPair kp = kps.get(i);
            net.keys.put(kp.getAddress(), kp);
            net.nodes.put(kp.getAddress(), new PBFTConsensus(validators, kp.getAddress(), kp.getPrivateKey()));
        }
        return net;
    }

    private static void shutdown(FaultyNetwork net) {
        net.nodes.values().forEach(PBFTConsensus::shutdown);
    }

    /** Broadcast a phase vote for `hash` from every non-paused replica. */
    private static void broadcast(FaultyNetwork net, PBFTConsensus.Phase phase,
                                  long view, long seq, String hash) {
        for (String from : net.nodes.keySet()) {
            byte[] sig = sign(net, from, phase, view, seq, hash);
            for (String to : net.nodes.keySet()) {
                net.send(new Msg(from, to, phase, seq, hash, sig));
            }
        }
    }

    // ── SAFETY ───────────────────────────────────────────────────────────────

    /**
     * [FINDING S7-01] A quorum must certify ONE value.
     *
     * <p>Classical PBFT requires 2f+1 COMMIT messages for the same (view, seq, digest).
     * If {@code hasQuorum} merely counts distinct voters regardless of which block they
     * voted for, then 2f+1 validators voting for 2f+1 DIFFERENT blocks satisfies it — and
     * {@code PeerNode.applyBlockAtSequence} then applies whatever block sits in
     * {@code pendingBlocks[seq]}, which may have had no votes at all.
     *
     * <p>This is a deterministic construction, not a fuzz run: three distinct validators
     * commit three distinct hashes at the same sequence.
     */
    @Test
    @DisplayName("[S7-01] SAFETY: a COMMIT quorum must be for a SINGLE block hash")
    void quorumMustBeHashBound() {
        FaultyNetwork net = cluster(4, 999);
        try {
            long seq = 42;
            List<String> ids = new ArrayList<>(net.nodes.keySet());
            PBFTConsensus victim = net.nodes.get(ids.get(0));

            // Three DIFFERENT validators, three DIFFERENT block hashes, same sequence.
            for (int i = 0; i < 3; i++) {
                String voter = ids.get(i);
                String distinctHash = "HASH_" + i;
                victim.addCommitVote(seq, distinctHash, voter,
                        sign(net, voter, PBFTConsensus.Phase.COMMIT, 0, seq, distinctHash));
            }

            String quorumHash = victim.getQuorumHash(seq, PBFTConsensus.Phase.COMMIT);
            System.out.printf("[S7-01] 3 voters / 3 different hashes -> hasQuorum=%s quorumHash=%s%n",
                    victim.hasQuorum(seq, PBFTConsensus.Phase.COMMIT), quorumHash);

            assertThat(victim.hasQuorum(seq, PBFTConsensus.Phase.COMMIT))
                    .as("2f+1 votes for DIFFERENT blocks is NOT a valid commit certificate")
                    .isFalse();
            assertThat(quorumHash)
                    .as("no single hash reached 2f+1, so there is no quorum hash")
                    .isNull();
        } finally {
            shutdown(net);
        }
    }

    @Test
    @DisplayName("[S7-01] A genuine quorum (same hash) is still accepted")
    void genuineQuorumStillAccepted() {
        FaultyNetwork net = cluster(4, 998);
        try {
            long seq = 43;
            String agreed = "AGREED_BLOCK";
            List<String> ids = new ArrayList<>(net.nodes.keySet());
            PBFTConsensus node = net.nodes.get(ids.get(0));
            for (int i = 0; i < 3; i++) {
                node.addCommitVote(seq, agreed, ids.get(i),
                        sign(net, ids.get(i), PBFTConsensus.Phase.COMMIT, 0, seq, agreed));
            }
            assertThat(node.hasQuorum(seq, PBFTConsensus.Phase.COMMIT)).isTrue();
            assertThat(node.getQuorumHash(seq, PBFTConsensus.Phase.COMMIT)).isEqualTo(agreed);
        } finally {
            shutdown(net);
        }
    }

    @Test
    @DisplayName("SAFETY: no two correct nodes reach COMMIT quorum on different hashes at the same seq")
    void safetyUnderRandomFaults() {
        int violations = 0;
        StringBuilder witness = new StringBuilder();

        for (long seed = 1; seed <= 60; seed++) {
            FaultyNetwork net = cluster(4, seed);
            try {
                Random rng = net.rng;
                long seq = 1;
                // Randomised fault schedule for this run.
                net.dropRate  = rng.nextDouble() * 0.5;
                net.delayRate = rng.nextDouble() * 0.4;
                net.dupRate   = rng.nextDouble() * 0.3;

                // Randomly partition the cluster.
                if (rng.nextBoolean()) {
                    int i = 0;
                    for (String id : net.nodes.keySet()) net.partition.put(id, (i++) % 2);
                }
                // Randomly pause a node (process fault).
                if (rng.nextBoolean()) {
                    List<String> ids = new ArrayList<>(net.nodes.keySet());
                    net.paused.add(ids.get(rng.nextInt(ids.size())));
                }

                // RESPECT THE FAULT MODEL: N=4 tolerates f=1 Byzantine node. Exactly ONE
                // validator equivocates; the other three are correct and vote a single
                // value, as the protocol requires. (An earlier version of this test made
                // ALL FOUR equivocate, which is 4f > f — outside PBFT's guarantee — and it
                // duly reported "violations" that were artifacts of the harness, not bugs.
                // Testing beyond the fault model proves nothing.)
                String hashA = "AAAA" + seed;   // the honest value
                String hashB = "BBBB" + seed;   // the liar's conflicting value
                List<String> ids = new ArrayList<>(net.nodes.keySet());
                String liar = ids.get(0);

                for (PBFTConsensus.Phase phase :
                        List.of(PBFTConsensus.Phase.PREPARE, PBFTConsensus.Phase.COMMIT)) {
                    for (String from : ids) {
                        if (from.equals(liar)) {
                            // Byzantine: tell half the cluster A and half B.
                            int k = 0;
                            for (String to : ids) {
                                String h = (k++ % 2 == 0) ? hashA : hashB;
                                net.send(new Msg(from, to, phase, seq, h,
                                        sign(net, from, phase, 0, seq, h)));
                            }
                        } else {
                            // Correct: one value, to everyone.
                            byte[] sig = sign(net, from, phase, 0, seq, hashA);
                            for (String to : ids) net.send(new Msg(from, to, phase, seq, hashA, sig));
                        }
                    }
                }
                net.healAndFlush();

                // SAFETY CHECK (the real property): no two correct nodes may hold commit
                // certificates for DIFFERENT block hashes at the same sequence.
                //
                // NOTE: an earlier version of this assertion checked "quorum implies
                // something was slashed", which is not a statement of safety at all — a
                // quorum can legitimately form with no equivocation detected locally. That
                // formulation produced 3 spurious "violations" in 60 runs. The check below
                // is the actual agreement property.
                String agreedHash = null;
                String agreedBy = null;
                for (Map.Entry<String, PBFTConsensus> e : net.nodes.entrySet()) {
                    String h = e.getValue().getQuorumHash(seq, PBFTConsensus.Phase.COMMIT);
                    if (h == null) continue;
                    if (agreedHash == null) {
                        agreedHash = h;
                        agreedBy = e.getKey();
                    } else if (!agreedHash.equals(h)) {
                        violations++;
                        witness.append("seed=").append(seed)
                               .append(" CONFLICTING COMMIT at seq ").append(seq)
                               .append(": ").append(agreedBy).append(" -> ").append(agreedHash)
                               .append(" vs ").append(e.getKey()).append(" -> ").append(h)
                               .append('\n');
                        break;
                    }
                }
            } finally {
                shutdown(net);
            }
        }

        System.out.printf("[FAULT-INJECT] safety: %d violation(s) over 60 seeded runs%n", violations);
        if (violations > 0) System.out.print(witness);

        assertThat(violations)
                .as("SAFETY: two correct nodes must never certify different blocks at the same seq\n%s", witness)
                .isZero();
    }

    // ── LIVENESS ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LIVENESS: cluster still reaches quorum after a partition heals")
    void livenessAfterPartitionHeals() {
        int wedged = 0;
        for (long seed = 1; seed <= 30; seed++) {
            FaultyNetwork net = cluster(4, seed);
            try {
                long seq = 1;
                String hash = "LIVE" + seed;

                // Total partition: split into two halves, nothing crosses.
                int i = 0;
                for (String id : net.nodes.keySet()) net.partition.put(id, (i++) % 2);

                broadcast(net, PBFTConsensus.Phase.PREPARE, 0, seq, hash);
                broadcast(net, PBFTConsensus.Phase.COMMIT,  0, seq, hash);

                // Under a 2/2 split of 4 nodes, no side can reach 2f+1 = 3. Expected.
                boolean anyQuorumWhilePartitioned = net.nodes.values().stream()
                        .anyMatch(c -> c.hasQuorum(seq, PBFTConsensus.Phase.COMMIT));
                assertThat(anyQuorumWhilePartitioned)
                        .as("a 2/2 partition of 4 nodes must NOT reach a 3-vote quorum (seed %d)", seed)
                        .isFalse();

                // Heal, then re-broadcast: progress must now be possible.
                net.healAndFlush();
                broadcast(net, PBFTConsensus.Phase.PREPARE, 0, seq, hash);
                broadcast(net, PBFTConsensus.Phase.COMMIT,  0, seq, hash);

                boolean progressed = net.nodes.values().stream()
                        .anyMatch(c -> c.hasQuorum(seq, PBFTConsensus.Phase.COMMIT));
                if (!progressed) wedged++;
            } finally {
                shutdown(net);
            }
        }

        System.out.printf("[FAULT-INJECT] liveness: %d wedged run(s) out of 30 after healing%n", wedged);
        assertThat(wedged)
                .as("LIVENESS: after partitions heal the cluster must be able to reach quorum again")
                .isZero();
    }

    // ── Equivocation detection ───────────────────────────────────────────────

    @Test
    @DisplayName("Equivocating validator is slashed even under message loss and duplication")
    void equivocationDetectedUnderFaults() {
        int missed = 0;
        for (long seed = 1; seed <= 40; seed++) {
            FaultyNetwork net = cluster(4, seed);
            try {
                // Lossy but not partitioned, so the conflicting pair can still be observed.
                net.dupRate = 0.3;
                long seq = 5;
                String liar = net.nodes.keySet().iterator().next();

                // The liar sends two different PREPARE hashes for the same (view, seq).
                for (String to : net.nodes.keySet()) {
                    net.send(new Msg(liar, to, PBFTConsensus.Phase.PREPARE, seq, "X" + seed,
                            sign(net, liar, PBFTConsensus.Phase.PREPARE, 0, seq, "X" + seed)));
                    net.send(new Msg(liar, to, PBFTConsensus.Phase.PREPARE, seq, "Y" + seed,
                            sign(net, liar, PBFTConsensus.Phase.PREPARE, 0, seq, "Y" + seed)));
                }

                boolean anyDetected = net.nodes.values().stream()
                        .anyMatch(c -> c.getSlashedValidators().contains(liar));
                if (!anyDetected) missed++;
            } finally {
                shutdown(net);
            }
        }
        System.out.printf("[FAULT-INJECT] equivocation: %d/40 runs where NO node detected the liar%n", missed);
        assertThat(missed)
                .as("an equivocating validator must be detected by at least one node")
                .isZero();
    }
}
