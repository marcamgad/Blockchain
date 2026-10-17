package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [HC-018] Negative tests for every UTXO validation rule, plus the positive case. */
class UtxoValidationTest {

    private static Transaction.Builder spend(String txid, int idx) {
        return new Transaction.Builder().type(Transaction.Type.UTXO).addInput(txid, idx).fee(0).nonce(1);
    }

    @Test
    @DisplayName("owner-signed spend of an existing output is valid")
    void validSpend() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            TestKeyPair alice = new TestKeyPair(11);
            tb.getBlockchain().getUTXOSet().addOutput("seed", 0, alice.getAddress(), 1000L);
            Transaction tx = spend("seed", 0).addOutput("bob", 1000L).sign(alice.getPrivateKey(), alice.getPublicKey());
            tb.getBlockchain().validateTransaction(tx, true);
        }
    }

    @Test
    @DisplayName("spending someone else's output is rejected")
    void foreignInput() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            TestKeyPair alice = new TestKeyPair(11), mallory = new TestKeyPair(12);
            tb.getBlockchain().getUTXOSet().addOutput("seed", 0, alice.getAddress(), 1000L);
            Transaction tx = spend("seed", 0).addOutput("m", 1000L).sign(mallory.getPrivateKey(), mallory.getPublicKey());
            assertThatThrownBy(() -> tb.getBlockchain().validateTransaction(tx, true)).hasMessageContaining("not owned");
        }
    }

    @Test
    @DisplayName("the same input twice is rejected")
    void duplicateInput() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            TestKeyPair alice = new TestKeyPair(11);
            tb.getBlockchain().getUTXOSet().addOutput("seed", 0, alice.getAddress(), 1000L);
            Transaction tx = spend("seed", 0).addInput("seed", 0).addOutput("bob", 2000L)
                    .sign(alice.getPrivateKey(), alice.getPublicKey());
            assertThatThrownBy(() -> tb.getBlockchain().validateTransaction(tx, true)).hasMessageContaining("Duplicate");
        }
    }

    @Test
    @DisplayName("negative and zero outputs are rejected")
    void nonPositiveOutput() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            TestKeyPair alice = new TestKeyPair(11);
            tb.getBlockchain().getUTXOSet().addOutput("seed", 0, alice.getAddress(), 1000L);
            Transaction neg = spend("seed", 0).addOutput("x", -500L).addOutput("y", 1500L)
                    .sign(alice.getPrivateKey(), alice.getPublicKey());
            Transaction zero = spend("seed", 0).addOutput("x", 0L).sign(alice.getPrivateKey(), alice.getPublicKey());
            assertThatThrownBy(() -> tb.getBlockchain().validateTransaction(neg, true)).hasMessageContaining("positive");
            assertThatThrownBy(() -> tb.getBlockchain().validateTransaction(zero, true)).hasMessageContaining("positive");
        }
    }

    @Test
    @DisplayName("unsigned UTXO tx is rejected")
    void unsigned() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Transaction tx = new Transaction.Builder().type(Transaction.Type.UTXO).addOutput("x", 5L).build();
            assertThatThrownBy(() -> tb.getBlockchain().validateTransaction(tx, true)).hasMessageContaining("signed");
        }
    }

    @Test
    @DisplayName("UTXOSet refuses non-positive amounts and overwrites")
    void utxoSetInvariants() {
        UTXOSet set = new UTXOSet();
        set.addOutput("t", 0, "a", 1L);
        assertThatThrownBy(() -> set.addOutput("t", 1, "a", -1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> set.addOutput("t", 0, "b", 5L)).isInstanceOf(IllegalStateException.class);
        assertThat(set.getOwner("t", 0)).isEqualTo("a");
    }
}
