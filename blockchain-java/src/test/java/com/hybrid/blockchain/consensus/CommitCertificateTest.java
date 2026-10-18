package com.hybrid.blockchain.consensus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.BlockApplier;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.TestBlockchain;
import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [HC-046] finality needs a quorum (commit certificate), not one leader signature;
 * [HC-066] vote signatures are unambiguous.
 */
class CommitCertificateTest {

    /** A block committed on chain A, as a syncing peer receives it (JSON round trip). */
    private static Block committedOnA(TestBlockchain a) throws Exception {
        Block b = BlockApplier.createAndApplyBlock(a, List.of());
        b.setCommitCertificate(a.getConsensus().buildCommitCertificate(b.getIndex(), b.getHash()));
        ObjectMapper m = new ObjectMapper();
        return m.convertValue(m.convertValue(b, Map.class), Block.class);
    }

    @Test
    @DisplayName("a synced block with a valid 3-of-4 certificate is accepted by a node that never saw the votes")
    void certificateLetsAPeerSync() throws Exception {
        try (TestBlockchain a = new TestBlockchain(); TestBlockchain b = new TestBlockchain()) {
            Block block = committedOnA(a);
            assertThat(block.getCommitCertificate()).hasSize(3);
            b.getBlockchain().validateBlock(block);
            b.getBlockchain().applyBlock(block);
            assertThat(b.getBlockchain().getHeight()).isEqualTo(block.getIndex());
        }
    }

    @Test
    @DisplayName("leader signature only, or an under-quorum / forged certificate, is not final")
    void leaderSignatureAloneIsNotFinal() throws Exception {
        try (TestBlockchain a = new TestBlockchain(); TestBlockchain b = new TestBlockchain()) {
            Block block = committedOnA(a);
            List<Block.CommitSignature> full = block.getCommitCertificate();

            block.setCommitCertificate(null);
            assertThatThrownBy(() -> b.getBlockchain().applyBlock(block)).hasMessageContaining("signature");

            block.setCommitCertificate(new ArrayList<>(full.subList(0, 2)));          // 2 < quorum 3
            assertThatThrownBy(() -> b.getBlockchain().applyBlock(block)).hasMessageContaining("signature");

            List<Block.CommitSignature> dup = new ArrayList<>(full.subList(0, 2));
            dup.add(full.get(0));                                                     // same signer twice
            block.setCommitCertificate(dup);
            assertThatThrownBy(() -> b.getBlockchain().applyBlock(block)).hasMessageContaining("signature");

            List<Block.CommitSignature> forged = new ArrayList<>(full.subList(0, 2));
            forged.add(new Block.CommitSignature(full.get(2).validatorId, full.get(2).view,
                    full.get(0).signature));                                          // someone else's signature
            block.setCommitCertificate(forged);
            assertThatThrownBy(() -> b.getBlockchain().applyBlock(block)).hasMessageContaining("signature");

            assertThat(b.getBlockchain().getHeight()).isZero();
            block.setCommitCertificate(full);
            b.getBlockchain().applyBlock(block);
            assertThat(b.getBlockchain().getHeight()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("HC-065: a proposal with a bad proposer signature fails validateBlock")
    void proposalSignatureIsVerified() throws Exception {
        try (TestBlockchain t = new TestBlockchain()) {
            Block last = t.getBlockchain().getLatestBlock();
            Block p = new Block(last.getIndex() + 1, last.getTimestamp() + 1, List.of(), last.getHash(), 1, "root");
            p.setValidatorId(t.getValidatorKey().getAddress());
            p.setSignature(Crypto.sign(Crypto.hash(p.serializeCanonical()), new TestKeyPair(99).getPrivateKey()));
            assertThatThrownBy(() -> t.getBlockchain().validateBlock(p)).hasMessageContaining("Invalid proposer signature");
        }
    }

    @Test
    @DisplayName("HC-066: (view=1, seq=23) and (view=12, seq=3) no longer share a signature")
    void voteEncodingIsUnambiguous() {
        TestKeyPair k = new TestKeyPair(1);
        PBFTConsensus.PBFTMessage v1 = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.COMMIT, 1, 23, "h", k.getAddress());
        v1.sign(k.getPrivateKey());
        PBFTConsensus.PBFTMessage v12 = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.COMMIT, 12, 3, "h", k.getAddress());
        v12.signature = v1.signature;
        assertThat(v1.verify(k.getPublicKey())).isTrue();
        assertThat(v12.verify(k.getPublicKey())).isFalse();
    }
}
