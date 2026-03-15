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
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;

public class PeerNode implements PBFTConsensus.PBFTMessenger {
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

    public enum MsgType {
        HELLO, CHALLENGE, HANDSHAKE_OK, TRANSACTION, BLOCK, PEER_LIST,
        PBFT_PRE_PREPARE, PBFT_PREPARE, PBFT_COMMIT, PBFT_VIEW_CHANGE, PBFT_NEW_VIEW
    }

    private static final int PROTOCOL_VERSION = 1;

    public PeerNode(int port, Blockchain blockchain, Consensus consensus) {
        this(port, blockchain, consensus, Config.getNodePrivateKey(), new ArrayList<>());
    }

    public PeerNode(int port, Blockchain blockchain, Consensus consensus, BigInteger privateKey) {
        this(port, blockchain, consensus, privateKey, new ArrayList<>());
    }

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
            X9ECParameters ecParams = CustomNamedCurves.getByName("secp256k1");
            ECParameterSpec spec = new ECParameterSpec(ecParams.getCurve(), ecParams.getG(), ecParams.getN(), ecParams.getH());
            
            KeyFactory kf = KeyFactory.getInstance("EC", "BC");
            PrivateKey ecPrivateKey = kf.generatePrivate(new ECPrivateKeySpec(privateKey, spec));
            PublicKey ecPublicKey = kf.generatePublic(new ECPublicKeySpec(ecParams.getG().multiply(privateKey), spec));
            
            KeyPair tlsKeyPair = new KeyPair(ecPublicKey, ecPrivateKey);
            this.sslContext = com.hybrid.blockchain.security.SSLUtils.createSSLContext(tlsKeyPair, localAddress, trustedCerts);
        } catch (Exception e) {
            System.err.println("[P2P] Failed to initialize SSLContext: " + e.getMessage());
            if (Config.DEBUG) e.printStackTrace();
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
                System.out.println("[P2P] Indexed transaction via gossip: " + tx.getId());
            } catch (Exception e) {
                System.err.println("[P2P] Failed to process gossiped transaction: " + e.getMessage());
                peerManager.updatePeerScore(msg.getSenderId(), -1.0);
            }
        });

        gossipEngine.registerHandler(P2PMessage.Type.BLOCK, msg -> {
            try {
                Block block = blockchain.deserializeBlock(msg.getPayload());
                if (consensus instanceof PBFTConsensus) {
                    PBFTConsensus pbft = (PBFTConsensus) consensus;
                    if (pbft.isValidator(localAddress)) {
                        // Validate basic structure
                        if (block.getHash() == null || block.getValidatorId() == null || block.getSignature() == null) return;
                        if (!block.getPrevHash().equals(blockchain.getLatestBlock().getHash())) return;

                        // Verify leader signature
                        String leader = pbft.getCurrentLeader();
                        if (!block.getValidatorId().equals(leader)) return;
                        
                        byte[] leaderPubKey = pbft.getValidators().stream()
                                .filter(v -> v.getId().equals(leader))
                                .findFirst().map(Validator::getPublicKey).orElse(null);
                        
                        if (leaderPubKey == null || !Crypto.verify(Crypto.hash(block.serializeCanonical()), block.getSignature(), leaderPubKey)) {
                            return;
                        }

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
            } catch (Exception e) {
                peerManager.updatePeerScore(msg.getSenderId(), -1.0);
            }
        });

        gossipEngine.registerHandler(P2PMessage.Type.CONSENSUS, msg -> {
            handleConsensusMessage(msg);
        });

        gossipEngine.registerHandler(P2PMessage.Type.PEER_DISCOVERY, msg -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
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
    }

    public void startPeerSync() {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30000); // Sync every 30s
                    broadcastPeerList();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
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
            System.err.println("[P2P] Error broadcasting peer list: " + e.getMessage());
        }
    }

    public void start() throws IOException {
        SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
        serverSocket = ssf.createServerSocket(this.port);
        ((SSLServerSocket) serverSocket).setNeedClientAuth(true); // Force mTLS

        System.out.println("Secure P2P Node (mTLS) started on port " + port + " ID: " + Crypto.deriveAddress(localPubKey));
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket client = serverSocket.accept();
                    String remoteIp = client.getInetAddress().getHostAddress();
                    
                    // Enforce global peer limit
                    if (peerConnections.size() >= Config.MAX_PEERS) {
                        System.err.println("[P2P] Rejecting connection from " + remoteIp + ": global peer limit reached");
                        client.close();
                        continue;
                    }

                    // Enforce per-IP limit
                    int count = ipConnectionCounts.getOrDefault(remoteIp, 0);
                    if (count >= MAX_CONN_PER_IP) {
                        System.err.println("[P2P] Rejecting connection from " + remoteIp + ": per-IP limit reached");
                        client.close();
                        continue;
                    }

                    ipConnectionCounts.put(remoteIp, count + 1);
                    handleConnection(client, true);
                } catch (SocketException e) {
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
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
                if (Config.DEBUG) {
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

    public void broadcastBlock(Block block) {
        byte[] payload = blockchain.serializeBlock(block);
        P2PMessage msg = P2PMessage.create(localAddress, privateKey, P2PMessage.Type.BLOCK, payload);
        gossipEngine.onMessageReceived(msg, localAddress);
    }

    public void shutdown() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed())
            serverSocket.close();
        blockchain.shutdown();
        executor.shutdown();
    }

    public void connectToPeer(String host, int port) {
        executor.submit(() -> {
            try {
                SSLSocketFactory sf = sslContext.getSocketFactory();
                SSLSocket socket = (SSLSocket) sf.createSocket(host, port);
                socket.startHandshake();
                
                handleConnection(socket, false);
                System.out.println("[P2P] Connected to peer via mTLS: " + host + ":" + port);
            } catch (IOException e) {
                System.err.println("[P2P] mTLS connection failed to " + host + ":" + port + " - " + e.getMessage());
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
