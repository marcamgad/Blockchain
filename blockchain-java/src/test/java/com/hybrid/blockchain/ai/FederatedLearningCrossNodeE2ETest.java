package com.hybrid.blockchain.ai;

import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.Storage;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Cross-node federated learning end-to-end test harness.
 *
 * <p>Each test simulates an independent "node" as a dedicated
 * {@link FederatedLearningManager} instance. A shared in-process {@link Storage}
 * acts as the P2P gossip channel, just as the blockchain storage would in production
 * (the leader persists the model; peers reload it).
 *
 * <ul>
 *   <li>FL1.1 — Single-round happy path with 3 nodes</li>
 *   <li>FL1.2 — Byzantine node excluded in round 2</li>
 *   <li>FL1.3 — Differential-privacy noise bounds respected</li>
 *   <li>FL1.4 — Round persistence: fresh manager reload</li>
 *   <li>FL1.5 — Empty round returns null</li>
 *   <li>FL1.6 — Dimension mismatch: minority excluded</li>
 *   <li>FL1.7 — applyCommittedModel propagation</li>
 * </ul>
 */
@Tag("federated")
@Tag("e2e")
@TestMethodOrder(MethodOrderer.DisplayName.class)
public class FederatedLearningCrossNodeE2ETest {

    private static final byte[] AES_KEY = HexUtils.decode("00112233445566778899001122334455");

    private Path tempDir;
    private Storage sharedStorage;

    @BeforeEach
    void setUp() throws Exception {
        tempDir       = Files.createTempDirectory("fl-e2e-" + UUID.randomUUID());
        sharedStorage = new Storage(tempDir.toString(), AES_KEY);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sharedStorage != null) sharedStorage.close();
        if (tempDir != null) {
            try { FileUtils.deleteDirectory(tempDir.toFile()); }
            catch (IOException ignored) {}
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // FL1.1 — Single-round happy path
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FL1.1 — 3 nodes submit weights; leader aggregates; peer reloads same hash from Storage")
    void testSingleRoundHappyPath() {
        FederatedLearningManager leader = freshManager();
        FederatedLearningManager peer2  = freshManager();
        FederatedLearningManager peer3  = freshManager();

        // All three nodes submit weights of the same dimension
        leader.submitUpdate("node-1", new double[]{1.0, 2.0, 3.0});
        leader.submitUpdate("node-2", new double[]{2.0, 4.0, 6.0});
        leader.submitUpdate("node-3", new double[]{3.0, 6.0, 9.0});

        FederatedLearningManager.AggregationResult result =
                leader.aggregate("node-1", sharedStorage);

        assertThat(result).isNotNull();
        assertThat(result.contributors).isEqualTo(3);
        assertThat(result.round).isEqualTo(1);
        // Average of [1,2,3], [2,4,6], [3,6,9] = [2, 4, 6]
        assertThat(result.model[0]).isCloseTo(2.0, within(0.001));
        assertThat(result.model[1]).isCloseTo(4.0, within(0.001));
        assertThat(result.model[2]).isCloseTo(6.0, within(0.001));

        // A fresh peer reloads from Storage and arrives at the same hash
        FederatedLearningManager freshPeer = freshManager();
        boolean loaded = freshPeer.loadLatestModel(sharedStorage);

        assertThat(loaded).isTrue();
        assertThat(freshPeer.getCurrentModelHash()).isEqualTo(result.modelHash);
        assertThat(freshPeer.getCurrentModel()[1]).isCloseTo(4.0, within(0.001));
    }

    // ────────────────────────────────────────────────────────────────────────────
    // FL1.2 — Byzantine node excluded in round 2
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FL1.2 — Byzantine node (dist > 3.0 from round-1 model) excluded in round 2")
    void testByzantineRejection() {
        FederatedLearningManager mgr = freshManager();

        // Round 1: establish baseline model ≈ [1, 1, 1]
        mgr.submitUpdate("honest-1", new double[]{1.0, 1.0, 1.0});
        mgr.submitUpdate("honest-2", new double[]{1.0, 1.0, 1.0});
        mgr.submitUpdate("honest-3", new double[]{1.0, 1.0, 1.0});
        FederatedLearningManager.AggregationResult r1 = mgr.aggregate("honest-1", sharedStorage);
        assertThat(r1).isNotNull();
        assertThat(r1.contributors).isEqualTo(3);

        // Round 2: byzantine node submits weights far from the round-1 model
        mgr.submitUpdate("honest-1", new double[]{1.1, 1.1, 1.1});
        mgr.submitUpdate("honest-2", new double[]{1.0, 1.0, 1.0});
        mgr.submitUpdate("byzantine", new double[]{100.0, 100.0, 100.0}); // dist ≈ 172, >> 3.0

        FederatedLearningManager.AggregationResult r2 = mgr.aggregate("honest-1", sharedStorage);
        assertThat(r2).isNotNull();
        // Byzantine node must be excluded
        assertThat(r2.contributors).isEqualTo(2);
        // Aggregated value must not be pulled toward 100
        assertThat(r2.model[0]).isLessThan(5.0);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // FL1.3 — Differential-privacy noise bounds
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FL1.3 — DP-enabled aggregate differs from plain average by ≤ 1.0 per dimension")
    void testDifferentialPrivacyNoiseBounds() {
        FederatedLearningManager mgr = freshManager();
        mgr.setDifferentialPrivacyEnabled(true);
        mgr.setEpsilon(1.0);

        mgr.submitUpdate("n1", new double[]{10.0, 20.0, 30.0});
        mgr.submitUpdate("n2", new double[]{10.0, 20.0, 30.0});
        mgr.submitUpdate("n3", new double[]{10.0, 20.0, 30.0});

        FederatedLearningManager.AggregationResult result = mgr.aggregate("n1", sharedStorage);
        assertThat(result).isNotNull();

        // Plain average is exactly [10, 20, 30]; with DP noise (σ = 0.1/ε) the maximum
        // plausible deviation is much less than 1.0 per dimension in practice.
        assertThat(Math.abs(result.model[0] - 10.0)).isLessThan(1.0);
        assertThat(Math.abs(result.model[1] - 20.0)).isLessThan(1.0);
        assertThat(Math.abs(result.model[2] - 30.0)).isLessThan(1.0);

        // Model hash is still persisted
        assertThat(result.modelHash).isNotBlank();
        assertThat(result.modelHash).isNotEqualTo("0".repeat(64));
    }

    // ────────────────────────────────────────────────────────────────────────────
    // FL1.4 — Round persistence and reload
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FL1.4 — loadLatestModel() on fresh manager returns same model array and hash")
    void testRoundPersistenceAndReload() {
        FederatedLearningManager leader = freshManager();
        leader.submitUpdate("a", new double[]{5.0, 5.0});
        leader.submitUpdate("b", new double[]{3.0, 3.0});

        FederatedLearningManager.AggregationResult r = leader.aggregate("a", sharedStorage);
        assertThat(r).isNotNull();

        // New manager instance, same Storage
        FederatedLearningManager reloaded = freshManager();
        boolean loaded = reloaded.loadLatestModel(sharedStorage);

        assertThat(loaded).isTrue();
        assertThat(reloaded.getCurrentModelHash()).isEqualTo(r.modelHash);
        assertThat(reloaded.getCurrentModel()).hasSize(2);
        assertThat(reloaded.getCurrentModel()[0]).isCloseTo(4.0, within(0.001)); // avg of 5 and 3
        assertThat(reloaded.getRoundNumber()).isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // FL1.5 — Empty round
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FL1.5 — aggregate() with zero pending updates returns null; model unchanged")
    void testEmptyRound() {
        FederatedLearningManager mgr = freshManager();

        FederatedLearningManager.AggregationResult result = mgr.aggregate("leader", sharedStorage);
        assertThat(result).isNull();
        assertThat(mgr.getCurrentModel()).isEmpty();
        assertThat(mgr.getRoundNumber()).isEqualTo(0);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // FL1.6 — Dimension mismatch: minority excluded
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FL1.6 — Dimension-mismatch node excluded; result dimension equals majority dim")
    void testDimensionMismatchExclusion() {
        FederatedLearningManager mgr = freshManager();

        mgr.submitUpdate("n1", new double[]{1.0, 2.0, 3.0, 4.0}); // dim 4 (majority)
        mgr.submitUpdate("n2", new double[]{1.0, 2.0, 3.0, 4.0}); // dim 4
        mgr.submitUpdate("n3", new double[]{9.0, 9.0, 9.0, 9.0, 9.0}); // dim 5 (minority)

        FederatedLearningManager.AggregationResult r = mgr.aggregate("n1", sharedStorage);
        assertThat(r).isNotNull();
        assertThat(r.model).hasSize(4);
        assertThat(r.contributors).isEqualTo(2); // n3 excluded
        // Average of the two dim-4 vectors = [1, 2, 3, 4]
        assertThat(r.model[0]).isCloseTo(1.0, within(0.001));
        assertThat(r.model[3]).isCloseTo(4.0, within(0.001));
    }

    // ────────────────────────────────────────────────────────────────────────────
    // FL1.7 — applyCommittedModel propagation
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FL1.7 — applyCommittedModel() simulates gossip propagation to a peer")
    void testApplyCommittedModelPropagation() {
        // Leader aggregates
        FederatedLearningManager leader = freshManager();
        leader.submitUpdate("x", new double[]{7.0, 8.0, 9.0});
        leader.submitUpdate("y", new double[]{1.0, 2.0, 3.0});
        FederatedLearningManager.AggregationResult r = leader.aggregate("x", sharedStorage);
        assertThat(r).isNotNull();

        // Peer receives committed model via gossip (simulated as applyCommittedModel call)
        FederatedLearningManager peer = freshManager();
        peer.applyCommittedModel(r.modelHash, r.model, r.round, r.contributors, sharedStorage);

        assertThat(peer.getCurrentModelHash()).isEqualTo(r.modelHash);
        assertThat(peer.getCurrentModel()).hasSize(3);
        assertThat(peer.getCurrentModel()[0]).isCloseTo(4.0, within(0.001)); // avg(7, 1) = 4
        assertThat(peer.getCurrentModel()[1]).isCloseTo(5.0, within(0.001)); // avg(8, 2) = 5
        assertThat(peer.getCurrentModel()[2]).isCloseTo(6.0, within(0.001)); // avg(9, 3) = 6
        assertThat(peer.getRoundNumber()).isEqualTo(r.round);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /** Creates a fresh, isolated {@link FederatedLearningManager} per simulated node. */
    private FederatedLearningManager freshManager() {
        FederatedLearningManager m = new FederatedLearningManager();
        m.resetForTesting();
        return m;
    }

    private static <T extends Comparable<T>> org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }
}
