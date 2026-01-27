package com.hybrid.blockchain;

public class PrunedBlockchain extends Blockchain {

    private final int maxBlocks;
    @SuppressWarnings("unused")
    private final PoAConsensus poa;

    public PrunedBlockchain(Storage storage, Mempool mempool, int maxBlocks, PoAConsensus poa) throws Exception {
        super(storage, mempool, poa);
        this.maxBlocks = maxBlocks;
        this.poa = poa;
    }

    @Override
    protected void pruneBlock(Block newBlock) {
        try {
            if (chain.size() <= maxBlocks) return;

            Block old = chain.remove(0);

            storage.del("block:" + old.getHash());
            storage.del("height:" + old.getIndex());

            System.out.println("[PRUNE] Removed block " + old.getIndex());

            if (old.getIndex() % 100 == 0) {
                storage.saveSnapshot(
                    old.getIndex(),
                    state.toJSON(),
                    utxo.toJSON()
                );
            }
        } catch (Exception e) {
            throw new RuntimeException("Pruning failed", e);
        }
    }
}
