package com.hybrid.blockchain.api;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.IdentityManager;
import com.hybrid.blockchain.Mempool;
import com.hybrid.blockchain.PeerNode;
import com.hybrid.blockchain.PoAConsensus;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.Validator;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.security.RateLimiter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication
@RestController
@RequestMapping("/api/v1")
public class IoTRestAPI {

    private static final Logger log = LoggerFactory.getLogger(IoTRestAPI.class);

    private Blockchain blockchain;
    private Mempool mempool;
    private IdentityManager identityManager;
    private PoAConsensus poa;
    // Removed ContractVM contractVM;
    private JwtManager jwtManager;
    private IoTDeviceManager deviceManager;
    private MQTTAdapter mqttAdapter;
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

    public static void main(String[] args) {
        SpringApplication.run(IoTRestAPI.class, args);
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

    @Bean
    public WebMvcConfigurer webMvcConfigurer(HandlerInterceptor apiRateLimitInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(apiRateLimitInterceptor).addPathPatterns("/api/v1/**");
            }
        };
    }

    @PostConstruct
    public void init() throws Exception {
        if (sharedBlockchain != null) {
            this.blockchain = sharedBlockchain;
            this.mempool = blockchain.getMempool();
            this.deviceManager = new IoTDeviceManager();
            this.jwtManager = new JwtManager();
            this.identityManager = new IdentityManager();
            this.mqttAdapter = new MQTTAdapter(this.blockchain);
            this.mqttAdapter.start();
        } else {
            // Fallback bootstrap
            this.mempool = new Mempool(10000);
            List<Validator> validators = new ArrayList<>();
            // Bootstrap validator set from env if possible
            String valPubKeysEnv = System.getenv("VALIDATOR_PUBKEYS");
            if (valPubKeysEnv != null && !valPubKeysEnv.isEmpty()) {
                for (String pubHex : valPubKeysEnv.split(",")) {
                    byte[] vPub = HexUtils.decode(pubHex.trim());
                    validators.add(new Validator(Crypto.deriveAddress(vPub), vPub));
                }
            }
            this.poa = new PoAConsensus(validators);
            this.blockchain = new Blockchain(null, mempool, poa);
            this.blockchain.init();
            this.identityManager = new IdentityManager();
            this.jwtManager = new JwtManager();
            this.deviceManager = new IoTDeviceManager();
            this.mqttAdapter = new MQTTAdapter(this.blockchain);
            this.mqttAdapter.start();
        }

        log.info("========================================");
        log.info("IoT Blockchain Node Starting");
        log.info("========================================");
        log.info("Node ID: {}", Config.NODE_ID);
        log.info("========================================");
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
    public ResponseEntity<?> getAccount(@PathVariable String address,
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
    public ResponseEntity<?> getTransaction(@PathVariable String txId) {
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
            @RequestParam(required = false) Integer limit) {
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
    public ResponseEntity<?> getBlockByHeight(@PathVariable int height) {
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
    public ResponseEntity<?> getBlockByHash(@PathVariable String hash) {
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
    public ResponseEntity<?> getAccountProof(@PathVariable String address) {
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
    public ResponseEntity<?> getIoTDevice(@PathVariable String deviceId) {
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
    public ResponseEntity<?> callContract(@PathVariable String address,
            @RequestBody ContractRequest payload) throws Exception {
        log.info("Contract call request received (Stub)");
        return ResponseEntity
                .ok(Map.of("status", "pending", "info", "Submit via /transactions/submit with Type=CONTRACT"));
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
