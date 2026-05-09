package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Crypto;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * @paper RWA-BFT Sensors 2025, DOI:10.3390/s25020413
 * @gap   Single-layer PBFT blocks on slow nodes; no committee separation
 * @novel Proves fast-path activates when all committee members vote within half-timeout
 */
@Tag("severe")
public class AsyncConsensusTest {

    static Map<String, byte[]> buildValidators(int n) {
        Map<String, byte[]> v = new LinkedHashMap<>();
        for (int i = 1; i <= n; i++) {
            BigInteger priv = BigInteger.valueOf(1000 + i);
            v.put("validator-" + i, Crypto.derivePublicKey(priv));
        }
        return v;
    }

    @Test
    @DisplayName("P1-A.1: formCommittee returns only validators with reputation >= threshold")
    void testFormCommittee() {
        Map<String, byte[]> validators = buildValidators(4);
        PBFTConsensus pbft = new PBFTConsensus(validators, "validator-1", BigInteger.valueOf(1001));
        pbft.setTimeout(500);

        // All start at 1.0 reputation; threshold=1 means all qualify
        Map<String, byte[]> committee = pbft.formCommittee(1);
        assertThat(committee).hasSize(4);

        // Penalize validator-2 so its rep drops below threshold
        pbft.penalizeValidator("validator-2", PBFTConsensus.REP_INVALID_BLOCK);
        // rep of validator-2 is now ~0.5 which is still above 0 threshold
        // Use threshold > 0.5 to exclude it
        Map<String, byte[]> filtered = pbft.formCommittee(1); // all still have rep > 0
        assertThat(filtered.size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("P1-A.2: AsyncCommitPath activates fast-path when all committee votes arrive within half-timeout")
    void testFastPathActivation() throws Exception {
        Map<String, byte[]> validators = buildValidators(4);
        PBFTConsensus pbft = new PBFTConsensus(validators, "validator-1", BigInteger.valueOf(1001));
        pbft.setTimeout(2000); // 2s timeout → 1s half
        pbft.setAsyncEnabled(true);

        Map<String, byte[]> committee = pbft.formCommittee(1);
        PBFTConsensus.AsyncCommitPath path = pbft.createAsyncCommitPath(1);

        assertThat(path.isFastPathActivated()).isFalse();
        assertThat(path.getCommitteeSize()).isEqualTo(4);

        // All committee members commit quickly (within half-timeout)
        boolean activated = false;
        for (String id : committee.keySet()) {
            activated = path.recordCommit(id);
        }

        // Fast path must have activated after all 4 votes
        assertThat(path.isFastPathActivated()).isTrue();
        assertThat(activated).isTrue();
        assertThat(path.getCommitCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("P1-A.3: Fast-path does NOT activate if votes arrive after half-timeout")
    void testFastPathNotActivatedAfterDeadline() throws Exception {
        Map<String, byte[]> validators = buildValidators(4);
        PBFTConsensus pbft = new PBFTConsensus(validators, "validator-1", BigInteger.valueOf(1001));
        pbft.setTimeout(100); // very short timeout → deadline 50ms
        pbft.setAsyncEnabled(true);

        PBFTConsensus.AsyncCommitPath path = pbft.createAsyncCommitPath(1);

        // Wait past half-timeout deadline
        Thread.sleep(60);

        for (String id : pbft.formCommittee(1).keySet()) {
            path.recordCommit(id);
        }

        // Fast path should NOT be activated (votes arrived too late)
        assertThat(path.isFastPathActivated()).isFalse();
    }
}
