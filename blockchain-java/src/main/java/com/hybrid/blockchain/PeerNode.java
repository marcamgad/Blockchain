package com.hybrid.blockchain;

import java.net.*;
import javax.net.ssl.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.consensus.PBFTConsensus;

public class PeerNode implements PBFTConsensus.PBFTMessenger {
    private final int port;
    private final Set<String> peers;
    private final ExecutorService executor;
    private ServerSocket serverSocket;
    private final java.math.BigInteger privateKey;
    private final byte[] localPubKey;
    private Blockchain blockchain;
    private Consensus consensus;
    private SSLContext sslContext;
    private final List<DataOutputStream> activeConnections = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, Integer> ipConnectionCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_CONN_PER_IP = 3;

    public enum MsgType {
        HELLO, CHALLENGE, HANDSHAKE_OK, TRANSACTION, BLOCK, PEER_LIST,
        PBFT_PRE_PREPARE, PBFT_PREPARE, PBFT_COMMIT, PBFT_VIEW_CHANGE, PBFT_NEW_VIEW
    }

    private static final int PROTOCOL_VERSION = 1;

    public PeerNode(int port, Blockchain blockchain, Consensus consensus) {
        this.port = port;
        this.peers = ConcurrentHashMap.newKeySet();
        this.executor = Executors.newCachedThreadPool();
        this.serverSocket = null;
        this.privateKey = Config.getNodePrivateKey();
        this.localPubKey = Crypto.derivePublicKey(this.privateKey);
        this.blockchain = blockchain;
        this.consensus = consensus;

        try {
            // Generate temporary KeyPair for TLS (EC)
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(256);
            KeyPair tlsKeyPair = kpg.generateKeyPair();
            String identity = Crypto.deriveAddress(localPubKey);
            this.sslContext = com.hybrid.blockchain.security.SSLUtils.createSSLContext(tlsKeyPair, identity);
        } catch (Exception e) {
            System.err.println("[P2P] Failed to initialize SSLContext: " + e.getMessage());
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
                    if (activeConnections.size() >= Config.MAX_PEERS) {
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
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());

                byte[] localNonce = new byte[32];
                new SecureRandom().nextBytes(localNonce);

                if (isInbound)
                    performInboundHandshake(in, out, localNonce);
                else
                    performOutboundHandshake(in, out, localNonce);

                System.out.println("Secure session established with " + socket.getInetAddress());
                
                activeConnections.add(out);

                long nextInboundSeq = 0;
                while (true) {
                    int typeOrdinal = in.readInt();
                    long seq = in.readLong();
                    int payloadLen = in.readInt();

                    if (payloadLen < 0 || payloadLen > 5 * 1024 * 1024)
                        throw new IOException("Payload too large");
                    if (seq != nextInboundSeq++)
                        throw new IOException("Invalid sequence number");

                    byte[] payload = new byte[payloadLen];
                    in.readFully(payload);
                    processMessage(MsgType.values()[typeOrdinal], payload);
                }
            } catch (Exception e) {
                if (Config.DEBUG) {
                    System.err.println("[P2P] Connection error from " + socket.getInetAddress() + ": " + e.getMessage());
                }
            } finally {
                if (out != null) activeConnections.remove(out);
                String remoteIp = socket.getInetAddress().getHostAddress();
                ipConnectionCounts.computeIfPresent(remoteIp, (ip, c) -> c > 1 ? c - 1 : null);
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        });
    }

    private void performInboundHandshake(DataInputStream in, DataOutputStream out, byte[] localNonce) throws Exception {
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
    }

    private void performOutboundHandshake(DataInputStream in, DataOutputStream out, byte[] localNonce)
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

    private void processMessage(MsgType type, byte[] payload) {
        try {
            switch (type) {
                case TRANSACTION:
                    Transaction tx = blockchain.deserializeTransaction(payload);
                    blockchain.addTransaction(tx);
                    break;
                case BLOCK:
                    Block block = blockchain.deserializeBlock(payload);
                    blockchain.applyBlock(block);
                    break;
                case PBFT_PRE_PREPARE:
                    // Pre-prepare is usually the block itself
                    break;
                case PBFT_PREPARE:
                case PBFT_COMMIT:
                case PBFT_VIEW_CHANGE:
                    if (consensus instanceof PBFTConsensus) {
                        PBFTConsensus pbft = (PBFTConsensus) consensus;
                        ObjectMapper mapper = new ObjectMapper();
                        Map<String, Object> data = mapper.readValue(payload, Map.class);
                        
                        String valId = (String) data.get("validatorId");
                        byte[] sig = HexUtils.decode((String) data.get("signature"));
                        
                        if (type == MsgType.PBFT_PREPARE) {
                            long seq = ((Number) data.get("sequenceNumber")).longValue();
                            String hash = (String) data.get("blockHash");
                            pbft.addPrepareVote(seq, hash, valId, sig);
                        } else if (type == MsgType.PBFT_COMMIT) {
                            long seq = ((Number) data.get("sequenceNumber")).longValue();
                            String hash = (String) data.get("blockHash");
                            pbft.addCommitVote(seq, hash, valId, sig);
                        } else if (type == MsgType.PBFT_VIEW_CHANGE) {
                            long newView = ((Number) data.get("newView")).longValue();
                            long lastSeq = ((Number) data.get("lastSeq")).longValue();
                            pbft.addViewChangeVote(newView, lastSeq, valId, sig);
                        }
                    }
                    break;
                case PBFT_NEW_VIEW:
                    // TODO: Implement NEW_VIEW reconciliation
                    System.out.println("[P2P] Received NEW_VIEW message");
                    break;
            }
        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
        }
    }

    public void broadcastPBFT(MsgType type, long seq, String hash, String valId, byte[] sig) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("sequenceNumber", seq);
            data.put("blockHash", hash);
            data.put("validatorId", valId);
            data.put("signature", HexUtils.encode(sig));
            
            byte[] payload = new ObjectMapper().writeValueAsBytes(data);
            broadcast(type, payload);
        } catch (Exception e) {
            System.err.println("Error broadcasting PBFT message: " + e.getMessage());
        }
    }

    @Override
    public void broadcastPrepare(long seq, String hash, String valId, byte[] sig) {
        broadcastPBFT(MsgType.PBFT_PREPARE, seq, hash, valId, sig);
    }

    @Override
    public void broadcastCommit(long seq, String hash, String valId, byte[] sig) {
        broadcastPBFT(MsgType.PBFT_COMMIT, seq, hash, valId, sig);
    }

    @Override
    public void broadcastViewChange(long newView, long lastSeq, String valId, byte[] sig) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("newView", newView);
            data.put("lastSeq", lastSeq);
            data.put("validatorId", valId);
            data.put("signature", HexUtils.encode(sig));
            
            byte[] payload = new ObjectMapper().writeValueAsBytes(data);
            broadcast(MsgType.PBFT_VIEW_CHANGE, payload);
        } catch (Exception e) {
            System.err.println("Error broadcasting VIEW_CHANGE: " + e.getMessage());
        }
    }

    @Override
    public void broadcastNewView(long newView, List<PBFTConsensus.PBFTMessage> proofs, List<Block> recoveredBlocks, String valId) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("newView", newView);
            data.put("validatorId", valId);
            // In a production system, we'd serialize the proofs and recoveredBlocks properly
            data.put("proofCount", proofs.size());
            
            byte[] payload = new ObjectMapper().writeValueAsBytes(data);
            broadcast(MsgType.PBFT_NEW_VIEW, payload);
        } catch (Exception e) {
            System.err.println("Error broadcasting NEW_VIEW: " + e.getMessage());
        }
    }

    public void broadcast(MsgType type, byte[] payload) {
        for (DataOutputStream out : activeConnections) {
            try {
                out.writeInt(type.ordinal());
                out.writeLong(System.currentTimeMillis()); // Simplified seq number
                out.writeInt(payload.length);
                out.write(payload);
                out.flush();
            } catch (Exception e) {
                activeConnections.remove(out);
            }
        }
    }

    public void shutdown() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed())
            serverSocket.close();
        executor.shutdown();
    }

    public void connectToPeer(String host, int port) {
        executor.submit(() -> {
            try {
                SSLSocketFactory sf = sslContext.getSocketFactory();
                SSLSocket socket = (SSLSocket) sf.createSocket(host, port);
                socket.startHandshake(); // Explicitly start TLS handshake
                
                handleConnection(socket, false);
                peers.add(host + ":" + port);
                System.out.println("[P2P] Connected to peer via mTLS: " + host + ":" + port);
            } catch (IOException e) {
                System.err.println("[P2P] mTLS connection failed to " + host + ":" + port + " - " + e.getMessage());
            }
        });
    }

    public Set<String> getPeers() {
        return this.peers;
    }
}
