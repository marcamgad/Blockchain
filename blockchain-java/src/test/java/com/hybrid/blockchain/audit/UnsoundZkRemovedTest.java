package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import com.hybrid.blockchain.privacy.ZKProofSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [O-7] The ZK eligibility gate and the ZK primitives that were unsound for their stated use are
 * gone, and nothing can turn the gate back on. Evidence for the removal:
 * docs/decisions/O-7-unsound-zk-removal.md.
 */
class UnsoundZkRemovedTest {

    private static boolean hasMethod(Class<?> c, String fragment) {
        return Arrays.stream(c.getDeclaredMethods()).map(Method::getName).anyMatch(n -> n.toLowerCase().contains(fragment));
    }

    @Test
    @DisplayName("no eligibility gate: no gate class, no PBFT method to enable or feed it")
    void gateRemoved() {
        assertThatThrownBy(() -> Class.forName("com.hybrid.blockchain.reputation.ZkEligibilityGate"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThat(hasMethod(PBFTConsensus.class, "eligib")).as("PBFT eligibility methods").isFalse();
        assertThat(Arrays.stream(PBFTConsensus.class.getDeclaredFields()).map(f -> f.getName().toLowerCase()))
                .noneMatch(n -> n.contains("eligib"));
    }

    @Test
    @DisplayName("unsound primitives removed: range, threshold, firmware 'proofs', key-mismatched commitments")
    void unsoundPrimitivesRemoved() {
        assertThat(Arrays.stream(ZKProofSystem.class.getDeclaredClasses()).map(Class::getSimpleName))
                .doesNotContain("RangeProof", "ThresholdProof", "FirmwareProof")
                .contains("OwnershipProof", "EqualityProof");
        for (String m : new String[]{"rangeproof", "thresholdproof", "generatecommitment", "verifycommitment"}) {
            assertThat(hasMethod(ZKProofSystem.class, m)).as(m).isFalse();
        }
        assertThat(Arrays.stream(DeviceLifecycleManager.class.getDeclaredMethods())
                .flatMap(x -> Arrays.stream(x.getParameterTypes())).map(Class::getSimpleName))
                .doesNotContain("FirmwareProof");
    }
}
