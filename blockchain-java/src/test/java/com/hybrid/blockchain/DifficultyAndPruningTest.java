package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class DifficultyAndPruningTest {

    private List<Block> syntheticChain(long spacingMs, int count) {
        List<Block> chain = new ArrayList<>();
        long ts = System.currentTimeMillis();
        chain.add(new Block(0, ts, List.of(), "0000000000000000000000000000000000000000000000000000000000000000", 1, HexUtils.encode(new byte[32])));
        for (int i = 1; i <= count; i++) {
            ts += spacingMs;
            chain.add(new Block(i, ts, List.of(), chain.get(i - 1).getHash(), 1, HexUtils.encode(new byte[32])));
        }
        return chain;
    }

    @Test
    @DisplayName("Difficulty increases for blocks produced faster than target/2")
    void difficultyIncreasesForFastBlocks() {
        List<Block> chain = syntheticChain(Config.TARGET_BLOCK_TIME_MS / 4, Config.DIFFICULTY_ADJUSTMENT_INTERVAL + 1);
        int adjusted = Difficulty.adjustDifficulty(chain, 5);
        assertEquals(6, adjusted, "Difficulty must increase by one when production is faster than half target interval");
    }

    @Test
    @DisplayName("Difficulty decreases for blocks produced slower than target*2")
    void difficultyDecreasesForSlowBlocks() {
        List<Block> chain = syntheticChain(Config.TARGET_BLOCK_TIME_MS * 3, Config.DIFFICULTY_ADJUSTMENT_INTERVAL + 1);
        int adjusted = Difficulty.adjustDifficulty(chain, 5);
        assertEquals(4, adjusted, "Difficulty must decrease by one when production is slower than double target interval");
    }

    @Test
    @DisplayName("Difficulty never drops below one")
    void difficultyNeverBelowOne() {
        List<Block> chain = syntheticChain(Config.TARGET_BLOCK_TIME_MS * 3, Config.DIFFICULTY_ADJUSTMENT_INTERVAL + 1);
        int adjusted = Difficulty.adjustDifficulty(chain, 1);
        assertEquals(1, adjusted, "Difficulty floor must remain at one even for extremely slow production");
    }

    @Test
    @DisplayName("PrunedBlockchain keeps at most maxBlocks in memory after 3x blocks")
    void prunedChainMemoryCap() throws Exception {
        byte[] aes = HexUtils.decode("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
        var temp = Files.createTempDirectory("pruned-");

        BigInteger vPriv = BigInteger.valueOf(1001);
        Validator validator = new Validator(Crypto.deriveAddress(Crypto.derivePublicKey(vPriv)), Crypto.derivePublicKey(vPriv));
        PoAConsensus poa = new PoAConsensus(List.of(validator));

        Storage storage = new Storage(temp.toString(), aes);
        PrunedBlockchain chain = new PrunedBlockchain(storage, new Mempool(1000), 20, poa);
        chain.init();

        for (int i = 0; i < 60; i++) {
            Block b = chain.createBlock(validator.getId(), 10);
            poa.signBlock(b, validator, vPriv);
            chain.applyBlock(b);
        }

        assertTrue(chain.getChain().size() <= 20, "Pruned chain in-memory block list must never exceed configured maxBlocks");
        assertEquals(60, chain.getHeight(), "Pruned chain absolute height must continue increasing despite in-memory pruning");

        chain.shutdown();
        Files.walk(temp).sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
    }
}
