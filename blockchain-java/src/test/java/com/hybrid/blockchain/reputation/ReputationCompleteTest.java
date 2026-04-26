package com.hybrid.blockchain.reputation;

import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the Reputation Engine.
 * Covers score arithmetic, boundary clamping, and inactivity penalties.
 */
@Tag("reputation")
public class ReputationCompleteTest {

    @Test
    @DisplayName("RE1.1 — Initial score")
    void testInitialScore() {
        assertThat(ReputationEngine.INITIAL_SCORE).isEqualTo(0.5);
    }

    @Test
    @DisplayName("RE1.2-1.3 — Score arithmetic")
    void testScoreArithmetic() {
        double s1 = ReputationEngine.calculateNewScore(0.5, true);
        assertThat(s1).isCloseTo(0.51, within(0.0001));
        
        double s2 = ReputationEngine.calculateNewScore(0.5, false);
        assertThat(s2).isCloseTo(0.45, within(0.0001));
    }

    @Test
    @DisplayName("RE1.4-1.5 — Boundary clamping")
    void testClamping() {
        double high = 0.999;
        for(int i=0; i<100; i++) high = ReputationEngine.calculateNewScore(high, true);
        assertThat(high).isEqualTo(ReputationEngine.MAX_SCORE);
        
        double low = 0.001;
        for(int i=0; i<100; i++) low = ReputationEngine.calculateNewScore(low, false);
        assertThat(low).isEqualTo(ReputationEngine.MIN_SCORE);
    }

    @Test
    @DisplayName("RE1.6-1.8 — Inactivity Penalty")
    void testInactivityPenalty() {
        // Grace period is 100 blocks
        assertThat(ReputationEngine.applyInactivityPenalty(0.5, 100)).isEqualTo(0.5);
        
        // 1 block past grace: 0.001 deduction
        assertThat(ReputationEngine.applyInactivityPenalty(0.5, 101)).isEqualTo(0.499);
        
        // Huge gap
        assertThat(ReputationEngine.applyInactivityPenalty(0.5, 2000)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("RE1.9 — Persistence Check (Stub)")
    @Disabled("Requires Fix 4 — reputation persistence in device lifecycle manager")
    void testPersistence() {
        // Verify score survives node restart
    }

    @Test
    @DisplayName("RE1.10 — State Root Inclusion (Stub)")
    @Disabled("Requires Fix 4 — inclusion of reputation scores in state root calculation")
    void testStateRootInclusion() {
        // Verify different reputations lead to different state roots
    }
}
