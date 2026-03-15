package com.hybrid.blockchain;

import java.util.LinkedList;

public class PrunedBlockchain extends Blockchain {

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

            storage.del("block:" + old.getHash());
            storage.del("height:" + old.getIndex());

            System.out.println("[PRUNE] Removed block " + old.getIndex());

            storage.saveSnapshot(
                old.getIndex(),
                state.toJSON(),
                utxo.toJSON()
            );
        } catch (Exception e) {
            throw new RuntimeException("Pruning failed", e);
        }
    }

    @Override
    public int getHeight() {
        return getLatestBlock().getIndex();
    }
}
