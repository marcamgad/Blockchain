package com.hybrid.blockchain.ai;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.util.List;

/**
 * Unit and integration tests for Federated Learning aggregation logic.
 */
@Tag("ai")
public class FederatedLearningCompleteTest {

    private FederatedLearningManager manager;

    @BeforeEach
    void setUp() {
        manager = FederatedLearningManager.getInstance();
        manager.reset();
    }

    @Test
    @DisplayName("FL1.1-1.2 — Validation")
    void testUpdateValidation() {
        assertThatThrownBy(() -> manager.submitUpdate("node1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.submitUpdate("node1", new double[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FL1.5 — FedAvg with 3 nodes")
    void testAggregation() {
        manager.submitUpdate("n1", new double[]{1.0, 2.0, 3.0});
        manager.submitUpdate("n2", new double[]{3.0, 4.0, 5.0});
        manager.submitUpdate("n3", new double[]{5.0, 6.0, 7.0});
        
        double[] avg = manager.aggregate();
        assertThat(avg).containsExactly(new double[]{3.0, 4.0, 5.0}, within(0.001));
        assertThat(manager.getPendingUpdateCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("FL1.6 — Dimension mismatch rejection")
    void testDimensionMismatch() {
        manager.submitUpdate("n1", new double[]{1, 2});
        manager.submitUpdate("n2", new double[]{3, 4});
        manager.submitUpdate("n3", new double[]{5, 6, 7}); // Wrong dim
        
        double[] avg = manager.aggregate();
        assertThat(avg).hasSize(2);
        assertThat(avg).containsExactly(new double[]{2.0, 3.0}, within(0.001));
    }

    @Test
    @DisplayName("FL1.7-1.8 — State tracking")
    void testStateTracking() {
        String h1 = manager.getCurrentModelHash();
        int r1 = manager.getRoundNumber();
        
        manager.submitUpdate("n1", new double[]{1.0});
        manager.aggregate();
        
        assertThat(manager.getCurrentModelHash()).isNotEqualTo(h1);
        assertThat(manager.getRoundNumber()).isEqualTo(r1 + 1);
    }

    @Test
    @DisplayName("FL1.11 — Differential privacy")
    void testDifferentialPrivacy() {
        manager.setDifferentialPrivacyEnabled(true);
        manager.setEpsilon(1.0);
        
        // Use large values so that 0.1/1.0 noise is detectable but not overwhelming
        double[] input = {100.0, 100.0};
        manager.submitUpdate("n1", input);
        manager.submitUpdate("n2", input);
        
        double[] avg = manager.aggregate();
        
        // With DP enabled, the average of two [100, 100] inputs should NOT be exactly [100, 100]
        assertThat(avg[0]).isNotEqualTo(100.0);
        assertThat(avg[1]).isNotEqualTo(100.0);
    }
}
