package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.AccountState;
import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.Mempool;
import com.hybrid.blockchain.Storage;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [HC-089] Found while writing docs/ARCHITECTURE_DEEP_DIVE.md. After a block, Blockchain wrote
 * the block, the tip (with a synchronous write), the UTXO set, the state and the snapshot as
 * separate writes. A failure (or crash) in between left a store whose tip said block N while its
 * state, snapshot and fee metadata described N - 1, or mixed the two. Now one atomic batch per block.
 */
class BlockPersistenceAtomicityTest {

    private static final byte[] AES = HexUtils.decode("00112233445566778899001122334455");

    /** A store whose state write fails once, like a crash in the middle of persisting a block. */
    static final class FailingStorage extends Storage {
        volatile boolean failNextStateWrite;
        FailingStorage(String dir) throws Exception { super(dir, AES); }
        @Override public void saveState(AccountState state) {
            if (failNextStateWrite) {
                failNextStateWrite = false;
                throw new RuntimeException("simulated crash while writing the state");
            }
            super.saveState(state);
        }
    }

    private static PBFTConsensus consensus() {
        Map<String, byte[]> vs = new LinkedHashMap<>();
        for (TestKeyPair k : LedgerPoCFixture.VALIDATORS) vs.put(k.getAddress(), k.getPublicKey());
        return new PBFTConsensus(vs, LedgerPoCFixture.VALIDATORS[0].getAddress(), LedgerPoCFixture.VALIDATORS[0].getPrivateKey());
    }

    @Test
    @DisplayName("a failure while persisting block N leaves the store exactly at block N - 1")
    void persistenceIsAllOrNothing() throws Exception {
        Path dir = Files.createTempDirectory("hc089-");
        try {
            String genesisHash, genesisRoot;
            PBFTConsensus pbft = consensus();
            FailingStorage storage = new FailingStorage(dir.toString());
            Blockchain chain = new Blockchain(storage, new Mempool(1000), pbft);
            chain.init();
            genesisHash = chain.getLatestBlock().getHash();
            genesisRoot = chain.getLatestBlock().getStateRoot();

            TestKeyPair v = LedgerPoCFixture.VALIDATORS[0];
            Block b1 = LedgerPoCFixture.build(chain,
                    List.of(LedgerPoCFixture.mint(v.getAddress(),
                            com.hybrid.blockchain.Tokenomics.getCurrentReward(1, 0))),
                    0, chain.getLatestBlock().getTimestamp() + 1);
            pbft.markCommitted(b1.getHash(), 1, b1.getValidatorId());
            storage.failNextStateWrite = true;
            assertThatThrownBy(() -> chain.applyBlock(b1)).hasMessageContaining("simulated crash");

            assertThat(storage.loadTipHash()).as("tip written although the block was not persisted").isEqualTo(genesisHash);
            assertThat(storage.loadBlockByHeight(1)).as("no half-written block 1").isNull();
            chain.shutdown();
            pbft.shutdown();

            // restart on the same directory: the node must come back at genesis, consistently
            PBFTConsensus pbft2 = consensus();
            Storage reopened = new Storage(dir.toString(), AES);
            Blockchain restarted = new Blockchain(reopened, new Mempool(1000), pbft2);
            restarted.init();
            assertThat(restarted.getLatestBlock().getHash()).isEqualTo(genesisHash);
            assertThat(restarted.getState().calculateStateRoot()).isEqualTo(genesisRoot);

            // and it can still apply block 1 normally
            pbft2.markCommitted(b1.getHash(), 1, b1.getValidatorId());
            restarted.applyBlock(b1);
            assertThat(restarted.getHeight()).isEqualTo(1);
            restarted.shutdown();
            pbft2.shutdown();

            // a store whose state does not match its tip is refused at start-up
            Storage tampered = new Storage(dir.toString(), AES);
            AccountState wrong = new AccountState();
            wrong.credit("hb" + "00".repeat(20), 12345);
            tampered.saveState(wrong);
            tampered.saveSnapshot(1, wrong.toJSON(), new java.util.HashMap<>());   // what a restart resumes from
            tampered.close();
            PBFTConsensus pbft3 = consensus();
            Storage refusedStore = new Storage(dir.toString(), AES);
            Blockchain refused = new Blockchain(refusedStore, new Mempool(1000), pbft3);
            assertThatThrownBy(refused::init).hasMessageContaining("HC-089");
            refusedStore.close();
            pbft3.shutdown();
        } finally {
            // best effort, as in LedgerPoCFixture: on Windows LevelDB's mapped files stay locked until GC
            try { FileUtils.deleteDirectory(dir.toFile()); } catch (Exception ignored) { }
        }
    }
}
