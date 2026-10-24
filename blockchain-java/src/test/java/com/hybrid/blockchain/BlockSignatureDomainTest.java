package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-091] The proposer signed SHA-256(canonical header), i.e. the block hash itself: the only
 * signed structure without its own domain tag, and the proposer id was not covered
 * (Block.DOMAIN_PREFIX "BLOCK\0" existed but was unused). The proposer now signs
 * hashWithDomain("HYBRIDCHAIN-BLOCK-SIG-v1", blockHash, validatorId).
 */
class BlockSignatureDomainTest {

    @Test
    @DisplayName("the proposer signature is domain-separated, covers the proposer id, and the old form is refused")
    void domainSeparated() {
        TestKeyPair[] k = {new TestKeyPair(9101), new TestKeyPair(9102), new TestKeyPair(9103), new TestKeyPair(9104)};
        Map<String, byte[]> set = new HashMap<>();
        for (TestKeyPair t : k) set.put(t.getAddress(), t.getPublicKey());
        PBFTConsensus pbft = new PBFTConsensus(set, k[0].getAddress(), k[0].getPrivateKey());
        try {
            Block b = new Block(1, Config.GENESIS_TIMESTAMP + 1, new ArrayList<>(), "00".repeat(32), 4, "11".repeat(32));
            b.setValidatorId(k[0].getAddress());
            b.setHash(b.calculateHash());

            byte[] digest = b.signingDigest();
            assertThat(digest).as("not the bare block hash").isNotEqualTo(Crypto.hash(b.serializeCanonical()));

            b.setSignature(Crypto.sign(Crypto.hash(b.serializeCanonical()), k[0].getPrivateKey()));   // the old form
            assertThat(pbft.verifyProposerSignature(b, null)).as("signature over the bare hash").isFalse();

            b.sign(k[0].getPrivateKey());
            assertThat(pbft.verifyProposerSignature(b, null)).isTrue();

            Block relabelled = new Block(1, b.getTimestamp(), new ArrayList<>(), b.getPrevHash(), 4, b.getStateRoot());
            relabelled.setValidatorId(k[1].getAddress());
            relabelled.setHash(relabelled.calculateHash());
            assertThat(relabelled.signingDigest()).as("the proposer id is covered").isNotEqualTo(digest);
        } finally {
            pbft.shutdown();
        }
    }
}
