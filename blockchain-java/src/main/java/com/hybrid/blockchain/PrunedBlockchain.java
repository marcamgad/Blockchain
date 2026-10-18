package com.hybrid.blockchain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

public class PrunedBlockchain extends Blockchain {
    private static final Logger log = LoggerFactory.getLogger(PrunedBlockchain.class);

    private final int maxBlocks;
    @SuppressWarnings("unused")
    private final Consensus consensus;

    public PrunedBlockchain(Storage storage, Mempool mempool, int maxBlocks, Consensus consensus) throws Exception {
        super(storage, mempool, consensus);
        this.maxBlocks = maxBlocks;
        this.consensus = consensus;
        this.chain = new LinkedList<>(this.chain);
    }

    public PrunedBlockchain(Storage storage, Mempool mempool, int maxBlocks, PoAConsensus poa) throws Exception {
        this(storage, mempool, maxBlocks, (Consensus) poa);
    }

    @Override
    protected void pruneBlock(Block newBlock) {
        try {
            if (chain.size() <= maxBlocks) return;

            Block old = ((LinkedList<Block>) chain).removeFirst();

            // [HC-031] delete the body under its real key, its height index and its snapshot.
            // The previous code deleted a key that never existed and then wrote a FULL snapshot of
            // the current state labelled with the old height (which also reset lastSnapshotHeight).
            storage.deleteBlock(old.getHash());
            storage.del("height:" + old.getIndex());
            storage.del("snapshot:" + old.getIndex());

            log.info("[PRUNE] Removed block {}", old.getIndex());
        } catch (Exception e) {
            throw new RuntimeException("Pruning failed", e);
        }
    }

    @Override
    public int getHeight() {
        return getLatestBlock().getIndex();
    }
}
