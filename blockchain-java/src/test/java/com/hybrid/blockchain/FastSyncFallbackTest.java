package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** [HC-032] A checkpoint whose state does not match must leave NO trace in the node's state. */
class FastSyncFallbackTest {

    @Test
    @DisplayName("checkpoint state-root mismatch -> clean genesis, nothing from the checkpoint kept")
    void mismatchFallsBackCleanly() throws Exception {
        Path dir = Files.createTempDirectory("fastsync-");
        TestKeyPair[] v = {new TestKeyPair(1), new TestKeyPair(2), new TestKeyPair(3), new TestKeyPair(4)};
        Map<String, byte[]> vals = new LinkedHashMap<>();
        for (TestKeyPair k : v) vals.put(k.getAddress(), k.getPublicKey());
        PBFTConsensus pbft = new PBFTConsensus(vals, v[0].getAddress(), v[0].getPrivateKey());
        Storage st = new Storage(dir.toString(), HexUtils.decode("00112233445566778899001122334455"));
        try {
            // a checkpoint block (stored under the legacy key, so no chain:tip is written) ...
            Block cpBlock = new Block(1000, Config.GENESIS_TIMESTAMP + 1000, new ArrayList<>(), "00".repeat(32), 1, "11".repeat(32));
            st.put("block:" + cpBlock.getHash(), cpBlock);
            // ... with a stored "state" that holds a big balance but does NOT match the checkpoint root
            AccountState bogus = new AccountState();
            bogus.credit("mallory", 1_000_000);
            st.saveState(bogus);
            Checkpoint cp = new Checkpoint(1000, cpBlock.getHash(), "22".repeat(32), "33".repeat(32), 1L, new HashMap<>());
            byte[] digest = HexUtils.decode(cp.computeCheckpointHash());
            for (int i = 0; i < 3; i++)                      // quorum of valid validator signatures
                cp.getValidatorSignatures().put(v[i].getAddress(), HexUtils.encode(Crypto.sign(digest, v[i].getPrivateKey())));
            st.saveCheckpoint(cp);

            Blockchain chain = new Blockchain(st, new Mempool(10), pbft);
            chain.init();

            assertThat(chain.getChain()).hasSize(1);
            assertThat(chain.getLatestBlock().getIndex()).as("genesis only, no checkpoint block").isZero();
            assertThat(chain.getState().getBalance("mallory")).as("unverified state discarded").isZero();
        } finally {
            pbft.shutdown();
            st.close();
            FileUtils.deleteDirectory(dir.toFile());
        }
    }
}
