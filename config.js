const { gossipsub } = require("@libp2p/gossipsub");

module.exports = {
    networkId: 'hybrid-net-1',
    dbPath: './data/chain-db',
    mempoolMaxSize: 5000,
    gossipsubTopicTx: 'hybrid:tx:v1',
    gossipsubTopicBlock: 'hybrid:block:v1',
    difficultyAdjusmentInterval: 10,
    targetBlockTimeSec: 15,
    initialDifficulty: 3,
    maxBlockTxs: 200,
    maxNonceAttempts: 5_000_000,
    minerReward: 50,
    checkpointInterval: 100
};