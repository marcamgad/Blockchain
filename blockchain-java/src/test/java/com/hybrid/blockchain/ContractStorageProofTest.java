package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract storage Merkle proofs must verify for stored values and fail for anything else. */
class ContractStorageProofTest {

    @Test
    @DisplayName("a proof for a stored slot verifies; wrong value / wrong key / wrong root do not")
    void storageProofRoundTrip() {
        ContractState cs = new ContractState();
        for (long k = 0; k < 50; k++) cs.put(k, k * 7 + 1);
        byte[] root = HexUtils.decode(cs.calculateRoot());

        List<byte[]> proof = cs.getStorageProof(5);
        assertThat(ContractState.verifyStorageProof(5, 36, proof, root)).as("stored value").isTrue();
        assertThat(ContractState.verifyStorageProof(5, 37, proof, root)).as("wrong value").isFalse();
        assertThat(ContractState.verifyStorageProof(6, 36, proof, root)).as("wrong key").isFalse();
        assertThat(ContractState.verifyStorageProof(5, 36, proof, new byte[32])).as("wrong root").isFalse();
    }
}
