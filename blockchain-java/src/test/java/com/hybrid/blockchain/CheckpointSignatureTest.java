package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** [HC-043] checkpoint signing and signature collection. */
class CheckpointSignatureTest {

    private static String sign(Checkpoint cp, TestKeyPair k) {
        return HexUtils.encode(Crypto.sign(HexUtils.decode(cp.computeCheckpointHash()), k.getPrivateKey()));
    }

    @Test
    @DisplayName("a validator signs only checkpoints that match its own chain")
    void signsOnlyMatchingCheckpoints() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Block b = BlockApplier.createAndApplyBlock(tb, List.of());
            PeerNode node = new PeerNode(0, tb.getBlockchain(), tb.getConsensus(), tb.getValidatorKey().getPrivateKey());
            Checkpoint good = new Checkpoint(b.getIndex(), b.getHash(), b.getStateRoot(), "00", 1L, new HashMap<>());
            Checkpoint wrongRoot = new Checkpoint(b.getIndex(), b.getHash(), "ab".repeat(32), "00", 1L, new HashMap<>());
            Checkpoint wrongHash = new Checkpoint(b.getIndex(), "cd".repeat(32), b.getStateRoot(), "00", 1L, new HashMap<>());
            assertThat(node.matchesLocalChain(good)).isTrue();
            assertThat(node.matchesLocalChain(wrongRoot)).isFalse();
            assertThat(node.matchesLocalChain(wrongHash)).isFalse();
        }
    }

    @Test
    @DisplayName("only valid signatures by known validators over the same content are merged")
    void mergesOnlyVerifiedSignatures() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            PeerNode node = new PeerNode(0, tb.getBlockchain(), tb.getConsensus(), tb.getValidatorKey().getPrivateKey());
            Checkpoint pending = new Checkpoint(5, "aa".repeat(32), "bb".repeat(32), "cc", 1L, new HashMap<>());
            Checkpoint incoming = new Checkpoint(5, "aa".repeat(32), "bb".repeat(32), "cc", 1L, new HashMap<>());
            incoming.getValidatorSignatures().put(new TestKeyPair(2).getAddress(), sign(incoming, new TestKeyPair(2)));   // valid
            incoming.getValidatorSignatures().put(new TestKeyPair(3).getAddress(), HexUtils.encode(new byte[64]));      // garbage
            incoming.getValidatorSignatures().put(new TestKeyPair(99).getAddress(), sign(incoming, new TestKeyPair(99))); // not a validator
            incoming.getValidatorSignatures().put(new TestKeyPair(4).getAddress(), sign(incoming, new TestKeyPair(5))); // wrong key
            assertThat(node.mergeVerifiedSignatures(pending, incoming)).isEqualTo(1);
            assertThat(pending.getValidatorSignatures()).containsOnlyKeys(new TestKeyPair(2).getAddress());

            Checkpoint different = new Checkpoint(5, "ee".repeat(32), "bb".repeat(32), "cc", 1L, new HashMap<>());
            different.getValidatorSignatures().put(new TestKeyPair(3).getAddress(), sign(different, new TestKeyPair(3)));
            assertThat(node.mergeVerifiedSignatures(pending, different)).as("other checkpoint, same height").isZero();
        }
    }
}
