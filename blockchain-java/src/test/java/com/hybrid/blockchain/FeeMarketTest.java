package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("unit")
public class FeeMarketTest {
    private final FeeMarket feeMarket = new FeeMarket();

    @Test
    @DisplayName("Invariant: Base fee must increase when block is full (congestion)")
    void testBaseFeeIncrease() {
        long currentBaseFee = 100;
        int targetGas = 1000;
        int usedGas = 2000; // Double the target
        
        long nextFee = new FeeMarket().calculateNextBaseFee(currentBaseFee, usedGas, targetGas);
        
        // EIP-1559 max change is 12.5% (1/8)
        // 100 * (1000) / 1000 / 8 = 100 / 8 = 12
        assertThat(nextFee).isEqualTo(112);
    }

    @Test
    @DisplayName("Invariant: Base fee must decrease when block is empty")
    void testBaseFeeDecrease() {
        long currentBaseFee = 100;
        int targetGas = 1000;
        int usedGas = 0; // Empty block
        
        long nextFee = new FeeMarket().calculateNextBaseFee(currentBaseFee, usedGas, targetGas);
        
        // 100 * (-1000) / 1000 / 8 = -12
        assertThat(nextFee).isEqualTo(88);
    }

    @Test
    @DisplayName("Security: Base fee must never drop below 0")
    void testBaseFeeFloor() {
        long currentBaseFee = 1;
        int targetGas = 1000;
        int usedGas = 0;
        
        long nextFee = new FeeMarket().calculateNextBaseFee(currentBaseFee, usedGas, targetGas);
        assertThat(nextFee).isEqualTo(0);
    }

    @Test
    @DisplayName("Invariant: Base fee must be persisted and loaded correctly")
    void testBaseFeePersistence() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Storage storage = tb.getStorage();
            new FeeMarket().saveBaseFee(storage, 555);
            
            assertThat(new FeeMarket().getCurrentBaseFee(storage)).isEqualTo(555);
        }
    }

    @Test
    @DisplayName("AI Feature: Polynomial Fee Prediction + Scarcity")
    void testFeePredictionPolynomial() {
        feeMarket.resetHistory();
        
        // Add minimal points to test polynomial regression degree 2
        feeMarket.recordFeeDataPoint(100, 10);
        feeMarket.recordFeeDataPoint(200, 20);
        feeMarket.recordFeeDataPoint(300, 40); // Exponention increase
        
        // Predict for 400 with 10 active validators (no scarcity premium)
        long predictedFee = feeMarket.predictOptimalFee(400, 1000, null, 10);
        
        // It should capture the upward curve.
        assertThat(predictedFee).isGreaterThan(40);

        // Test scarcity premium (> 15 validators -> 1.25x premium)
        long scarceFee = feeMarket.predictOptimalFee(400, 1000, null, 20);
        assertThat(scarceFee).isEqualTo(Math.max(0L, Math.round(predictedFee * 1.25)));
    }

    @Test
    @DisplayName("Task 3: Predict falls back when history < 3 entries")
    void testPredictFallbackUnder3Points() throws Exception {
        feeMarket.resetHistory();
        feeMarket.recordFeeDataPoint(100, 10);
        feeMarket.recordFeeDataPoint(200, 20);
        try (TestBlockchain tb = new TestBlockchain()) {
            Storage storage = tb.getStorage();
            feeMarket.saveBaseFee(storage, 15);
            // With < 3 points, it falls back to current base fee via storage
            long pred = feeMarket.predictOptimalFee(500, 1000, storage, 10);
            assertThat(pred).isEqualTo(15);
        }
    }

    @Test
    @DisplayName("Task 3: Regression > baseFee with 20+ data points and high txCount")
    void testRegressionWith20PlusDataPoints() throws Exception {
        feeMarket.resetHistory();
        for (int i = 1; i <= 25; i++) {
            feeMarket.recordFeeDataPoint(i * 10, i * 2); // Linear climb
        }
        try (TestBlockchain tb = new TestBlockchain()) {
            Storage storage = tb.getStorage();
            feeMarket.saveBaseFee(storage, 50); // current base fee
            // Predict for high txCount (above target)
            long pred = feeMarket.predictOptimalFee(1000, 1000, storage, 10);
            assertThat(pred).isGreaterThan(50);
        }
    }

    @Test
    @DisplayName("Severe: Fee Market must maintain stability under extreme 100x congestion")
    void testExtremeCongestionScaling() {
        feeMarket.resetHistory();
        long currentFee = 100;
        
        // Simulate massive staircase increase
        for (int i = 0; i < 50; i++) {
            currentFee = feeMarket.calculateNextBaseFee(currentFee, 2000, 1000); // Constant 100% overflow
        }
        
        // EIP-1559 increases 1.125x each block. 1.125^50 = 361x increase.
        assertThat(currentFee).isGreaterThan(10000);
        
        // Check if prediction is still reasonable (non-overflowing)
        long pred = feeMarket.predictOptimalFee(2000, 100, null, 10);
        assertThat(pred).isLessThan(10000000); // Just a sanity check against overflow
    }
}
