package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.privacy.ZKProofSystem.ThresholdProof;
import com.hybrid.blockchain.reputation.ZkEligibilityGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Leader selection gated by zero-knowledge reputation eligibility: a validator proves
 * it meets the bar without publishing its score.
 */
public class ZkLeaderEligibilityTest {

    private PBFTConsensus consensus;
    private TestKeyPair v1, v2, v3, v4;

    @BeforeEach
    void setup() {
        v1 = new TestKeyPair(301);
        v2 = new TestKeyPair(302);
        v3 = new TestKeyPair(303);
        v4 = new TestKeyPair(304);
        Map<String, byte[]> validators = new HashMap<>();
        validators.put(v1.getAddress(), v1.getPublicKey());
        validators.put(v2.getAddress(), v2.getPublicKey());
        validators.put(v3.getAddress(), v3.getPublicKey());
        validators.put(v4.getAddress(), v4.getPublicKey());
        consensus = new PBFTConsensus(validators, v1.getAddress(), v1.getPrivateKey());
    }

    @AfterEach
    void teardown() {
        if (consensus != null) consensus.shutdown();
    }

    @Test
    @DisplayName("Gate is off by default — leader selection is unchanged")
    void gateOffByDefault() {
        assertThat(consensus.isZkEligibilityRequired()).isFalse();
        assertThat(consensus.selectLeader(0L)).isNotNull();
    }

    @Test
    @DisplayName("With the gate on and no proofs submitted, no leader is selectable")
    void noProofsMeansNoLeader() {
        consensus.setZkEligibilityRequired(true);
        assertThat(consensus.selectLeader(0L)).isNull();
    }

    @Test
    @DisplayName("A validator that proves reputation above the bar becomes leader-eligible")
    void provenValidatorIsEligible() {
        consensus.setZkEligibilityRequired(true);
        consensus.setMinEligibleReputation(0.5);

        ThresholdProof proof = ZkEligibilityGate.prove(0.90, 0.5);
        assertThat(consensus.submitEligibilityProof(v2.getAddress(), proof)).isTrue();
        assertThat(consensus.isZkEligible(v2.getAddress())).isTrue();

        // Only v2 is eligible, so it must be the selected leader for any view.
        assertThat(consensus.selectLeader(0L)).isEqualTo(v2.getAddress());
        assertThat(consensus.selectLeader(7L)).isEqualTo(v2.getAddress());
    }

    @Test
    @DisplayName("A proof for a lower bar than required is rejected")
    void weakProofRejected() {
        consensus.setZkEligibilityRequired(true);
        consensus.setMinEligibleReputation(0.80);

        // Prover proves only ≥0.20 while the gate demands ≥0.80.
        ThresholdProof weak = ZkEligibilityGate.prove(0.25, 0.20);
        assertThat(consensus.submitEligibilityProof(v2.getAddress(), weak)).isFalse();
        assertThat(consensus.isZkEligible(v2.getAddress())).isFalse();
        assertThat(consensus.selectLeader(0L)).isNull();
    }

    @Test
    @DisplayName("An unknown (non-validator) identity cannot become eligible")
    void unknownValidatorRejected() {
        consensus.setZkEligibilityRequired(true);
        ThresholdProof proof = ZkEligibilityGate.prove(0.99, 0.5);
        assertThat(consensus.submitEligibilityProof("not-a-validator", proof)).isFalse();
    }

    @Test
    @DisplayName("Revoking eligibility removes the validator from leader selection")
    void revokeRemovesEligibility() {
        consensus.setZkEligibilityRequired(true);
        consensus.setMinEligibleReputation(0.5);
        consensus.submitEligibilityProof(v3.getAddress(), ZkEligibilityGate.prove(0.75, 0.5));
        assertThat(consensus.selectLeader(0L)).isEqualTo(v3.getAddress());

        consensus.revokeEligibility(v3.getAddress());
        assertThat(consensus.isZkEligible(v3.getAddress())).isFalse();
        assertThat(consensus.selectLeader(0L)).isNull();
    }

    @Test
    @DisplayName("The eligibility proof never carries the plaintext reputation score")
    void scoreStaysHidden() {
        consensus.setZkEligibilityRequired(true);
        consensus.setMinEligibleReputation(0.5);
        double secret = 0.87;
        ThresholdProof proof = ZkEligibilityGate.prove(secret, 0.5);
        consensus.submitEligibilityProof(v2.getAddress(), proof);

        // Verifier only ever sees the public bar, never the score.
        assertThat(proof.getThreshold())
                .isEqualTo(Math.round(0.5 * ZkEligibilityGate.SCALE))
                .isNotEqualTo(Math.round(secret * ZkEligibilityGate.SCALE));
    }
}
