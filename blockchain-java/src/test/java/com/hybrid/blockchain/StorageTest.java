package com.hybrid.blockchain;

import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.fusesource.leveldbjni.JniDBFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class StorageTest {

    private Path tempDir;
    private Storage storage;
    private static final byte[] AES = HexUtils.decode("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("storage-test-");
        storage = new Storage(tempDir.toString(), AES);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) {
            storage.close();
        }
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    @Test
    @DisplayName("put/get round-trip returns original object")
    void putGetRoundTrip() throws Exception {
        storage.put("k1", Map.of("a", 1, "b", "x"));
        Map value = storage.get("k1", Map.class);
        assertEquals(1, value.get("a"), "Stored map must preserve numeric field values across encrypted round-trip");
        assertEquals("x", value.get("b"), "Stored map must preserve string field values across encrypted round-trip");
    }

    @Test
    @DisplayName("get on non-existent key returns null")
    void getMissingReturnsNull() throws Exception {
        assertNull(storage.get("missing", Map.class), "Reading a missing key must return null rather than throwing");
    }

    @Test
    @DisplayName("del removes key so subsequent get returns null")
    void delRemovesKey() throws Exception {
        storage.put("k2", Map.of("x", 1));
        storage.del("k2");
        assertNull(storage.get("k2", Map.class), "Deleted keys must not be retrievable afterwards");
    }

    @Test
    @DisplayName("Data persists across distinct Storage instances on same path")
    void persistenceAcrossInstances() throws Exception {
        storage.put("persist", Map.of("n", 42));
        storage.close();

        Storage second = new Storage(tempDir.toString(), AES);
        Map value = second.get("persist", Map.class);
        second.close();

        assertEquals(42, value.get("n"), "Data must persist on disk and remain readable by subsequent Storage instances");
    }

    @Test
    @DisplayName("Raw LevelDB payload differs from plaintext JSON due encryption")
    void dataIsEncryptedOnDisk() throws Exception {
        storage.put("enc", Map.of("secret", "value"));
        storage.close();

        DB rawDb = factory.open(tempDir.toFile(), new Options().createIfMissing(true));
        byte[] raw = rawDb.get(bytes("enc"));
        rawDb.close();
        storage = new Storage(tempDir.toString(), AES);

        assertNotNull(raw, "Raw LevelDB value bytes must exist for stored key");
        assertFalse(new String(raw).contains("secret"), "Encrypted LevelDB bytes must not expose plaintext JSON content");
    }

    @Test
    @DisplayName("Wrong AES key cannot decrypt existing payload")
    void wrongAesKeyFailsDecryption() throws Exception {
        storage.put("secure", Map.of("v", 1));
        storage.close();

        byte[] wrong = HexUtils.decode("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Storage wrongStorage = new Storage(tempDir.toString(), wrong);
        assertThrows(IOException.class, () -> wrongStorage.get("secure", Map.class), "Opening same DB with wrong AES key must fail authenticated decryption");
        wrongStorage.close();
    }

    @Test
    @DisplayName("Invalid AES key lengths throw IllegalArgumentException")
    void wrongAesLengthThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Storage(tempDir.resolve("k15").toString(), new byte[15]), "15-byte AES keys must be rejected by constructor");
        assertThrows(IllegalArgumentException.class, () -> new Storage(tempDir.resolve("k17").toString(), new byte[17]), "17-byte AES keys must be rejected by constructor");
    }

    @Test
    @DisplayName("saveBlock/loadBlockByHash round-trip preserves block fields")
    void saveLoadByHashRoundTrip() throws Exception {
        Block b = new Block(1, System.currentTimeMillis(), java.util.List.of(), "00", 1, HexUtils.encode(new byte[32]));
        b.setValidatorId("v1");
        b.setSignature(new byte[64]);
        storage.saveBlock("h1", b);

        Block loaded = storage.loadBlockByHash("h1");
        assertEquals(b.getIndex(), loaded.getIndex(), "Block index must survive save/load by hash");
        assertEquals(b.getPrevHash(), loaded.getPrevHash(), "Block prevHash must survive save/load by hash");
    }

    @Test
    @DisplayName("saveBlock/loadBlockByHeight round-trip works")
    void saveLoadByHeightRoundTrip() throws Exception {
        Block b = new Block(2, System.currentTimeMillis(), java.util.List.of(), "00", 1, HexUtils.encode(new byte[32]));
        storage.saveBlock("h2", b);

        Block loaded = storage.loadBlockByHeight(2);
        assertNotNull(loaded, "Block saved by height mapping must be resolvable by height");
        assertEquals(2, loaded.getIndex(), "Loaded block height must match stored block index");
    }

    @Test
    @DisplayName("saveBlock updates chain tip and loadTipHash returns it")
    void tipHashRoundTrip() throws Exception {
        Block b = new Block(3, System.currentTimeMillis(), java.util.List.of(), "00", 1, HexUtils.encode(new byte[32]));
        storage.saveBlock("tip-hash", b);

        assertEquals("tip-hash", storage.loadTipHash(), "loadTipHash must return hash written by most recent saveBlock tip update");
    }

    @Test
    @DisplayName("saveUTXO/loadUTXO round-trip works")
    void utxoRoundTrip() throws Exception {
        Map<String, Object> utxo = Map.of("a:0", Map.of("address", "hb-a", "amount", 5));
        storage.saveUTXO(utxo);
        assertEquals(utxo, storage.loadUTXO(), "UTXO serialized map must survive storage round-trip");
    }

    @Test
    @DisplayName("saveState/loadState round-trip works")
    void stateRoundTrip() throws Exception {
        Map<String, Object> state = Map.of("accounts", Map.of("hb-a", Map.of("balance", 10, "nonce", 1)));
        storage.saveState(state);
        assertEquals(state, storage.loadState(), "Account state map must survive encrypted storage round-trip");
    }

    @Test
    @DisplayName("putMeta/getMeta supports Integer, String, and Long")
    void metaRoundTripTypes() throws Exception {
        storage.putMeta("i", 7);
        storage.putMeta("s", "ok");
        storage.putMeta("l", 9L);

        assertEquals(7, ((Number) storage.getMeta("i")).intValue(), "Integer metadata must round-trip through encrypted storage");
        assertEquals("ok", storage.getMeta("s"), "String metadata must round-trip through encrypted storage");
        assertEquals(9L, ((Number) storage.getMeta("l")).longValue(), "Long metadata must round-trip through encrypted storage");
    }

    @Test
    @DisplayName("LRU cache evicts old entries past 512 while data remains on disk")
    void lruCacheEvictionBehavior() throws Exception {
        for (int i = 0; i < 600; i++) {
            storage.put("k" + i, Map.of("v", i));
        }

        Field cacheField = Storage.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        Map<String, Object> cache = (Map<String, Object>) cacheField.get(storage);

        assertFalse(cache.containsKey("k0"), "Oldest cache entry should be evicted once cache size exceeds 512 entries");
        assertEquals(0, ((Number) ((Map<?, ?>) storage.get("k0", Map.class)).get("v")).intValue(), "Evicted cache entries must still be readable from persistent LevelDB storage");
    }

    @Test
    @DisplayName("Operations after close throw an exception")
    void operationsAfterCloseThrow() throws Exception {
        storage.close();
        assertThrows(Exception.class, () -> storage.put("x", Map.of("a", 1)), "Storage operations after close must fail rather than silently corrupt state");
    }
}
