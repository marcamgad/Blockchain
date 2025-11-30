package com.hybrid.blockchain;

import java.util.*;

public class Blockchain {

    private List<Block> chain;
    private Mempool mempool;
    private UTXOSet utxo;
    private AccountState state;
    private Storage storage;
    private int difficulty;
    private List<Transaction> pendingTransactions;

    public Blockchain(Storage storage, Mempool mempool) {
        this.storage = storage != null ? storage : new Storage(null);
        this.mempool = mempool != null ? mempool : new Mempool();
        this.chain = new ArrayList<>();
        this.utxo = new UTXOSet();
        this.state = new AccountState();
        this.difficulty = Config.INITIAL_DIFFICULTY;
        this.pendingTransactions = new ArrayList<>();
    }

    // Initialize blockchain: load tip, UTXO, state, or create genesis
    public void init() throws Exception {
        String tipHash = storage.loadTipHash();

        if (tipHash == null) {
            // Create genesis block
            Block genesis = new Block(0, System.currentTimeMillis(), new ArrayList<>(), "0", difficulty);
            genesis.setHash(genesis.calculateHash());
            storage.saveBlock(genesis.getHash(), genesis);
            chain.add(genesis);
            storage.saveUTXO(utxo.toJSON());
            storage.saveState(state.toJSON());
            storage.putMeta("difficulty", difficulty);
        } else {
            Block tip = storage.loadBlockByHash(tipHash);
            chain.add(tip);
            utxo = new UTXOSet(storage.loadUTXO());
            state = new AccountState(storage.loadState());
            Integer diff = (Integer) storage.getMeta("difficulty");
            if (diff != null) difficulty = diff;
        }
    }

    public Block getLatestBlock() {
        return chain.get(chain.size() - 1);
    }

    // Validate a transaction according to type
    public void validateTransaction(Transaction tx) throws Exception {
        if (!tx.verify()) throw new Exception("Invalid signature");
        if (!tx.getNetworkId().equals(Config.NETWORK_ID)) throw new Exception("Wrong networkId");

        switch (tx.getType()) {
            case "utxo":
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

        if (!block.getHash().startsWith("0".repeat(block.getDifficulty())))
            throw new Exception("Invalid PoW");

        // Validate transactions
        for (Transaction tx : block.getTransactions()) {
            validateTransaction(tx);
        }

        // Apply transactions
        for (Transaction tx : block.getTransactions()) {
            switch (tx.getType()) {
                case "utxo":
                    tx.getInputs().forEach(inp -> utxo.spendOutput(inp.getTxid(), inp.getIndex()));
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
                    // Contracts handled externally
                    break;
            }
        }

        chain.add(block);
        storage.saveBlock(block.getHash(), block);
        storage.saveUTXO(utxo.toJSON());
        storage.saveState(state.toJSON());

        // Adjust difficulty if needed
        if ((chain.size() - 1) % Config.DIFFICULTY_ADJUSTMENT_INTERVAL == 0) {
            difficulty = Difficulty.adjustDifficulty(chain, difficulty);
            storage.putMeta("difficulty", difficulty);
        }
    }

    // Create a new block with transactions from mempool
    public Block createBlock(String minerAddress, int maxTx) throws Exception {
        List<Transaction> candidateTxs = mempool.getTop(maxTx);
        List<Transaction> txsToInclude = new ArrayList<>();

        for (Transaction tx : candidateTxs) {
            try {
                validateTransaction(tx);
                txsToInclude.add(tx);
            } catch (Exception ignored) {
            }
        }

        // Miner reward
        Transaction rewardTx = new Transaction("account", null, minerAddress, Config.MINER_REWARD, 0, 0);
        rewardTx.setSignature(null);
        txsToInclude.add(rewardTx);

        Block newBlock = new Block(chain.size(), System.currentTimeMillis(), txsToInclude,
                getLatestBlock().getHash(), difficulty);
        newBlock.mine(difficulty, Config.MAX_NONCE_ATTEMPTS);
        return newBlock;
    }

    // Add transaction to pending pool
    public void addTransaction(Transaction tx) throws Exception {
        if (tx.getTo() == null) throw new Exception("Missing destination");
        if (!tx.isValid()) throw new Exception("Invalid transaction");
        long balance = getBalance(tx.getFrom());
        if (balance < tx.getAmount() + tx.getFee()) throw new Exception("Insufficient funds");
        pendingTransactions.add(tx);
    }

    // Compute balance of an account
    public long getBalance(String address) {
        long balance = 0;
        for (Block block : chain) {
            for (Transaction tx : block.getTransactions()) {
                if (address.equals(tx.getFrom())) balance -= (tx.getAmount() + tx.getFee());
                if (address.equals(tx.getTo())) balance += tx.getAmount();
            }
        }
        for (Transaction tx : pendingTransactions) {
            if (address.equals(tx.getFrom())) balance -= (tx.getAmount() + tx.getFee());
        }
        return balance;
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
}
