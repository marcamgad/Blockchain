package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("unit")
public class FeeMarketTest {

    @Test
    @DisplayName("Invariant: Base fee must increase when block is full (congestion)")
    void testBaseFeeIncrease() {
        long currentBaseFee = 100;
        int targetGas = 1000;
        int usedGas = 2000; // Double the target
        
        long nextFee = FeeMarket.calculateNextBaseFee(currentBaseFee, usedGas, targetGas);
        
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
        
        long nextFee = FeeMarket.calculateNextBaseFee(currentBaseFee, usedGas, targetGas);
        
        // 100 * (-1000) / 1000 / 8 = -12
        assertThat(nextFee).isEqualTo(88);
    }

    @Test
    @DisplayName("Security: Base fee must never drop below 1")
    void testBaseFeeFloor() {
        long currentBaseFee = 1;
        int targetGas = 1000;
        int usedGas = 0;
        
        long nextFee = FeeMarket.calculateNextBaseFee(currentBaseFee, usedGas, targetGas);
        assertThat(nextFee).isEqualTo(1);
    }

    @Test
    @DisplayName("Invariant: Base fee must be persisted and loaded correctly")
    void testBaseFeePersistence() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Storage storage = tb.getStorage();
            FeeMarket.saveBaseFee(storage, 555);
            
            assertThat(FeeMarket.getCurrentBaseFee(storage)).isEqualTo(555);
        }
    }

    @Test
    @DisplayName("AI Feature: Polynomial Fee Prediction + Scarcity")
    void testFeePredictionPolynomial() {
        FeeMarket.resetHistory();
        
        // Add minimal points to test polynomial regression degree 2
        FeeMarket.recordFeeDataPoint(100, 10);
        FeeMarket.recordFeeDataPoint(200, 20);
        FeeMarket.recordFeeDataPoint(300, 40); // Exponention increase
        
        // Predict for 400 with 10 active validators (no scarcity premium)
        long predictedFee = FeeMarket.predictOptimalFee(400, 1000, null, 10);
        
        // It should capture the upward curve.
        assertThat(predictedFee).isGreaterThan(40);

        // Test scarcity premium (> 15 validators -> 1.25x premium)
        long scarceFee = FeeMarket.predictOptimalFee(400, 1000, null, 20);
        assertThat(scarceFee).isEqualTo(Math.max(1L, Math.round(predictedFee * 1.25)));
    }

    @Test
    @DisplayName("Severe: Fee Market must maintain stability under extreme 100x congestion")
    void testExtremeCongestionScaling() {
        FeeMarket.resetHistory();
        long currentFee = 100;
        
        // Simulate massive staircase increase
        for (int i = 0; i < 50; i++) {
            currentFee = FeeMarket.calculateNextBaseFee(currentFee, 2000, 1000); // Constant 100% overflow
        }
        
        // EIP-1559 increases 1.125x each block. 1.125^50 = 361x increase.
        assertThat(currentFee).isGreaterThan(10000);
        
        // Check if prediction is still reasonable (non-overflowing)
        long pred = FeeMarket.predictOptimalFee(2000, 100, null, 10);
        assertThat(pred).isLessThan(10000000); // Just a sanity check against overflow
    }
}
