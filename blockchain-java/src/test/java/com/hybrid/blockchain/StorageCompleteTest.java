package com.hybrid.blockchain;

import com.hybrid.blockchain.testutil.*;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Unit and integration tests for persistent storage.
 * Covers basic key-value operations, block/state/receipt serialization,
 * blockchain specific indexing, and AES encryption-at-rest.
 */
@Tag("storage")
public class StorageCompleteTest {

    private Path tempDir;
    private Storage storage;
    private static final byte[] AES_KEY = HexUtils.decode("00112233445566778899aabbccddeeff");

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("storage-test-" + UUID.randomUUID());
        storage = new Storage(tempDir.toString(), AES_KEY);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) storage.close();
        if (tempDir != null) FileUtils.deleteDirectory(tempDir.toFile());
    }

    @Test
    @DisplayName("S1.1-1.2 — put/get round-trip for various types")
    void testBasicPutGet() {
        storage.put("string", "hello");
        storage.put("int", 123);
        storage.put("long", 456L);
        Map<String, String> map = Map.of("k1", "v1");
        storage.put("map", (Object)map);
        
        assertThat(storage.get("string", String.class)).isEqualTo("hello");
        assertThat(storage.get("int", Integer.class)).isEqualTo(123);
        assertThat(storage.get("long", Long.class)).isEqualTo(456L);
        assertThat((Map<String, String>)storage.get("map", Map.class)).containsEntry("k1", "v1");
        
        assertThat(storage.get("none", Object.class)).isNull();
    }

    @Test
    @DisplayName("S1.3 — metadata round-trip")
    void testMetadata() {
        storage.putMeta("version", "1.0");
        storage.putMeta("height", 100);
        
        assertThat(storage.getMeta("version")).isEqualTo("1.0");
        assertThat(storage.getMeta("height")).isEqualTo("100"); // Meta often returns String or depends on implementation
    }

    @Test
    @DisplayName("S1.4-1.6 — Block persistence and tip tracking")
    void testBlockPersistence() {
        assertThat(storage.loadTipHash()).isNull();
        
        Block b = new Block(1, 1000L, Collections.emptyList(), "prev", 1, "state");
        String hash = b.getHash();
        storage.saveBlock(b);
        
        Block restored = storage.loadBlockByHash(hash);
        assertThat(restored).isNotNull();
        assertThat(restored.getHash()).isEqualTo(hash);
        assertThat(restored.getIndex()).isEqualTo(1);
        
        assertThat(storage.loadBlockByHeight(1).getHash()).isEqualTo(hash);
        assertThat(storage.loadTipHash()).isEqualTo(hash);
    }

    @Test
    @DisplayName("S1.7 — State persistence")
    void testStatePersistence() {
        AccountState state = new AccountState();
        state.credit("alice", 1000L);
        state.incrementNonce("alice");
        
        storage.saveState(state);
        AccountState restored = storage.loadState();
        
        assertThat(restored.getBalance("alice")).isEqualTo(1000L);
        assertThat(restored.getNonce("alice")).isEqualTo(1L);
    }

    @Test
    @DisplayName("S1.9 — Receipt persistence")
    void testReceiptPersistence() {
        TransactionReceipt r = new TransactionReceipt("tx1", TransactionReceipt.STATUS_SUCCESS, 100, "0xaddr", null);
        r.addEvent("Topic", "Data");
        
        storage.saveReceipt(r);
        TransactionReceipt restored = storage.loadReceipt("tx1");
        
        assertThat(restored.getStatus()).isEqualTo(TransactionReceipt.STATUS_SUCCESS);
        assertThat(restored.getContractAddress()).isEqualTo("0xaddr");
        assertThat(restored.getEvents()).hasSize(1);
    }

    @Test
    @DisplayName("S1.12 — Telemetry persistence")
    void testTelemetryPersistence() {
        byte[] data = "sensor-data".getBytes();
        storage.saveTelemetry("dev1", 10, data);
        
        byte[] restored = storage.loadTelemetry("dev1", 10);
        assertThat(restored).containsExactly(data);
    }

    @Test
    @DisplayName("S1.15 — AES encryption-at-rest verification")
    void testEncryptionAtRest() throws Exception {
        Block b = new Block(1, System.currentTimeMillis(), List.of(), "prev", 1, "state");
        String hash = b.getHash();
        storage.saveBlock(b);

        // Ensure all DB handles are flushed/closed before scanning files on Windows.
        storage.close();
        storage = null;
        
        // Find the block file in LevelDB structure (usually .ldb or .log files)
        // Since we can't easily parse binary LevelDB, we check for the LACK of plaintext hash
        File dir = tempDir.toFile();
        Collection<File> files = FileUtils.listFiles(dir, null, true);
        
        boolean foundPlaintext = false;
        for (File f : files) {
            byte[] content = Files.readAllBytes(f.toPath());
            if (new String(content).contains(hash)) {
                foundPlaintext = true;
                break;
            }
        }
        
        assertThat(foundPlaintext).as("Blockchain data should be encrypted and not contain plaintext hashes in storage files").isFalse();
    }

    @Test
    @DisplayName("S1.16 — close() makes storage unusable")
    void testCloseBehavior() throws Exception {
        storage.close();
        assertThatThrownBy(() -> storage.put("a", "b"))
                .isNotNull(); // Expected exception on closed DB
    }

    @Test
    @DisplayName("S1.17 — Concurrent writes")
    void testConcurrentWrites() throws InterruptedException {
        int count = 100;
        ExecutorService service = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(count);
        
        for (int i = 0; i < count; i++) {
            final int id = i;
            service.submit(() -> {
                try {
                    storage.put("key" + id, id);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(5, TimeUnit.SECONDS);
        service.shutdown();
        
        for (int i = 0; i < count; i++) {
            assertThat(storage.get("key" + i, Integer.class)).isEqualTo(i);
        }
    }
}
