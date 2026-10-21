package com.hybrid.blockchain.harness;

import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.api.IoTRestAPI;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** [O-2] The parts of the fault-injection harness that can run without Docker. */
class HarnessHelpersTest {

    @Test
    @DisplayName("the evidence the harness builds is accepted by PBFT verification")
    void evidenceIsValid() throws Exception {
        TestKeyPair[] k = {new TestKeyPair(1), new TestKeyPair(2), new TestKeyPair(3), new TestKeyPair(4)};
        Map<String, byte[]> set = new HashMap<>();
        for (TestKeyPair t : k) set.put(t.getAddress(), t.getPublicKey());
        PBFTConsensus pbft = new PBFTConsensus(set, k[0].getAddress(), k[0].getPrivateKey());
        try {
            PBFTConsensus.EquivocationEvidence ev = EquivocationEvidenceTool.evidence(k[2].getPrivateKey(), 0, 999_999L);
            assertThat(ev.validatorId).isEqualTo(k[2].getAddress());
            assertThat(pbft.verifyEvidence(ev)).isTrue();
            Transaction tx = EquivocationEvidenceTool.transaction(ev, k[1].getPrivateKey(), 1, 1);
            assertThat(tx.verify()).isTrue();
        } finally {
            pbft.shutdown();
        }
    }

    @Test
    @DisplayName("GET /consensus reports the jail list, so a checker can compare it across nodes")
    void consensusStatusReportsJailList() {
        TestKeyPair[] k = {new TestKeyPair(1), new TestKeyPair(2), new TestKeyPair(3), new TestKeyPair(4)};
        Map<String, byte[]> set = new HashMap<>();
        for (TestKeyPair t : k) set.put(t.getAddress(), t.getPublicKey());
        PBFTConsensus pbft = new PBFTConsensus(set, k[0].getAddress(), k[0].getPrivateKey());
        try {
            pbft.setJailedValidators(List.of(k[3].getAddress()));
            IoTRestAPI.setNode(null, null, pbft);
            ResponseEntity<?> r = new IoTRestAPI().getConsensusStatus();
            assertThat((Map<String, Object>) r.getBody()).containsEntry("jailedValidators", new java.util.TreeSet<>(Set.of(k[3].getAddress())));
        } finally {
            IoTRestAPI.setNode(null, null, null);
            pbft.shutdown();
        }
    }
}
