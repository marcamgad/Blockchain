package com.hybrid.blockchain;

import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import com.hybrid.blockchain.security.RateLimiter;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReadWriteLock;

public class Blockchain {

    protected List<Block> chain;
    private Mempool mempool;
    protected UTXOSet utxo;
    protected AccountState state;
    protected Storage storage;
    private int difficulty;
    protected Consensus consensus;
    private final HardwareManager hardwareManager;
    private final RateLimiter transactionRateLimiter;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public Blockchain(Storage storage, Mempool mempool, Consensus consensus) throws Exception {
        this.storage = storage != null ? storage : new Storage("data", Config.STORAGE_AES_KEY);
        this.mempool = mempool != null ? mempool : new Mempool();
        this.chain = new ArrayList<>();
        this.utxo = new UTXOSet();
        this.state = new AccountState();
        this.difficulty = Config.INITIAL_DIFFICULTY;
        this.consensus = consensus;
        this.hardwareManager = new HardwareManager();
        this.transactionRateLimiter = RateLimiter.Presets.transactionLimiter();
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
            Block genesis = new Block(0, System.currentTimeMillis(), new ArrayList<>(), "0000000000000000000000000000000000000000000000000000000000000000", difficulty, state.calculateStateRoot());
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
        if (!Config.isDebug() && !tx.verify() && tx.getFrom() != null)
            throw new Exception("Invalid signature");
        if (tx.getNetworkId() != Config.NETWORK_ID)
            throw new Exception("Wrong networkId");
        if (tx.getAmount() < 0 || tx.getFee() < 0)
            throw new Exception("Negative amount or fee");
        if (tx.getValidUntilBlock() > 0 && getHeight() > tx.getValidUntilBlock())
            throw new Exception("Transaction expired");

        switch (tx.getType()) {
            case UTXO:
                if (tx.getInputs() == null || tx.getOutputs() == null)
                    throw new Exception("UTXO transaction missing inputs/outputs");
                long totalInput = 0;
                for (UTXOInput inp : tx.getInputs()) {
                    if (!utxo.isUnspent(inp.getTxid(), inp.getIndex()))
                        throw new Exception("UTXO input not available");
                    long inputAmount = utxo.getAmount(inp.getTxid(), inp.getIndex());
                    if (inputAmount < 0) {
                        throw new Exception("UTXO input amount unavailable");
                    }
                    totalInput += inputAmount;
                }
                long totalOutput = 0;
                for (UTXOOutput out : tx.getOutputs()) {
                    totalOutput += out.getAmount();
                }
                if (totalInput < (totalOutput + tx.getFee()))
                    throw new Exception("Insufficient UTXO input sum");
                break;

            case ACCOUNT:
                if (tx.getFrom() == null)
                    break; // coinbase reward tx
                long balance = state.getBalance(tx.getFrom());
                long expectedNonce = state.getNonce(tx.getFrom()) + 1;
                if (tx.getNonce() != expectedNonce)
                    throw new Exception("Invalid nonce: expected " + expectedNonce + " got " + tx.getNonce());
                long accountTotal;
                try {
                    accountTotal = Math.addExact(tx.getAmount(), tx.getFee());
                } catch (ArithmeticException e) {
                    throw new Exception("Invalid amount overflow", e);
                }
                if (balance < accountTotal)
                    throw new Exception("Insufficient funds");
                break;

            case CONTRACT:
                if (!Config.ENABLE_SMART_CONTRACTS)
                    throw new Exception("Contracts disabled");
                if (tx.getFrom() != null) {
                    long contractBalance = state.getBalance(tx.getFrom());
                    long expectedContractNonce = state.getNonce(tx.getFrom()) + 1;
                    if (tx.getNonce() != expectedContractNonce)
                        throw new Exception("Invalid nonce: expected " + expectedContractNonce + " got " + tx.getNonce());
                    long contractTotal;
                    try {
                        contractTotal = Math.addExact(tx.getAmount(), tx.getFee());
                    } catch (ArithmeticException e) {
                        throw new Exception("Invalid amount overflow", e);
                    }
                    if (contractBalance < contractTotal)
                        throw new Exception("Insufficient funds for contract tx");
                }
                break;
            case IOT_MANAGEMENT:
                if (tx.getFrom() != null) {
                    long iotBalance = state.getBalance(tx.getFrom());
                    long expectedIotNonce = state.getNonce(tx.getFrom()) + 1;
                    if (tx.getNonce() != expectedIotNonce)
                        throw new Exception("Invalid nonce: expected " + expectedIotNonce + " got " + tx.getNonce());
                    if (iotBalance < tx.getFee())
                        throw new Exception("Insufficient funds for iot tx fee");
                }
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
                long burnTotal;
                try {
                    burnTotal = Math.addExact(tx.getAmount(), tx.getFee());
                } catch (ArithmeticException e) {
                    throw new Exception("Invalid amount overflow", e);
                }
                if (burnBalance < burnTotal)
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
            Block latest = getLatestBlock();
            if (block.getIndex() != latest.getIndex() + 1)
                throw new Exception("Invalid block height");
            if (!block.getPrevHash().equals(latest.getHash()))
                throw new Exception("Block does not chain to tip");
            if (block.getTimestamp() > System.currentTimeMillis() + Config.MAX_TIMESTAMP_DRIFT)
                throw new Exception("Block timestamp too far in future");
            if (block.getTimestamp() < latest.getTimestamp())
                throw new Exception("Block timestamp older than previous block");
            if (!calculateTxRoot(block.getTransactions()).equals(block.getTxRoot()))
                throw new Exception("Invalid tx root");
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
            applyTransactionToState(state, utxo, tx, block.getIndex(), block.getTimestamp(), block.getHash());
            mempool.remove(tx.getId());
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

        String computedStateRoot = state.calculateStateRoot();
        if (!computedStateRoot.equals(block.getStateRoot())) {
            throw new Exception("Invalid state root: expected " + block.getStateRoot() + " got " + computedStateRoot);
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
    public boolean addTransaction(Transaction tx) throws Exception {
        lock.writeLock().lock();
        try {
            if (tx.getTo() == null && tx.getType() != Transaction.Type.CONTRACT)
                throw new Exception("Missing destination");
            if (tx.getFrom() != null && !tx.verify()) {
                if (!Config.isDebug()) {
                    throw new Exception("Invalid transaction");
                }
            }
            // Skipping balance check in DEBUG mode for tests
            if (!Config.isDebug() && tx.getFrom() != null) {
                long balance = getBalance(tx.getFrom());
                long total;
                try {
                    total = Math.addExact(tx.getAmount(), tx.getFee());
                } catch (ArithmeticException e) {
                    throw new Exception("Invalid amount overflow", e);
                }
                if (balance < total)
                    throw new Exception("Insufficient funds");
            }
            if (tx.getFrom() != null && !transactionRateLimiter.allowRequest(tx.getFrom())) {
                throw new Exception("Rate limit exceeded for sender");
            }
            mempool.add(tx);
            return true;
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

    public Block createBlock(String minerAddress, int maxTx) {
        lock.writeLock().lock();
        try {
            Block prev = getLatestBlock();
            int nextIndex = prev.getIndex() + 1;
            List<Transaction> candidateTxs = mempool.getTop(maxTx);
            List<Transaction> txsToInclude = new ArrayList<>();
            
            // Miner reward (coinbase)
            Transaction rewardTx = new Transaction.Builder()
                    .type(Transaction.Type.MINT)
                    .to(minerAddress)
                    .amount(Config.MINER_REWARD)
                    .build();
            txsToInclude.add(rewardTx);

            // Simulate transactions on a clone of state to get the post-state root
            AccountState clonedState = state.cloneState();
            UTXOSet clonedUtxo = UTXOSet.fromMap(utxo.toJSON());
            clonedState.setBlockHeight(nextIndex);
            long timestamp = System.currentTimeMillis();
            String tempHash = "SIMULATION_" + timestamp;
            try {
                applyTransactionToState(clonedState, clonedUtxo, rewardTx, nextIndex, timestamp, tempHash);
            } catch (Exception ignored) {
            }
            
            for (Transaction tx : candidateTxs) {
                try {
                    validateTransaction(tx);
                    applyTransactionToState(clonedState, clonedUtxo, tx, nextIndex, timestamp, tempHash);
                    txsToInclude.add(tx);
                } catch (Exception ignored) {
                }
            }
            
            long totalFees = txsToInclude.stream().mapToLong(Transaction::getFee).sum();
            clonedState.credit(minerAddress, totalFees);

            String postStateRoot = clonedState.calculateStateRoot();
                Block newBlock = new Block(nextIndex, timestamp, txsToInclude,
                    prev.getHash(), difficulty, postStateRoot);
            newBlock.setValidatorId(Config.NODE_ID);
            newBlock.setHash(newBlock.calculateHash());
            return newBlock;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void applyTransactionToState(AccountState targetState, UTXOSet targetUtxo, Transaction tx, long blockIndex, long timestamp, String blockHash) throws Exception {
        switch (tx.getType()) {
            case UTXO:
                for (UTXOInput inp : tx.getInputs()) {
                    targetUtxo.spendOutput(inp.getTxid(), inp.getIndex());
                }
                List<UTXOOutput> outs = tx.getOutputs();
                for (int i = 0; i < outs.size(); i++) {
                    UTXOOutput out = outs.get(i);
                    targetUtxo.addOutput(tx.getId(), i, out.getAddress(), out.getAmount());
                }
                break;
            case ACCOUNT:
                if (tx.getFrom() != null) {
                    targetState.debit(tx.getFrom(), Math.addExact(tx.getAmount(), tx.getFee()));
                    targetState.incrementNonce(tx.getFrom());
                }
                targetState.credit(tx.getTo(), tx.getAmount());
                break;
            case CONTRACT:
                if (!Config.ENABLE_SMART_CONTRACTS)
                    throw new Exception("Contracts disabled");

                if (tx.getFrom() != null) {
                    targetState.debit(tx.getFrom(), tx.getFee());
                    targetState.incrementNonce(tx.getFrom());
                }

                byte[] code = tx.getData();
                String contractAddr = tx.getTo();

                if (contractAddr == null) {
                    String creator = tx.getFrom();
                    long nonce = targetState.getNonce(creator);
                    contractAddr = Crypto.deriveAddress(Crypto.hash((creator + nonce).getBytes()));
                    targetState.ensure(contractAddr);
                    targetState.getAccount(contractAddr).setCode(code);
                    // Nonce already incremented for fee debit
                } else {
                    AccountState.Account account = targetState.getAccount(contractAddr);
                    if (account != null && account.getCode() != null) {
                        code = account.getCode();
                    }

                    Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                            timestamp,
                            (int) blockIndex,
                            tx.getFrom(),
                            contractAddr,
                            tx.getAmount(),
                            targetState,
                            hardwareManager,
                            blockHash);

                    if (isWasm(code)) {
                        WasmContractEngine wasmEngine = new WasmContractEngine(code, tx.getFee() * 1000, ctx);
                        wasmEngine.execute("main", new ArrayList<>());
                    } else {
                        Interpreter vm = new Interpreter(code, tx.getFee() * 1000, ctx);
                        vm.execute();
                    }
                }
                break;
            case IOT_MANAGEMENT:
                if (tx.getFrom() != null) {
                    targetState.debit(tx.getFrom(), tx.getFee());
                    targetState.incrementNonce(tx.getFrom());
                }
                processIoTTransactionWithState(targetState, tx, blockIndex);
                break;
            case MINT:
                targetState.credit(tx.getTo(), tx.getAmount());
                break;
            case BURN:
                targetState.debit(tx.getFrom(), Math.addExact(tx.getAmount(), tx.getFee()));
                targetState.incrementNonce(tx.getFrom());
                break;
        }
    }

    private void processIoTTransactionWithState(AccountState targetState, Transaction tx, long blockHeight) throws Exception {
        Map<String, Object> data = new ObjectMapper().readValue(tx.getData(), Map.class);
        String action = (String) data.get("action");
        DeviceLifecycleManager lifecycle = targetState.getLifecycleManager();
        // ... rest of processIoTTransaction logic
        handleIoTAction(lifecycle, action, data, tx.getFrom());
    }

    private void handleIoTAction(DeviceLifecycleManager lifecycle, String action, Map<String, Object> data, String from) throws Exception {
        switch (action) {
            case "PROVISION":
                lifecycle.provisionDevice((String) data.get("deviceId"), (String) data.get("manufacturer"), (String) data.get("model"), HexUtils.decode((String) data.get("devicePublicKey")), HexUtils.decode((String) data.get("manufacturerSignature")));
                break;
            case "ACTIVATE":
                lifecycle.activateDevice((String) data.get("deviceId"), (String) data.get("owner"), HexUtils.decode((String) data.get("devicePublicKey")));
                break;
            case "SUSPEND":
                lifecycle.suspendDevice((String) data.get("deviceId"), from, (String) data.get("reason"));
                break;
            case "RESUME":
                lifecycle.resumeDevice((String) data.get("deviceId"), from);
                break;
            case "REVOKE":
                lifecycle.revokeDevice((String) data.get("deviceId"), from, (String) data.get("reason"));
                break;
            case "UPDATE_FIRMWARE":
                lifecycle.updateFirmware((String) data.get("deviceId"), (String) data.get("version"), HexUtils.decode((String) data.get("hash")), from);
                break;
        }
    }

    // Expose chain height for convenience
    public int getHeight() {
        return chain.size() - 1;
    }

    public int getDifficulty() {
        return difficulty;
    }

    public void recalculateStateRoot() {
        Block latest = getLatestBlock();
        if (latest != null) {
            latest.setStateRoot(state.calculateStateRoot());
        }
    }

    private String calculateTxRoot(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return Crypto.bytesToHex(new byte[32]);
        }
        List<byte[]> leaves = new ArrayList<>();
        for (Transaction tx : transactions) {
            if (tx == null) {
                return "";
            }
            leaves.add(Crypto.hash(tx.serializeCanonical()));
        }
        return Crypto.bytesToHex(MerkleTree.computeRoot(leaves));
    }

    public List<Block> getChain() {
        return chain;
    }

    public Storage getStorage() {
        return storage;
    }

    @SuppressWarnings("unchecked")
    public <T extends Consensus> T getConsensus() {
        return (T) consensus;
    }

    protected void pruneBlock(Block block) {
        // default: do nothing
    }

    // Blockchain.java
    public AccountState getState() {
        return state;
    }

    public Mempool getMempool() {
        return mempool;
    }

    public HardwareManager getHardwareManager() {
        return hardwareManager;
    }

    public Transaction deserializeTransaction(byte[] payload) throws Exception {
        return new ObjectMapper().readValue(payload, Transaction.class);
    }

    public Block deserializeBlock(byte[] payload) throws Exception {
        return new ObjectMapper().readValue(payload, Block.class);
    }

    public byte[] serializeTransaction(Transaction tx) {
        try {
            return new ObjectMapper().writeValueAsBytes(tx);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    public byte[] serializeBlock(Block block) {
        try {
            return new ObjectMapper().writeValueAsBytes(block);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    public boolean isWasm(byte[] data) {
        // WASM magic bytes: \0asm (0x00 0x61 0x73 0x6D)
        return data != null && data.length >= 4 && 
               data[0] == 0x00 && data[1] == 0x61 && data[2] == 0x73 && data[3] == 0x6D;
    }

    public void shutdown() throws IOException {
        if (consensus instanceof PBFTConsensus) {
            ((PBFTConsensus) consensus).shutdown();
        }
        if (storage != null) {
            storage.close();
        }
    }
}
