package com.hybrid.blockchain.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("FederatedLearning")
public class FederatedLearningE2ETest {

    private TestBlockchain tb;
    private FederatedLearningManager manager;

    @BeforeEach
    public void setup() throws Exception {
        tb = new TestBlockchain();
        manager = FederatedLearningManager.getInstance();
        manager.resetForTesting();
    }

    @AfterEach
    public void teardown() throws Exception {
        if (tb != null) tb.close();
    }

    @Test
    @DisplayName("C1.1: Federated Update and Commit Flow")
    public void testFederatedUpdateAndCommitFlow() throws Exception {
        // Create 3 nodes, fund each with 100_000 tokens
        TestKeyPair node1 = new TestKeyPair(10);
        TestKeyPair node2 = new TestKeyPair(11);
        TestKeyPair node3 = new TestKeyPair(12);
        
        tb.getBlockchain().getAccountState().credit(node1.getAddress(), 100_000L);
        tb.getBlockchain().getAccountState().credit(node2.getAddress(), 100_000L);
        tb.getBlockchain().getAccountState().credit(node3.getAddress(), 100_000L);

        // Submit updates
        double[] w1 = {1.0, 2.0, 3.0};
        double[] w2 = {3.0, 4.0, 5.0};
        double[] w3 = {5.0, 6.0, 7.0};
        
        Transaction tx1 = createUpdateTx(node1, w1, 1);
        Transaction tx2 = createUpdateTx(node2, w2, 1);
        Transaction tx3 = createUpdateTx(node3, w3, 1);
        
        BlockApplier.createAndApplyBlock(tb, Arrays.asList(tx1, tx2, tx3));

        assertThat(manager.getRoundNumber()).isEqualTo(0);
        assertThat(manager.getPendingUpdateCount()).isEqualTo(3);

        // Aggregate
        FederatedLearningManager.AggregationResult result = manager.aggregate("leader", tb.getStorage());
        
        assertThat(manager.getRoundNumber()).isEqualTo(1);
        assertThat(result.model[0]).isCloseTo(3.0, offset(0.001));
        assertThat(result.model[1]).isCloseTo(4.0, offset(0.001));
        assertThat(result.model[2]).isCloseTo(5.0, offset(0.001));

        // Submit COMMIT
        String modelHash = manager.getCurrentModelHash();
        Transaction commitTx = new Transaction.Builder()
                .type(Transaction.Type.FEDERATED_COMMIT)
                .from(tb.getValidatorKey().getAddress())
                .data(modelHash.getBytes())
                .nonce(tb.getBlockchain().getAccountState().getNonce(tb.getValidatorKey().getAddress()) + 1)
                .build();
        commitTx.sign(tb.getValidatorKey().getPrivateKey());
        
        BlockApplier.createAndApplyBlock(tb, Collections.singletonList(commitTx));

        assertThat(tb.getStorage().getMeta("federated:latest:hash")).isEqualTo(modelHash);
    }

    @Test
    @DisplayName("C1.2: Federated Round Isolation")
    public void testFederatedRoundIsolation() throws Exception {
        String leader = "leader";
        
        // Round 1
        manager.submitUpdate("node1", new double[]{1.0, 1.0});
        manager.submitUpdate("node2", new double[]{2.0, 2.0});
        String hash1 = manager.aggregate(leader, tb.getStorage()).modelHash;
        assertThat(manager.getRoundNumber()).isEqualTo(1);
        assertThat(manager.getPendingUpdateCount()).isEqualTo(0);

        // Round 2
        manager.submitUpdate("node3", new double[]{3.0, 3.0});
        manager.submitUpdate("node4", new double[]{4.0, 4.0});
        String hash2 = manager.aggregate(leader, tb.getStorage()).modelHash;
        assertThat(manager.getRoundNumber()).isEqualTo(2);
        assertThat(hash2).isNotEqualTo(hash1);
        assertThat(manager.getPendingUpdateCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("C1.3: Federated Empty Aggregate")
    public void testFederatedEmptyAggregate() {
        assertThat(manager.aggregate("leader", tb.getStorage())).isNull();
    }

    @Test
    @DisplayName("C1.4: Federated Mismatched Dimensions")
    public void testFederatedMismatchedDimensions() {
        manager.submitUpdate("node1", new double[]{1.0, 1.0, 1.0, 1.0});
        manager.submitUpdate("node2", new double[]{2.0, 2.0, 2.0, 2.0});
        manager.submitUpdate("node3", new double[]{3.0, 3.0, 3.0}); // outlier

        FederatedLearningManager.AggregationResult result = manager.aggregate("leader", tb.getStorage());
        assertThat(result.contributors).isEqualTo(2);
        assertThat(result.model.length).isEqualTo(4);
    }

    private Transaction createUpdateTx(TestKeyPair kp, double[] weights, long nonce) throws Exception {
        String json = new ObjectMapper().writeValueAsString(weights);
        Transaction tx = new Transaction.Builder()
                .type(Transaction.Type.FEDERATED_UPDATE)
                .from(kp.getAddress())
                .data(json.getBytes())
                .nonce(nonce)
                .fee(10)
                .build();
        tx.sign(kp.getPrivateKey());
        return tx;
    }
}
