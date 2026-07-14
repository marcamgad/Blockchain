package com.hybrid.blockchain.ai;

import java.security.SecureRandom;

/**
 * PAPER-IMPL: P1-E — DP-Enhanced Federated Learning
 * Differential Privacy mechanism for model weight aggregation.
 *
 * <p><b>Security:</b> the noise source is a {@link SecureRandom}. Differential-privacy
 * guarantees depend on the noise being unpredictable — a {@code java.util.Random}
 * (48-bit LCG, recoverable seed/state) would let an adversary who observes a few
 * outputs predict subsequent noise and subtract it to recover the true aggregate,
 * silently voiding the (ε,δ)-DP guarantee. SecureRandom closes that hole.
 */
public class DPMechanism {
    /** Cryptographically strong shared noise source (thread-safe). */
    private static final SecureRandom random = new SecureRandom();

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
        // Draw u in the OPEN interval (-0.5, 0.5). SecureRandom.nextDouble() returns
        // [0, 1), so u could hit exactly -0.5, making (1 - 2|u|) == 0 and log(0) == -inf
        // (infinite noise). Reject the degenerate endpoint so the inverse-CDF stays finite.
        double u;
        do {
            u = random.nextDouble() - 0.5;
        } while (u <= -0.5);
        // mu - b * sgn(u) * ln(1 - 2|u|)
        return mu - b * Math.signum(u) * Math.log(1 - 2 * Math.abs(u));
    }
}
