package com.hybrid.blockchain;

import java.net.*;
import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.p2p.GossipEngine;
import com.hybrid.blockchain.p2p.P2PMessage;
import com.hybrid.blockchain.p2p.PeerManager;
import com.hybrid.blockchain.security.CertificateAuthority;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P2P Node for distributed blockchain communication.
 * Manages peer connections, gossip propagation, and consensus message routing.
 */
public class PeerNode implements PBFTConsensus.PBFTMessenger {
    private static final Logger log = LoggerFactory.getLogger(PeerNode.class);
    
    private final int port;
    private final ExecutorService executor;
    private final ScheduledExecutorService peerSyncScheduler;
    private volatile ScheduledFuture<?> peerSyncTask;
    private ServerSocket serverSocket;
    private final java.math.BigInteger privateKey;
    private final byte[] localPubKey;
    private final String localAddress;
    private final String localDID;
    private Blockchain blockchain;
    private Consensus consensus;
    private SSLContext sslContext;
    
    private final PeerManager peerManager;
    private final GossipEngine gossipEngine;
    private final Map<String, DataOutputStream> peerConnections = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> ipConnectionCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_CONN_PER_IP = 3;
    private final Map<Long, AtomicBoolean> appliedSequences = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> sentCommits = new ConcurrentHashMap<>();
    private final Map<Integer, Checkpoint> pendingCheckpoints = new ConcurrentHashMap<>();

    /** [HC-045] bounded list of discovered (unconnected) peers; never used as gossip targets */
    static final int MAX_DISCOVERED_PEERS = 64;
    private final Map<String, String> discoveredPeers = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) {
                    return size() > MAX_DISCOVERED_PEERS;
                }
            });

    void recordCandidate(String id, String address, int port) {
        if (address == null || port <= 0 || port > 65535) return;
        discoveredPeers.put(id, address + ":" + port);
    }

    /** Discovered peer candidates (id -> host:port), e.g. for an operator or a future dialer. */
    public Map<String, String> getDiscoveredPeers() {
        synchronized (discoveredPeers) { return new java.util.HashMap<>(discoveredPeers); }
    }

    /** [HC-012] unauthenticated-phase bounds */
    static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    static final int MAX_PENDING_HANDSHAKES = 16;
    private final java.util.concurrent.atomic.AtomicInteger pendingHandshakes = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * [HC-011] Who may join after a successful handshake. Under PBFT: validators only, unless the
     * operator explicitly allows read-only observer nodes (ALLOW_OBSERVER_PEERS=true).
     */
    boolean isAuthorisedPeer(String peerAddress) {
        if (!(consensus instanceof PBFTConsensus)) return true;
        if (consensus.isValidator(peerAddress)) return true;
        String allow = System.getProperty("ALLOW_OBSERVER_PEERS", System.getenv("ALLOW_OBSERVER_PEERS"));
        return Boolean.parseBoolean(allow);
    }

    /**
     * Message types for peer-to-peer communication.
     */
    public enum MsgType {
        HELLO, CHALLENGE, HANDSHAKE_OK, TRANSACTION, BLOCK, PEER_LIST,
        PBFT_PRE_PREPARE, PBFT_PREPARE, PBFT_COMMIT, PBFT_VIEW_CHANGE, PBFT_NEW_VIEW,
        REQUEST_BLOCKS, BLOCKS_RESPONSE, CHECKPOINT
    }

    // [HC-042] v2: handshake signatures are domain-separated (see handshakeDigest).
    private static final int PROTOCOL_VERSION = 2;
    /** [HC-043] A checkpoint matches our chain if we hold that block with that hash and state root. */
    boolean matchesLocalChain(Checkpoint cp) {
        Block local = blockchain.getStorage().loadBlockByHeight(cp.getBlockHeight());
        return local != null && local.getHash().equals(cp.getBlockHash())
                && local.getStateRoot() != null && local.getStateRoot().equals(cp.getStateRoot());
    }

    /**
     * [HC-043] Adds to {@code pending} only the signatures in {@code incoming} that are by a known
     * validator and verify over the SAME checkpoint content. Before, any signature map was merged
     * unverified (and across different checkpoints of one height), so fabricated entries could
     * "finalise" a checkpoint. Returns the number of signatures added.
     */
    int mergeVerifiedSignatures(Checkpoint pending, Checkpoint incoming) {
        if (!pending.computeCheckpointHash().equals(incoming.computeCheckpointHash())) return 0;
        byte[] digest = HexUtils.decode(pending.computeCheckpointHash());
        int added = 0;
        for (Map.Entry<String, String> e : incoming.getValidatorSignatures().entrySet()) {
            Validator v = consensus.getValidators().stream()
                    .filter(x -> x.getId().equals(e.getKey())).findFirst().orElse(null);
            if (v == null || pending.getValidatorSignatures().containsKey(e.getKey())) continue;
            if (Crypto.verify(digest, HexUtils.decode(e.getValue()), v.getPublicKey())) {
                pending.getValidatorSignatures().put(e.getKey(), e.getValue());
                added++;
            }
        }
        return added;
    }

    /** HELLO body: version (4) + network id (4) + rule flags (4) + nonce (32). */
    private static final int HELLO_LENGTH = 4 + 4 + 4 + 32;

    /**
     * [HC-008] Consensus-rule flags a node must agree on with its peers. REQUIRE_QUANTUM_SIG is read
     * per node from the environment and changes which transactions are valid; two nodes configured
     * differently would fork on the first EC-only transaction. Now they refuse to connect instead.
     */
    static int ruleFlags() {
        return Config.REQUIRE_QUANTUM_SIG ? 1 : 0;
    }

    private static void checkRuleFlags(int remote) throws IOException {
        if (remote != ruleFlags())
            throw new IOException("Consensus rule mismatch (REQUIRE_QUANTUM_SIG local=" + Config.REQUIRE_QUANTUM_SIG
                    + ", remote=" + ((remote & 1) == 1) + ")");
    }

    /** [HC-007] fixed CHALLENGE payload size: compressed secp256k1 key (33) + signature (64). */
    private static final int CHALLENGE_LENGTH = 33 + 64;

    /**
     * [HC-042] Digest a node signs to prove key possession during the handshake.
     *
     * <p>Previously the node signed the peer-chosen 32-byte nonce directly with its consensus key.
     * Because transaction payloads and block hashes are also 32-byte digests, a peer could send one
     * of those as its "nonce" and receive a valid transaction or block signature. The domain tag
     * makes a handshake signature unusable for anything else, and binding both identities and the
     * network id stops it being relayed to a third party.
     */
    static byte[] handshakeDigest(byte[] nonce, String signerAddress, int networkId) {
        return Crypto.hashWithDomain("HC-P2P-HANDSHAKE-v2", nonce,
                signerAddress.getBytes(StandardCharsets.UTF_8),
                java.nio.ByteBuffer.allocate(4).putInt(networkId).array());
    }

    public PeerNode(int port, Blockchain blockchain, Consensus consensus) {
        this(port, blockchain, consensus, Config.getNodePrivateKey(), new ArrayList<>());
    }

    public PeerNode(int port, Blockchain blockchain, Consensus consensus, BigInteger privateKey) {
        this(port, blockchain, consensus, privateKey, new ArrayList<>());
    }

    /**
     * Create a PeerNode with legacy self-signed certificates.
     * 
     * @param port the P2P port
     * @param blockchain the blockchain instance
     * @param consensus the consensus instance
     * @param privateKey the node's private key
     * @param trustedCerts list of trusted peer certificates
     */
    public PeerNode(int port, Blockchain blockchain, Consensus consensus, BigInteger privateKey, List<X509Certificate> trustedCerts) {
        this.port = port;
        this.executor = Executors.newCachedThreadPool();
        this.peerSyncScheduler = Executors.newSingleThreadScheduledExecutor();
        this.serverSocket = null;
        this.privateKey = privateKey;
        this.localPubKey = Crypto.derivePublicKey(this.privateKey);
        this.localAddress = Crypto.deriveAddress(localPubKey);
        this.localDID = com.hybrid.blockchain.security.IdentityUtils.addressToDID(localAddress);
        this.blockchain = blockchain;
        this.consensus = consensus;
        
        this.peerManager = new PeerManager();
        this.gossipEngine = new GossipEngine(peerManager, 3); // Fan-out = 3

        initializeGossipHandlers();

        try {
            // Use real blockchain private key for TLS identity
            X9ECParameters ecParams = CustomNamedCurves.getByName(Config.EC_CURVE);
            ECParameterSpec spec = new ECParameterSpec(ecParams.getCurve(), ecParams.getG(), ecParams.getN(), ecParams.getH());
            
            KeyFactory kf = KeyFactory.getInstance("EC", "BC");
            PrivateKey ecPrivateKey = kf.generatePrivate(new ECPrivateKeySpec(privateKey, spec));
            PublicKey ecPublicKey = kf.generatePublic(new ECPublicKeySpec(ecParams.getG().multiply(privateKey), spec));
            
            KeyPair tlsKeyPair = new KeyPair(ecPublicKey, ecPrivateKey);
            this.sslContext = com.hybrid.blockchain.security.SSLUtils.createSSLContext(tlsKeyPair, localAddress, trustedCerts);
        } catch (Exception e) {
            log.error("[P2P] Failed to initialize SSLContext: {}", e.getMessage(), e);
            try {
                KeyPairGenerator fallbackGen = KeyPairGenerator.getInstance("EC");
                fallbackGen.initialize(256);
                KeyPair fallbackKeyPair = fallbackGen.generateKeyPair();
                this.sslContext = com.hybrid.blockchain.security.SSLUtils.createSSLContext(fallbackKeyPair, localAddress, trustedCerts);
            } catch (Exception fallbackError) {
                throw new RuntimeException("Unable to initialize SSL context for P2P node", fallbackError);
            }
        }
    }

    /**
     * Create a PeerNode with CA-signed certificates (recommended for production).
     * 
     * @param port the P2P port
     * @param blockchain the blockchain instance
     * @param consensus the consensus instance
     * @param privateKey the node's consensus private key
     * @param nodeKeyPair the node's TLS certificate keypair
     * @param ca the CertificateAuthority for mTLS
     */
    public PeerNode(int port, Blockchain blockchain, Consensus consensus, BigInteger privateKey, 
                    KeyPair nodeKeyPair, CertificateAuthority ca) {
        this.port = port;
        this.executor = Executors.newCachedThreadPool();
        this.peerSyncScheduler = Executors.newSingleThreadScheduledExecutor();
        this.serverSocket = null;
        this.privateKey = privateKey;
        this.localPubKey = Crypto.derivePublicKey(this.privateKey);
        this.localAddress = Crypto.deriveAddress(localPubKey);
        this.localDID = com.hybrid.blockchain.security.IdentityUtils.addressToDID(localAddress);
        this.blockchain = blockchain;
        this.consensus = consensus;
        
        this.peerManager = new PeerManager();
        this.gossipEngine = new GossipEngine(peerManager, 3); // Fan-out = 3

        initializeGossipHandlers();

        try {
            this.sslContext = com.hybrid.blockchain.security.SSLUtils.createSSLContextWithCA(ca, nodeKeyPair, localAddress);
            log.info("[P2P] SSLContext initialized with CA-signed certificate for {}", localAddress);
        } catch (Exception e) {
            log.error("[P2P] Failed to initialize SSLContext with CA: {}", e.getMessage(), e);
            throw new RuntimeException("Unable to initialize SSL context for P2P node with CA", e);
        }
    }


    private void initializeGossipHandlers() {
        gossipEngine.setRelayDispatcher((target, message) -> {
            DataOutputStream out = peerConnections.get(target.getId());
            if (out != null) {
                sendMessage(out, message);
            }
        });

        gossipEngine.registerHandler(P2PMessage.Type.TRANSACTION, msg -> {
            try {
                Transaction tx = blockchain.deserializeTransaction(msg.getPayload());
                blockchain.addTransaction(tx);
                log.debug("[P2P] Indexed transaction via gossip: {}", tx.getId());
            } catch (Exception e) {
                log.warn("[P2P] Failed to process gossiped transaction: {}", e.getMessage());
                peerManager.updatePeerScore(msg.getSenderId(), -1.0);
            }
        });

        gossipEngine.registerHandler(P2PMessage.Type.BLOCK, msg -> {
            try {
                Block block = blockchain.deserializeBlock(msg.getPayload());
                blockchain.validateBlock(block);

                if (consensus instanceof PBFTConsensus) {
                    PBFTConsensus pbft = (PBFTConsensus) consensus;
                    if (pbft.isValidator(localAddress)) {
                        // ... PBFT logic ...
                        long seq = block.getIndex();
                        String hash = block.getHash();
                        long view = pbft.getViewNumber();

                        pbft.setPendingBlock(seq, block);

                        // [PHASE-1] P1-A: Async Fast Path
                        if (pbft.isFastPathActive(seq)) {
                            log.info("[PBFT-ASYNC] Fast path active for seq {}. Skipping PREPARE.", seq);
                            sendCommit(pbft, seq, hash, view);
                            return;
                        }

                        // Generate and send PREPARE
                        PBFTConsensus.PBFTMessage prepMsg = new PBFTConsensus.PBFTMessage(
                                PBFTConsensus.Phase.PREPARE, view, seq, hash, localAddress);
                        prepMsg.sign(privateKey);
                        pbft.addPrepareVote(seq, hash, localAddress, prepMsg.signature);
                        pbft.getMessenger().broadcastPrepare(seq, hash, localAddress, prepMsg.signature);

                        if (pbft.hasQuorum(seq, PBFTConsensus.Phase.PREPARE)) {
                            sendCommit(pbft, seq, hash, view);
                        }
                    }
                } else {
                    blockchain.applyBlock(block);
                }
            } catch (BlockValidationException e) {
                log.warn("[P2P] Block validation failed from peer {}: {}", msg.getSenderId(), e.getMessage());
                peerManager.updatePeerScore(msg.getSenderId(), -10.0); // Harsh penalty for invalid blocks
            } catch (Exception e) {
                log.error("[P2P] Error processing block from {}: {}", msg.getSenderId(), e.getMessage());
                peerManager.updatePeerScore(msg.getSenderId(), -1.0);
            }
        });

        gossipEngine.registerHandler(P2PMessage.Type.CONSENSUS, msg -> {
            handleConsensusMessage(msg);
        });

        gossipEngine.registerHandler(P2PMessage.Type.PEER_DISCOVERY, msg -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> peerList = mapper.readValue(msg.getPayload(), List.class);
                for (Map<String, Object> p : peerList) {
                    String id = (String) p.get("id");
                    String addr = (String) p.get("address");
                    int port = ((Number) p.get("port")).intValue();
                    // [HC-045] Discovered peers are only CANDIDATES. They used to be inserted into the
                    // gossip peer table without any connection: they filled its 50 slots (so real
                    // authenticated peers could no longer be added) and were picked as gossip
                    // targets that silently dropped every message.
                    if (id != null && !id.equals(localAddress)) {
                        recordCandidate(id, addr, port);
                    }
                }
            } catch (Exception e) {
                log.error("[P2P] Peer discovery error: {}", e.getMessage());
            }
        });

        gossipEngine.registerHandler(P2PMessage.Type.REQUEST_BLOCKS, msg -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> req = mapper.readValue(msg.getPayload(), Map.class);
                long from = ((Number) req.get("fromHeight")).longValue();
                long to = ((Number) req.get("toHeight")).longValue();
                
                List<Block> blocks = new ArrayList<>();
                for (long i = from; i <= to; i++) {
                    Block b = blockchain.getStorage().loadBlockByHeight((int) i);
                    if (b != null) blocks.add(b);
                    else break;
                }
                
                if (!blocks.isEmpty()) {
                    byte[] payload = mapper.writeValueAsBytes(blocks);
                    P2PMessage resp = P2PMessage.create(localAddress, privateKey, P2PMessage.Type.BLOCKS_RESPONSE, payload);
                    DataOutputStream out = peerConnections.get(msg.getSenderId());
                    if (out != null) sendMessage(out, resp);
                }
            } catch (Exception e) {
                log.warn("[P2P] Error handling block request: {}", e.getMessage());
            }
        });

        gossipEngine.registerHandler(P2PMessage.Type.BLOCKS_RESPONSE, msg -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rawBlocks = mapper.readValue(msg.getPayload(), List.class);
                for (Map<String, Object> raw : rawBlocks) {
                    Block b = mapper.convertValue(raw, Block.class);
                    if (b.getIndex() == blockchain.getHeight() + 1) {
                        try {
                            blockchain.validateBlock(b);
                            blockchain.applyBlock(b);
                        } catch (BlockValidationException ve) {
                            log.warn("[P2P] Sync block validation failed: {}", ve.getMessage());
                            peerManager.updatePeerScore(msg.getSenderId(), -5.0);
                            break; // Stop processing this response
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[P2P] Error handling blocks response: {}", e.getMessage());
            }
        });

        gossipEngine.registerHandler(P2PMessage.Type.CHECKPOINT, msg -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Checkpoint cp = mapper.readValue(msg.getPayload(), Checkpoint.class);
                
                if (cp.getValidatorSignatures().isEmpty()) {
                    // This is a signature request
                    // [HC-043] sign only a checkpoint that matches OUR chain at that height
                    if (consensus instanceof PBFTConsensus && ((PBFTConsensus) consensus).isValidator(localAddress)
                            && matchesLocalChain(cp)) {
                        String hash = cp.computeCheckpointHash();
                        byte[] sig = Crypto.sign(HexUtils.decode(hash), privateKey);
                        cp.getValidatorSignatures().put(localAddress, HexUtils.encode(sig));
                        
                        byte[] payload = mapper.writeValueAsBytes(cp);
                        P2PMessage resp = P2PMessage.create(localAddress, privateKey, P2PMessage.Type.CHECKPOINT, payload);
                        DataOutputStream out = peerConnections.get(msg.getSenderId());
                        if (out != null) sendMessage(out, resp);
                    }
                } else {
                    // This is a signature response or a finalized checkpoint
                    // [HC-043] only for a checkpoint WE requested, same content, verified signatures
                    Checkpoint pending = pendingCheckpoints.get(cp.getBlockHeight());
                    if (pending == null) return;
                    mergeVerifiedSignatures(pending, cp);
                    
                    if (consensus instanceof PBFTConsensus) {
                        PBFTConsensus pbft = (PBFTConsensus) consensus;
                        int quorum = pbft.quorumSize();   // [HC-002] same bound as commit
                        if (pending.getValidatorSignatures().size() >= quorum) {
                            blockchain.getStorage().saveCheckpoint(pending);
                            log.info("[CHECKPOINT] Finalized checkpoint at height {} with quorum", pending.getBlockHeight());
                            // Optional: broadcast final checkpoint once
                            pendingCheckpoints.remove(pending.getBlockHeight());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[P2P] Error handling checkpoint: {}", e.getMessage());
            }
        });

        gossipEngine.registerHandler(P2PMessage.Type.FL_MODEL_BROADCAST, msg -> {
            try {
                String raw = new String(msg.getPayload(), StandardCharsets.UTF_8);
                String[] parts = raw.split(":", 3);
                if (parts.length != 3) {
                    log.warn("[FL] Malformed FL_MODEL_BROADCAST payload");
                    return;
                }

                // [HC-051] only validators may distribute models (sender authenticated by HC-044)
                if (consensus == null || !consensus.isValidator(msg.getSenderId())) {
                    log.warn("[FL] Ignoring model broadcast from non-validator {}", msg.getSenderId());
                    return;
                }
                int round = Integer.parseInt(parts[0]);
                String modelHash = parts[1].trim();
                double[] weights = bytesToWeights(Base64.getDecoder().decode(parts[2]));

                int currentRound = com.hybrid.blockchain.ai.FederatedLearningManager.getInstance().getRoundNumber();
                if (round < currentRound) {
                    log.debug("[FL] Ignoring stale model round={} currentRound={}", round, currentRound);
                    return;
                }

                com.hybrid.blockchain.ai.FederatedLearningManager.getInstance()
                        .applyCommittedModel(modelHash, weights, round, 0, blockchain.getStorage());

                if (weights.length >= 2) {
                    double phi1 = Math.max(0.0, Math.min(0.99, weights[0]));
                    double theta1 = Math.max(0.0, Math.min(0.99, weights[1]));
                    // [PHASE-0] FIX-1: Global coefficients removed in favor of per-device stats.
                    // New devices will start with these as defaults if implemented in TelemetryAnomalyDetector.
                    log.info("[FL] Received new model round={} hash={} baseline_phi1={} baseline_theta1={}",
                            round,
                            modelHash.length() > 12 ? modelHash.substring(0, 12) : modelHash,
                            String.format("%.3f", phi1),
                            String.format("%.3f", theta1));
                }
            } catch (Exception e) {
                log.warn("[FL] Error applying FL model broadcast: {}", e.getMessage());
            }
        });
    }

    /**
     * Start the peer synchronization loop.
     * Periodically syncs the blockchain state with peers and broadcasts peer lists.
     */
    public void startPeerSync() {
        if (peerSyncTask != null && !peerSyncTask.isCancelled()) {
            return;
        }

        final java.util.concurrent.atomic.AtomicInteger noProgressCount = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicLong lastSyncHeight =
                new java.util.concurrent.atomic.AtomicLong(blockchain.getLatestBlock().getIndex());

        peerSyncTask = peerSyncScheduler.scheduleWithFixedDelay(() -> {
            try {
                boolean progress = syncBlocksWithPeers();

                long currentHeight = blockchain.getLatestBlock().getIndex();
                if (currentHeight == lastSyncHeight.get() && !progress) {
                    int idleRounds = noProgressCount.incrementAndGet();
                    if (idleRounds >= 3) {
                        log.warn("[SYNC] No progress in 3 rounds.");
                        noProgressCount.set(0);
                    }
                } else {
                    noProgressCount.set(0);
                    lastSyncHeight.set(currentHeight);
                }

                broadcastPeerList();
            } catch (Throwable t) {   // a throwing periodic task is cancelled silently
                log.warn("[SYNC] Error during sync: {}", t.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Attempt to synchronize blocks with connected peers.
     * Requests missing blocks starting from local tip height.
     * 
     * @return true if progress was made, false otherwise
     */
    private boolean syncBlocksWithPeers() {
        try {
            long localHeight = blockchain.getLatestBlock().getIndex();
            Collection<PeerManager.PeerInfo> peers = peerManager.getAllPeers();
            
            if (peers.isEmpty()) {
                return false; // No peers to sync with
            }
            
            // Find peer with highest block height
            long maxHeight = localHeight;
            PeerManager.PeerInfo bestPeer = null;
            for (PeerManager.PeerInfo peer : peers) {
                if (peer.getBlockHeight() > maxHeight) {
                    maxHeight = peer.getBlockHeight();
                    bestPeer = peer;
                }
            }
            
            // If behind, request blocks
            if (bestPeer != null && localHeight < maxHeight) {
                return requestBlocksFromPeer(bestPeer, localHeight + 1, maxHeight);
            }
            
            return false;
        } catch (Exception e) {
            log.warn("[SYNC] Error syncing blocks: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Request missing blocks from a peer.
     * 
     * @param peer the peer to request from
     * @param fromHeight the starting block height (inclusive)
     * @param toHeight the ending block height (inclusive)
     * @return true if blocks were received, false otherwise
     */
    private boolean requestBlocksFromPeer(PeerManager.PeerInfo peer, long fromHeight, long toHeight) {
        try {
            DataOutputStream out = peerConnections.get(peer.getId());
            if (out == null) {
                log.debug("[SYNC] No connection to peer {}, skipping block request", peer.getId());
                return false;
            }
            
            Map<String, Object> data = new HashMap<>();
            data.put("fromHeight", fromHeight);
            data.put("toHeight", Math.min(toHeight, fromHeight + 49)); // Request max 50 blocks at a time
            
            String payloadJson = new ObjectMapper().writeValueAsString(data);
            byte[] payload = payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            
            // Create and send REQUEST_BLOCKS message
            P2PMessage msg = P2PMessage.create(localAddress, privateKey, 
                    P2PMessage.Type.REQUEST_BLOCKS, payload);
            
            sendMessage(out, msg);
            log.debug("[SYNC] Requested blocks {} to {} from peer {}", fromHeight, data.get("toHeight"), peer.getId());
            
            return true;
        } catch (Exception e) {
            log.warn("[SYNC] Error requesting blocks from peer: {}", e.getMessage());
            return false;
        }
    }

    public void broadcastPeerList() {  // public: also called by App.java (com.hybrid) seed broadcaster
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (PeerManager.PeerInfo p : peerManager.getTopPeers(10)) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", p.getId());
                map.put("address", p.getAddress());
                map.put("port", p.getPort());
                list.add(map);
            }
            byte[] payload = new ObjectMapper().writeValueAsBytes(list);
            P2PMessage msg = P2PMessage.create(localAddress, privateKey, P2PMessage.Type.PEER_DISCOVERY, payload);
            gossipEngine.onMessageReceived(msg, localAddress);
        } catch (Exception e) {
            log.warn("[P2P] Error broadcasting peer list: {}", e.getMessage());
        }
    }

    public void start() throws IOException {
        SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
        serverSocket = ssf.createServerSocket(this.port);
        ((SSLServerSocket) serverSocket).setNeedClientAuth(true); // Force mTLS

        log.info("Secure P2P Node (mTLS) started on port {} ID: {}", port, Crypto.deriveAddress(localPubKey));
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket client = serverSocket.accept();
                    String remoteIp = client.getInetAddress().getHostAddress();
                    
                    // Enforce global peer limit
                    if (peerConnections.size() >= Config.MAX_PEERS) {
                        log.warn("[P2P] Rejecting connection from {}: global peer limit reached", remoteIp);
                        client.close();
                        continue;
                    }

                    // Enforce per-IP limit
                    int count = ipConnectionCounts.getOrDefault(remoteIp, 0);
                    if (count >= MAX_CONN_PER_IP) {
                        log.warn("[P2P] Rejecting connection from {}: per-IP limit reached", remoteIp);
                        client.close();
                        continue;
                    }

                    ipConnectionCounts.put(remoteIp, count + 1);
                    handleConnection(client, true);
                } catch (SocketException e) {
                    break;
                } catch (IOException e) {
                    log.error("[P2P] Error accepting connection", e);
                }
            }
        });
    }

    private void handleConnection(Socket socket, boolean isInbound) {
        executor.submit(() -> {
            DataOutputStream out = null;
            String remotePeerId = null;
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());

                byte[] localNonce = new byte[32];
                new SecureRandom().nextBytes(localNonce);

                if (peerConnections.size() >= Config.MAX_PEERS) {
                    log.warn("[P2P] Rejecting connection: MAX_PEERS ({}) reached.", Config.MAX_PEERS);
                    socket.close();
                    return;
                }

                // [HC-012] Bound the unauthenticated phase: at most MAX_PENDING_HANDSHAKES at once,
                // each must complete within HANDSHAKE_TIMEOUT_MS. Before, there was no timeout at
                // all, so a peer that opened a socket and sent nothing held a thread forever, and
                // half-open connections were not counted against any limit.
                if (pendingHandshakes.incrementAndGet() > MAX_PENDING_HANDSHAKES) {
                    pendingHandshakes.decrementAndGet();
                    log.warn("[P2P] Rejecting connection: {} handshakes already pending", MAX_PENDING_HANDSHAKES);
                    socket.close();
                    return;
                }
                try {
                    socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    remotePeerId = !isInbound ? performOutboundHandshake(in, out, localNonce)
                            : performInboundHandshake(in, out, localNonce);
                } finally {
                    pendingHandshakes.decrementAndGet();
                }
                socket.setSoTimeout(0);   // authenticated peers may legitimately stay quiet

                if (peerManager.isBanned(socket.getInetAddress().getHostAddress())) {
                    log.warn("[P2P] Rejecting banned peer {}", remotePeerId);
                    socket.close();
                    return;
                }

                // [HC-011] The handshake proves the peer holds the key for remotePeerId; under PBFT
                // only validators may join the validator network. Before, any key holder was admitted
                // (Sybil identities filling the peer table, eclipse).
                if (!isAuthorisedPeer(remotePeerId)) {
                    log.warn("[P2P] Rejecting {}: not in the validator set", remotePeerId);
                    socket.close();
                    remotePeerId = null;   // nothing registered yet; skip cleanup in finally
                    return;
                }

                peerConnections.put(remotePeerId, out);
                log.info("[P2P] Secure session established with {} ID: {} (Local: {})", 
                        socket.getInetAddress(), remotePeerId, localDID);
                
                peerManager.addPeer(remotePeerId, socket.getInetAddress().getHostAddress(), socket.getPort());

                ObjectMapper mapper = new ObjectMapper();
                while (true) {
                    int payloadLen = in.readInt();
                    if (payloadLen < 0 || payloadLen > 10 * 1024 * 1024)
                        throw new IOException("Payload too large");

                    byte[] jsonBytes = new byte[payloadLen];
                    in.readFully(jsonBytes);
                    
                    P2PMessage msg = mapper.readValue(jsonBytes, P2PMessage.class);
                    gossipEngine.onMessageReceived(msg, remotePeerId);
                }
            } catch (Exception e) {
                if (Config.isDebug()) {
                    log.error("[P2P] Connection error from {}: {}", socket.getInetAddress(), e.getMessage());
                }
            } finally {
                if (remotePeerId != null) {
                    peerConnections.remove(remotePeerId);
                    peerManager.removePeer(remotePeerId);
                }
                String remoteIp = socket.getInetAddress().getHostAddress();
                ipConnectionCounts.computeIfPresent(remoteIp, (ip, c) -> c > 1 ? c - 1 : null);
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        });
    }

    private synchronized void sendMessage(DataOutputStream out, P2PMessage msg) {
        try {
            byte[] json = new ObjectMapper().writeValueAsBytes(msg);
            out.writeInt(json.length);
            out.write(json);
            out.flush();
        } catch (IOException e) {
            // Socket will close and cleanup in handleConnection
        }
    }

    private String performInboundHandshake(DataInputStream in, DataOutputStream out, byte[] localNonce) throws Exception {
        if (in.readInt() != MsgType.HELLO.ordinal())
            throw new IOException("Expected HELLO");
        if (in.readInt() != HELLO_LENGTH)
            throw new IOException("Bad HELLO length");
        if (in.readInt() != PROTOCOL_VERSION || in.readInt() != Config.NETWORK_ID)
            throw new IOException("Mismatch");
        checkRuleFlags(in.readInt());
        byte[] remoteNonce = new byte[32];
        in.readFully(remoteNonce);

        sendHello(out, localNonce);
        sendChallenge(out, localPubKey, Crypto.sign(handshakeDigest(remoteNonce, localAddress, Config.NETWORK_ID), privateKey));

        if (in.readInt() != MsgType.CHALLENGE.ordinal())
            throw new IOException("Expected CHALLENGE");
        int challengeLen = in.readInt();
        // [HC-007] a CHALLENGE is exactly a 33-byte compressed key + a 64-byte signature; reject
        // any other length BEFORE allocating (the peer-chosen length used to size the buffer)
        if (challengeLen != CHALLENGE_LENGTH)
            throw new IOException("Bad CHALLENGE length " + challengeLen);
        byte[] remotePubKey = new byte[33];
        in.readFully(remotePubKey);
        byte[] remoteSig = new byte[challengeLen - 33];
        in.readFully(remoteSig);

        if (!Crypto.verify(handshakeDigest(localNonce, Crypto.deriveAddress(remotePubKey), Config.NETWORK_ID), remoteSig, remotePubKey))
            throw new IOException("Identity verification failed");

        out.writeInt(MsgType.HANDSHAKE_OK.ordinal());
        out.writeInt(0);
        out.flush();
        return Crypto.deriveAddress(remotePubKey);
    }

    private String performOutboundHandshake(DataInputStream in, DataOutputStream out, byte[] localNonce)
            throws Exception {
        sendHello(out, localNonce);

        if (in.readInt() != MsgType.HELLO.ordinal())
            throw new IOException("Expected HELLO");
        if (in.readInt() != HELLO_LENGTH)
            throw new IOException("Bad HELLO length");
        if (in.readInt() != PROTOCOL_VERSION || in.readInt() != Config.NETWORK_ID)
            throw new IOException("Mismatch");
        checkRuleFlags(in.readInt());
        byte[] remoteNonce = new byte[32];
        in.readFully(remoteNonce);

        if (in.readInt() != MsgType.CHALLENGE.ordinal())
            throw new IOException("Expected CHALLENGE");
        int challengeLen = in.readInt();
        // [HC-007] a CHALLENGE is exactly a 33-byte compressed key + a 64-byte signature; reject
        // any other length BEFORE allocating (the peer-chosen length used to size the buffer)
        if (challengeLen != CHALLENGE_LENGTH)
            throw new IOException("Bad CHALLENGE length " + challengeLen);
        byte[] remotePubKey = new byte[33];
        in.readFully(remotePubKey);
        byte[] remoteSig = new byte[challengeLen - 33];
        in.readFully(remoteSig);

        if (!Crypto.verify(handshakeDigest(localNonce, Crypto.deriveAddress(remotePubKey), Config.NETWORK_ID), remoteSig, remotePubKey))
            throw new IOException("Identity verification failed");

        sendChallenge(out, localPubKey, Crypto.sign(handshakeDigest(remoteNonce, localAddress, Config.NETWORK_ID), privateKey));

        if (in.readInt() != MsgType.HANDSHAKE_OK.ordinal())
            throw new IOException("Peer rejected handshake");
        in.readInt();
        return Crypto.deriveAddress(remotePubKey);
    }

    private void sendHello(DataOutputStream out, byte[] nonce) throws IOException {
        out.writeInt(MsgType.HELLO.ordinal());
        out.writeInt(HELLO_LENGTH);
        out.writeInt(PROTOCOL_VERSION);
        out.writeInt(Config.NETWORK_ID);
        out.writeInt(ruleFlags());   // [HC-008]
        out.write(nonce);
        out.flush();
    }

    private void sendChallenge(DataOutputStream out, byte[] pubKey, byte[] sig) throws IOException {
        out.writeInt(MsgType.CHALLENGE.ordinal());
        out.writeInt(pubKey.length + sig.length);
        out.write(pubKey);
        out.write(sig);
        out.flush();
    }

    private void handleConsensusMessage(P2PMessage msg) {
        try {
            if (consensus instanceof PBFTConsensus) {
                PBFTConsensus pbft = (PBFTConsensus) consensus;
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> data = mapper.readValue(msg.getPayload(), Map.class);
                
                String valId = (String) data.get("validatorId");
                byte[] sig = HexUtils.decode((String) data.get("signature"));
                String msgSubtype = (String) data.get("subtype");
                long view = pbft.getViewNumber();

                if ("PREPARE".equals(msgSubtype)) {
                    long seq = ((Number) data.get("sequenceNumber")).longValue();
                    String hash = (String) data.get("blockHash");
                    pbft.addPrepareVote(seq, hash, valId, sig);
                    if (pbft.hasQuorum(seq, PBFTConsensus.Phase.PREPARE)) {
                        sendCommit(pbft, seq, hash, view);
                    }
                } else if ("COMMIT".equals(msgSubtype)) {
                    long seq = ((Number) data.get("sequenceNumber")).longValue();
                    String hash = (String) data.get("blockHash");
                    pbft.addCommitVote(seq, hash, valId, sig);
                    if (pbft.hasQuorum(seq, PBFTConsensus.Phase.COMMIT)) {
                        applyBlockAtSequence(pbft, seq);
                    }
                } else if ("VIEW_CHANGE".equals(msgSubtype)) {
                    long newView = ((Number) data.get("newView")).longValue();
                    long lastSeq = ((Number) data.get("lastSeq")).longValue();
                    pbft.addViewChangeVote(newView, lastSeq, valId, sig);
                }
            }
        } catch (Exception e) {
            log.error("[P2P] Error processing consensus message: {}", e.getMessage());
        }
    }

    private void sendCommit(PBFTConsensus pbft, long seq, String hash, long view) {
        if (!pbft.isValidator(localAddress)) return;
        if (sentCommits.computeIfAbsent(seq, k -> new AtomicBoolean(false)).compareAndSet(false, true)) {
            PBFTConsensus.PBFTMessage commitMsg = new PBFTConsensus.PBFTMessage(
                    PBFTConsensus.Phase.COMMIT, view, seq, hash, localAddress);
            commitMsg.sign(privateKey);
            pbft.addCommitVote(seq, hash, localAddress, commitMsg.signature);
            pbft.getMessenger().broadcastCommit(seq, hash, localAddress, commitMsg.signature);
        }
    }

    private void applyBlockAtSequence(PBFTConsensus pbft, long seq) {
        if (appliedSequences.computeIfAbsent(seq, k -> new AtomicBoolean(false)).compareAndSet(false, true)) {
            Block block = pbft.removePendingBlock(seq);
            if (block != null) {
                try {
                    // [S7-01] The commit certificate must be for THIS block. hasQuorum()
                    // tells us *a* quorum exists; it does not tell us it was for the block
                    // sitting in pendingBlocks[seq]. Without this check a node could apply a
                    // block that no quorum ever voted for.
                    String quorumHash = pbft.getQuorumHash(seq, PBFTConsensus.Phase.COMMIT);
                    if (quorumHash != null && !quorumHash.equals(block.getHash())) {
                        log.error("[CONSENSUS] REJECTED block {} at seq {}: commit quorum was for {}",
                                block.getHash(), seq, quorumHash);
                        appliedSequences.get(seq).set(false); // allow the correct block later
                        return;
                    }
                    // Bug 1 fix: mark committed BEFORE applyBlock so that
                    // Blockchain.verifyBlock() finds the block in committedBlocks
                    pbft.markCommitted(block.getHash(), seq, block.getValidatorId());
                    blockchain.applyBlock(block);
                    log.info("[CONSENSUS] Applied block {} hash: {}", seq, block.getHash());
                    // Bug 7 fix: prune old entries to prevent unbounded memory growth
                    long cutoff = seq - 100;
                    if (cutoff > 0) {
                        appliedSequences.entrySet().removeIf(e -> e.getKey() < cutoff);
                        sentCommits.entrySet().removeIf(e -> e.getKey() < cutoff);
                    }
                } catch (Exception e) {
                    log.error("[CONSENSUS] Failed to apply block {}: {}", seq, e.getMessage());
                }
            }
        }
    }

    private void broadcastPBFT(String subtype, Map<String, Object> data) {
        try {
            data.put("subtype", subtype);
            byte[] payload = new ObjectMapper().writeValueAsBytes(data);
            P2PMessage msg = P2PMessage.create(localAddress, privateKey, P2PMessage.Type.CONSENSUS, payload);
            gossipEngine.onMessageReceived(msg, localAddress); // Local "injection"
        } catch (Exception e) {
            log.error("Error broadcasting PBFT message: {}", e.getMessage());
        }
    }

    @Override
    public void broadcastPrepare(long seq, String hash, String valId, byte[] sig) {
        Map<String, Object> data = new HashMap<>();
        data.put("sequenceNumber", seq);
        data.put("blockHash", hash);
        data.put("validatorId", valId);
        data.put("signature", HexUtils.encode(sig));
        broadcastPBFT("PREPARE", data);
    }

    @Override
    public void broadcastCommit(long seq, String hash, String valId, byte[] sig) {
        Map<String, Object> data = new HashMap<>();
        data.put("sequenceNumber", seq);
        data.put("blockHash", hash);
        data.put("validatorId", valId);
        data.put("signature", HexUtils.encode(sig));
        broadcastPBFT("COMMIT", data);
    }

    @Override
    public void broadcastViewChange(long newView, long lastSeq, String valId, byte[] sig) {
        Map<String, Object> data = new HashMap<>();
        data.put("newView", newView);
        data.put("lastSeq", lastSeq);
        data.put("validatorId", valId);
        data.put("signature", HexUtils.encode(sig));
        broadcastPBFT("VIEW_CHANGE", data);
    }

    @Override
    public void broadcastNewView(long newView, List<PBFTConsensus.PBFTMessage> proofs, List<Block> recoveredBlocks, String valId) {
        Map<String, Object> data = new HashMap<>();
        data.put("newView", newView);
        data.put("validatorId", valId);
        // In a production system, we'd serialize the proofs and blocks
        data.put("proofCount", proofs.size());
        broadcastPBFT("NEW_VIEW", data);
    }

    public void broadcastTransaction(Transaction tx) {
        byte[] payload = blockchain.serializeTransaction(tx);
        P2PMessage msg = P2PMessage.create(localAddress, privateKey, P2PMessage.Type.TRANSACTION, payload);
        gossipEngine.onMessageReceived(msg, localAddress);
    }

    public void broadcastFLModel(int round, String modelHash, double[] weights) {
        if (weights == null || weights.length == 0 || modelHash == null || modelHash.isBlank()) {
            return;
        }

        try {
            byte[] payloadBytes = weightsToBytes(weights);
            String encodedWeights = Base64.getEncoder().encodeToString(payloadBytes);
            String payload = round + ":" + modelHash + ":" + encodedWeights;
            P2PMessage msg = P2PMessage.create(localAddress, privateKey,
                    P2PMessage.Type.FL_MODEL_BROADCAST,
                    payload.getBytes(StandardCharsets.UTF_8));
            gossipEngine.onMessageReceived(msg, localAddress);
            log.info("[FL] Broadcast model round={} hash={}", round,
                    modelHash.length() > 12 ? modelHash.substring(0, 12) : modelHash);
        } catch (Exception e) {
            log.warn("[FL] Failed to broadcast model: {}", e.getMessage());
        }
    }

    private static byte[] weightsToBytes(double[] weights) {
        ByteBuffer buffer = ByteBuffer.allocate(weights.length * Double.BYTES);
        for (double weight : weights) {
            buffer.putDouble(weight);
        }
        return buffer.array();
    }

    private static double[] bytesToWeights(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        double[] weights = new double[bytes.length / Double.BYTES];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = buffer.getDouble();
        }
        return weights;
    }

    /**
     * Broadcast a block to all connected peers via gossip.
     * 
     * @param block the block to broadcast
     */
    public void broadcastBlock(Block block) {
        byte[] payload = blockchain.serializeBlock(block);
        P2PMessage msg = P2PMessage.create(localAddress, privateKey, P2PMessage.Type.BLOCK, payload);
        gossipEngine.onMessageReceived(msg, localAddress);
    }

    public void broadcastCheckpointRequest(Checkpoint cp) {
        try {
            pendingCheckpoints.put(cp.getBlockHeight(), cp);
            // [HC-043] count our own signature (the local "response" had no connection to go to)
            if (consensus instanceof PBFTConsensus && consensus.isValidator(localAddress) && matchesLocalChain(cp)) {
                cp.getValidatorSignatures().put(localAddress,
                        HexUtils.encode(Crypto.sign(HexUtils.decode(cp.computeCheckpointHash()), privateKey)));
            }
            // the REQUEST carries no signatures (an empty map is what marks it as a request)
            Checkpoint request = new Checkpoint(cp.getBlockHeight(), cp.getBlockHash(), cp.getStateRoot(),
                    cp.getUtxoRoot(), cp.getTimestamp(), new HashMap<>());
            byte[] payload = new ObjectMapper().writeValueAsBytes(request);
            P2PMessage msg = P2PMessage.create(localAddress, privateKey, P2PMessage.Type.CHECKPOINT, payload);
            gossipEngine.onMessageReceived(msg, localAddress);
        } catch (Exception e) {
            log.error("[P2P] Failed to broadcast checkpoint request", e);
        }
    }

    /**
     * Disconnect a specific peer by ID.
     * Used for administrative peer management.
     * 
     * @param peerId the ID of the peer to disconnect
     */
    public void disconnectPeer(String peerId) {
        DataOutputStream out = peerConnections.remove(peerId);
        if (out != null) {
            try {
                out.close();
                log.info("[P2P] Disconnected peer: {}", peerId);
            } catch (IOException e) {
                log.warn("[P2P] Error closing connection to peer {}: {}", peerId, e.getMessage());
            }
        }
        peerManager.removePeer(peerId);
    }

    /**
     * Gracefully stop the P2P node, closing all peer connections and shutting down the executor.
     * 
     * @throws IOException if an I/O error occurs
     */
    public void stop() throws IOException {
        log.info("[P2P] Stopping peer node...");
        
        // Close server socket to stop accepting new connections
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
            log.debug("[P2P] Server socket closed");
        }
        
        // Close all peer connections
        for (DataOutputStream out : peerConnections.values()) {
            try {
                out.close();
            } catch (IOException ignored) {}
        }
        peerConnections.clear();
        log.debug("[P2P] All peer connections closed");
        
        // Shut down executor
        if (peerSyncTask != null) {
            peerSyncTask.cancel(true);
        }
        peerSyncScheduler.shutdownNow();
        executor.shutdownNow();
        if (consensus != null) {
            consensus.shutdown();
        }
        log.debug("[P2P] Executor and consensus shut down");
    }

    /**
     * Shutdown method for backward compatibility. Delegates to stop().
     * 
     * @throws IOException if an I/O error occurs
     */
    public void shutdown() throws IOException {
        stop();
        blockchain.shutdown();
    }

    /**
     * Connect to a peer via mTLS and initiate a peer connection.
     * 
     * @param host the peer's hostname or IP address
     * @param port the peer's P2P port
     */
    public void connectToPeer(String host, int port) {
        if (peerConnections.size() >= Config.MAX_PEERS) {
            log.debug("[P2P] Skip connecting to {}:{}: MAX_PEERS reached.", host, port);
            return;
        }
        executor.submit(() -> {
            try {
                SSLSocketFactory sf = sslContext.getSocketFactory();
                SSLSocket socket = (SSLSocket) sf.createSocket(host, port);
                socket.startHandshake();
                
                handleConnection(socket, false);
                log.info("[P2P] Connected to peer via mTLS: {}:{}", host, port);
            } catch (IOException e) {
                log.warn("[P2P] mTLS connection failed to {}:{} - {}", host, port, e.getMessage());
            }
        });
    }

    public Collection<PeerManager.PeerInfo> getPeers() {
        return peerManager.getAllPeers();
    }

    public String getLocalAddress() {
        return localAddress;
    }

    public Blockchain getBlockchain() {
        return blockchain;
    }

    public PeerManager getPeerManager() {
        return peerManager;
    }

    public GossipEngine getGossipEngine() {
        return gossipEngine;
    }
}
