package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-105] Found by the live harness run of 2026-10-24: v4 restarted in view 2 (its signing record)
 * while the others had moved to view 3. Nothing moved a lagging node's view forward, so it refused
 * every proposal of the current leader, its PREPAREs and COMMITs carried the wrong view, and it only
 * followed by sync. A valid commit certificate from view V holds q signatures, so at least f + 1
 * honest validators have reached V: the node now joins that view (honest views only increase).
 */
class ViewCatchUpTest {

    private static final TestKeyPair[] K = {new TestKeyPair(10501), new TestKeyPair(10502), new TestKeyPair(10503), new TestKeyPair(10504)};
    private final List<PBFTConsensus> started = new ArrayList<>();

    private PBFTConsensus node() {
        Map<String, byte[]> set = new HashMap<>();
        for (TestKeyPair t : K) set.put(t.getAddress(), t.getPublicKey());
        PBFTConsensus c = new PBFTConsensus(set, K[0].getAddress(), K[0].getPrivateKey());
        started.add(c);
        return c;
    }

    @AfterEach
    void stop() {
        started.forEach(PBFTConsensus::shutdown);
    }

    private static Block certified(int index, long view, int signers) {
        Block b = new Block(index, 1, new ArrayList<>(), "00".repeat(32), 4, "11".repeat(32));
        b.setValidatorId(K[0].getAddress());
        b.setHash(b.calculateHash());
        b.sign(K[0].getPrivateKey());
        List<Block.CommitSignature> cert = new ArrayList<>();
        for (int i = 1; i <= signers; i++) {
            PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.COMMIT, view, index, b.getHash(), K[i].getAddress());
            m.sign(K[i].getPrivateKey());
            cert.add(new Block.CommitSignature(K[i].getAddress(), view, HexUtils.encode(m.signature)));
        }
        b.setCommitCertificate(cert);
        return b;
    }

    @Test
    @DisplayName("a valid commit certificate from a later view moves the node to that view, durably")
    void joinsTheCertifiedView() throws Exception {
        Path dir = Files.createTempDirectory("hc105");
        Path record = dir.resolve("consensus-signing-record.json");
        PBFTConsensus c = node();
        c.useSigningRecord(record);
        assertThat(c.getViewNumber()).isZero();

        assertThat(c.adoptViewFromCertificate(certified(5, 3, 3))).isTrue();
        assertThat(c.getViewNumber()).isEqualTo(3);
        assertThat(c.getCurrentLeader()).isEqualTo(c.selectLeader(3));

        PBFTConsensus restarted = node();
        restarted.useSigningRecord(record);
        assertThat(restarted.getViewNumber()).as("persisted in the signing record").isEqualTo(3);
    }

    @Test
    @DisplayName("no move backwards, and none from a certificate without a quorum")
    void onlyForwardAndOnlyWithQuorum() {
        PBFTConsensus c = node();
        assertThat(c.adoptViewFromCertificate(certified(5, 9, 2))).as("2 signatures, q = 3").isFalse();
        assertThat(c.getViewNumber()).isZero();

        assertThat(c.adoptViewFromCertificate(certified(5, 4, 3))).isTrue();
        assertThat(c.adoptViewFromCertificate(certified(6, 2, 3))).as("older view").isFalse();
        assertThat(c.getViewNumber()).isEqualTo(4);
    }
}
