package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** [HC-002] Quorum-intersection property for every validator-set size the system may run with. */
@Tag("audit-property")
class QuorumPropertyTest {

    @Test
    void quorumIsSafeAndLiveForEveryN() {
        for (int n = 4; n <= 64; n++) {
            int f = PBFTConsensus.maxFaulty(n);
            int q = PBFTConsensus.quorumSize(n);
            // safety: any two quorums share at least f + 1 validators, hence one honest validator
            assertThat(2 * q - n).as("n=%d f=%d q=%d intersection", n, f, q).isGreaterThanOrEqualTo(f + 1);
            // liveness: the n - f honest validators can form a quorum on their own
            assertThat(q).as("n=%d f=%d q=%d liveness", n, f, q).isLessThanOrEqualTo(n - f);
        }
    }

    @Test
    void oldTwoFPlusOneWasUnsafeExactlyWhenNIsNot3fPlus1() {
        for (int n = 4; n <= 64; n++) {
            int f = PBFTConsensus.maxFaulty(n);
            boolean oldSafe = 2 * (2 * f + 1) - n >= f + 1;
            assertThat(oldSafe).as("n=%d", n).isEqualTo(n == 3 * f + 1);
        }
    }
}
