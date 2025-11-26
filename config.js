module.exports = {
    networkId: 'hybrid-net-1',
    dbPath: './data/chain-db',
    mempoolMaxSize: 5000,
    gossipsubTopicTx: 'hybrid:tx:v1',
    gossipsubTopicBlock: 'hybrid:block:v1',
    difficultyAdjustmentInterval: 10,
    targetBlockTimeSec: 15,
    initialDifficulty: 2,
    maxBlockTxs: 200,
    maxNonceAttempts: 10_000_000,
    minerReward: 50,
    checkpointInterval: 100
};