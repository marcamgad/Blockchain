package com.hybrid.blockchain;

import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import com.hybrid.blockchain.security.RateLimiter;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core Blockchain implementation for HybridChain.
 * Manages the chain state, transaction processing, and consensus integration.
 */
public class Blockchain {
    private static final Logger log = LoggerFactory.getLogger(Blockchain.class);

    protected List<Block> chain;
    private Mempool mempool;
    protected UTXOSet utxo;
    protected AccountState state;
    protected Storage storage;
    private final FeeMarket feeMarket;
    private int difficulty;
    protected Consensus consensus;
    private final HardwareManager hardwareManager;
    private final RateLimiter transactionRateLimiter;
    private boolean paused = false;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private TokenRegistry tokenRegistry;
    private com.hybrid.blockchain.api.EventBus eventBus;
    private PeerNode peerNode;
    private long totalMinted = 0;
    private com.hybrid.blockchain.monitoring.BlockchainMonitor monitor;
    private com.hybrid.blockchain.audit.AuditLogger auditLogger;

    public Blockchain(Storage storage, Mempool mempool, Consensus consensus) throws Exception {
        this.storage = storage != null ? storage : new Storage("data", Config.STORAGE_AES_KEY);
        this.mempool = mempool != null ? mempool : new Mempool();
        this.chain = new ArrayList<>();
        this.utxo = new UTXOSet();
        this.state = new AccountState();
        // FIX 4: Inject storage into AccountState so the lifecycle manager can persist
        // reputation scores across restarts via ReputationEngine.updateScore().
        this.state.setStorage(this.storage);
        this.feeMarket = new FeeMarket();
        this.difficulty = Config.INITIAL_DIFFICULTY;
        this.consensus = consensus;
        this.hardwareManager = new HardwareManager();
        this.transactionRateLimiter = RateLimiter.Presets.transactionLimiter();
        this.tokenRegistry = new TokenRegistry(this.storage);
        this.monitor = com.hybrid.blockchain.monitoring.BlockchainMonitor.getInstance();
    }

    public void setTokenRegistry(TokenRegistry registry) { this.tokenRegistry = registry; }
    public TokenRegistry getTokenRegistry() { return tokenRegistry; }
    public FeeMarket getFeeMarket() { return feeMarket; }
    public com.hybrid.blockchain.api.EventBus getEventBus() { return eventBus; }
    public void setEventBus(com.hybrid.blockchain.api.EventBus eventBus) { this.eventBus = eventBus; }
    public void setPeerNode(PeerNode peerNode) { this.peerNode = peerNode; }
    public DeviceLifecycleManager getDeviceLifecycleManager() { return state.getDeviceLifecycleManager(); }
    public void setMonitor(com.hybrid.blockchain.monitoring.BlockchainMonitor monitor) { this.monitor = monitor; }
    public com.hybrid.blockchain.monitoring.BlockchainMonitor getMonitor() { return monitor; }
    public com.hybrid.blockchain.ai.TelemetryAnomalyDetector getAnomalyDetector() { return com.hybrid.blockchain.ai.TelemetryAnomalyDetector.getInstance(); }
    public com.hybrid.blockchain.ai.FederatedLearningManager getFederatedLearningManager() { return com.hybrid.blockchain.ai.FederatedLearningManager.getInstance(); }
    public void setAuditLogger(com.hybrid.blockchain.audit.AuditLogger auditLogger) { this.auditLogger = auditLogger; }
    public com.hybrid.blockchain.audit.AuditLogger getAuditLogger() { return auditLogger; }
    public void setConsensus(Consensus consensus) { this.consensus = consensus; }

    public void init() throws Exception {
        lock.writeLock().lock();
        try {
            Object snapHeightObj = storage.getMeta("lastSnapshotHeight");
            Integer snapHeight = null;
            if (snapHeightObj instanceof Number) snapHeight = ((Number) snapHeightObj).intValue();
            
            if (snapHeight != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> snap = storage.get("snapshot:" + snapHeight, Map.class);
                if (snap != null) {
                    log.info("[INIT] Loaded snapshot at height {}", snapHeight);
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
                        log.info("[INIT] Snapshot block at height {} was pruned; falling back to tip-hash", snapHeight);
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
                state = storage.loadState();
                if (state == null) state = new AccountState();
                Object diffObj = storage.getMeta("difficulty");
                if (diffObj instanceof Number) difficulty = ((Number) diffObj).intValue();
                
                Object mintedObj = storage.getMeta("totalMinted");
                if (mintedObj instanceof Number) totalMinted = ((Number) mintedObj).longValue();
                
                log.info("[INIT] Resumed from height {} (totalMinted: {})", tip.getIndex(), totalMinted);
                return;
            }

            // Fast Sync: Check for latest checkpoint if no tip is found
            com.hybrid.blockchain.Checkpoint latestCp = storage.loadLatestCheckpoint();
            if (latestCp != null) {
                log.info("[INIT] Fast sync from checkpoint at height {}", latestCp.getBlockHeight());
                Block cpBlock = storage.loadBlockByHash(latestCp.getBlockHash());
                if (cpBlock != null) {
                    // Load snapshot instead of tip state if tip state is missing or outdated
                    Map<String, Object> snap = storage.get("snapshot:" + latestCp.getBlockHeight(), Map.class);
                    if (snap != null) {
                        state = AccountState.fromMap((Map<String, Object>) snap.get("state"));
                        utxo = UTXOSet.fromMap((Map<String, Object>) snap.get("utxo"));
                    } else {
                        state = storage.loadState();
                        utxo = UTXOSet.fromMap(storage.loadUTXO());
                    }
                    if (state == null) state = new AccountState();
                    
                    chain.add(cpBlock);
                    if (state.calculateStateRoot().equals(latestCp.getStateRoot())) {
                        log.info("[INIT] Fast sync successful at height {}", latestCp.getBlockHeight());
                        return;
                    } else {
                        log.warn("[INIT] Checkpoint state root mismatch! Falling back to genesis.");
                    }
                }
            }

            log.info("[INIT] Creating genesis block");
            Block genesis = new Block(0, System.currentTimeMillis(), new ArrayList<>(), "0000000000000000000000000000000000000000000000000000000000000000", difficulty, state.calculateStateRoot());
            genesis.setHash(genesis.calculateHash());
            chain.add(genesis);
            storage.saveBlock(genesis.getHash(), genesis);
            storage.saveUTXO(utxo.toJSON());
            storage.saveState(state);
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

    public long getTotalMinted() { return totalMinted; }

    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }
    private boolean skipRateLimit = false;
    public void setSkipRateLimit(boolean skipRateLimit) { this.skipRateLimit = skipRateLimit; }
    public UTXOSet getUtxoSet() { return this.utxo; }


    // Validate a transaction according to type
    public void validateTransaction(Transaction tx) throws Exception { validateTransaction(tx, false); }
    public void validateTransaction(Transaction tx, boolean skipNonce) throws Exception {
        if (tx.getType() == Transaction.Type.MINT && tx.getFrom() != null)
            throw new Exception("MINT must be system-initiated");
        if (tx.getFrom() != null && !tx.verify())
            throw new Exception("Invalid signature");
        if (tx.getNetworkId() != Config.NETWORK_ID)
            throw new Exception("Wrong networkId");
        
        // Ensure destination is present unless it's a contract creation, burn, telemetry, IoT management, or federated
        if (tx.getTo() == null && 
            tx.getType() != Transaction.Type.CONTRACT && 
            tx.getType() != Transaction.Type.BURN && 
            tx.getType() != Transaction.Type.TOKEN_BURN &&
            tx.getType() != Transaction.Type.TOKEN_REGISTER &&
            tx.getType() != Transaction.Type.TELEMETRY &&
            tx.getType() != Transaction.Type.FEDERATED_UPDATE &&
            tx.getType() != Transaction.Type.FEDERATED_COMMIT &&
            tx.getType() != Transaction.Type.IOT_MANAGEMENT &&
            tx.getType() != Transaction.Type.UTXO &&
            tx.getType() != Transaction.Type.MINT) {
            throw new Exception("Missing destination");
        }

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
                long accountTotal;
                try {
                    accountTotal = Math.addExact(tx.getAmount(), tx.getFee());
                } catch (ArithmeticException e) {
                    throw new Exception("Invalid amount overflow", e);
                }
                
                if (balance < accountTotal)
                    throw new Exception("Insufficient funds");

                if (!skipNonce) {
                    // For adding to mempool, allow nonces up to the max pending nonce + 1
                    long currentNonce = state.getNonce(tx.getFrom());
                    long expectedNonce = currentNonce + 1;
                    if (this.mempool != null) {
                        for (Transaction ptx : mempool.toArray()) {
                            if (tx.getFrom().equals(ptx.getFrom())) {
                                expectedNonce = Math.max(expectedNonce, ptx.getNonce() + 1);
                            }
                        }
                    }
                    if (tx.getNonce() != expectedNonce) throw new IllegalArgumentException("Invalid nonce: expected " + expectedNonce + " got " + tx.getNonce());
                }
                long baseFeeAcc = feeMarket.getCurrentBaseFee(storage);
                if (tx.getFee() < baseFeeAcc)
                    throw new Exception("Fee " + tx.getFee() + " below baseFee " + baseFeeAcc);
                break;

                case CONTRACT:
                    if (!Config.ENABLE_SMART_CONTRACTS)
                        throw new Exception("Contracts disabled");
                    
                    // [SECURITY] Audit check for contract creation
                    if (tx.getTo() == null && tx.getData() != null && tx.getData().length > 0) {
                        if (!Config.BYPASS_CONTRACT_AUDIT) {
                            com.hybrid.blockchain.ai.SmartContractAuditor.AuditResult audit = 
                                com.hybrid.blockchain.ai.SmartContractAuditor.audit(tx.getData());
                            if (audit.isRejected()) {
                                throw new Exception("Contract rejected by AI Audit: " + String.join(", ", audit.getFindings()));
                            }
                        }
                    }

                    if (tx.getFrom() != null) {
                    long contractBalance = state.getBalance(tx.getFrom());
                    if (!skipNonce) {
                        long expectedContractNonce = state.getNonce(tx.getFrom()) + 1;
                        if (tx.getNonce() != expectedContractNonce) throw new IllegalArgumentException("Invalid nonce: expected " + expectedContractNonce + " got " + tx.getNonce());
                    }
                    long contractTotal;
                    try {
                        contractTotal = Math.addExact(tx.getAmount(), tx.getFee());
                    } catch (ArithmeticException e) {
                        throw new Exception("Invalid amount overflow", e);
                    }
                    if (contractBalance < contractTotal)
                        throw new Exception("Insufficient funds for contract tx: balance=" + contractBalance + ", amount=" + tx.getAmount() + ", fee=" + tx.getFee());
                }
                break;
            case IOT_MANAGEMENT:
            case FEDERATED_UPDATE:
            case FEDERATED_COMMIT:
                if (tx.getFrom() != null) {
                    long iotBalance = state.getBalance(tx.getFrom());
                    if (!skipNonce) {
                        long expectedIotNonce = state.getNonce(tx.getFrom()) + 1;
                        if (tx.getNonce() != expectedIotNonce) throw new IllegalArgumentException("Invalid nonce: expected " + expectedIotNonce + " got " + tx.getNonce());
                    }
                    if (iotBalance < tx.getFee())
                        throw new Exception("Insufficient funds for iot tx fee");
                }
                break;
            case MINT:
                if (tx.getFrom() != null)
                    throw new Exception("MINT must be system-initiated (from address must be null)");
                // Enforce scheduled reward amount against tokenomics (exact amount required)
                {
                    long expectedReward = Tokenomics.getCurrentReward(getHeight() + 1, Tokenomics.getTotalMinted(this));
                    if (expectedReward == 0)
                        throw new Exception("MINT rejected: supply cap reached");
                    if (tx.getAmount() != expectedReward)
                        throw new Exception("MINT amount " + tx.getAmount() + " does not match scheduled reward " + expectedReward);
                }
                break;
            case BURN:
                if (tx.getFrom() == null)
                    throw new Exception("BURN must have a from address");
                long burnBalance = state.getBalance(tx.getFrom());
                    if (!skipNonce) {
                        long expectedBurnNonce = state.getNonce(tx.getFrom()) + 1;
                        if (tx.getNonce() != expectedBurnNonce) throw new IllegalArgumentException("Invalid nonce: expected " + expectedBurnNonce + " got " + tx.getNonce());
                    }
                long burnTotal;
                try {
                    burnTotal = Math.addExact(tx.getAmount(), tx.getFee());
                } catch (ArithmeticException e) {
                    throw new Exception("Invalid amount overflow", e);
                }
                long baseFeeBurn = feeMarket.getCurrentBaseFee(storage);
                if (tx.getFee() < baseFeeBurn)
                    throw new Exception("Fee " + tx.getFee() + " below baseFee " + baseFeeBurn);
                if (burnBalance < burnTotal)
                    throw new Exception("Insufficient funds for burn");
                break;

            case TOKEN_TRANSFER:
                if (tx.getFrom() == null)
                    throw new Exception("TOKEN_TRANSFER must have a from address");
                if (tx.getTo() == null)
                    throw new Exception("TOKEN_TRANSFER must have a to address");
                if (tx.getData() == null || tx.getData().length == 0)
                    throw new Exception("TOKEN_TRANSFER missing tokenId in data");
                {
                    String tokenId = new String(tx.getData(), java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (tokenRegistry == null || !tokenRegistry.tokenExists(tokenId))
                        throw new Exception("Insufficient " + tokenId + " balance");
                    long tokenBal = state.getTokenBalance(tx.getFrom(), tokenId);
                    if (tokenBal < tx.getAmount())
                        throw new Exception("Insufficient " + tokenId + " balance");
                    // Native fee check
                    long baseFee = feeMarket.getCurrentBaseFee(storage);
                    if (tx.getFee() < baseFee)
                        throw new Exception("Fee " + tx.getFee() + " below baseFee " + baseFee);
                    long nativeBal = state.getBalance(tx.getFrom());
                    if (nativeBal < tx.getFee())
                        throw new Exception("Insufficient native balance for fee");
                    if (!skipNonce) {
                        long ttExpected = state.getNonce(tx.getFrom()) + 1;
                        if (tx.getNonce() != ttExpected) throw new IllegalArgumentException("Invalid nonce: expected " + ttExpected + " got " + tx.getNonce());
                    }
                }
                break;

            case TELEMETRY:
                if (tx.getFrom() == null)
                    throw new Exception("TELEMETRY must have a from address (device)");
                if (tx.getData() == null || tx.getData().length == 0)
                    throw new Exception("TELEMETRY tx has no data payload");
                {
                    com.hybrid.blockchain.lifecycle.DeviceLifecycleManager lcm = state.getLifecycleManager();
                    com.hybrid.blockchain.identity.SSIManager ssi = state.getSSIManager();
                    String deviceId = tx.getTo();
                    boolean isOperational = false;
                    
                     boolean hasRecord = false;
                     try {
                         if (deviceId != null) {
                             lcm.getDeviceRecord(deviceId);
                             hasRecord = true;
                         }
                     } catch (Exception e) {}

                     if (hasRecord) {
                         isOperational = lcm.isDeviceOperational(deviceId);
                        if (isOperational) {
                            com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceRecord record = lcm.getDeviceRecord(deviceId);
                            String did = record.getDid();
                            boolean authorized = false;
                            
                            // Check if sender is owner
                            if (record.getOwner() != null && record.getOwner().equals(tx.getFrom())) {
                                authorized = true;
                            } else {
                                // Check if sender is the device itself (from its DID public key)
                                try {
                                    com.hybrid.blockchain.identity.DecentralizedIdentifier didDoc = ssi.resolveDID(did);
                                    if (!didDoc.getVerificationMethods().isEmpty()) {
                                        String pkHex = didDoc.getVerificationMethods().get(0).getPublicKeyHex();
                                        byte[] pkBytes = com.hybrid.blockchain.HexUtils.decode(pkHex);
                                        String deviceAddr = com.hybrid.blockchain.Crypto.deriveAddress(pkBytes);
                                        if (deviceAddr.equals(tx.getFrom())) {
                                            authorized = true;
                                        }
                                    }
                                } catch (Exception e) {
                                    // DID not found or other SSI error
                                }
                            }
                            
                            if (!authorized) {
                                throw new Exception("TELEMETRY rejected: sender " + tx.getFrom() + " not authorized for device " + deviceId);
                            }
                        }
                    } else {
                        // Fallback: Check if the address is an owner or device ID directly (legacy/simplified)
                        isOperational = lcm.isDeviceOperational(tx.getFrom());
                        if (!isOperational) {
                            List<com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceRecord> owned = lcm.getDevicesOwnedBy(tx.getFrom());
                            isOperational = owned.stream().anyMatch(d -> d.getStatus() == com.hybrid.blockchain.lifecycle.DeviceLifecycleManager.DeviceStatus.ACTIVE);
                        }
                    }

                    if (!isOperational)
                        throw new Exception("TELEMETRY rejected: device/owner not in ACTIVE state");
                    long baseFee = feeMarket.getCurrentBaseFee(storage);
                    if (tx.getFee() < baseFee)
                        throw new Exception("Fee " + tx.getFee() + " below baseFee " + baseFee);
                    long telemBal = state.getBalance(tx.getFrom());
                    if (telemBal < tx.getFee())
                        throw new Exception("Insufficient funds for telemetry fee: balance=" + telemBal + ", fee=" + tx.getFee());
                    if (!skipNonce) {
                        long telemExpectedNonce = state.getNonce(tx.getFrom()) + 1;
                        if (tx.getNonce() != telemExpectedNonce) throw new IllegalArgumentException("Invalid nonce: expected " + telemExpectedNonce + " got " + tx.getNonce());
                    }
                }
                break;

            case TOKEN_REGISTER:
                if (tx.getFrom() == null)
                    throw new Exception("TOKEN_REGISTER must have a from address (owner)");
                if (tx.getData() == null || tx.getData().length == 0)
                    throw new Exception("TOKEN_REGISTER missing token metadata in data");
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = new ObjectMapper().readValue(tx.getData(), Map.class);
                    String tokenId = (String) metadata.get("tokenId");
                    if (tokenId == null || tokenId.trim().isEmpty())
                        throw new Exception("TOKEN_REGISTER missing tokenId");
                    if (!skipNonce) {
                        long trExpected = state.getNonce(tx.getFrom()) + 1;
                        if (tx.getNonce() != trExpected) throw new IllegalArgumentException("Invalid nonce: expected " + trExpected + " got " + tx.getNonce());
                    }
                }
                break;

            case TOKEN_MINT:
                if (tx.getFrom() == null)
                    throw new Exception("TOKEN_MINT must have a from address (owner)");
                if (tx.getTo() == null)
                    throw new Exception("TOKEN_MINT must have a to address (recipient)");
                if (tx.getData() == null || tx.getData().length == 0)
                    throw new Exception("TOKEN_MINT missing tokenId in data");
                {
                    String tokenId = new String(tx.getData(), java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (tokenRegistry == null) throw new Exception("TokenRegistry not configured");
                    TokenRegistry.TokenInfo info = tokenRegistry.getTokenInfo(tokenId);
                    if (info == null) throw new Exception("Unknown token: " + tokenId);
                    if (!info.owner.equals(tx.getFrom()))
                        throw new Exception("Only token owner can mint: " + info.owner);
                    if (info.maxSupply > 0 && info.totalMinted + tx.getAmount() > info.maxSupply)
                        throw new Exception("Minting would exceed max supply for " + tokenId);
                    if (!skipNonce) {
                        long tmExpected = state.getNonce(tx.getFrom()) + 1;
                        if (tx.getNonce() != tmExpected) throw new IllegalArgumentException("Invalid nonce: expected " + tmExpected + " got " + tx.getNonce());
                    }
                }
                break;

            case TOKEN_BURN:
                if (tx.getFrom() == null)
                    throw new Exception("TOKEN_BURN must have a from address");
                if (tx.getData() == null || tx.getData().length == 0)
                    throw new Exception("TOKEN_BURN missing tokenId in data");
                {
                    String tokenId = new String(tx.getData(), java.nio.charset.StandardCharsets.UTF_8).trim();
                    long tokenBal = state.getTokenBalance(tx.getFrom(), tokenId);
                    if (tokenBal < tx.getAmount())
                        throw new Exception("Insufficient " + tokenId + " balance for burn");
                    if (!skipNonce) {
                        long tbExpected = state.getNonce(tx.getFrom()) + 1;
                        if (tx.getNonce() != tbExpected) throw new IllegalArgumentException("Invalid nonce: expected " + tbExpected + " got " + tx.getNonce());
                    }
                }
                break;

            default:
                throw new Exception("Unknown transaction type: " + tx.getType());
        }
    }

    // Apply block to chain, update UTXO and account state
    public void applyBlock(Block block) throws Exception {
        lock.writeLock().lock();
        try {
            Block latest = getLatestBlock();
            if (block.getIndex() == latest.getIndex() + 1) {
                applyBlockInternal(block, latest);
            } else if (block.getIndex() == latest.getIndex()) {
                handleFork(block);
            } else {
                log.warn("[BLOCKCHAIN] Ignored block {} at height {} (current tip: {})", block.getHash(), block.getIndex(), latest.getIndex());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void handleFork(Block block) throws Exception {
        Block tip = getLatestBlock();
        if (block.getHash().equals(tip.getHash())) return;

        // FIX 7: Use lexicographic comparison of block hashes for deterministic fork resolution.
        // Block-hash comparison is stake-neutral and manipulation-resistant: a validator cannot
        // predict or control its block hash without changing block content (txMerkleRoot,
        // stateRoot, timestamp), all of which are committed in the hash. Validator-ID hashing
        // (the previous approach) was biased toward specific validator identities.
        if (block.getHash().compareTo(tip.getHash()) > 0) {
            log.info("[FORK] Block {} wins fork at height {} (lexicographically higher hash)", block.getHash(), block.getIndex());
            // Capture losing tip transactions BEFORE reverting, so we can return them to mempool
            List<Transaction> evictedTxs = new ArrayList<>(tip.getTransactions());
            revertTip();
            applyBlockInternal(block, getLatestBlock());
            // Return all non-MINT losing transactions to the mempool
            for (Transaction evicted : evictedTxs) {
                if (evicted.getType() != Transaction.Type.MINT) {
                    try { mempool.add(evicted); } catch (Exception ignored) {}
                }
            }
        } else {
            log.debug("[FORK] Ignored competing block {} (lower hash than current tip)", block.getHash());
        }
    }

    private void revertTip() throws Exception {
        if (chain.size() <= 1) return;
        chain.remove(chain.size() - 1);
        Block newTip = getLatestBlock();
        
        // Reload state from last snapshot (which is index of newTip)
        Map<String, Object> snapshot = (Map<String, Object>) storage.get("snapshot:" + newTip.getIndex(), Map.class);
        if (snapshot == null) {
            throw new Exception("Critical: Cannot revert tip, no snapshot found for height " + newTip.getIndex());
        }
        
        this.state = AccountState.fromMap((Map<String, Object>) snapshot.get("state"));
        this.utxo = UTXOSet.fromMap((Map<String, Object>) snapshot.get("utxo"));
        
        // Restore global storage pointers
        storage.saveState(this.state);
        storage.saveUTXO(this.utxo.toJSON());
        
        // Update storage tip
        storage.put("chain:tip", newTip.getHash()); 
    }

    private void applyBlockInternal(Block block, Block latest) throws Exception {
        validateBlock(block);
        
        if (!block.getPrevHash().equals(latest.getHash()))
            throw new Exception("Hash mismatch: block " + block.getIndex() + " references " + block.getPrevHash() + " but tip is " + latest.getHash());
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

        java.util.List<Transaction> sortedTxs = new java.util.ArrayList<>(block.getTransactions());
        sortedTxs.sort(java.util.Comparator
                .comparingInt((Transaction tx) -> tx.getType() == Transaction.Type.MINT ? 0 : 1)
                .thenComparing(tx -> tx.getFrom() == null ? "" : tx.getFrom())
                .thenComparingLong(Transaction::getNonce));

        java.util.Map<String, Long> expectedNonces = new java.util.LinkedHashMap<>();
        for (Transaction tx : sortedTxs) {
            if (tx.getType() == Transaction.Type.UTXO) continue;
            if (tx.getFrom() != null) {
                long base = expectedNonces.computeIfAbsent(tx.getFrom(),
                        addr -> state.getNonce(addr) + 1);
                if (tx.getNonce() != base) {
                    throw new IllegalArgumentException("Invalid nonce for " + tx.getFrom() + ": expected " + base + " got " + tx.getNonce());
                }
                expectedNonces.put(tx.getFrom(), base + 1);
            }
        }
        for (Transaction tx : sortedTxs) {
            validateTransaction(tx, true);
        }

        state.setBlockHeight(block.getIndex());

        long totalFees = 0;
        int txCountForFeeMkt = 0;
        // Apply transactions and create receipts
        for (Transaction tx : sortedTxs) {
            txCountForFeeMkt++;
            String receiptStatus = TransactionReceipt.STATUS_SUCCESS;
            String receiptError = null;
            long gasUsed = 0;
            List<ContractEvent> events = new ArrayList<>();
            ExecutionResult result;
            try {
                result = applyTransactionToState(state, utxo, tx, block.getIndex(), block.getTimestamp(), block.getHash(), events);
                gasUsed = result.getGasUsed();
                if (tx.getType() == Transaction.Type.MINT) {
                    // Handled inside applyTransactionToState or by checking canonical state
                }
            } catch (RevertException re) {
                receiptStatus = TransactionReceipt.STATUS_REVERTED;
                receiptError = re.getMessage();
                // Even on revert, some values might be known
                result = new ExecutionResult(0, null, re.getReturnData(), events);
            } catch (Exception ex) {
                receiptStatus = TransactionReceipt.STATUS_FAILED;
                receiptError = ex.getMessage();
                result = new ExecutionResult(0, null, null, events);
            }
            totalFees += tx.getFee();
            // Build and save receipt
            TransactionReceipt receipt = new TransactionReceipt(
                    tx.getId(), block.getHash(), (int) block.getIndex(),
                    receiptStatus, gasUsed, receiptError, events, block.getTimestamp(),
                    result.getContractAddress(), result.getReturnData());
            try {
                storage.saveReceipt(tx.getId(), receipt);
                storage.indexTransaction(tx.getId(), block.getHash(), (int) block.getIndex());
                if (tx.getFrom() != null) {
                    storage.indexAddressTx(tx.getFrom(), tx.getId(), block.getTimestamp());
                }
                if (tx.getTo() != null) {
                    storage.indexAddressTx(tx.getTo(), tx.getId(), block.getTimestamp());
                }
            } catch (Exception ignored) {}
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
                    log.warn("[BLOCKCHAIN] SLASHED validator {}: burned {} tokens", slashedId, actualBurn);
                }
                consensus.clearSlashedValidator(slashedId);
            } catch (Exception e) {
                log.error("[ERROR] Failed to slash validator {}: {}", slashedId, e.getMessage());
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

        // Update base fee for next block
        long currentBaseFee = feeMarket.getCurrentBaseFee(storage);
        long nextBaseFee = feeMarket.calculateNextBaseFee(currentBaseFee, txCountForFeeMkt, Config.TARGET_GAS_PER_BLOCK);
        feeMarket.saveBaseFee(storage, nextBaseFee);
        feeMarket.recordFeeDataPoint(txCountForFeeMkt, nextBaseFee);
        // FIX 3: Record fee data point for prediction regression.
        // Gated by FEE_HISTORY_ENABLED so unit tests that call feeMarket.resetHistory()
        // can prevent cross-test contamination.
        if (Config.FEE_HISTORY_ENABLED) {
            feeMarket.recordFeeDataPoint(txCountForFeeMkt, nextBaseFee);
        }

        storage.putMeta("totalMinted", totalMinted);

        storage.saveBlock(block.getHash(), block);
        storage.saveUTXO(utxo.toJSON());
        storage.saveState(state);
        
        // Save snapshot for fork resolution (save at height index to allow revert from index+1)
        storage.saveSnapshot((int)block.getIndex(), state.toJSON(), utxo.toJSON());
        
        pruneBlock(block);
        
        // Record monitoring metrics
        if (monitor != null) {
            monitor.recordMetric("blocks.validated", 1);
            monitor.recordMetric("blocks.size", block.serializeCanonical().length);
            monitor.recordMetric("transactions.validated", block.getTransactions().size());
        }
        
        // Audit log block creation
        if (auditLogger != null) {
            java.util.HashMap<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("blockHash", block.getHash());
            metadata.put("blockHeight", block.getIndex());
            metadata.put("txCount", block.getTransactions().size());
            auditLogger.log(
                com.hybrid.blockchain.audit.AuditLogger.AuditEventType.BLOCK_CREATED,
                block.getValidatorId(),
                "Block created at height " + block.getIndex(),
                metadata);
        }

        // Checkpoint every 1000 blocks
        if (block.getIndex() > 0 && block.getIndex() % 1000 == 0) {
            try {
                Checkpoint cp = new Checkpoint(
                        (int) block.getIndex(), block.getHash(),
                        block.getStateRoot(), Crypto.bytesToHex(Crypto.hash(utxo.toJSON().toString().getBytes())),
                        block.getTimestamp(), new HashMap<>());
                // NOTE: Checkpoint is NOT saved here - it's only saved in PeerNode after quorum is reached
                log.info("[CHECKPOINT] Broadcasting checkpoint request at height {}", block.getIndex());
                if (peerNode != null) {
                    peerNode.broadcastCheckpointRequest(cp);
                }
            } catch (Exception e) {
                log.warn("[CHECKPOINT] Failed to broadcast checkpoint: {}", e.getMessage());
            }
        }

        // Publish block event
        if (eventBus != null) {
            try { eventBus.publish("blocks", block); } catch (Exception ignored) {}
        }

        // Adjust difficulty if needed
        if ((chain.size() - 1) % Config.DIFFICULTY_ADJUSTMENT_INTERVAL == 0) {
            int newDiff = Difficulty.adjustDifficulty(chain, difficulty);
            difficulty = newDiff;
            storage.putMeta("difficulty", difficulty);
        }
    }

    // Add transaction to pending pool
    public boolean addTransaction(Transaction tx) throws Exception {
        lock.writeLock().lock();
        try {
            // Perform deep validation (including AI audit for contracts).
            validateTransaction(tx, false);

            // Nonce check: already covered by validateTransaction(tx, false);
            // Balance check: must account for pending transactions in mempool
            if (tx.getFrom() != null) {
                long balance = getBalance(tx.getFrom());
                
                // Effective balance = committed balance - pending spends in mempool
                long pendingSpend = 0;
                for (Transaction ptx : mempool.toArray()) {
                    if (tx.getFrom().equals(ptx.getFrom())) {
                        try {
                            pendingSpend = Math.addExact(pendingSpend, Math.addExact(ptx.getAmount(), ptx.getFee()));
                        } catch (ArithmeticException e) {
                            pendingSpend = Long.MAX_VALUE; // Sentinel for overflow
                        }
                    }
                }

                long totalRequired;
                try {
                    totalRequired = Math.addExact(tx.getAmount(), tx.getFee());
                } catch (ArithmeticException e) {
                    throw new Exception("Invalid amount overflow");
                }

                if (balance < Math.addExact(pendingSpend, totalRequired)) {
                    throw new Exception("Insufficient funds (including pending transactions)");
                }
            }
            if (tx.getFrom() != null && !transactionRateLimiter.allowRequest(tx.getFrom())) {
                throw new Exception("Rate limit exceeded for sender");
            }
            mempool.add(tx);
            if (eventBus != null) {
                try { eventBus.publish("transactions", tx); } catch (Exception ignored) {}
            }
            
            // Record monitoring and audit for transaction submission
            if (monitor != null) {
                monitor.recordMetric("transactions.submitted", 1);
            }
            if (auditLogger != null) {
                java.util.HashMap<String, Object> metadata = new java.util.HashMap<>();
                metadata.put("txId", tx.getId());
                metadata.put("txType", tx.getType().toString());
                metadata.put("amount", tx.getAmount());
                metadata.put("fee", tx.getFee());
                auditLogger.log(
                    com.hybrid.blockchain.audit.AuditLogger.AuditEventType.TRANSACTION_SUBMITTED,
                    tx.getFrom() != null ? tx.getFrom() : "system",
                    "Transaction submitted: " + tx.getId(),
                    metadata);
            }
            
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Compute balance of an account (hybrid)
    public boolean check(String deviceId, double value) {
        return com.hybrid.blockchain.ai.TelemetryAnomalyDetector.getInstance().check(deviceId, value);
    }

    /** Alias for check() for backward compatibility with integration tests. */
    public boolean checkValue(String deviceId, double value) {
        return check(deviceId, value);
    }

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
            List<Transaction> candidateTxs = mempool.getReadyTransactions(maxTx, state);
            List<Transaction> txsToInclude = new ArrayList<>();
            
            // Miner reward simulation for post-state root calculation
            long totalMintedSim = Tokenomics.getTotalMinted(this);
            long blockRewardSim = Tokenomics.getCurrentReward(nextIndex, totalMintedSim);
            Transaction simRewardTx = null;
            if (blockRewardSim > 0) {
                simRewardTx = new Transaction.Builder()
                        .type(Transaction.Type.MINT)
                        .to(minerAddress)
                        .amount(blockRewardSim)
                        .build();
                txsToInclude.add(simRewardTx);
            }

            // Simulate transactions on a clone of state to get the post-state root
            AccountState clonedState = state.cloneState();
            UTXOSet clonedUtxo = UTXOSet.fromMap(utxo.toJSON());
            clonedState.setBlockHeight(nextIndex);
            long timestamp = System.currentTimeMillis();
            String tempHash = "SIMULATION_" + timestamp;
            try {
                if (simRewardTx != null) {
                    applyTransactionToState(clonedState, clonedUtxo, simRewardTx, nextIndex, timestamp, tempHash, new ArrayList<>());
                }
            } catch (Exception ignored) {
            }
            
            // Enforce block size limit (2 MB)
            int blockBytesUsed = 0;
            for (Transaction tx : candidateTxs) {
                try {
                    validateTransaction(tx);
                    byte[] serializedTx = tx.serializeCanonical();
                    int txSize = serializedTx.length;
                    
                    if (blockBytesUsed + txSize > Config.MAX_BLOCK_SIZE) {
                        log.warn("[Blockchain] Block full at {}/{} bytes. Stopping tx inclusion.", blockBytesUsed, Config.MAX_BLOCK_SIZE);
                        break; 
                    }
                    
                    applyTransactionToState(clonedState, clonedUtxo, tx, nextIndex, timestamp, tempHash, new ArrayList<>());
                    txsToInclude.add(tx);
                    blockBytesUsed += txSize;
                } catch (Exception e) {
                    log.debug("[Blockchain] Candidate tx {} validation failed: {}", tx.getId(), e.getMessage());
                }
            }
            
            // FIX 12: Do NOT double-credit the miner here.
            // The MINT tx already credited the block reward via applyTransactionToState().
            // The validator fee credit is applied in applyBlockInternal() after block inclusion.
            // We simulate it here only to compute an accurate post-state root.
            long simTotalFees = txsToInclude.stream()
                    .filter(t -> t.getType() != Transaction.Type.MINT)
                    .mapToLong(Transaction::getFee)
                    .sum();
            if (simTotalFees > 0) {
                // Simulate the fee credit that applyBlockInternal() will apply to the validator
                clonedState.credit(minerAddress, simTotalFees);
                log.debug("[Mining] Simulating fee credit {} to miner {} for state-root accuracy", simTotalFees, minerAddress);
            }

            String postStateRoot = clonedState.calculateStateRoot();
            Block newBlock = new Block((int)nextIndex, timestamp, txsToInclude,
                    prev.getHash(), difficulty, postStateRoot);
            newBlock.setValidatorId(Config.NODE_ID);
            newBlock.setHash(newBlock.calculateHash());
            return newBlock;
        } finally {
            lock.writeLock().unlock();
        }
    }

    ExecutionResult applyTransactionToState(AccountState targetState, UTXOSet targetUtxo, Transaction tx, long blockIndex, long timestamp, String blockHash, List<ContractEvent> transactionEvents) throws Exception {
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
                return new ExecutionResult(0, null, null, transactionEvents);
            case ACCOUNT:
                if (tx.getFrom() != null) {
                    targetState.debit(tx.getFrom(), Math.addExact(tx.getAmount(), tx.getFee()));
                    targetState.incrementNonce(tx.getFrom());
                }
                targetState.credit(tx.getTo(), tx.getAmount());
                return new ExecutionResult(0, null, null, transactionEvents);
            case CONTRACT:
                if (!Config.ENABLE_SMART_CONTRACTS)
                    throw new Exception("Contracts disabled");

                if (tx.getFrom() != null) {
                    targetState.debit(tx.getFrom(), tx.getFee());
                    targetState.incrementNonce(tx.getFrom());
                }

                byte[] code = tx.getData();
                String contractAddr = tx.getTo();
                long gasLimit = tx.getFee() * 1000;

                if (contractAddr == null) {
                    String creator = tx.getFrom();
                    long nonce = targetState.getNonce(creator);
                    contractAddr = Crypto.deriveAddress(Crypto.hash((creator + nonce).getBytes()));
                    targetState.ensure(contractAddr);
                    targetState.setCode(contractAddr, code);
                    // Nonce already incremented for fee debit
                    return new ExecutionResult(0, contractAddr, null, transactionEvents);
                } else {
                    AccountState.Account account = targetState.getAccount(contractAddr);
                    if (account != null && account.getCode() != null) {
                        code = account.getCode();
                    }
                    AccountState contractSimState = targetState.cloneState();
                    Interpreter.BlockchainContext ctx = new Interpreter.BlockchainContext(
                            timestamp,
                            (int) blockIndex,
                            tx.getFrom(),
                            contractAddr,
                            tx.getAmount(),
                            contractSimState, // use cloned state for execution
                            hardwareManager,
                            blockHash);
                    ctx.events = transactionEvents;

                    // Populate address registry: map each address to a deterministic long key
                    for (String addr : contractSimState.getAllAddresses()) {
                        byte[] h = Crypto.hash(addr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        long addrKey = java.nio.ByteBuffer.wrap(h, 0, 8)
                            .order(java.nio.ByteOrder.BIG_ENDIAN).getLong();
                        ctx.addressRegistry.put(addrKey, addr);
                    }

                    try {
                        if (isWasm(code)) {
                            WasmContractEngine wasmEngine = new WasmContractEngine(code, gasLimit, ctx);
                            wasmEngine.execute("main", new ArrayList<>());
                            targetState.merge(contractSimState);
                            return new ExecutionResult(wasmEngine.getGasConsumed(), contractAddr, null, transactionEvents);
                        } else {
                            Interpreter vm = new Interpreter(code, gasLimit, ctx);
                            vm.execute();
                            // Execution succeeded, commit state changes
                            targetState.merge(contractSimState);
                            return new ExecutionResult(gasLimit - vm.getGasRemaining(), contractAddr, vm.getReturnData(), transactionEvents);
                        }
                    } catch (RevertException re) {
                        // State changes intentionally discarded — contractSimState is not merged
                        throw re;
                    } catch (Exception e) {
                        // Execution failed, discard contractSimState
                        throw e;
                    }
                }
            case IOT_MANAGEMENT:
                if (tx.getFrom() != null) {
                    targetState.debit(tx.getFrom(), tx.getFee());
                    targetState.incrementNonce(tx.getFrom());
                }
                processIoTTransactionWithState(targetState, tx, blockIndex, timestamp);
                return new ExecutionResult(0, null, null, transactionEvents);
            case MINT:
                targetState.credit(tx.getTo(), tx.getAmount());
                if (targetState == this.state) {
                    this.totalMinted += tx.getAmount();
                }
                return new ExecutionResult(0, null, null, transactionEvents);
            case BURN:
                targetState.debit(tx.getFrom(), Math.addExact(tx.getAmount(), tx.getFee()));
                targetState.incrementNonce(tx.getFrom());
                return new ExecutionResult(0, null, null, transactionEvents);
            case TOKEN_TRANSFER:
                if (tokenRegistry == null) throw new Exception("TokenRegistry not configured");
                {
                    String tokenId = new String(tx.getData(), java.nio.charset.StandardCharsets.UTF_8).trim();
                    tokenRegistry.transferToken(targetState, tokenId, tx.getFrom(), tx.getTo(), tx.getAmount());
                    targetState.debit(tx.getFrom(), tx.getFee());
                    targetState.incrementNonce(tx.getFrom());
                }
                return new ExecutionResult(0, null, null, transactionEvents);
            case TELEMETRY:
                {
                    targetState.debit(tx.getFrom(), tx.getFee());
                    targetState.incrementNonce(tx.getFrom());
                    
                    // Run AI Anomaly Detection
                    int multiplier = com.hybrid.blockchain.ai.TelemetryAnomalyDetector.getInstance().checkTransaction(tx, timestamp);
                    boolean isAnomaly = multiplier > 1;
                    if (isAnomaly) {
                        try {
                            targetState.debit(tx.getFrom(), Math.multiplyExact(tx.getFee(), (long)multiplier - 1));
                        } catch (ArithmeticException e) {
                            log.error("[TELEMETRY] Penalty fee overflow for {}", tx.getFrom());
                        }
                    }

                    // Store telemetry data; hash large payloads (>= 1024 bytes)
                    byte[] rawData = tx.getData();
                    byte[] toStore = rawData.length >= 1024 ? Crypto.hash(rawData) : rawData;
                    try {
                        String targetDeviceId = tx.getTo();
                        if (targetDeviceId == null || targetState.getLifecycleManager().getDeviceRecord(targetDeviceId) == null) {
                            targetDeviceId = tx.getFrom(); // Simplified/Legacy fallback
                        }
                        
                        if (targetState == this.state) {
                            storage.saveTelemetry(targetDeviceId, (int) blockIndex, tx.getId(), toStore);
                        }
                        // Update device reputation on telemetry result
                        targetState.getLifecycleManager().recordDeviceActivity(targetDeviceId, !isAnomaly);
                    } catch (Exception e) {
                        log.warn("[TELEMETRY] Failed to save telemetry for {}: {}", tx.getFrom(), e.getMessage());
                    }
                }
                return new ExecutionResult(0, null, null, transactionEvents);
            case FEDERATED_UPDATE:
                {
                    targetState.debit(tx.getFrom(), tx.getFee());
                    targetState.incrementNonce(tx.getFrom());
                    
                    try {
                        double[] weights = new ObjectMapper().readValue(tx.getData(), double[].class);
                        com.hybrid.blockchain.ai.FederatedLearningManager.getInstance().submitUpdate(tx.getFrom(), weights);
                    } catch (Exception e) {
                        log.warn("[FedLearn] Failed to parse update from {}: {}", tx.getFrom(), e.getMessage());
                    }
                }
                return new ExecutionResult(0, null, null, transactionEvents);

            case FEDERATED_COMMIT:
                {
                    targetState.debit(tx.getFrom(), tx.getFee());
                    targetState.incrementNonce(tx.getFrom());

                    String modelHash = null;
                    double[] modelWeights = null;
                    Integer round = null;
                    Integer contributors = null;

                    if (tx.getData() != null && tx.getData().length > 0) {
                        String rawPayload = new String(tx.getData(), java.nio.charset.StandardCharsets.UTF_8).trim();
                        if (rawPayload.startsWith("{")) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> payload = new ObjectMapper().readValue(tx.getData(), Map.class);
                                Object hashObj = payload.get("modelHash");
                                if (hashObj == null) hashObj = payload.get("hash");
                                if (hashObj instanceof String) modelHash = ((String) hashObj).trim();

                                Object modelObj = payload.get("model");
                                if (modelObj == null) modelObj = payload.get("weights");
                                modelWeights = convertToDoubleArray(modelObj);

                                if (payload.get("round") instanceof Number) {
                                    round = ((Number) payload.get("round")).intValue();
                                }
                                if (payload.get("contributors") instanceof Number) {
                                    contributors = ((Number) payload.get("contributors")).intValue();
                                }
                            } catch (Exception e) {
                                log.warn("[FedLearn] Invalid FEDERATED_COMMIT JSON payload: {}", e.getMessage());
                            }
                        } else {
                            modelHash = rawPayload;
                        }
                    }

                    if (modelHash == null || modelHash.isBlank()) {
                        throw new Exception("FEDERATED_COMMIT missing model hash");
                    }

                    com.hybrid.blockchain.ai.FederatedLearningManager.getInstance()
                            .applyCommittedModel(modelHash, modelWeights, round, contributors, storage);
                }
                return new ExecutionResult(0, null, null, transactionEvents);

            case TOKEN_REGISTER:
                if (tx.getFrom() != null) {
                    targetState.debit(tx.getFrom(), tx.getFee());
                    targetState.incrementNonce(tx.getFrom());
                    if (tokenRegistry != null) {
                        Map<String, Object> metadata = new ObjectMapper().readValue(tx.getData(), Map.class);
                        tokenRegistry.registerToken(
                            (String) metadata.get("tokenId"),
                            (String) metadata.get("name"),
                            (String) metadata.get("symbol"),
                            ((Number) metadata.get("decimals")).intValue(),
                            ((Number) metadata.get("maxSupply")).longValue(),
                            tx.getFrom(),
                            targetState != this.state
                        );
                    }
                }
                return new ExecutionResult(0, null, null, transactionEvents);

            case TOKEN_MINT:
                if (tx.getFrom() != null) {
                    targetState.debit(tx.getFrom(), tx.getFee());
                    targetState.incrementNonce(tx.getFrom());
                    if (tokenRegistry != null) {
                        String tokenId = new String(tx.getData(), java.nio.charset.StandardCharsets.UTF_8).trim();
                        tokenRegistry.mintToken(targetState, tokenId, tx.getTo(), tx.getAmount(), targetState != this.state);
                    }
                }
                return new ExecutionResult(0, null, null, transactionEvents);

            case TOKEN_BURN:
                if (tx.getFrom() != null) {
                    targetState.debit(tx.getFrom(), tx.getFee());
                    targetState.incrementNonce(tx.getFrom());
                    if (tokenRegistry != null) {
                        String tokenId = new String(tx.getData(), java.nio.charset.StandardCharsets.UTF_8).trim();
                        tokenRegistry.burnToken(targetState, tokenId, tx.getFrom(), tx.getAmount(), targetState != this.state);
                    }
                }
                return new ExecutionResult(0, null, null, transactionEvents);
        }
        return new ExecutionResult(0, null, null, transactionEvents);  // Default return for any unhandled cases
    }

    private void processIoTTransactionWithState(AccountState targetState, Transaction tx, long blockHeight, long timestamp) throws Exception {
        Map<String, Object> data = new ObjectMapper().readValue(tx.getData(), Map.class);
        String action = (String) data.get("action");
        DeviceLifecycleManager lifecycle = targetState.getLifecycleManager();
        handleIoTAction(lifecycle, action, data, tx.getFrom(), timestamp);
    }

    private void handleIoTAction(DeviceLifecycleManager lifecycle, String action, Map<String, Object> data, String from, long timestamp) throws Exception {
        switch (action) {
            case "PROVISION":
                String sig = (String) (data.containsKey("manufacturerSignature") ? data.get("manufacturerSignature") : data.get("signature"));
                lifecycle.provisionDevice((String) data.get("deviceId"), (String) data.get("manufacturer"), (String) data.get("model"), HexUtils.decode((String) data.get("devicePublicKey")), HexUtils.decode(sig));
                break;
            case "ACTIVATE":
                lifecycle.activateDevice((String) data.get("deviceId"), (String) data.get("owner"), HexUtils.decode((String) data.get("devicePublicKey")), timestamp);
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

    private static double[] convertToDoubleArray(Object raw) {
        if (raw == null) return null;
        if (raw instanceof double[]) return Arrays.copyOf((double[]) raw, ((double[]) raw).length);
        if (!(raw instanceof List<?>)) return null;

        List<?> values = (List<?>) raw;
        double[] out = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (!(value instanceof Number)) return null;
            out[i] = ((Number) value).doubleValue();
        }
        return out;
    }

    // Expose chain height for convenience
    public int getHeight() {
        Block latest = getLatestBlock();
        return latest != null ? (int)latest.getIndex() : 0;
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

    public AccountState getAccountState() {
        return state;
    }


    public UTXOSet getUTXOSet() {
        return utxo;
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

    /**
     * Pre-validates an incoming peer block before calling {@link #applyBlock(Block)}.
     * Checks structural integrity without requiring full state replay.
     *
     * @param block the block to validate
     * @throws BlockValidationException if the block fails pre-validation
     */
    public void validateBlock(Block block) throws BlockValidationException {
        if (block == null)
            throw new BlockValidationException("Block is null", "null", -1);
        if (block.getHash() == null || block.getHash().isEmpty())
            throw new BlockValidationException("Block has no hash", "null", block.getIndex());

        if (!calculateTxRoot(block.getTransactions()).equals(block.getTxRoot()))
            throw new BlockValidationException("Invalid tx root", block.getHash(), block.getIndex());

        if (!block.getHash().equals(block.calculateHash()))
            throw new BlockValidationException("Block hash mismatch", block.getHash(), block.getIndex());
        if (block.getValidatorId() == null)
            throw new BlockValidationException("Block has no validatorId", block.getHash(), block.getIndex());
        if (!consensus.isValidator(block.getValidatorId()))
            throw new BlockValidationException("Unknown validator: " + block.getValidatorId(), block.getHash(), block.getIndex());
        if (block.getSignature() == null)
            throw new BlockValidationException("Block has no signature", block.getHash(), block.getIndex());
        lock.readLock().lock();
        try {
            Block latest = getLatestBlock();
            if (block.getIndex() != latest.getIndex() + 1)
                throw new BlockValidationException("Block height mismatch: expected "
                        + (latest.getIndex() + 1) + " got " + block.getIndex(), block.getHash(), block.getIndex());
            if (!block.getPrevHash().equals(latest.getHash()))
                throw new BlockValidationException("Block prevHash does not chain to tip", block.getHash(), block.getIndex());
        } finally {
            lock.readLock().unlock();
        }
        if (block.getTimestamp() > System.currentTimeMillis() + Config.MAX_TIMESTAMP_DRIFT)
            throw new BlockValidationException("Block timestamp too far in future", block.getHash(), block.getIndex());

        if (block.getTransactions() != null) {
            int currentBlockSize = 0;
            for (Transaction tx : block.getTransactions()) {
                currentBlockSize += serializeTransaction(tx).length;
            }
            if (currentBlockSize > Config.MAX_BLOCK_SIZE) {
                throw new BlockValidationException("Block size " + currentBlockSize + " exceeds limit " + Config.MAX_BLOCK_SIZE, block.getHash(), block.getIndex());
            }
        }
    }

    /**
     * Returns the total base fee currently required for new transactions.
     *
     * @return current base fee
     */
    public long getCurrentBaseFee() {
        return feeMarket.getCurrentBaseFee(storage);
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
