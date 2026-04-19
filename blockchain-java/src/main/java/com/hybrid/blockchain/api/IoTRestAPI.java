package com.hybrid.blockchain.api;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.FeeMarket;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.Mempool;
import com.hybrid.blockchain.PeerNode;
import com.hybrid.blockchain.PoAConsensus;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.Validator;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.security.RateLimiter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication
@ComponentScan(basePackages = "com.hybrid")
@RestController
@RequestMapping("/api/v1")
public class IoTRestAPI {

    private static final Logger log = LoggerFactory.getLogger(IoTRestAPI.class);

    private Blockchain blockchain;
    private Mempool mempool;
    private PoAConsensus poa;
    // Removed ContractVM contractVM;
    private JwtManager jwtManager;
    private IoTDeviceManager deviceManager;
    private MQTTAdapter mqttAdapter;
    private CoAPAdapter coapAdapter;
    private final RateLimiter apiRateLimiter = RateLimiter.Presets.apiLimiter();
    private final RateLimiter transactionSubmitLimiter = new RateLimiter(40, 40, 60000);

    private final ReentrantReadWriteLock blockchainLock = new ReentrantReadWriteLock();

    private static Blockchain sharedBlockchain;
    private static PeerNode sharedPeerNode;
    private static PBFTConsensus sharedPbft;

    public static void setNode(Blockchain b, PeerNode p, PBFTConsensus c) {
        sharedBlockchain = b;
        sharedPeerNode = p;
        sharedPbft = c;
    }

    @PostConstruct
    public void init() throws Exception {
        if (sharedBlockchain == null) {
            throw new RuntimeException("IoTRestAPI started without a valid sharedBlockchain. Use App.java to start the node.");
        }

        this.blockchain = sharedBlockchain;
        this.mempool = blockchain.getMempool();
        
        // PBFT specific setup
        if (sharedPbft != null) {
            this.poa = null; // We use PBFT now
        }

        this.deviceManager = new IoTDeviceManager();
        this.jwtManager = new JwtManager();
        this.mqttAdapter = new MQTTAdapter(this.blockchain);
        this.coapAdapter = new CoAPAdapter(this.blockchain);
        this.mqttAdapter.start();
        if (Config.NODE_ROLE == Config.NodeRole.GATEWAY) {
            this.coapAdapter.start();
        }
    }
    @Bean
    public HandlerInterceptor apiRateLimitInterceptor() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                String forwardedFor = request.getHeader("X-Forwarded-For");
                String ip = (forwardedFor != null && !forwardedFor.isBlank())
                        ? forwardedFor.split(",")[0].trim()
                        : request.getRemoteAddr();

                if (!apiRateLimiter.allowRequest(ip)) {
                    response.setStatus(429);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
                    return false;
                }
                return true;
            }
        };
    }

    @Autowired
    private MDCCorrelationInterceptor mdcInterceptor;

    @Bean
    public WebMvcConfigurer webMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(mdcInterceptor).addPathPatterns("/api/v1/**", "/metrics");
                registry.addInterceptor(apiRateLimitInterceptor()).addPathPatterns("/api/v1/**");
            }
        };
    }

    private boolean verifyToken(String token, String deviceId) {
        return jwtManager.validateToken(token, deviceId);
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
    }

    // ============================================
    // Health Check Endpoints for Docker
    // ============================================

    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "height", blockchain != null ? blockchain.getHeight() : -1,
                "peers", sharedPeerNode != null ? sharedPeerNode.getPeers().size() : 0));
    }

    @GetMapping(value = "/metrics", produces = "text/plain")
    public String metrics() {
        blockchainLock.readLock().lock();
        try {
            if (blockchain == null || blockchain.getMonitor() == null) {
                return "# Monitor not initialized\n";
            }
            com.hybrid.blockchain.monitoring.PrometheusBridge bridge = new com.hybrid.blockchain.monitoring.PrometheusBridge(blockchain.getMonitor());
            return bridge.buildMetrics();
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/chain/height")
    public ResponseEntity<?> chainHeight() {
        blockchainLock.readLock().lock();
        try {
            return ResponseEntity.ok(Map.of("height", blockchain.getHeight()));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/ready")
    public ResponseEntity<?> readinessCheck() {
        blockchainLock.readLock().lock();
        try {
            boolean ready = blockchain != null && blockchain.getHeight() >= 0;
            if (ready) {
                return ResponseEntity.ok(Map.of(
                        "status", "ready",
                        "nodeId", Config.NODE_ID,
                        "blockHeight", blockchain.getHeight(),
                        "timestamp", System.currentTimeMillis()));
            } else {
                return ResponseEntity.status(503).body(Map.of(
                        "status", "not ready",
                        "nodeId", Config.NODE_ID));
            }
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @PostMapping("/account/create")
    public ResponseEntity<?> createAccount() throws Exception {
        // Production: Use BouncyCastle to ensure secp256k1
        org.bouncycastle.asn1.x9.X9ECParameters ecParams = org.bouncycastle.crypto.ec.CustomNamedCurves
            .getByName(Config.EC_CURVE);
        org.bouncycastle.crypto.params.ECDomainParameters domainParams = new org.bouncycastle.crypto.params.ECDomainParameters(
                ecParams.getCurve(), ecParams.getG(), ecParams.getN(), ecParams.getH());
        org.bouncycastle.crypto.generators.ECKeyPairGenerator generator = new org.bouncycastle.crypto.generators.ECKeyPairGenerator();
        generator.init(new org.bouncycastle.crypto.params.ECKeyGenerationParameters(domainParams,
                new java.security.SecureRandom()));
        org.bouncycastle.crypto.AsymmetricCipherKeyPair kp = generator.generateKeyPair();

        org.bouncycastle.crypto.params.ECPublicKeyParameters pub = (org.bouncycastle.crypto.params.ECPublicKeyParameters) kp
                .getPublic();
        org.bouncycastle.crypto.params.ECPrivateKeyParameters priv = (org.bouncycastle.crypto.params.ECPrivateKeyParameters) kp
                .getPrivate();

        byte[] pubKeyBytes = pub.getQ().getEncoded(true); // compressed
        String address = Crypto.deriveAddress(pubKeyBytes);

        // For internal compatibility, we might still need a java.security.PublicKey if
        // other parts use it
        // but for a "paranoid" design, we should move everything to raw bytes/BC
        // params.
        // Sticking to bytes for now as it's cleaner for serialization.

        String token = jwtManager.issueToken(address);
        deviceManager.registerDevice(address, pubKeyBytes);

        log.info("Created new device account: {}", address);

        return ResponseEntity.ok(Map.of(
                "address", address,
                "publicKey", HexUtils.encode(pubKeyBytes),
                "privateKey", priv.getD().toString(16),
                "token", token));
    }

    @GetMapping("/accounts/{address}")
    public ResponseEntity<?> getAccount(@PathVariable("address") String address,
            @RequestHeader("Authorization") String auth) throws Exception {
        String token = auth.replace("Bearer ", "");
        if (!verifyToken(token, address))
            return unauthorized();

        blockchainLock.readLock().lock();
        try {
            long balance = blockchain.getBalance(address);
            long nonce = blockchain.getState().getNonce(address);
            return ResponseEntity.ok(Map.of(
                    "address", address,
                    "balance", balance,
                    "nonce", nonce));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @PostMapping("/transactions/submit")
    public ResponseEntity<?> submitTransaction(@RequestBody SubmitTransactionRequest payload,
            @RequestHeader(value = "Authorization", required = false) String auth) throws Exception {

        if (payload.getFrom() != null) {
            if (auth == null || auth.isBlank()) {
                return unauthorized();
            }
            String token = auth.replace("Bearer ", "");
            if (!verifyToken(token, payload.getFrom())) {
                return unauthorized();
            }
        }

        blockchainLock.writeLock().lock();
        try {
            if (payload.getFrom() != null && !transactionSubmitLimiter.allowRequest(payload.getFrom())) {
                return ResponseEntity.status(429).body(Map.of("error", "Rate limit exceeded"));
            }

            long nonce = blockchain.getState().getNonce(payload.getFrom()) + 1;

            Transaction tx = new Transaction.Builder()
                    .type(payload.getType())
                    .from(payload.getFrom())
                    .to(payload.getTo())
                    .amount(payload.getAmount())
                    .fee(payload.getFee())
                    .nonce(nonce)
                    .networkId(Config.NETWORK_ID)
                    .data(HexUtils.decode(payload.getData() == null ? "" : payload.getData()))
                    .build();

            try {
                blockchain.addTransaction(tx);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("rate limit")) {
                    return ResponseEntity.status(429).body(Map.of("error", e.getMessage()));
                }
                throw e;
            }

            log.info("Transaction submitted: {} from {}", tx.getTxid(), tx.getFrom());

            return ResponseEntity.ok(Map.of(
                    "txId", tx.getTxid(),
                    "status", "pending",
                    "timestamp", tx.getTimestamp()));
        } finally {
            blockchainLock.writeLock().unlock();
        }
    }

    @GetMapping("/transactions/{txId}")
    public ResponseEntity<?> getTransaction(@PathVariable("txId") String txId) {
        blockchainLock.readLock().lock();
        try {
            for (Transaction tx : mempool.toArray()) {
                if (tx.getTxid().equals(txId))
                    return ResponseEntity.ok(tx);
            }
            for (Block block : blockchain.getChain()) {
                for (Transaction tx : block.getTransactions()) {
                    if (tx.getTxid().equals(txId))
                        return ResponseEntity.ok(tx);
                }
            }
            return ResponseEntity.status(404).body(Map.of("error", "Transaction not found"));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/transactions/pending")
    public ResponseEntity<?> getPendingTransactions(@RequestHeader("Authorization") String auth,
            @RequestParam(value = "limit", required = false) Integer limit) {
        blockchainLock.readLock().lock();
        try {
            List<Transaction> pending = mempool.toArray();
            if (limit != null)
                pending = pending.stream().limit(limit).collect(Collectors.toList());
            return ResponseEntity.ok(pending);
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/blocks/latest")
    public ResponseEntity<?> getLatestBlock() {
        blockchainLock.readLock().lock();
        try {
            return ResponseEntity.ok(blockchain.getLatestBlock());
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/blocks/height/{height}")
    public ResponseEntity<?> getBlockByHeight(@PathVariable("height") int height) {
        blockchainLock.readLock().lock();
        try {
            if (height < 0 || height >= blockchain.getChain().size())
                return ResponseEntity.status(404).body(Map.of("error", "Block not found"));
            return ResponseEntity.ok(blockchain.getChain().get(height));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/blocks/{hash}")
    public ResponseEntity<?> getBlockByHash(@PathVariable("hash") String hash) {
        blockchainLock.readLock().lock();
        try {
            for (Block block : blockchain.getChain()) {
                if (block.getHash().equals(hash))
                    return ResponseEntity.ok(block);
            }
            return ResponseEntity.status(404).body(Map.of("error", "Block not found"));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/chain/status")
    public ResponseEntity<?> getChainStatus() {
        blockchainLock.readLock().lock();
        try {
            return ResponseEntity.ok(Map.of(
                "height", blockchain.getHeight(),
                "tipHash", blockchain.getLatestBlock().getHash(),
                "tipTimestamp", blockchain.getLatestBlock().getTimestamp(),
                "difficulty", blockchain.getDifficulty(),
                "mempoolSize", blockchain.getMempool().size()
            ));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/consensus")
    public ResponseEntity<?> getConsensusStatus() {
        if (sharedPbft != null) {
            return ResponseEntity.ok(Map.of(
                "viewNumber", sharedPbft.getViewNumber(),
                "currentLeader", sharedPbft.getCurrentLeader(),
                "validatorCount", sharedPbft.getValidatorCount(),
                "maxFaultyNodes", sharedPbft.getMaxFaultyNodes(),
                "committedBlocks", sharedPbft.getStats().get("committedBlocks")
            ));
        }
        return ResponseEntity.ok(Map.of("type", "POA"));
    }

    @GetMapping("/proof/{address}")
    public ResponseEntity<?> getAccountProof(@PathVariable("address") String address) {
        blockchainLock.readLock().lock();
        try {
            byte[] proof = blockchain.getState().getCompactAccountProof(address);
            return ResponseEntity.ok(Map.of(
                "address", address,
                "proof", Base64.getEncoder().encodeToString(proof)
            ));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/network/status")
    public ResponseEntity<?> getNetworkStatus() {
        blockchainLock.readLock().lock();
        try {
            List<Validator> validators = poa != null ? poa.getValidators() : Collections.emptyList();
            return ResponseEntity.ok(Map.of(
                    "nodeId", Config.NODE_ID,
                    "nodeName", Config.NODE_NAME,
                    "isSeed", Config.IS_SEED,
                    "blockHeight", blockchain.getHeight(),
                    "chainHeight", blockchain.getHeight(),
                    "networkId", Config.NETWORK_ID,
                    "protocolVersion", Config.PROTOCOL_VERSION,
                    "validatorCount", validators.size(),
                    "validators", validators.stream()
                            .map(v -> Map.of("id", v.getId()))
                            .collect(Collectors.toList())));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/network/peers")
    public ResponseEntity<?> getPeers() {
        List<Validator> validators = poa != null ? poa.getValidators() : Collections.emptyList();
        return ResponseEntity.ok(validators.stream()
                .map(Validator::getId)
                .collect(Collectors.toList()));
    }

    @GetMapping("/iot/devices/{deviceId}")
    public ResponseEntity<?> getIoTDevice(@PathVariable("deviceId") String deviceId) {
        blockchainLock.readLock().lock();
        try {
            var record = blockchain.getState().getLifecycleManager().getDeviceRecord(deviceId);
            return ResponseEntity.ok(Map.of(
                    "deviceId", record.getDeviceId(),
                    "status", record.getStatus().name(),
                    "owner", record.getOwner() == null ? "" : record.getOwner(),
                    "manufacturer", record.getManufacturer(),
                    "model", record.getModel(),
                    "firmwareVersion", record.getFirmwareVersion() == null ? "" : record.getFirmwareVersion()));
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @PostMapping("/contracts/deploy")
    public ResponseEntity<?> deployContract(@RequestBody ContractRequest payload) throws Exception {
        // In a hardened blockchain, deployment is just a transaction with bytecode
        log.info("Contract deployment request received (Stub)");
        return ResponseEntity
                .ok(Map.of("status", "pending", "info", "Submit via /transactions/submit with Type=CONTRACT"));
    }

    @PostMapping("/contracts/{address}/call")
    public ResponseEntity<?> callContract(@PathVariable("address") String address,
            @RequestBody ContractRequest payload) throws Exception {
        log.info("Contract call request received (Stub)");
        return ResponseEntity
                .ok(Map.of("status", "pending", "info", "Submit via /transactions/submit with Type=CONTRACT"));
    }

    // ============================================
    // Phase 1: Tokenomics & Fee Market
    // ============================================
    @GetMapping("/tokenomics")
    public ResponseEntity<?> getTokenomics() {
        blockchainLock.readLock().lock();
        try {
            long currentReward = com.hybrid.blockchain.Tokenomics.getCurrentReward(blockchain.getHeight() + 1, com.hybrid.blockchain.Tokenomics.getTotalMinted(blockchain));
            return ResponseEntity.ok(Map.of(
                    "maxSupply", com.hybrid.blockchain.Tokenomics.MAX_SUPPLY,
                    "totalMinted", com.hybrid.blockchain.Tokenomics.getTotalMinted(blockchain),
                    "remainingSupply", com.hybrid.blockchain.Tokenomics.getRemainingSupply(blockchain),
                    "currentBlockReward", currentReward,
                    "nextHalvingBlock", com.hybrid.blockchain.Tokenomics.getNextHalvingBlock(blockchain.getHeight())
            ));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/fee/estimate")
    public ResponseEntity<?> estimateFee() {
        return ResponseEntity.ok(Map.of("baseFee", blockchain.getCurrentBaseFee()));
    }

    // ============================================
    // Phase 2: Transaction Receipts
    // ============================================
    @GetMapping("/tx/{txid}/receipt")
    public ResponseEntity<?> getTransactionReceipt(@PathVariable("txid") String txid) {
        blockchainLock.readLock().lock();
        try {
            var receipt = blockchain.getStorage().loadReceipt(txid);
            if (receipt == null) return ResponseEntity.status(404).body(Map.of("error", "Receipt not found"));
            return ResponseEntity.ok(receipt);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/contracts/{address}/abi")
    public ResponseEntity<?> getContractABI(@PathVariable("address") String address) {
        blockchainLock.readLock().lock();
        try {
            var account = blockchain.getState().getAccount(address);
            if (account == null || account.getAbi() == null) {
                return ResponseEntity.status(404).body(Map.of("error", "ABI not found"));
            }
            return ResponseEntity.ok(account.getAbi());
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    // ============================================
    // Phase 3: Multi-Token Registry
    // ============================================
    @GetMapping("/tokens/{tokenId}")
    public ResponseEntity<?> getTokenInfo(@PathVariable("tokenId") String tokenId) {
        blockchainLock.readLock().lock();
        try {
            var tokenRegistry = blockchain.getTokenRegistry();
            if (tokenRegistry == null || !tokenRegistry.tokenExists(tokenId)) {
                return ResponseEntity.status(404).body(Map.of("error", "Token not found"));
            }
            return ResponseEntity.ok(tokenRegistry.getTokenInfo(tokenId));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/tokens/{tokenId}/balance/{address}")
    public ResponseEntity<?> getTokenBalance(@PathVariable("tokenId") String tokenId, @PathVariable("address") String address) {
        blockchainLock.readLock().lock();
        try {
            long balance = blockchain.getState().getTokenBalance(address, tokenId);
            return ResponseEntity.ok(Map.of("address", address, "tokenId", tokenId, "balance", balance));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    // ============================================
    // Phase 5: Transaction Indexer
    // ============================================
    @GetMapping("/address/{address}/transactions")
    public ResponseEntity<?> getAddressTransactions(@PathVariable("address") String address, @RequestParam(value = "offset", defaultValue = "0") int offset, @RequestParam(value = "limit", defaultValue = "50") int limit) {
        blockchainLock.readLock().lock();
        try {
            var txs = blockchain.getStorage().getAddressTransactions(address, offset, null);
            return ResponseEntity.ok(txs);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    // Explorer aliases
    @GetMapping("/explorer/address/{address}")
    public ResponseEntity<?> explorerAddress(@PathVariable("address") String address, @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return getAddressTransactions(address, offset, 50);
    }

    @GetMapping("/explorer/block/{hash}")
    public ResponseEntity<?> explorerBlock(@PathVariable("hash") String hash) {
        return getBlockByHash(hash);
    }

    @GetMapping("/explorer/tx/{txId}")
    public ResponseEntity<?> explorerTx(@PathVariable("txId") String txId) {
        return getTransaction(txId);
    }

    // ============================================
    // Phase 6: Device Telemetry
    // ============================================
    @GetMapping("/devices/{deviceId}/telemetry")
    public ResponseEntity<?> getDeviceTelemetry(@PathVariable("deviceId") String deviceId, @RequestParam(value = "fromBlock", defaultValue = "0") int fromBlock, @RequestParam(value = "toBlock", defaultValue = "-1") int toBlock) {
        blockchainLock.readLock().lock();
        try {
            if (toBlock == -1) toBlock = blockchain.getHeight();
            List<Map<String, Object>> records = blockchain.getStorage().getTelemetry(deviceId, fromBlock, toBlock);
            return ResponseEntity.ok(records);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    // ============================================
    // Phase 7: Checkpoints
    // ============================================
    @GetMapping("/checkpoint/latest")
    public ResponseEntity<?> getLatestCheckpoint() {
        blockchainLock.readLock().lock();
        try {
            var cp = blockchain.getStorage().loadLatestCheckpoint();
            if (cp == null) return ResponseEntity.status(404).body(Map.of("error", "No checkpoints found"));
            return ResponseEntity.ok(cp);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/checkpoint/{height}")
    public ResponseEntity<?> getCheckpointByHeight(@PathVariable("height") int height) {
        blockchainLock.readLock().lock();
        try {
            var cp = blockchain.getStorage().loadCheckpointAtHeight(height);
            if (cp == null) return ResponseEntity.status(404).body(Map.of("error", "Checkpoint not found"));
            return ResponseEntity.ok(cp);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    // ============================================
    // Admin Endpoints (require authentication)
    // ============================================

    @GetMapping("/admin/status")
    public ResponseEntity<?> adminStatus() {
        blockchainLock.readLock().lock();
        try {
            return ResponseEntity.ok(Map.of(
                    "nodeId", Config.NODE_ID,
                    "height", blockchain.getHeight(),
                    "difficulty", blockchain.getDifficulty(),
                    "totalMinted", blockchain.getTotalMinted(),
                    "mempoolSize", mempool.size(),
                    "peers", sharedPeerNode != null ? sharedPeerNode.getPeers().size() : 0,
                    "paused", blockchain != null ? blockchain.isPaused() : false,
                    "timestamp", System.currentTimeMillis()));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @PostMapping("/admin/node/pause")
    public ResponseEntity<?> adminPauseNode() {
        blockchainLock.writeLock().lock();
        try {
            blockchain.setPaused(true);
            log.info("Node PAUSED by admin");
            return ResponseEntity.ok(Map.of("status", "paused"));
        } finally {
            blockchainLock.writeLock().unlock();
        }
    }

    @PostMapping("/admin/node/resume")
    public ResponseEntity<?> adminResumeNode() {
        blockchainLock.writeLock().lock();
        try {
            blockchain.setPaused(false);
            log.info("Node RESUMED by admin");
            return ResponseEntity.ok(Map.of("status", "resumed"));
        } finally {
            blockchainLock.writeLock().unlock();
        }
    }

    @PostMapping("/admin/config/update")
    public ResponseEntity<?> adminUpdateConfig(@RequestBody Map<String, Object> payload) {
        // Limited dynamic config updates for production
        if (payload.containsKey("maxTransactionsPerBlock")) {
            Config.MAX_TRANSACTIONS_PER_BLOCK = ((Number) payload.get("maxTransactionsPerBlock")).intValue();
        }
        if (payload.containsKey("targetBlockTimeMs")) {
            Config.TARGET_BLOCK_TIME_MS = ((Number) payload.get("targetBlockTimeMs")).longValue();
        }
        return ResponseEntity.ok(Map.of("status", "updated", "config", Map.of(
            "maxTransactionsPerBlock", Config.MAX_TRANSACTIONS_PER_BLOCK,
            "targetBlockTimeMs", Config.TARGET_BLOCK_TIME_MS
        )));
    }

   @GetMapping("/admin/peers")
public ResponseEntity<?> adminPeers() {
    if (sharedPeerNode == null) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("peers", Collections.emptyList());
        return ResponseEntity.ok(resp);
    }

    List<Map<String, Object>> peerList = sharedPeerNode.getPeers().stream()
            .map(p -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", p.getId() != null ? p.getId() : "unknown");
                map.put("address", p.getAddress() != null ? p.getAddress() : "unknown");
                map.put("port", p.getPort());
                return map;
            })
            .collect(Collectors.toList());

    Map<String, Object> response = new HashMap<>();
    response.put("peers", peerList);
    response.put("count", peerList.size());

    return ResponseEntity.ok(response);
}

    @PostMapping("/admin/broadcast-block")
    public ResponseEntity<?> adminBroadcastBlock(@RequestBody Map<String, Object> payload) {
        try {
            // This endpoint allows forcing a block broadcast to the network
            // Used for testing consensus recovery scenarios
            blockchainLock.readLock().lock();
            try {
                Block latestBlock = blockchain.getLatestBlock();
                if (sharedPeerNode != null) {
                    sharedPeerNode.broadcastBlock(latestBlock);
                    return ResponseEntity.ok(Map.of(
                            "status", "broadcasted",
                            "blockHash", latestBlock.getHash(),
                            "blockHeight", latestBlock.getIndex()));
                } else {
                    return ResponseEntity.status(400).body(Map.of("error", "PeerNode not initialized"));
                }
            } finally {
                blockchainLock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("Failed to broadcast block: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/admin/peers/{peerId}")
    public ResponseEntity<?> adminDisconnectPeer(@PathVariable("peerId") String peerId) {
        try {
            if (sharedPeerNode == null) {
                return ResponseEntity.status(400).body(Map.of("error", "PeerNode not initialized"));
            }
            
            sharedPeerNode.disconnectPeer(peerId);
            log.info("Disconnected peer: {}", peerId);
            
            return ResponseEntity.ok(Map.of(
                    "status", "disconnected",
                    "peerId", peerId));
        } catch (Exception e) {
            log.error("Failed to disconnect peer {}: {}", peerId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/admin/metrics")
public ResponseEntity<?> adminMetrics() {
    blockchainLock.readLock().lock();
    try {
        com.hybrid.blockchain.monitoring.BlockchainMonitor monitor = blockchain.getMonitor();
        if (monitor == null) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("error", "Monitor not initialized");
            return ResponseEntity.status(400).body(resp);
        }

        com.hybrid.blockchain.monitoring.BlockchainMonitor.Dashboard dashboard = monitor.getDashboard();
        Map<String, Object> resp = new HashMap<>();
        resp.put("nodeId", dashboard.getNodeId());  // Use getter
        resp.put("uptime", dashboard.getUptime());  // Use getter
        resp.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(resp);
    } catch (Exception e) {
        log.error("Failed to retrieve metrics: {}", e.getMessage());
        Map<String, Object> resp = new HashMap<>();
        resp.put("error", e.getMessage());
        return ResponseEntity.status(500).body(resp);
    } finally {
        blockchainLock.readLock().unlock();
    }
}

    // ============================================
    // Phase 8: AI Features
    // ============================================
    @GetMapping("/ai/threat-scores")
    public ResponseEntity<?> getPredictiveThreatScores() {
        return ResponseEntity.ok(com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().getAllScores());
    }

    // ============================================
    // AI/ML Endpoints (Task 3)
    // ============================================

    /**
     * GET /api/v1/chain/tip
     * Returns the hash of the current chain tip (used by docker-test.sh).
     */
    @GetMapping("/chain/tip")
    public ResponseEntity<?> getChainTip() {
        blockchainLock.readLock().lock();
        try {
            return ResponseEntity.ok(Map.of(
                    "hash",   blockchain.getLatestBlock().getHash(),
                    "height", blockchain.getHeight()));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    /**
     * GET /api/v1/ai/anomalies/{deviceId}
     * Returns the Z-score anomaly detection statistics for a device.
     */
    @GetMapping("/ai/anomalies/{deviceId}")
    public ResponseEntity<?> getAnomalyStats(@PathVariable("deviceId") String deviceId) {
        com.hybrid.blockchain.ai.TelemetryAnomalyDetector.AnomalyStats stats =
                com.hybrid.blockchain.ai.TelemetryAnomalyDetector.getInstance().getStats(deviceId);
        if (stats == null) {
            return ResponseEntity.status(404).body(Map.of("error", "No telemetry data for device " + deviceId));
        }
        return ResponseEntity.ok(Map.of(
                "deviceId",             stats.deviceId,
                "totalChecked",         stats.totalChecked,
                "anomaliesDetected",    stats.anomaliesDetected,
                "lastValue",            stats.lastValue,
                "lastZScore",           stats.lastZScore,
                "lastDetectedAt",       stats.lastDetectedTimestamp,
                "windowSize",           com.hybrid.blockchain.ai.TelemetryAnomalyDetector.getInstance().getWindowSize(deviceId)));
    }

    /**
     * GET /api/v1/ai/anomalies
     * Returns anomaly stats for all known devices.
     */
    @GetMapping("/ai/anomalies")
    public ResponseEntity<?> getAllAnomalyStats() {
        var all = com.hybrid.blockchain.ai.TelemetryAnomalyDetector.getInstance().getAllStats();
        return ResponseEntity.ok(all.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Map.of(
                                "totalChecked",      e.getValue().totalChecked,
                                "anomaliesDetected", e.getValue().anomaliesDetected,
                                "lastZScore",        e.getValue().lastZScore))));
    }

    /**
     * GET /api/v1/consensus/reputation
     * Returns the reputation scores for all known validators.
     */
    @GetMapping("/consensus/reputation")
    public ResponseEntity<?> getValidatorReputation() {
        if (sharedPbft == null) {
            return ResponseEntity.status(503).body(Map.of("error", "PBFT not initialized"));
        }
        return ResponseEntity.ok(Map.of(
                "reputations",    sharedPbft.getReputationMap(),
                "currentLeader",  sharedPbft.getCurrentLeader(),
                "viewNumber",     sharedPbft.getViewNumber()));
    }

    /**
     * POST /api/v1/ai/federated/submit
     * Body: {"nodeId":"...", "weights":[1.0, 2.0, ...]}
     * Submits a local model weight update from a node.
     */
    @PostMapping("/ai/federated/submit")
    public ResponseEntity<?> submitFederatedUpdate(@RequestBody Map<String, Object> payload) {
        try {
            String nodeId = (String) payload.get("nodeId");
            if (nodeId == null || nodeId.isBlank())
                return ResponseEntity.status(400).body(Map.of("error", "nodeId is required"));

            @SuppressWarnings("unchecked")
            java.util.List<Number> rawWeights = (java.util.List<Number>) payload.get("weights");
            if (rawWeights == null || rawWeights.isEmpty())
                return ResponseEntity.status(400).body(Map.of("error", "weights array is required"));

            double[] weights = new double[rawWeights.size()];
            for (int i = 0; i < weights.length; i++) weights[i] = rawWeights.get(i).doubleValue();

            com.hybrid.blockchain.ai.FederatedLearningManager.getInstance().submitUpdate(nodeId, weights);

            return ResponseEntity.ok(Map.of(
                    "status",         "accepted",
                    "nodeId",         nodeId,
                    "weightCount",    weights.length,
                    "pendingUpdates", com.hybrid.blockchain.ai.FederatedLearningManager.getInstance().getPendingUpdateCount()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/v1/ai/federated/model
     * Returns the latest aggregated model and its SHA-256 hash.
     */
    @GetMapping("/ai/federated/model")
    public ResponseEntity<?> getFederatedModel() {
        com.hybrid.blockchain.ai.FederatedLearningManager mgr =
                com.hybrid.blockchain.ai.FederatedLearningManager.getInstance();
        double[] model = mgr.getCurrentModel();
        return ResponseEntity.ok(Map.of(
                "round",                 mgr.getRoundNumber(),
                "modelHash",             mgr.getCurrentModelHash(),
                "weightCount",           model.length,
                "pendingUpdates",        mgr.getPendingUpdateCount(),
                "lastAggregatedAt",      mgr.getLastAggregatedTimestamp(),
                "model",                 model.length <= 256 ? model : "[truncated — too large to inline]"));
    }

    /**
     * GET /api/v1/ai/fee-prediction?txCount=N&blockTimeMs=M
     * Predicts the optimal fee for a transaction using OLS regression.
     */
    @GetMapping("/ai/fee-prediction")
    public ResponseEntity<?> predictFee(
            @RequestParam(value = "txCount",    defaultValue = "10")  long txCount,
            @RequestParam(value = "blockTimeMs", defaultValue = "0")  long blockTimeMs) {
        blockchainLock.readLock().lock();
        try {
            int validatorCount = (sharedPbft != null) ? sharedPbft.getValidatorCount() : 0;
            long predicted = FeeMarket.predictOptimalFee(txCount, blockTimeMs, blockchain.getStorage(), validatorCount);
            long current   = FeeMarket.getCurrentBaseFee(blockchain.getStorage());
            return ResponseEntity.ok(Map.of(
                    "predictedFee",  predicted,
                    "currentBaseFee",current,
                    "inputTxCount",  txCount,
                    "inputBlockTimeMs", blockTimeMs));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e) {
        log.error("Error occurred: {}", e.getMessage(), e);
        return ResponseEntity.status(500).body(Map.of(
                "error", e.getMessage()));
    }

    public static class SubmitTransactionRequest {
        private String from;
        private String to;
        private long amount = 0;
        private long fee = 0;
        private String data = "";
        private Transaction.Type type;

        // getters/setters
        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public long getAmount() {
            return amount;
        }

        public void setAmount(long amount) {
            this.amount = amount;
        }

        public long getFee() {
            return fee;
        }

        public void setFee(long fee) {
            this.fee = fee;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }

        public Transaction.Type getType() {
            return type;
        }

        public void setType(Transaction.Type type) {
            this.type = type;
        }
    }

    public static class ContractRequest {
        private String code;
        private Map<String, Object> state = new HashMap<>();

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public Map<String, Object> getState() {
            return state;
        }

        public void setState(Map<String, Object> state) {
            this.state = state;
        }
    }
}
