package com.hybrid.blockchain.consensus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.Transaction;

import java.math.BigInteger;
import java.security.KeyPair;

/**
 * Builds the EQUIVOCATION_EVIDENCE transaction a node submits when it sees a double vote.
 *
 * <p>[HC-088] Signed with ECDSA AND ML-DSA. The node used to sign it with ECDSA only, so under the
 * default REQUIRE_QUANTUM_SIG=true its own evidence was rejected and no validator was ever jailed
 * automatically. The ML-DSA key can be any key the node holds: for a classical "hb" account it is
 * not bound to the address (HC-079), it only has to satisfy the network-wide signature rule.</p>
 */
public final class EvidenceTransactions {

    private EvidenceTransactions() {
    }

    public static Transaction build(PBFTConsensus.EquivocationEvidence ev, BigInteger reporterKey, KeyPair mldsa,
                                    long nonce, long fee) throws Exception {
        return new Transaction.Builder()
                .type(Transaction.Type.EQUIVOCATION_EVIDENCE)
                .from(Crypto.deriveAddress(Crypto.derivePublicKey(reporterKey)))
                .data(new ObjectMapper().writeValueAsBytes(ev))
                .fee(fee)
                .nonce(nonce)
                .signHybrid(reporterKey, Crypto.derivePublicKey(reporterKey), mldsa.getPrivate(), mldsa.getPublic());
    }
}
