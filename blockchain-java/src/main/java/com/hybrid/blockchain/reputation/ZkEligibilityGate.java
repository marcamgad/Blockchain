package com.hybrid.blockchain.reputation;

import com.hybrid.blockchain.privacy.ZKProofSystem.ThresholdProof;

/**
 * Zero-knowledge eligibility gate for validators / devices.
 *
 * <p>Lets a participant prove it meets a reputation bar — {@code reputation ≥ minReputation}
 * — <b>without revealing its actual reputation score</b>. This replaces plaintext
 * reputation comparisons (where every node learns every other node's exact standing)
 * with a verifiable-but-private predicate, using the Pedersen-commitment threshold
 * proof already in {@link com.hybrid.blockchain.privacy.ZKProofSystem}.
 *
 * <p>Typical use: gate reputation-weighted leader selection or a privileged action so a
 * validator proves it is above the eligibility threshold and (publicly) not slashed,
 * while its precise score stays hidden from peers.
 *
 * <p>Reputation is a double in [0,1]; it is scaled to a long by {@link #SCALE} because
 * the underlying threshold proof operates over integer scalars.
 */
public final class ZkEligibilityGate {

    /** Fixed-point scale mapping a [0,1] reputation to an integer scalar. */
    public static final long SCALE = 1_000_000L;

    private ZkEligibilityGate() {}

    /**
     * Produce a proof that {@code reputation ≥ minReputation}, hiding the actual value.
     *
     * @throws IllegalArgumentException if the prover is not actually eligible (the proof
     *         system refuses to fabricate a proof for a value below the threshold).
     */
    public static ThresholdProof prove(double reputation, double minReputation) {
        long v = scale(reputation);
        long thr = scale(minReputation);
        return ThresholdProof.create(v, thr, true);
    }

    /**
     * Verify an eligibility proof against the required bar and public slashing status.
     *
     * @param proof         the threshold proof presented by the participant
     * @param minReputation the required minimum reputation (verifier's policy)
     * @param slashed       public slashing status of the participant
     * @return true iff the participant is not slashed and the proof soundly shows
     *         reputation ≥ minReputation for exactly the required threshold
     */
    public static boolean verify(ThresholdProof proof, double minReputation, boolean slashed) {
        if (slashed || proof == null) return false;
        // Bind the proof to the required threshold: without this a prover could present a
        // valid proof for a lower bar than the verifier demands.
        if (proof.getThreshold() != scale(minReputation)) return false;
        return proof.verify();
    }

    private static long scale(double reputation) {
        long s = Math.round(reputation * SCALE);
        return Math.max(0, s);
    }
}
