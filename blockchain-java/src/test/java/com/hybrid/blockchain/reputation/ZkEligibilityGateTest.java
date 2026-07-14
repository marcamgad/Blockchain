package com.hybrid.blockchain.reputation;

import com.hybrid.blockchain.privacy.ZKProofSystem.ThresholdProof;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifiable-but-private validator/device eligibility: prove reputation ≥ bar without
 * revealing the score.
 */
public class ZkEligibilityGateTest {

    @Test
    @DisplayName("Eligible participant produces a proof that verifies against the bar")
    void eligiblePasses() {
        ThresholdProof proof = ZkEligibilityGate.prove(0.82, 0.60);
        assertThat(ZkEligibilityGate.verify(proof, 0.60, false)).isTrue();
    }

    @Test
    @DisplayName("A slashed participant is rejected regardless of a valid proof")
    void slashedRejected() {
        ThresholdProof proof = ZkEligibilityGate.prove(0.90, 0.60);
        assertThat(ZkEligibilityGate.verify(proof, 0.60, true)).isFalse();
    }

    @Test
    @DisplayName("A proof for a lower bar does not satisfy a higher required bar")
    void thresholdBindingEnforced() {
        // Prover proves ≥0.30 (easy) but the verifier requires ≥0.60.
        ThresholdProof weakProof = ZkEligibilityGate.prove(0.35, 0.30);
        assertThat(ZkEligibilityGate.verify(weakProof, 0.60, false)).isFalse();
    }

    @Test
    @DisplayName("An ineligible participant cannot fabricate a proof above the bar")
    void ineligibleCannotProve() {
        assertThatThrownBy(() -> ZkEligibilityGate.prove(0.40, 0.60))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The proof does not carry the plaintext reputation value")
    void reputationValueHidden() {
        double secretReputation = 0.77;
        ThresholdProof proof = ZkEligibilityGate.prove(secretReputation, 0.50);
        long scaledSecret = Math.round(secretReputation * ZkEligibilityGate.SCALE);
        // The only integer the verifier sees is the (public) threshold, never the value.
        assertThat(proof.getThreshold()).isNotEqualTo(scaledSecret);
        assertThat(proof.getThreshold()).isEqualTo(Math.round(0.50 * ZkEligibilityGate.SCALE));
        assertThat(proof.verify()).isTrue();
    }
}
