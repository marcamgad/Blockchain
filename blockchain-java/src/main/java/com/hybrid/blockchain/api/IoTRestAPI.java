package com.hybrid.blockchain.api;

import com.hybrid.blockchain.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

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

    private final ReentrantReadWriteLock blockchainLock = new ReentrantReadWriteLock();

    public static void main(String[] args) {
        SpringApplication.run(IoTRestAPI.class, args);
    }

    @PostConstruct
    public void init() throws Exception {
        this.mempool = new Mempool(10000);
        List<Validator> validators = new ArrayList<>();
        this.poa = new PoAConsensus(validators);

        this.blockchain = new Blockchain(null, mempool, poa);
        this.blockchain.init();

        this.identityManager = new IdentityManager();
        // Removed this.contractVM = new ContractVM();

        this.jwtManager = new JwtManager(); // JWT token manager
        this.deviceManager = new IoTDeviceManager(); // persistent device registry

        log.info("IoT REST API initialized successfully");
    }

    private boolean verifyToken(String token, String deviceId) {
        return jwtManager.validateToken(token, deviceId);
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
    }

    @PostMapping("/account/create")
    public ResponseEntity<?> createAccount() throws Exception {
        // Production: Use BouncyCastle to ensure secp256k1
        org.bouncycastle.asn1.x9.X9ECParameters ecParams = org.bouncycastle.crypto.ec.CustomNamedCurves
                .getByName("secp256k1");
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
            @RequestHeader("Authorization") String auth) throws Exception {

        String token = auth.replace("Bearer ", "");
        if (!verifyToken(token, payload.getFrom()))
            return unauthorized();

        blockchainLock.writeLock().lock();
        try {
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

            blockchain.addTransaction(tx);
            mempool.add(tx);

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

    @GetMapping("/network/status")
    public ResponseEntity<?> getNetworkStatus() {
        blockchainLock.readLock().lock();
        try {
            return ResponseEntity.ok(Map.of(
                    "chainHeight", blockchain.getHeight(),
                    "networkId", Config.NETWORK_ID,
                    "validatorCount", poa.getValidators().size(),
                    "validators", poa.getValidators().stream()
                            .map(v -> Map.of("id", v.getId()))
                            .collect(Collectors.toList())));
        } finally {
            blockchainLock.readLock().unlock();
        }
    }

    @GetMapping("/network/peers")
    public ResponseEntity<?> getPeers() {
        return ResponseEntity.ok(poa.getValidators().stream()
                .map(Validator::getId)
                .collect(Collectors.toList()));
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
