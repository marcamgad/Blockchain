package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.TestKeyPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [HC-095] Found by the independent audit of 2026-10-24 (docs/audit/independent-2026-10-24, C-1).
 * A validator's own votes (HC-087) and its view lived only in memory. After a restart it signed a
 * vote conflicting with one it signed before the restart (reproduced by the audit probe), which is
 * valid equivocation evidence: a Byzantine leader could get honest validators jailed whenever they
 * restarted. The view also fell back to 0. Like CometBFT's priv_validator_state.json, the signing
 * record is now written to disk before a vote is signed, and loaded at start-up.
 */
class SigningRecordTest {

    private static final TestKeyPair[] K = {new TestKeyPair(9501), new TestKeyPair(9502), new TestKeyPair(9503), new TestKeyPair(9504)};
    private final List<PBFTConsensus> started = new ArrayList<>();

    @TempDir
    Path dir;

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

    @Test
    @DisplayName("after a restart, a vote conflicting with one signed before is still refused")
    void votesSurviveRestart() throws Exception {
        Path record = dir.resolve("consensus-signing-record.json");
        PBFTConsensus before = node();
        before.useSigningRecord(record);
        assertThat(before.reserveOwnVote(PBFTConsensus.Phase.PREPARE, 0, 5, "aa".repeat(32))).isTrue();
        assertThat(before.reserveOwnVote(PBFTConsensus.Phase.COMMIT, 0, 5, "aa".repeat(32))).isTrue();
        before.shutdown();

        PBFTConsensus after = node();   // the same validator, restarted
        after.useSigningRecord(record);
        assertThat(after.reserveOwnVote(PBFTConsensus.Phase.PREPARE, 0, 5, "bb".repeat(32))).as("conflicting PREPARE").isFalse();
        assertThat(after.reserveOwnVote(PBFTConsensus.Phase.COMMIT, 0, 5, "bb".repeat(32))).as("conflicting COMMIT").isFalse();
        assertThat(after.reserveOwnVote(PBFTConsensus.Phase.PREPARE, 0, 5, "aa".repeat(32))).as("the same vote again").isTrue();
        assertThat(after.hasOwnVote(PBFTConsensus.Phase.PREPARE, 0, 5)).as("the proposer guard sees it too").isTrue();
    }

    @Test
    @DisplayName("the view survives a restart")
    void viewSurvivesRestart() throws Exception {
        Path record = dir.resolve("consensus-signing-record.json");
        PBFTConsensus before = node();
        before.useSigningRecord(record);
        for (int i = 1; i < K.length; i++) {   // a VIEW_CHANGE quorum (q = 3) for view 1
            PBFTConsensus.PBFTMessage vc = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.VIEW_CHANGE, 1, 0, "VIEW_CHANGE_PROOF", K[i].getAddress());
            vc.sign(K[i].getPrivateKey());
            before.addViewChangeVote(1, 0, K[i].getAddress(), vc.signature);
        }
        assertThat(before.getViewNumber()).isEqualTo(1);
        before.shutdown();

        PBFTConsensus after = node();
        after.useSigningRecord(record);
        assertThat(after.getViewNumber()).as("view after restart").isEqualTo(1);
    }

    @Test
    @DisplayName("a corrupt record stops the node instead of letting it sign without one")
    void corruptRecordRefused() throws Exception {
        Path record = dir.resolve("consensus-signing-record.json");
        Files.writeString(record, "{ not json");
        assertThatThrownBy(() -> node().useSigningRecord(record)).hasMessageContaining("HC-095");
    }

    @Test
    @DisplayName("if the record cannot be written, the node does not sign")
    void noSignatureWithoutDurableRecord() throws Exception {
        Path record = dir.resolve("consensus-signing-record.json");
        PBFTConsensus c = node();
        c.useSigningRecord(record);
        Files.delete(record);
        Files.createDirectory(record);   // the record path is now a directory: every write fails
        assertThat(c.reserveOwnVote(PBFTConsensus.Phase.PREPARE, 0, 7, "cc".repeat(32))).isFalse();
        assertThat(c.hasOwnVote(PBFTConsensus.Phase.PREPARE, 0, 7)).as("nothing reserved").isFalse();
    }

    @Test
    @DisplayName("the record can live inside the LevelDB directory (STORAGE_PATH) without disturbing it")
    void coexistsWithStorage() throws Exception {
        // own directory with best-effort cleanup: on Windows LevelDB's mapped files stay locked until GC
        Path db = Files.createTempDirectory("hc095-store-");
        try {
            byte[] aes = com.hybrid.blockchain.HexUtils.decode("00112233445566778899001122334455");
            com.hybrid.blockchain.Storage store = new com.hybrid.blockchain.Storage(db.toString(), aes);
            store.putMeta("probe", "kept");
            PBFTConsensus c = node();
            c.useSigningRecord(db.resolve("consensus-signing-record.json"));
            assertThat(c.reserveOwnVote(PBFTConsensus.Phase.PREPARE, 0, 1, "ee".repeat(32))).isTrue();
            store.close();
            com.hybrid.blockchain.Storage reopened = new com.hybrid.blockchain.Storage(db.toString(), aes);
            assertThat(String.valueOf(reopened.getMeta("probe"))).isEqualTo("kept");
            reopened.close();
            assertThat(db.resolve("consensus-signing-record.json")).exists();
        } finally {
            try { org.apache.commons.io.FileUtils.deleteDirectory(db.toFile()); } catch (Exception ignored) { }
        }
    }

    @Test
    @DisplayName("the record stays bounded: votes far below the committed height are dropped from it")
    void recordIsBounded() throws Exception {
        Path record = dir.resolve("consensus-signing-record.json");
        PBFTConsensus c = node();
        c.useSigningRecord(record);
        for (long seq = 1; seq <= 300; seq++) c.reserveOwnVote(PBFTConsensus.Phase.PREPARE, 0, seq, "dd".repeat(32));
        c.markCommitted("dd".repeat(32), 300, K[0].getAddress());
        c.shutdown();

        PBFTConsensus after = node();
        after.useSigningRecord(record);
        assertThat(after.hasOwnVote(PBFTConsensus.Phase.PREPARE, 0, 300)).isTrue();
        assertThat(after.hasOwnVote(PBFTConsensus.Phase.PREPARE, 0, 150)).as("pruned (more than 100 below the commit)").isFalse();
    }
}
