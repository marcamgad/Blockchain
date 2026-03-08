package com.hybrid.blockchain;

import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReadWriteLock;

public class Blockchain {

    protected List<Block> chain;
    private Mempool mempool;
    protected UTXOSet utxo;
    protected AccountState state;
    protected Storage storage;
    private int difficulty;
    private List<Transaction> pendingTransactions;
    protected Consensus consensus;
    private final HardwareManager hardwareManager;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public Blockchain(Storage storage, Mempool mempool, Consensus consensus) throws Exception {
        this.storage = storage != null ? storage : new Storage("data", Config.STORAGE_AES_KEY);
        this.mempool = mempool != null ? mempool : new Mempool();
        this.chain = new ArrayList<>();
        this.utxo = new UTXOSet();
        this.state = new AccountState();
        this.difficulty = Config.INITIAL_DIFFICULTY;
        this.pendingTransactions = new ArrayList<>();
        this.consensus = consensus;
        this.hardwareManager = new HardwareManager();
    }

    public void init() throws Exception {
        lock.writeLock().lock();
        try {
            Integer snapHeight = (Integer) storage.getMeta("lastSnapshotHeight");
            if (snapHeight != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> snap = storage.get("snapshot:" + snapHeight, Map.class);
                if (snap != null) {
                    System.out.println("[INIT] Loaded snapshot at height " + snapHeight);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rawState = (Map<String, Object>) snap.get("state");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rawUTXO = (Map<String, Object>) snap.get("utxo");
                    state = AccountState.fromMap(rawState);
                    utxo = UTXOSet.fromMap(rawUTXO);
                    Block tip = storage.loadBlockByHeight(snapHeight);
                    if (tip != null) {
                        chain.add(tip);
                        Object diffObj = storage.getMeta("difficulty");
                        if (diffObj instanceof Number)
                            difficulty = ((Number) diffObj).intValue();
                        return;
                    } else {
                        System.out.println("[INIT] Snapshot block at height " + snapHeight + " was pruned; falling back to tip-hash");
                    }
                }
            }
            String tipHash = storage.loadTipHash();
            if (tipHash != null) {
                Block tip = storage.loadBlockByHash(tipHash);
                if (tip == null) throw new IOException("Failed to load tip block");
                chain.add(tip);
                Map<String, Object> rawUTXO = storage.loadUTXO();
                utxo = UTXOSet.fromMap(rawUTXO != null ? rawUTXO : new HashMap<>());
                Map<String, Object> rawState = storage.loadState();
                state = AccountState.fromMap(rawState != null ? rawState : new HashMap<>());
                Object diffObj = storage.getMeta("difficulty");
                if (diffObj instanceof Number) difficulty = ((Number) diffObj).intValue();
                System.out.println("[INIT] Resumed from height " + tip.getIndex());
                return;
            }
            System.out.println("[INIT] Creating genesis block");
            Block genesis = new Block(0, System.currentTimeMillis(), new ArrayList<>(), "0", difficulty, state.calculateStateRoot());
            genesis.setHash(genesis.calculateHash());
            chain.add(genesis);
            storage.saveBlock(genesis.getHash(), genesis);
            storage.saveUTXO(utxo.toJSON());
            storage.saveState(state.toJSON());
            storage.putMeta("difficulty", difficulty);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Block getLatestBlock() {
        lock.readLock().lock();
        try {
            if (chain.isEmpty())
                throw new IllegalStateException("Chain is empty");
            return chain.get(chain.size() - 1);
        } finally {
            lock.readLock().unlock();
        }
    }

    // Validate a transaction according to type
    public void validateTransaction(Transaction tx) throws Exception {
        if (!Config.DEBUG && !tx.verify() && tx.getFrom() != null)
            throw new Exception("Invalid signature");
        if (tx.getNetworkId() != Config.NETWORK_ID)
            throw new Exception("Wrong networkId");
        if (tx.getAmount() < 0 || tx.getFee() < 0)
            throw new Exception("Negative amount or fee");

        switch (tx.getType()) {
            case UTXO:
                if (tx.getInputs() == null || tx.getOutputs() == null)
                    throw new Exception("UTXO transaction missing inputs/outputs");
                for (UTXOInput inp : tx.getInputs()) {
                    if (!utxo.isUnspent(inp.getTxid(), inp.getIndex()))
                        throw new Exception("UTXO input not available");
                }
                break;

            case ACCOUNT:
                if (tx.getFrom() == null)
                    break; // coinbase reward tx
                long balance = state.getBalance(tx.getFrom());
                long expectedNonce = state.getNonce(tx.getFrom()) + 1;
                if (tx.getNonce() != expectedNonce)
                    throw new Exception("Invalid nonce: expected " + expectedNonce + " got " + tx.getNonce());
                if (balance < (tx.getAmount() + tx.getFee()))
                    throw new Exception("Insufficient funds");
                break;

            case CONTRACT:
                if (!Config.ENABLE_SMART_CONTRACTS)
                    throw new Exception("Contracts disabled");
                break;
            case IOT_MANAGEMENT:
                // Specific validation for IoT actions
                break;
            case MINT:
                if (tx.getFrom() != null)
                    throw new Exception("MINT must be system-initiated (from address must be null)");
                break;
            case BURN:
                if (tx.getFrom() == null)
                    throw new Exception("BURN must have a from address");
                long burnBalance = state.getBalance(tx.getFrom());
                long expectedBurnNonce = state.getNonce(tx.getFrom()) + 1;
                if (tx.getNonce() != expectedBurnNonce)
                    throw new Exception("Invalid nonce: expected " + expectedBurnNonce + " got " + tx.getNonce());
                if (burnBalance < (tx.getAmount() + tx.getFee()))
                    throw new Exception("Insufficient funds for burn");
                break;

            default:
                throw new Exception("Unknown transaction type");
        }
    }

    // Apply block to chain, update UTXO and account state
    public void applyBlock(Block block) throws Exception {
        lock.writeLock().lock();
        try {
            if (!block.getPrevHash().equals(getLatestBlock().getHash()))
                throw new Exception("Block does not chain to tip");
        if (!consensus.isValidator(block.getValidatorId()))
            throw new Exception("Unknown validator");

        Validator validator = consensus.getValidators().stream()
                .filter(v -> v.getId().equals(block.getValidatorId()))
                .findFirst().orElseThrow(() -> new Exception("Validator not found"));

        if (!consensus.verifyBlock(block, validator))
            throw new Exception("Invalid validator signature");

        for (Transaction tx : block.getTransactions()) {
            validateTransaction(tx);
        }

        state.setBlockHeight(block.getIndex());

        long totalFees = 0;
        // Apply transactions
        for (Transaction tx : block.getTransactions()) {
            totalFees += tx.getFee();
            switch (tx.getType()) {
                case UTXO:
                    for (UTXOInput inp : tx.getInputs()) {
                        utxo.spendOutput(inp.getTxid(), inp.getIndex());
                    }
                    List<UTXOOutput> outs = tx.getOutputs();
                    for (int i = 0; i < outs.size(); i++) {
                        UTXOOutput out = outs.get(i);
                        utxo.addOutput(tx.getId(), i, out.getAddress(), out.getAmount());
                    }
                    break;
                case ACCOUNT:
                    if (tx.getFrom() != null) {
                        state.debit(tx.getFrom(), tx.getAmount() + tx.getFee());
                        state.incrementNonce(tx.getFrom());
                    }
                    state.credit(tx.getTo(), tx.getAmount());
                    break;
                case CONTRACT:
                    if (!Config.ENABLE_SMART_CONTRACTS)
                        throw new Exception("Contracts disabled");

                    byte[] code = tx.getData();
                    String contractAddr = tx.getTo();

                    // 1. Contract Deployment
                    if (contractAddr == null) {
                        // Create a unique contract address
                        String creator = tx.getFrom();
                        long nonce = state.getNonce(creator);
                        contractAddr = Crypto.deriveAddress(Crypto.hash((creator + nonce).getBytes()));
                        
                        state.ensure(contractAddr);
                        state.getAccount(contractAddr).setCode(code);
                        state.incrementNonce(creator);
                        System.out.println("[BLOCKCHAIN] Deployed new contract at: " + contractAddr);
                    } else {
                        // 2. Contract Execution
                        AccountState.Account account = state.getAccount(contractAddr);
                        if (account != null && account.getCode() != null) {
                            code = account.getCode();
                        }

                        Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                                block.getTimestamp(),
                                block.getIndex(),
                                tx.getFrom(),
                                contractAddr,
                                tx.getAmount(),
                                state,
                                hardwareManager,
                                block.getHash());

                        if (isWasm(code)) {
                            System.out.println("[BLOCKCHAIN] Executing WASM contract: " + contractAddr);
                            WasmContractEngine wasmEngine = new WasmContractEngine(code, tx.getFee() * 1000, ctx);
                            wasmEngine.execute("main", new ArrayList<>()); // Default entry point
                        } else {
                            System.out.println("[BLOCKCHAIN] Executing Bytecode script: " + contractAddr);
                            Interpreter vm = new Interpreter(code, tx.getFee() * 1000, ctx);
                            vm.execute();
                        }
                    }
                    break;
                case IOT_MANAGEMENT:
                    processIoTTransaction(tx, block.getIndex());
                    break;
                case MINT:
                    state.credit(tx.getTo(), tx.getAmount());
                    break;
                case BURN:
                    state.debit(tx.getFrom(), tx.getAmount() + tx.getFee());
                    state.incrementNonce(tx.getFrom());
                    break;
            }
        }

        // Credit validator with fees
        if (totalFees > 0) {
            state.credit(validator.getId(), totalFees);
        }

        // Slashing: Penalize Byzantine validators
        for (String slashedId : consensus.getSlashedValidators()) {
            try {
                long penalty = 1000; // Fixed penalty for double-signing
                long validatorBalance = state.getBalance(slashedId);
                long actualBurn = Math.min(validatorBalance, penalty);
                if (actualBurn > 0) {
                    state.debit(slashedId, actualBurn);
                    System.out.println("[BLOCKCHAIN] SLASHED validator " + slashedId + ": burned " + actualBurn + " tokens");
                }
                consensus.clearSlashedValidator(slashedId);
            } catch (Exception e) {
                System.out.println("[ERROR] Failed to slash validator " + slashedId + ": " + e.getMessage());
            }
        }

        // Finality Check: Commit deferred actions for blocks that reached 6
        // confirmations (chain size >= 7 means block at index 0 has 6 confirmations)
        if (chain.size() >= 7) {
            Block finalizedBlock = chain.get(chain.size() - 7);
            hardwareManager.commitDeferredActions(finalizedBlock.getHash());
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
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Add transaction to pending pool
    public void addTransaction(Transaction tx) throws Exception {
        lock.writeLock().lock();
        try {
            if (tx.getTo() == null && tx.getType() != Transaction.Type.CONTRACT)
                throw new Exception("Missing destination");
            if (tx.getFrom() != null && !tx.verify()) {
                if (!Config.DEBUG) {
                    throw new Exception("Invalid transaction");
                }
            }
            long balance = getBalance(tx.getFrom());
            if (balance < tx.getAmount() + tx.getFee())
                throw new Exception("Insufficient funds");
            pendingTransactions.add(tx);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Compute balance of an account (hybrid)
    public long getBalance(String address) {
        if (address == null)
            return 0;
        lock.readLock().lock();
        try {
            long accountBalance = state.getBalance(address);
            long utxoBalance = utxo.getBalance(address);
            return accountBalance + utxoBalance;
        } finally {
            lock.readLock().unlock();
        }
    }

    // Validate the blockchain
    public boolean isChainValid() {
        for (int i = 1; i < chain.size(); i++) {
            Block current = chain.get(i);
            Block previous = chain.get(i - 1);
            if (!current.hasValidTransactions())
                return false;
            if (!current.getHash().equals(current.calculateHash()))
                return false;
            if (!current.getPrevHash().equals(previous.getHash()))
                return false;
        }
        return true;
    }

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

        // Miner reward (coinbase) - use Builder pattern for immutable Transaction
        Transaction rewardTx = new Transaction.Builder()
                .type(Transaction.Type.ACCOUNT)
                .to(minerAddress)
                .amount(Config.MINER_REWARD)
                .networkId(Config.NETWORK_ID)
                .build();
        txsToInclude.add(rewardTx);

        Block newBlock = new Block(chain.size(), System.currentTimeMillis(), txsToInclude,
                getLatestBlock().getHash(), difficulty, state.calculateStateRoot());
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

    // Blockchain.java
    public AccountState getState() {
        return state;
    }

    public HardwareManager getHardwareManager() {
        return hardwareManager;
    }

    private void processIoTTransaction(Transaction tx, long blockHeight) throws Exception {
        Map<String, Object> data = new ObjectMapper().readValue(tx.getData(), Map.class);
        String action = (String) data.get("action");
        DeviceLifecycleManager lifecycle = state.getLifecycleManager();

        switch (action) {
            case "PROVISION":
                lifecycle.provisionDevice(
                        (String) data.get("deviceId"),
                        (String) data.get("manufacturer"),
                        (String) data.get("model"),
                        HexUtils.decode((String) data.get("devicePublicKey")),
                        HexUtils.decode((String) data.get("manufacturerSignature")));
                break;
            case "ACTIVATE":
                lifecycle.activateDevice(
                        (String) data.get("deviceId"),
                        (String) data.get("owner"),
                        HexUtils.decode((String) data.get("devicePublicKey")));
                break;
            case "SUSPEND":
                lifecycle.suspendDevice((String) data.get("deviceId"), tx.getFrom(), (String) data.get("reason"));
                break;
            case "RESUME":
                lifecycle.resumeDevice((String) data.get("deviceId"), tx.getFrom());
                break;
            case "REVOKE":
                lifecycle.revokeDevice((String) data.get("deviceId"), tx.getFrom(), (String) data.get("reason"));
                break;
            case "UPDATE_FIRMWARE":
                lifecycle.updateFirmware(
                        (String) data.get("deviceId"),
                        (String) data.get("version"),
                        HexUtils.decode((String) data.get("hash")),
                        tx.getFrom());
                break;
        }
    }

    public Transaction deserializeTransaction(byte[] payload) throws Exception {
        return new ObjectMapper().readValue(payload, Transaction.class);
    }

    public Block deserializeBlock(byte[] payload) throws Exception {
        return new ObjectMapper().readValue(payload, Block.class);
    }

    private boolean isWasm(byte[] data) {
        if (data == null || data.length < 4) return false;
        // WASM magic bytes: \0asm (0x00 0x61 0x73 0x6D)
        return data[0] == 0x00 && data[1] == 0x61 && data[2] == 0x73 && data[3] == 0x6D;
    }
}
