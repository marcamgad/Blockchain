package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.privacy.ZKProofSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [O-6] PRIVATE_TRANSFER moved no value; it is now rejected outright, with its ordinal kept. */
class PrivateTransferDisabledTest {

    @Test
    @DisplayName("even a well-formed, correctly bound PRIVATE_TRANSFER is rejected as disabled")
    void rejected() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair alice = new TestKeyPair(6601);
            f.fund(alice.getAddress());
            ZKProofSystem.OwnershipProof proof = ZKProofSystem.OwnershipProof.createForPrivateTransfer(
                    alice.getPrivateKey().toByteArray(), alice.getPublicKey(), alice.getAddress(), 1);
            Transaction tx = new Transaction.Builder().type(Transaction.Type.PRIVATE_TRANSFER).from(alice.getAddress())
                    .data(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(proof))
                    .fee(Math.max(1, f.chain.getCurrentBaseFee())).nonce(1)
                    .sign(alice.getPrivateKey(), alice.getPublicKey());
            assertThatThrownBy(() -> f.chain.validateTransaction(tx)).hasMessageContaining("PRIVATE_TRANSFER is disabled");
        }
    }

    @Test
    @DisplayName("the enum position is unchanged, so existing transaction encodings stay valid")
    void ordinalKept() throws Exception {
        assertThat(Transaction.Type.PRIVATE_TRANSFER.ordinal()).isEqualTo(13);
        assertThat(Transaction.Type.class.getField("PRIVATE_TRANSFER").isAnnotationPresent(Deprecated.class)).isTrue();
    }
}
