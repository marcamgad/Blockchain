package com.hybrid.blockchain;

import java.net.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public class PeerNode {
    private final int port;
    private final Set<String> peers;
    private final ExecutorService executor;
    private ServerSocket serverSocket;
    private final java.math.BigInteger privateKey;
    private final byte[] localPubKey;

    public enum MsgType {
        HELLO, CHALLENGE, HANDSHAKE_OK, TRANSACTION, BLOCK, PEER_LIST
    }

    private static final int PROTOCOL_VERSION = 1;

    public PeerNode(int port) {
        this.port = port;
        this.peers = ConcurrentHashMap.newKeySet();
        this.executor = Executors.newCachedThreadPool();
        this.serverSocket = null;
        this.privateKey = Config.getNodePrivateKey();
        this.localPubKey = Crypto.derivePublicKey(this.privateKey);
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(this.port);
        System.out.println("P2P Node started on port " + port + " ID: " + Crypto.deriveAddress(localPubKey));
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket client = serverSocket.accept();
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
            try (DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

                byte[] localNonce = new byte[32];
                new SecureRandom().nextBytes(localNonce);

                if (isInbound)
                    performInboundHandshake(in, out, localNonce);
                else
                    performOutboundHandshake(in, out, localNonce);

                System.out.println("Secure session established with " + socket.getInetAddress());

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
                // Silently drop in paranoid mode
            } finally {
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
        // Core node logic
    }

    public void shutdown() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed())
            serverSocket.close();
        executor.shutdown();
    }

    public void connectToPeer(String host, int port) {
        executor.submit(() -> {
            try {
                Socket socket = new Socket(host, port);
                handleConnection(socket, false);
                peers.add(host + ":" + port);
            } catch (IOException e) {
            }
        });
    }

    public Set<String> getPeers() {
        return this.peers;
    }
}
