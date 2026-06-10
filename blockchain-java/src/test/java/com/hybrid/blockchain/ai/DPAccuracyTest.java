package com.hybrid.blockchain.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Verification of Gaussian Mechanism accuracy and noise distribution.
 *
 * @paper FL-DABE-BC arXiv:2410.20259
 */
public class DPAccuracyTest {

    @Test
    @DisplayName("P1-E — Gaussian noise variance aligns with theoretical sigma")
    void testGaussianNoiseVariance() {
        double epsilon = 1.0;
        double delta = 1e-5;
        double sensitivity = 1.0;
        double[] input = {100.0};
        
        // sigma = sensitivity * sqrt(2 * ln(1.25/delta)) / epsilon
        // ln(125000) \approx 11.73, sqrt(2 * 11.73) \approx 4.84
        // expected sigma \approx 4.84
        // expected variance \approx 23.4
        
        int trials = 5000;
        double sumNoiseSq = 0;
        
        for (int i = 0; i < trials; i++) {
            double[] noised = DPMechanism.gaussianMechanism(input, epsilon, delta, sensitivity);
            double noise = noised[0] - input[0];
            sumNoiseSq += noise * noise;
        }
        
        double empiricalVariance = sumNoiseSq / trials;
        assertThat(empiricalVariance).isBetween(20.0, 27.0);
    }

    @Test
    @DisplayName("P1-E — Sensitivity scaling: lower sensitivity results in lower noise")
    void testSensitivityScaling() {
        double epsilon = 1.0;
        double delta = 1e-5;
        double[] input = {10.0};
        
        double[] highNoise = DPMechanism.gaussianMechanism(input, epsilon, delta, 1.0);
        double[] lowNoise = DPMechanism.gaussianMechanism(input, epsilon, delta, 0.1);
        
        // This is probabilistic, but 0.1 sensitivity should statistically be much closer to 10.0
        double highDist = Math.abs(highNoise[0] - 10.0);
        double lowDist = Math.abs(lowNoise[0] - 10.0);
        
        // We run a few trials to be sure
        int wins = 0;
        for (int i = 0; i < 100; i++) {
            double h = Math.abs(DPMechanism.gaussianMechanism(input, epsilon, delta, 1.0)[0] - 10.0);
            double l = Math.abs(DPMechanism.gaussianMechanism(input, epsilon, delta, 0.1)[0] - 10.0);
            if (l < h) wins++;
        }
        assertThat(wins).isGreaterThan(70); // 0.1 sensitivity is 10x less noise
    }
}
