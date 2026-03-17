package com.hybrid.blockchain.testutil;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.Mempool;
import com.hybrid.blockchain.Storage;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.HexUtils;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A lightweight, deterministic harness for testing blockchain logic.
 * Enforces Config.DEBUG = false.
 */
public class ChainFixture implements AutoCloseable {

    private final Path tempDir;
    private final Storage storage;
    private final Mempool mempool;
    private final PBFTConsensus consensus;
    private final Blockchain blockchain;

    public ChainFixture() throws Exception {
        // Absolute Rule: DEBUG must be FALSE
        if (Config.isDebug()) {
            throw new IllegalStateException("Absolute Law Violation: Config.DEBUG is true. Please ensure tests are run without debug mode.");
        }

        this.tempDir = Files.createTempDirectory("chain-fixture-" + UUID.randomUUID());
        byte[] aesKey = HexUtils.decode("00112233445566778899001122334455"); // Static test key
        this.storage = new Storage(tempDir.toString(), aesKey);
        this.mempool = new Mempool(1000);

        // Setup dummy consensus with minimum 4 validators (just for instantiation, tests might override behavior)
        Map<String, byte[]> validators = new HashMap<>();
        TestKeyPair v1 = new TestKeyPair(1001);
        TestKeyPair v2 = new TestKeyPair(1002);
        TestKeyPair v3 = new TestKeyPair(1003);
        TestKeyPair v4 = new TestKeyPair(1004);
        
        validators.put(v1.getAddress(), v1.getPublicKey());
        validators.put(v2.getAddress(), v2.getPublicKey());
        validators.put(v3.getAddress(), v3.getPublicKey());
        validators.put(v4.getAddress(), v4.getPublicKey());
        
        this.consensus = new PBFTConsensus(validators, v1.getAddress(), v1.getPrivateKey());

        this.blockchain = new Blockchain(storage, mempool, consensus);
    }

    public Blockchain getBlockchain() {
        return blockchain;
    }

    public Storage getStorage() {
        return storage;
    }

    public Mempool getMempool() {
        return mempool;
    }

    @Override
    public void close() throws Exception {
        if (blockchain != null) {
            blockchain.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
        if (tempDir != null) {
            try {
                FileUtils.deleteDirectory(tempDir.toFile());
            } catch (IOException e) {
                // Ignore cleanup errors in tests
            }
        }
    }
}
