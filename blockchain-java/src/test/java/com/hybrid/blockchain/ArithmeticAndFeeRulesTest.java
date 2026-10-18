package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [HC-033] regression tests. */
class ArithmeticAndFeeRulesTest {

    @Test
    @DisplayName("a balance credit that would overflow throws instead of wrapping negative")
    void creditOverflowThrows() {
        AccountState s = new AccountState();
        s.credit("a", Long.MAX_VALUE);
        assertThatThrownBy(() -> s.credit("a", 1)).isInstanceOf(ArithmeticException.class);
        assertThat(s.getBalance("a")).isEqualTo(Long.MAX_VALUE);
        s.creditToken("a", "T", Long.MAX_VALUE);
        assertThatThrownBy(() -> s.creditToken("a", "T", 1)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> s.creditToken("a", "T", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("every signed transaction type must pay at least the base fee")
    void zeroFeeTokenTxRejected() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            TestKeyPair owner = new TestKeyPair(6101);
            tb.getBlockchain().getAccountState().credit(owner.getAddress(), 1000);
            BlockApplier.createAndApplyBlock(tb, java.util.List.of());   // base fee becomes >= 1
            Transaction burn = new Transaction.Builder().type(Transaction.Type.TOKEN_BURN).from(owner.getAddress())
                    .data("X".getBytes(StandardCharsets.UTF_8)).fee(0).nonce(1)
                    .sign(owner.getPrivateKey(), owner.getPublicKey());
            assertThatThrownBy(() -> tb.getBlockchain().validateTransaction(burn, true))
                    .hasMessageContaining("below baseFee");
        }
    }

    @Test
    @DisplayName("MPT put(byte[]) / get(byte[]) / delete(byte[]) agree on key hashing")
    void mptByteKeyApiConsistent() {
        MerklePatriciaTrie t = new MerklePatriciaTrie();
        byte[] k = "key".getBytes(StandardCharsets.UTF_8);
        t.put(k, new byte[]{1});
        assertThat(t.get(k)).containsExactly(1);
        assertThat(t.get("key")).containsExactly(1);
        t.delete(k);
        assertThat(t.get(k)).isNull();
    }
}
