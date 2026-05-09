package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.ai.TelemetryAnomalyDetector;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

@Tag("Shadow")
public class ShadowTests {

    @Test
    @DisplayName("Shadow Test 1: Rapid node churn breaks PBFT quorum")
    void testValidatorChurnBelowMinimum() {
        // Start with 4 validators
        Map<String, byte[]> validatorSet = new HashMap<>();
        TestKeyPair v0 = new TestKeyPair(0);
        TestKeyPair v1 = new TestKeyPair(1);
        TestKeyPair v2 = new TestKeyPair(2);
        TestKeyPair v3 = new TestKeyPair(3);
        validatorSet.put("v0", v0.getPublicKey());
        validatorSet.put("v1", v1.getPublicKey());
        validatorSet.put("v2", v2.getPublicKey());
        validatorSet.put("v3", v3.getPublicKey());
        
        PBFTConsensus pbft = new PBFTConsensus(validatorSet, "v0", v0.getPrivateKey());

        // Remove validators until below threshold (simulates node crashes)
        pbft.removeValidator("v3"); // now 3 — violates 3f+1 = 4 minimum
        pbft.removeValidator("v2"); // now 2

        // Attempt to propose a block — should throw or refuse to proceed
        Block block = new Block(1, System.currentTimeMillis(), new ArrayList<>(), "0000", 0, "0000");
        block.setValidatorId("v0");
        
        boolean threw = false;
        try {
            boolean result = pbft.validateBlock(block, Collections.emptyList());
            assertFalse(result, "Consensus must halt if live validators drop below minimum threshold");
        } catch (IllegalStateException e) {
            threw = true;
        }
        assertTrue(threw, "Expected IllegalStateException due to missing minimum validators");
    }

    @Test
    @DisplayName("Shadow Test 2: Corrupted IoT telemetry triggers ARIMA NaN propagation")
    void testArimaStateNaNPropagation() {
        TelemetryAnomalyDetector detector = TelemetryAnomalyDetector.getInstance();
        detector.reset(); // clear any global state
        String device = "sensor-1";

        // Submit NaN — simulates sensor hardware fault or truncated packet
        detector.check(device, Double.NaN);
        
        // Next check must NOT propagate NaN — any return value must be a finite boolean
        boolean result = detector.check(device, 26.0);
        assertTrue(Double.isFinite(detector.getStats(device).lastZScore), "NaN input must not corrupt ARIMA state — z-score became NaN");
        assertFalse(detector.getWindowSnapshot(device).contains(Double.NaN), "NaN values must be purged from sliding window");
    }

    @Test
    @DisplayName("Shadow Test 3: Snapshot revert races concurrent balance reads")
    void testRevertTipRaceCondition() throws Exception {
        TestBlockchain tb = new TestBlockchain();
        Blockchain bc = tb.getBlockchain();
        String addr = "test-wallet";
        
        // Create baseline
        bc.getAccountState().credit(addr, 1000L);
        bc.getStorage().saveSnapshot(1, bc.getAccountState().toJSON(), bc.getUTXOSet().toJSON());
        
        // We need a dummy block at tip to revert
        Block dummyBlock = new Block(2, System.currentTimeMillis(), new ArrayList<>(), "0000", 0, "0000");
        bc.getChain().add(dummyBlock);
        
        AtomicLong racedBalance = new AtomicLong(-1);
        CountDownLatch startLatch = new CountDownLatch(1);
        
        Thread reader = new Thread(() -> {
            startLatch.countDown();
            for (int i = 0; i < 1000; i++) {
                long bal = bc.getBalance(addr);
                if (bal < 0) racedBalance.set(bal); // detect impossible value
            }
        });
        
        Thread reverter = new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 50; i++) {
                    bc.revertTipForTest();
                    // Add it back so we can revert it again
                    bc.getChain().add(dummyBlock);
                }
            } catch (Exception e) { e.printStackTrace(); }
        });
        
        reader.start(); reverter.start();
        reader.join(); reverter.join();
        
        assertEquals(-1, racedBalance.get(), "Race condition detected: balance read during state swap");
    }
}
