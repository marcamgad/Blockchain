package com.hybrid.blockchain;

import java.util.List;

public class Difficulty {
    public static int adjustDifficulty(List<Block> chain, int currentDifficulty) {
        if (chain.size() < Config.DIFFICULTY_ADJUSTMENT_INTERVAL + 1) return currentDifficulty;
        int interval = Config.DIFFICULTY_ADJUSTMENT_INTERVAL;
        long actualTime = chain.get(chain.size() - 1).getTimestamp() - chain.get(chain.size() - interval - 1).getTimestamp();
        long expectedTime = interval * Config.TARGET_BLOCK_TIME_MS;
        
        int newDifficulty = currentDifficulty;
        if (actualTime < expectedTime / 2) {
            newDifficulty++;
        } else if (actualTime > expectedTime * 2) {
            newDifficulty = Math.max(1, newDifficulty - 1);
        }

        // TPS-based scaling: Increase difficulty if transaction volume is high to prevent spam
        long totalTxs = 0;
        for (int i = chain.size() - interval; i < chain.size(); i++) {
            totalTxs += chain.get(i).getTransactions().size();
        }
        double tps = (double) totalTxs / (actualTime / 1000.0);
        
        if (tps > 200.0) newDifficulty += 2;
        else if (tps > 50.0) newDifficulty += 1;

        return newDifficulty;
    }
}
