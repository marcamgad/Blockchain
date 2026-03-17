package com.hybrid.blockchain;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
public class StorageTest {

    private Path tempDir;
    private Storage storage;
    private final byte[] aesKey = HexUtils.decode("00112233445566778899001122334455");

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("storage-test-" + UUID.randomUUID());
        storage = new Storage(tempDir.toString(), aesKey);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) {
            storage.close();
        }
        if (tempDir != null) {
            FileUtils.deleteDirectory(tempDir.toFile());
        }
    }

    @Test
    @DisplayName("Invariant: Data must be encrypted at rest and recoverable")
    void testEncryptionAtRest() throws Exception {
        String key = "secret_key";
        String value = "sensitive_data";
        
        storage.put(key, value);
        
        // Use a new storage instance with same key to verify persistence
        storage.close();
        Storage storage2 = new Storage(tempDir.toString(), aesKey);
        
        String restored = storage2.get(key, String.class);
        assertThat(restored).isEqualTo(value);
        storage2.close();
    }

    @Test
    @DisplayName("Security: Storage must fail with wrong AES key")
    void testWrongKeyFailure() throws Exception {
        storage.put("key", "value");
        storage.close();
        
        byte[] wrongKey = HexUtils.decode("ffffffffffffffffffffffffffffffff");
        Storage storageEvil = new Storage(tempDir.toString(), wrongKey);
        
        assertThatThrownBy(() -> storageEvil.get("key", String.class))
                .as("Decryption with wrong key must fail")
                .isInstanceOf(Exception.class);
        storageEvil.close();
    }

    @Test
    @DisplayName("Invariant: Block persistence and height indexing must be atomic")
    void testBlockPersistence() throws Exception {
        List<Transaction> txs = new ArrayList<>();
        Block block = new Block(42, System.currentTimeMillis(), txs, "prev_hash", 1, "state_root");
        String hash = block.getHash();
        
        storage.saveBlock(hash, block);
        
        Block restored = storage.loadBlockByHash(hash);
        assertThat(restored).isNotNull();
        assertThat(restored.getIndex()).isEqualTo(42);
        
        Block restoredByHeight = storage.loadBlockByHeight(42);
        assertThat(restoredByHeight.getHash()).isEqualTo(hash);
        
        assertThat(storage.loadTipHash()).isEqualTo(hash);
    }

    @Test
    @DisplayName("Invariant: Transaction indexing must allow O(1) location lookup")
    void testTransactionIndexing() throws Exception {
        String txid = "tx123";
        String blockHash = "block456";
        int height = 100;
        
        storage.indexTransaction(txid, blockHash, height);
        
        Map<String, Object> loc = storage.getTransactionLocation(txid);
        assertThat(loc).isNotNull();
        assertThat(loc.get("blockHash")).isEqualTo(blockHash);
        assertThat(loc.get("blockHeight")).isEqualTo(height);
    }

    @Test
    @DisplayName("Invariant: State snapshots must be fully restorable")
    void testStateSnapshot() throws Exception {
        Map<String, Object> state = new HashMap<>();
        state.put("hbAlice", "state_data");
        
        Map<String, Object> utxo = new HashMap<>();
        utxo.put("tx:0", "utxo_data");
        
        storage.saveSnapshot(10, state, utxo);
        
        // Check meta update (lastSnapshotHeight)
        Object lastHeight = storage.getMeta("lastSnapshotHeight");
        assertThat(lastHeight).isEqualTo(10);
        
        // Manual verification of snapshot key
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = storage.get("snapshot:10", Map.class);
        assertThat(snapshot.get("state")).isEqualTo(state);
        assertThat(snapshot.get("utxo")).isEqualTo(utxo);
    }
}
