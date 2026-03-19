package com.hybrid.blockchain;

import java.net.*;
import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
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
    private ServerSocket serverSocket;
    private final java.math.BigInteger privateKey;
    private final byte[] localPubKey;
    private final String localAddress;
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

    /**
     * Message types for peer-to-peer communication.
     */
    public enum MsgType {
        HELLO, CHALLENGE, HANDSHAKE_OK, TRANSACTION, BLOCK, PEER_LIST,
        PBFT_PRE_PREPARE, PBFT_PREPARE, PBFT_COMMIT, PBFT_VIEW_CHANGE, PBFT_NEW_VIEW,
        REQUEST_BLOCKS, BLOCKS_RESPONSE, CHECKPOINT
    }

    private static final int PROTOCOL_VERSION = 1;

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
        this.serverSocket = null;
        this.privateKey = privateKey;
        this.localPubKey = Crypto.derivePublicKey(this.privateKey);
        this.localAddress = Crypto.deriveAddress(localPubKey);
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
        this.serverSocket = null;
        this.privateKey = privateKey;
        this.localPubKey = Crypto.derivePublicKey(this.privateKey);
        this.localAddress = Crypto.deriveAddress(localPubKey);
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
                    int port = (int) p.get("port");
                    if (!id.equals(localAddress)) {
                        peerManager.addPeer(id, addr, port);
                    }
                }
            } catch (Exception e) {
                System.err.println("[P2P] Peer discovery error: " + e.getMessage());
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
                    if (consensus instanceof PBFTConsensus && ((PBFTConsensus) consensus).isValidator(localAddress)) {
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
                    Checkpoint pending = pendingCheckpoints.computeIfAbsent(cp.getBlockHeight(), k -> cp);
                    pending.getValidatorSignatures().putAll(cp.getValidatorSignatures());
                    
                    if (consensus instanceof PBFTConsensus) {
                        PBFTConsensus pbft = (PBFTConsensus) consensus;
                        int quorum = (2 * pbft.getValidators().size() / 3) + 1;
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
    }

    /**
     * Start the peer synchronization loop.
     * Periodically syncs the blockchain state with peers and broadcasts peer lists.
     */
    public void startPeerSync() {
        executor.submit(() -> {
            int noProgressCount = 0;
            long lastSyncHeight = blockchain.getLatestBlock().getIndex();
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30000); // Sync every 30s
                    
                    // Attempt to sync blocks with peers
                    boolean progress = syncBlocksWithPeers();
                    
                    long currentHeight = blockchain.getLatestBlock().getIndex();
                    if (currentHeight == lastSyncHeight && !progress) {
                        noProgressCount++;
                        if (noProgressCount >= 3) {
                            log.warn("[SYNC] No progress in 3 rounds. Pausing sync for 10 seconds.");
                            Thread.sleep(10000);
                            noProgressCount = 0;
                        }
                    } else {
                        noProgressCount = 0;
                        lastSyncHeight = currentHeight;
                    }
                    
                    broadcastPeerList();
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    log.warn("[SYNC] Error during sync: {}", e.getMessage());
                }
            }
        });
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
            byte[] payload = payloadJson.getBytes();
            
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

    private void broadcastPeerList() {
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

                remotePeerId = isInbound ? 
                    performInboundHandshake(in, out, localNonce) : 
                    performOutboundHandshake(in, out, localNonce);

                System.out.println("Secure session established with " + socket.getInetAddress() + " ID: " + remotePeerId);
                
                peerManager.addPeer(remotePeerId, socket.getInetAddress().getHostAddress(), socket.getPort());
                peerConnections.put(remotePeerId, out);

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
                    System.err.println("[P2P] Connection error from " + socket.getInetAddress() + ": " + e.getMessage());
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
        in.readInt(); // discard len
        if (in.readInt() != PROTOCOL_VERSION || in.readInt() != Config.NETWORK_ID)
            throw new IOException("Mismatch");
        byte[] remoteNonce = new byte[32];
        in.readFully(remoteNonce);

        sendHello(out, localNonce);
        sendChallenge(out, localPubKey, Crypto.sign(remoteNonce, privateKey));

        if (in.readInt() != MsgType.CHALLENGE.ordinal())
            throw new IOException("Expected CHALLENGE");
        int challengeLen = in.readInt();
        byte[] remotePubKey = new byte[33];
        in.readFully(remotePubKey);
        byte[] remoteSig = new byte[challengeLen - 33];
        in.readFully(remoteSig);

        if (!Crypto.verify(localNonce, remoteSig, remotePubKey))
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
        in.readInt();
        if (in.readInt() != PROTOCOL_VERSION || in.readInt() != Config.NETWORK_ID)
            throw new IOException("Mismatch");
        byte[] remoteNonce = new byte[32];
        in.readFully(remoteNonce);

        if (in.readInt() != MsgType.CHALLENGE.ordinal())
            throw new IOException("Expected CHALLENGE");
        int challengeLen = in.readInt();
        byte[] remotePubKey = new byte[33];
        in.readFully(remotePubKey);
        byte[] remoteSig = new byte[challengeLen - 33];
        in.readFully(remoteSig);

        if (!Crypto.verify(localNonce, remoteSig, remotePubKey))
            throw new IOException("Identity verification failed");

        sendChallenge(out, localPubKey, Crypto.sign(remoteNonce, privateKey));

        if (in.readInt() != MsgType.HANDSHAKE_OK.ordinal())
            throw new IOException("Peer rejected handshake");
        in.readInt();
        return Crypto.deriveAddress(remotePubKey);
    }

    private void sendHello(DataOutputStream out, byte[] nonce) throws IOException {
        out.writeInt(MsgType.HELLO.ordinal());
        out.writeInt(40);
        out.writeInt(PROTOCOL_VERSION);
        out.writeInt(Config.NETWORK_ID);
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
                long seq = ((Number) data.get("sequenceNumber")).longValue();
                String hash = (String) data.get("blockHash");
                long view = pbft.getViewNumber();

                if ("PREPARE".equals(msgSubtype)) {
                    pbft.addPrepareVote(seq, hash, valId, sig);
                    if (pbft.hasQuorum(seq, PBFTConsensus.Phase.PREPARE)) {
                        sendCommit(pbft, seq, hash, view);
                    }
                } else if ("COMMIT".equals(msgSubtype)) {
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
            System.err.println("[P2P] Error processing consensus message: " + e.getMessage());
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
                    blockchain.applyBlock(block);
                    System.out.println("[CONSENSUS] Applied block " + seq + " hash: " + block.getHash());
                } catch (Exception e) {
                    System.err.println("[CONSENSUS] Failed to apply block " + seq + ": " + e.getMessage());
                    // Allow retry on error if needed, but AtomicBoolean prevents it currently.
                    // For now, failure to apply is fatal for this block sequence.
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
            System.err.println("Error broadcasting PBFT message: " + e.getMessage());
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
            byte[] payload = new ObjectMapper().writeValueAsBytes(cp);
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
