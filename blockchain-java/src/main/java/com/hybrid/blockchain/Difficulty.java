package com.hybrid.blockchain;

import java.util.List;

public class Difficulty {
    public static int adjustDifficulty(List<Block> chain, int currentDifficulty) {
        if (chain.size() < Config.DIFFICULTY_ADJUSTMENT_INTERVAL + 1) return currentDifficulty;
        int interval = Config.DIFFICULTY_ADJUSTMENT_INTERVAL;
        long actualTime = chain.get(chain.size() - 1).getTimestamp() - chain.get(chain.size() - interval - 1).getTimestamp();
        long expectedTime = interval * Config.TARGET_BLOCK_TIME_MS;
        if (actualTime < expectedTime / 2) {
            return currentDifficulty + 1;
        } else if (actualTime > expectedTime * 2) {
            return Math.max(1, currentDifficulty - 1);
        }
        return currentDifficulty;
    }
}
