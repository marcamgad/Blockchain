package com.hybrid.blockchain.ai;

import java.util.Random;

/**
 * PAPER-IMPL: P1-E — DP-Enhanced Federated Learning
 * Differential Privacy mechanism for model weight aggregation.
 */
public class DPMechanism {
    private static final Random random = new Random();

    /**
     * Gaussian mechanism for (epsilon, delta)-DP.
     * sigma = sensitivity * sqrt(2 * ln(1.25 / delta)) / epsilon
     *
     * @paper FL-DABE-BC arXiv:2410.20259
     */
    public static double[] gaussianMechanism(double[] values, double epsilon, double delta, double sensitivity) {
        if (epsilon <= 0) return values;
        double sigma = sensitivity * Math.sqrt(2.0 * Math.log(1.25 / delta)) / epsilon;
        double[] noisy = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            noisy[i] = values[i] + random.nextGaussian() * sigma;
        }
        return noisy;
    }

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
