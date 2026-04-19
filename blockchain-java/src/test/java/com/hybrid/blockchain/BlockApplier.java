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
        
        // 1. Create MINT reward transaction for the simulation
        long rwd = Tokenomics.getCurrentReward(nextIndex, chain.getTotalMinted());
        Transaction mintTx = new Transaction.Builder()
                .type(Transaction.Type.MINT)
                .to(leader.getAddress())
                .amount(rwd)
                .nonce(nextIndex)
                .build();
        
        List<Transaction> finalTxsForBlock = new ArrayList<>();
        finalTxsForBlock.add(mintTx);
        finalTxsForBlock.addAll(transactions);

        // Calculate state root by simulating execution on a cloned state
        AccountState simState = chain.getAccountState().cloneState();
        UTXOSet simUtxo = chain.getUTXOSet().cloneUtxo(); 
        simState.setBlockHeight(nextIndex);

        // 2. Sort all transactions for simulation/root calculation
        List<Transaction> simulationTxs = new ArrayList<>(finalTxsForBlock);
        simulationTxs.sort(java.util.Comparator
                .comparingInt((Transaction tx) -> tx.getType() == Transaction.Type.MINT ? 0 : 1)
                .thenComparing(tx -> tx.getFrom() == null ? "" : tx.getFrom())
                .thenComparingLong(Transaction::getNonce));

        long totalFees = 0;
        for (Transaction tx : simulationTxs) {
            totalFees += tx.getFee();
            try {
                String simBlockHash = Crypto.bytesToHex(Crypto.hash((nextIndex + "|" + timestamp + "|" + lastBlock.getHash()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                chain.applyTransactionToState(simState, simUtxo, tx, nextIndex, timestamp, simBlockHash, new ArrayList<>());
            } catch (Exception e) {
                System.err.println("[BlockApplier] Simulation warning: " + e.getMessage());
            }
        }
        
        // 3. Finalize state (fees only, reward is already in MINT tx)
        if (totalFees > 0) {
            // Match the exact credit target that applyBlock uses (validator.getId())
            String feeTarget = consensus.getValidators().stream()
                .filter(v -> v.getId().equals(leader.getAddress()))
                .map(com.hybrid.blockchain.Validator::getId)
                .findFirst().orElse(leader.getAddress());
            simState.credit(feeTarget, totalFees);
        }
        
        // 4. Simulate slashing (if any)
        for (String slashedId : consensus.getSlashedValidators()) {
            try {
                long penalty = 1000;
                long validatorBalance = simState.getBalance(slashedId);
                long actualBurn = Math.min(validatorBalance, penalty);
                if (actualBurn > 0) {
                    simState.debit(slashedId, actualBurn);
                }
            } catch (Exception ignored) {}
        }
        
        // 5. Note: We don't simulate deferred actions since they only commit after finality (chain.size() >= 7)
                
        String stateRoot = simState.calculateStateRoot();
        System.err.println("[BlockApplier] Block " + nextIndex + " simulated state root: " + stateRoot);
        
        Block block = new Block(
                nextIndex,
                timestamp,
                finalTxsForBlock,
                lastBlock.getHash(),
                Config.INITIAL_DIFFICULTY,
                stateRoot
        );
        block.setValidatorId(leader.getAddress());
        
        // Sign as leader
        byte[] blockHash = Crypto.hash(block.serializeCanonical());
        block.setSignature(Crypto.sign(blockHash, leader.getPrivateKey()));
        
        String hash = block.getHash();
        
        long view = consensus.getViewNumber();
        
        // Collect Prepare votes (3 required for quorum of 4)
        for (int i = 1; i <= 3; i++) {
            TestKeyPair validator = new TestKeyPair(i);
            PBFTConsensus.PBFTMessage msg = new PBFTConsensus.PBFTMessage(
                    PBFTConsensus.Phase.PREPARE, view, nextIndex, hash, validator.getAddress());
            msg.sign(validator.getPrivateKey());
            consensus.addPrepareVote(nextIndex, hash, validator.getAddress(), msg.signature);
        }
        
        // Collect Commit votes (3 required)
        for (int i = 1; i <= 3; i++) {
            TestKeyPair validator = new TestKeyPair(i);
            PBFTConsensus.PBFTMessage msg = new PBFTConsensus.PBFTMessage(
                    PBFTConsensus.Phase.COMMIT, view, nextIndex, hash, validator.getAddress());
            msg.sign(validator.getPrivateKey());
            consensus.addCommitVote(nextIndex, hash, validator.getAddress(), msg.signature);
        }
        
        // Commit via consensus
        consensus.validateBlock(block, new ArrayList<>()); // We can pass empty list for chain if it doesn't use it for history check
        
        // Apply to chain
        consensus.markCommitted(hash, nextIndex, leader.getAddress());
        chain.applyBlock(block);
        
        return block;
    }
}
