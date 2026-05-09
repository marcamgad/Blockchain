package com.hybrid.blockchain.ai;

import java.util.Random;

/**
 * PAPER-IMPL: P1-E — DP-Enhanced Federated Learning
 * Differential Privacy mechanism for model weight aggregation.
 */
public class DPMechanism {
    private static final Random random = new Random();

    /**
     * Applies Laplace noise to a vector of values.
     *
     * @param values      raw aggregated weights
     * @param epsilon     privacy budget (lower = more noise/privacy)
     * @param sensitivity L1 sensitivity of the aggregation function
     * @return noisy weights
     */
    public static double[] laplaceMechanism(double[] values, double epsilon, double sensitivity) {
        if (epsilon <= 0) return values; // DP disabled
        
        double[] noisyValues = new double[values.length];
        double b = sensitivity / epsilon;
        for (int i = 0; i < values.length; i++) {
            noisyValues[i] = values[i] + sampleLaplace(0, b);
        }
        return noisyValues;
    }

    private static double sampleLaplace(double mu, double b) {
        double u = random.nextDouble() - 0.5;
        // mu - b * sgn(u) * ln(1 - 2|u|)
        return mu - b * Math.signum(u) * Math.log(1 - 2 * Math.abs(u));
    }
}
