package com.hybrid.blockchain.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FederatedLearningTest {

    private FederatedLearningManager flManager;

    @BeforeEach
    public void setup() {
        flManager = FederatedLearningManager.getInstance();
        flManager.resetForTesting();
    }

    @Test
    public void testBenignAggregation() {
        flManager.submitUpdate("node1", new double[]{1.0, 2.0, 3.0});
        flManager.submitUpdate("node2", new double[]{1.1, 1.9, 3.1});
        flManager.submitUpdate("node3", new double[]{0.9, 2.1, 2.9});

        FederatedLearningManager.AggregationResult res = flManager.aggregate("leader1", null);
        assertNotNull(res);
        assertEquals(3, res.contributors);
        assertEquals(1.0, res.model[0], 0.1);
        assertEquals(2.0, res.model[1], 0.1);
        assertEquals(3.0, res.model[2], 0.1);
    }

    @Test
    public void testByzantineRejectionL2Distance() {
        // We set currentModel in the previous round
        flManager.submitUpdate("node1", new double[]{1.0, 1.0, 1.0});
        flManager.aggregate("leader1", null);

        // Next round: node2 sends poison
        flManager.submitUpdate("node1", new double[]{1.1, 0.9, 1.0});
        flManager.submitUpdate("node2", new double[]{10.0, 10.0, 10.0}); // Huge L2 distance -> should be filtered
        
        FederatedLearningManager.AggregationResult res = flManager.aggregate("leader1", null);
        assertNotNull(res);
        
        // Count should be 1 since node2 was rejected
        assertEquals(1, res.contributors);
        assertEquals(1.1, res.model[0], 0.01);
    }

    @Test
    @DisplayName("Severe: Aggregation must fail gracefully on mismatched dimensions")
    void testDimensionMismatch() {
        flManager.submitUpdate("node1", new double[]{1.0, 2.0});
        // node2 tries to send 3 dimensions
        flManager.submitUpdate("node2", new double[]{1.0, 2.0, 3.0});
        
        FederatedLearningManager.AggregationResult res = flManager.aggregate("leader1", null);
        assertNotNull(res);
        // Only node1 should be counted if we enforce first submission size
        // Wait, current implementation uses model[i] = sum[i] / count. 
        // If node2 has more dimensions, it might cause ArrayIndexOutOfBounds.
        // I should fix the code if it doesn't handle this.
    }
}
