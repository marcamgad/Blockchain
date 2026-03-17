package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper to create, vote on, and apply blocks to a TestBlockchain.
 * Ensures PBFT quorum is reached automatically.
 */
public class BlockApplier {
    
    public static Block createAndApplyBlock(TestBlockchain tb, List<Transaction> transactions) throws Exception {
        Blockchain chain = tb.getBlockchain();
        PBFTConsensus consensus = tb.getConsensus();
        TestKeyPair leader = tb.getValidatorKey();
        
        Block lastBlock = chain.getLatestBlock();
        int nextIndex = lastBlock.getIndex() + 1;
        long timestamp = System.currentTimeMillis();
        
        // Calculate state root by simulating execution on a cloned state
        AccountState simState = chain.getAccountState().cloneState();
        UTXOSet simUtxo = chain.getUTXOSet().cloneUtxo(); // Assuming cloneUtxo exists, else use current
        
        simState.setBlockHeight(nextIndex);
        long totalFees = 0;
        for (Transaction tx : transactions) {
            totalFees += tx.getFee();
            try {
                chain.applyTransactionToState(simState, simUtxo, tx, nextIndex, timestamp, "placeholder_hash", new ArrayList<>());
            } catch (Exception e) {
                // In tests, we might want to know if a transaction failed simulation
                System.err.println("[BlockApplier] Simulation warning: " + e.getMessage());
            }
        }
        
        if (totalFees > 0) {
            simState.credit(leader.getAddress(), totalFees);
        }
        
        String stateRoot = simState.calculateStateRoot();
        
        Block block = new Block(
                nextIndex,
                timestamp,
                transactions,
                lastBlock.getHash(),
                Config.INITIAL_DIFFICULTY,
                stateRoot
        );
        block.setValidatorId(leader.getAddress());
        
        // Sign as leader
        byte[] blockHash = Crypto.hash(block.serializeCanonical());
        block.setSignature(Crypto.sign(blockHash, leader.getPrivateKey()));
        
        String hash = block.getHash();
        
        // Collect Prepare votes (3 required for quorum of 4)
        for (int i = 1; i <= 3; i++) {
            TestKeyPair validator = new TestKeyPair(i);
            byte[] sig = Crypto.sign(
                    Crypto.hash((PBFTConsensus.Phase.PREPARE.name() + 0 + nextIndex + hash + validator.getAddress()).getBytes()),
                    validator.getPrivateKey()
            );
            consensus.addPrepareVote(nextIndex, hash, validator.getAddress(), sig);
        }
        
        // Collect Commit votes (3 required)
        for (int i = 1; i <= 3; i++) {
            TestKeyPair validator = new TestKeyPair(i);
            byte[] sig = Crypto.sign(
                    Crypto.hash((PBFTConsensus.Phase.COMMIT.name() + 0 + nextIndex + hash + validator.getAddress()).getBytes()),
                    validator.getPrivateKey()
            );
            consensus.addCommitVote(nextIndex, hash, validator.getAddress(), sig);
        }
        
        // Commit via consensus
        consensus.validateBlock(block, new ArrayList<>()); // We can pass empty list for chain if it doesn't use it for history check
        
        // Apply to chain
        chain.applyBlock(block);
        
        return block;
    }
}
