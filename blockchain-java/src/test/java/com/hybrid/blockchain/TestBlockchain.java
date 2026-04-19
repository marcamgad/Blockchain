package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.apache.commons.io.FileUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Isolated blockchain instance for testing.
 * Automatically handles cleanup of temporary LevelDB storage.
 */
public class TestBlockchain implements AutoCloseable {
    private final Path tempDir;
    private final Storage storage;
    private final Blockchain blockchain;
    private final PBFTConsensus consensus;
    private final TestKeyPair validatorKey;

    public TestBlockchain() throws Exception {
        this.tempDir = Files.createTempDirectory("hybrid-test-" + UUID.randomUUID());
        byte[] aesKey = HexUtils.decode("00112233445566778899001122334455");
        
        this.storage = new Storage(tempDir.toString(), aesKey);
        
        // Setup PBFT with 4 validators (minimum required for PBFT quorum of 3)
        Map<String, byte[]> validatorSet = new HashMap<>();
        this.validatorKey = new TestKeyPair(1);
        validatorSet.put(validatorKey.getAddress(), validatorKey.getPublicKey());
        
        for (int i = 2; i <= 4; i++) {
            TestKeyPair extra = new TestKeyPair(i);
            validatorSet.put(extra.getAddress(), extra.getPublicKey());
        }

        this.consensus = new PBFTConsensus(validatorSet, validatorKey.getAddress(), validatorKey.getPrivateKey());
        
        Mempool mempool = new Mempool();
        
        this.blockchain = new Blockchain(storage, mempool, consensus);
        // Config.BYPASS_CONTRACT_AUDIT = false; (Default)
        this.blockchain.init();
    }

    public Blockchain getBlockchain() {
        return blockchain;
    }

    public Storage getStorage() {
        return storage;
    }

    public PBFTConsensus getConsensus() {
        return consensus;
    }

    public TestKeyPair getValidatorKey() {
        return validatorKey;
    }

    @Override
    public void close() throws Exception {
        if (storage != null) {
            storage.close();
        }
        if (tempDir != null) {
            FileUtils.deleteDirectory(tempDir.toFile());
        }
    }
}
