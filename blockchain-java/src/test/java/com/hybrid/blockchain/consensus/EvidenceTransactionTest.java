package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.security.QuantumResistantCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.spec.X509EncodedKeySpec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-088] The node's automatic EQUIVOCATION_EVIDENCE transaction was signed with ECDSA only.
 * Under the default REQUIRE_QUANTUM_SIG=true every transaction also needs an ML-DSA signature, so
 * the node's own evidence was rejected ("Could not submit evidence") and nobody was ever jailed
 * automatically. The live harness passed only because its tool signs with both keys.
 */
class EvidenceTransactionTest {

    @Test
    @DisplayName("the node's evidence transaction carries a valid ML-DSA signature as well as ECDSA")
    void evidenceIsHybridSigned() throws Exception {
        TestKeyPair reporter = new TestKeyPair(8801), cheater = new TestKeyPair(8802);
        KeyPair pq = QuantumResistantCrypto.generateDilithiumKeyPair(3);

        PBFTConsensus.EquivocationEvidence ev = new PBFTConsensus.EquivocationEvidence();
        ev.phase = "PREPARE"; ev.view = 0; ev.seq = 9; ev.validatorId = cheater.getAddress();
        ev.hashA = "aa".repeat(32); ev.hashB = "bb".repeat(32);

        Transaction tx = EvidenceTransactions.build(ev, reporter.getPrivateKey(), pq, 7, 3);

        assertThat(tx.getType()).isEqualTo(Transaction.Type.EQUIVOCATION_EVIDENCE);
        assertThat(tx.getFrom()).isEqualTo(reporter.getAddress());
        assertThat(tx.getNonce()).isEqualTo(7);
        assertThat(tx.getFee()).isEqualTo(3);
        assertThat(tx.verify()).as("ECDSA").isTrue();
        assertThat(tx.getDilithiumSignature()).as("ML-DSA signature present").isNotNull();
        java.security.PublicKey pub = KeyFactory.getInstance(QuantumResistantCrypto.ALGORITHM, QuantumResistantCrypto.PROVIDER)
                .generatePublic(new X509EncodedKeySpec(tx.getDilithiumPublicKey()));
        assertThat(pub.getEncoded()).isEqualTo(pq.getPublic().getEncoded());
        // the same check Transaction.verify() makes when REQUIRE_QUANTUM_SIG=true (the test JVM runs with it off)
        assertThat(tx.verifyQuantumSignature()).as("ML-DSA verifies").isTrue();
    }
}
