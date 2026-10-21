package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.security.QuantumResistantCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-079] An hq address is bound to BOTH keys: holding only the ECDSA key or only the ML-DSA
 * key is not enough to spend from it. See docs/decisions/HC-079-hybrid-address-binding.md.
 */
class HybridAddressBindingTest {

    private static Transaction.Builder transfer(String from, long nonce) {
        return new Transaction.Builder().type(Transaction.Type.ACCOUNT).from(from).to("hbrecipient")
                .amount(1).fee(1).nonce(nonce);
    }

    @Test
    @DisplayName("the owner of both keys can spend; ECDSA-only or ML-DSA-only attackers cannot")
    void bothKeysRequired() throws Exception {
        TestKeyPair ec = new TestKeyPair(9101);
        KeyPair pq = QuantumResistantCrypto.generateDilithiumKeyPair(3);
        String hq = Crypto.deriveHybridAddress(ec.getPublicKey(), pq.getPublic().getEncoded());
        assertThat(hq).startsWith("hq").hasSize(42);

        Transaction legit = transfer(null, 1).signHybridBound(ec.getPrivateKey(), ec.getPublicKey(), pq.getPrivate(), pq.getPublic());
        assertThat(legit.getFrom()).isEqualTo(hq);
        assertThat(legit.verify()).as("owner of both keys").isTrue();

        // attacker recovered the ECDSA key (quantum attack) and brings their own ML-DSA key
        KeyPair attackerPq = QuantumResistantCrypto.generateDilithiumKeyPair(3);
        Transaction ecOnly = transfer(hq, 1).signHybrid(ec.getPrivateKey(), ec.getPublicKey(), attackerPq.getPrivate(), attackerPq.getPublic());
        assertThat(ecOnly.verify()).as("ECDSA key + foreign ML-DSA key").isFalse();

        // attacker stole the ML-DSA key and brings their own ECDSA key
        TestKeyPair attackerEc = new TestKeyPair(9102);
        Transaction pqOnly = transfer(hq, 1).signHybrid(attackerEc.getPrivateKey(), attackerEc.getPublicKey(), pq.getPrivate(), pq.getPublic());
        assertThat(pqOnly.verify()).as("foreign ECDSA key + ML-DSA key").isFalse();

        // the right keys but no ML-DSA signature at all
        Transaction noPq = transfer(hq, 1).sign(ec.getPrivateKey(), ec.getPublicKey());
        assertThat(noPq.verify()).as("hq sender without ML-DSA signature").isFalse();
    }

    @Test
    @DisplayName("end to end: an hq account receives funds and spends them on a real chain")
    void spendFromHybridAccount() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair ec = new TestKeyPair(9103);
            KeyPair pq = QuantumResistantCrypto.generateDilithiumKeyPair(3);
            String hq = Crypto.deriveHybridAddress(ec.getPublicKey(), pq.getPublic().getEncoded());
            f.fund(hq);
            long fee = Math.max(1, f.chain.getCurrentBaseFee());
            Transaction spend = new Transaction.Builder().type(Transaction.Type.ACCOUNT).to("hbrecipient")
                    .amount(5).fee(fee).nonce(1)
                    .signHybridBound(ec.getPrivateKey(), ec.getPublicKey(), pq.getPrivate(), pq.getPublic());
            f.chain.validateTransaction(spend);
            f.commitAndApply(f.build(java.util.List.of(spend), 0));
            assertThat(f.chain.getState().getBalance("hbrecipient")).isEqualTo(5);
        }
    }
}
