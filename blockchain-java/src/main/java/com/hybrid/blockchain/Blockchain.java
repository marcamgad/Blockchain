package com.hybrid.blockchain;

import java.io.IOException;
import java.security.PrivateKey;
import java.util.*;

public class Blockchain {

    protected List<Block> chain;
    private Mempool mempool;
    protected UTXOSet utxo;
    protected AccountState state;
    protected Storage storage;
    private int difficulty;
    private List<Transaction> pendingTransactions;
    protected PoAConsensus poaConsensus;

    public Blockchain(Storage storage, Mempool mempool, PoAConsensus poa) throws Exception {
    this.storage = storage != null ? storage : new Storage("data", Config.STORAGE_AES_KEY);
    this.mempool = mempool != null ? mempool : new Mempool();
    this.chain = new ArrayList<>();
    this.utxo = new UTXOSet();
    this.state = new AccountState();
    this.difficulty = Config.INITIAL_DIFFICULTY;
    this.pendingTransactions = new ArrayList<>();
    this.poaConsensus = poa;
}

    public void init() throws Exception {

        Integer snapHeight = (Integer) storage.getMeta("lastSnapshotHeight");
        if (snapHeight != null) {

            Map<String, Object> snap =
                storage.get("snapshot:" + snapHeight, Map.class);

            if (snap != null) {
                System.out.println("[INIT] Loaded snapshot at height " + snapHeight);

                Map<String, Object> rawState =
                    (Map<String, Object>) snap.get("state");
                Map<String, Object> rawUTXO =
                    (Map<String, Object>) snap.get("utxo");

                state = AccountState.fromMap(rawState);
                utxo  = UTXOSet.fromMap(rawUTXO);

                Block tip = storage.loadBlockByHeight(snapHeight);
                if (tip != null) {
                    // Snapshot block exists; load from it
                    chain.add(tip);

                    Object diffObj = storage.getMeta("difficulty");
                    if (diffObj instanceof Number)
                        difficulty = ((Number) diffObj).intValue();

                    return;
                } else {
                    // Snapshot block was pruned; fall back to tip-hash recovery below
                    System.out.println("[INIT] Snapshot block at height " + snapHeight + " was pruned; falling back to tip-hash");
                }
            }
        }

        String tipHash = storage.loadTipHash();

        if (tipHash != null) {

            Block tip = storage.loadBlockByHash(tipHash);
            if (tip == null)
                throw new IOException("Failed to load tip block");

            chain.add(tip);

            Map<String, Object> rawUTXO = storage.loadUTXO();
            if (rawUTXO == null) rawUTXO = new HashMap<>();
            utxo = UTXOSet.fromMap(rawUTXO);

            Map<String, Object> rawState = storage.loadState();
            if (rawState == null) rawState = new HashMap<>();
            state = AccountState.fromMap(rawState);

            Object diffObj = storage.getMeta("difficulty");
            if (diffObj instanceof Number)
                difficulty = ((Number) diffObj).intValue();

            System.out.println("[INIT] Resumed from height " + tip.getIndex());
            return;
        }
        System.out.println("[INIT] Creating genesis block");

        Block genesis = new Block(
            0,
            System.currentTimeMillis(),
            new ArrayList<>(),
            "0",
            difficulty
        );

        genesis.setHash(genesis.calculateHash());

        chain.add(genesis);
        storage.saveBlock(genesis.getHash(), genesis);
        storage.saveUTXO(utxo.toJSON());
        storage.saveState(state.toJSON());
        storage.putMeta("difficulty", difficulty);
    }


    public Block getLatestBlock() {
        if (chain.isEmpty()) throw new IllegalStateException("Chain is empty");
        return chain.get(chain.size() - 1);
    }

    // Validate a transaction according to type
    public void validateTransaction(Transaction tx) throws Exception {
        if (!tx.verify() && tx.getFrom() != null) throw new Exception("Invalid signature");
        if (!tx.getNetworkId().equals(String.valueOf(Config.NETWORK_ID))) throw new Exception("Wrong networkId");
        if (tx.getAmount() < 0 || tx.getFee() < 0) throw new Exception("Negative amount or fee");

        switch (tx.getType()) {
            case "utxo":
                if (tx.getInputs() == null || tx.getOutputs() == null)
                    throw new Exception("UTXO transaction missing inputs/outputs");
                for (UTXOInput inp : tx.getInputs()) {
                    if (!utxo.isUnspent(inp.getTxid(), inp.getIndex()))
                        throw new Exception("UTXO input not available");
                }
                break;

            case "account":
                if (tx.getFrom() == null) break; // coinbase reward tx
                long balance = state.getBalance(tx.getFrom());
                long expectedNonce = state.getNonce(tx.getFrom()) + 1;
                if (tx.getNonce() != expectedNonce)
                    throw new Exception("Invalid nonce: expected " + expectedNonce + " got " + tx.getNonce());
                if (balance < (tx.getAmount() + tx.getFee()))
                    throw new Exception("Insufficient funds");
                break;

            case "contract":
                if (!Config.ENABLE_SMART_CONTRACTS) throw new Exception("Contracts disabled");
                // Minimal validation; VM handles contract execution
                break;

            default:
                throw new Exception("Unknown transaction type");
        }
    }

    // Apply block to chain, update UTXO and account state
    public void applyBlock(Block block) throws Exception {
        if (!block.getPrevHash().equals(getLatestBlock().getHash()))
            throw new Exception("Block does not chain to tip");
        if (!poaConsensus.isValidator(block.getValidatorId()))
            throw new Exception("Unknown validator");

        Validator validator = poaConsensus.getValidators().stream()
            .filter(v -> v.getId().equals(block.getValidatorId()))
            .findFirst().orElseThrow(() -> new Exception("Validator not found"));

        if (!poaConsensus.verifyBlock(block, validator))
            throw new Exception("Invalid validator signature");

        for (Transaction tx : block.getTransactions()) {
            validateTransaction(tx);
        }

        // Apply transactions
        for (Transaction tx : block.getTransactions()) {
            switch (tx.getType()) {
                case "utxo":
                    for (UTXOInput inp : tx.getInputs()) {
                        utxo.spendOutput(inp.getTxid(), inp.getIndex());
                    }
                    List<UTXOOutput> outs = tx.getOutputs();
                    for (int i = 0; i < outs.size(); i++) {
                        UTXOOutput out = outs.get(i);
                        utxo.addOutput(tx.getId(), i, out.getAddress(), out.getAmount());
                    }
                    break;
                case "account":
                    if (tx.getFrom() != null) {
                        state.debit(tx.getFrom(), tx.getAmount() + tx.getFee());
                        state.incrementNonce(tx.getFrom());
                    }
                    state.credit(tx.getTo(), tx.getAmount());
                    break;
                case "contract":
                    // Placeholder for contract execution (upgrade: add VM here)
                    System.out.println("Executing contract: " + tx.getData());
                    break;
            }
        }

        chain.add(block);
        storage.saveBlock(block.getHash(), block);
        storage.saveUTXO(utxo.toJSON());
        storage.saveState(state.toJSON());
        pruneBlock(block);

        // Adjust difficulty if needed
        if ((chain.size() - 1) % Config.DIFFICULTY_ADJUSTMENT_INTERVAL == 0) {
            int newDiff = Difficulty.adjustDifficulty(chain, difficulty);
            difficulty = newDiff;
            storage.putMeta("difficulty", difficulty);
        }
    }


    // Add transaction to pending pool
    public void addTransaction(Transaction tx) throws Exception {
        if (tx.getTo() == null && !"contract".equals(tx.getType())) throw new Exception("Missing destination");
        if (tx.getFrom() != null && !tx.verify()) throw new Exception("Invalid transaction");
        long balance = getBalance(tx.getFrom());
        if (balance < tx.getAmount() + tx.getFee()) throw new Exception("Insufficient funds");
        pendingTransactions.add(tx);
    }

    // Compute balance of an account (hybrid)
    public long getBalance(String address) {
        if (address == null) return 0;
        long accountBalance = state.getBalance(address);
        long utxoBalance = utxo.getBalance(address);
        return accountBalance + utxoBalance;
    }

    // Validate the blockchain
    public boolean isChainValid() {
        for (int i = 1; i < chain.size(); i++) {
            Block current = chain.get(i);
            Block previous = chain.get(i - 1);
            if (!current.hasValidTransactions()) return false;
            if (!current.getHash().equals(current.calculateHash())) return false;
            if (!current.getPrevHash().equals(previous.getHash())) return false;
        }
        return true;
    }

   public Block createBlock(String minerAddress, int maxTx) throws Exception {
    List<Transaction> candidateTxs = mempool.getTop(maxTx);
    List<Transaction> txsToInclude = new ArrayList<>();
    for (Transaction tx : candidateTxs) {
        try {
            validateTransaction(tx);
            tx.setNetworkId(Config.NETWORK_ID); // ensure networkId is correct
            txsToInclude.add(tx);
        } catch (Exception ignored) {}
    }

    // Miner reward (coinbase)
    Transaction rewardTx = new Transaction("account", null, minerAddress, Config.MINER_REWARD, 0, 0, null);
    rewardTx.setNetworkId(Config.NETWORK_ID);
    rewardTx.setSignature(null);
    txsToInclude.add(rewardTx);

    Block newBlock = new Block(chain.size(), System.currentTimeMillis(), txsToInclude,
            getLatestBlock().getHash(), difficulty);
    newBlock.mine(difficulty, Config.MAX_NONCE_ATTEMPTS);
    return newBlock;
}


    // Expose chain height for convenience
    public int getHeight() {
        return chain.size() - 1;
    }

    public List<Block> getChain() {
        return chain;
    }

    public Storage getStorage() {
        return storage;
    }

    protected void pruneBlock(Block block) {
    // default: do nothing
    }

    
}
