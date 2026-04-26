package com.hybrid.blockchain.reputation;

import com.hybrid.blockchain.testutil.*;
import com.hybrid.blockchain.AccountState;
import com.hybrid.blockchain.Storage;
import org.junit.jupiter.api.*;
import java.io.File;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Tag("reputation")
public class ReputationCompleteTest {

    @Test
    @DisplayName("RE1.2-1.3 — Score arithmetic")
    void testScoreArithmetic() {
        double s1 = ReputationEngine.calculateNewScore(0.5, true);
        assertThat(s1).isCloseTo(0.51, within(0.0001));

        double s2 = ReputationEngine.calculateNewScore(0.5, false);
        assertThat(s2).isCloseTo(0.45, within(0.0001));
    }

    @Test
    @DisplayName("RE1.6-1.8 — Inactivity Penalty")
    void testInactivityPenalty() {
        assertThat(ReputationEngine.applyInactivityPenalty(0.5, 100)).isEqualTo(0.5);
        assertThat(ReputationEngine.applyInactivityPenalty(0.5, 101)).isEqualTo(0.499);
    }

    @Test
    @DisplayName("RE1.9 — Persistence Check")
    void testPersistence() throws Exception {
        File tempDir = new File("/tmp/rep-test-" + UUID.randomUUID());
        tempDir.mkdirs();
        try {
            Storage storage = new Storage(tempDir.getAbsolutePath(), "0123456789abcdef".getBytes());
            ReputationEngine.writeScore("device1", 0.8, storage);
            assertThat(ReputationEngine.readScore("device1", storage)).isEqualTo(0.8);
        } finally {
            deleteDir(tempDir);
        }
    }

    @Test
    @DisplayName("RE1.10 — State Root Inclusion")
    void testStateRootInclusion() {
        AccountState state = new AccountState();
        state.credit("0xAlice", 100);
        String root1 = state.calculateRoot();
        
        state.setAccountReputation("0xAlice", 0.8);
        String root2 = state.calculateRoot();
        
        assertThat(root1).isNotEqualTo(root2);
    }

    private void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
