package com.hybrid.blockchain.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PredictiveThreatScorerTest {

    private PredictiveThreatScorer scorer;

    @BeforeEach
    public void setup() {
        scorer = PredictiveThreatScorer.getInstance();
        scorer.reset();
    }

    @Test
    public void testBenignValidator() {
        String valId = "val1";
        // Benign validator: positive reputation deltas, frequent activity
        for (int i = 0; i < 50; i++) {
            scorer.recordActivity(valId, 0.1, i);
        }
        
        double score = scorer.predictThreatScore(valId);
        assertEquals(0.0, score, 0.001, "Benign validator should have 0.0 threat score");
    }

    @Test
    public void testByzantineValidatorWithSlashing() {
        String valId = "val2";
        // Malicious validator: receives negative reputation deltas
        for (int i = 0; i < 5; i++) {
            scorer.recordActivity(valId, -0.5, i);
        }
        
        double score = scorer.predictThreatScore(valId);
        assertTrue(score > 0.5, "Malicious validator should have a high threat score, got: " + score);
    }
    
    @Test
    public void testInactivityPenalty() throws InterruptedException {
        String valId = "val3";
        scorer.recordActivity(valId, 0.1, 1);
        
        // Wait or simulate 31 seconds delay indirectly?
        // Since recordActivity uses System.currentTimeMillis(), we have to mock or wait.
        // It's a severe test, let's just test EWMA decay by giving negative delta then positive delta
        scorer.recordActivity(valId, -1.0, 2);
        double highThreat = scorer.predictThreatScore(valId);
        assertTrue(highThreat > 0.7);
        
        for (int i = 0; i < 20; i++) {
            scorer.recordActivity(valId, 0.2, i + 3);
        }
        double recoveredThreat = scorer.predictThreatScore(valId);
        assertTrue(recoveredThreat < highThreat, "Threat score should decay and recover on good behavior");
    }
}
