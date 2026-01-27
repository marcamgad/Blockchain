package com.hybrid.blockchain;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * A malicious peer implementation for adversarial testing.
 * Designed to inject junk, replay messages, and violate protocol invariants.
 */
public class NetworkHardeningScanner {

    private final String targetHost;
    private final int targetPort;

    public NetworkHardeningScanner(String host, int port) {
        this.targetHost = host;
        this.targetPort = port;
    }

    public void runAttackSuite() {
        System.out.println("Starting Adversarial Attack Suite...");

        testInvalidNetworkId();
        testOversizedPayload();
        testDuplicateSequenceNumber();
        testInvalidHandshakeSignature();
    }

    private void testInvalidNetworkId() {
        try (Socket s = new Socket(targetHost, targetPort);
                DataOutputStream out = new DataOutputStream(s.getOutputStream())) {
            System.out.println("[ATTACK] Sending HELLO with invalid NetworkID...");
            out.writeInt(0); // MsgType.HELLO
            out.writeInt(40);
            out.writeInt(1); // Protocol Version
            out.writeInt(999); // WRONG NETWORK ID
            out.write(new byte[32]);
            out.flush();
            // Node should disconnect us
        } catch (IOException ignored) {
        }
    }

    private void testOversizedPayload() {
        try (Socket s = new Socket(targetHost, targetPort);
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                DataInputStream in = new DataInputStream(s.getInputStream())) {

            System.out.println("[ATTACK] Sending oversized payload (10MB)...");
            // Need to pass handshake first or node might drop before payload check
            // For simplicity in this test, we just send a giant length header
            out.writeInt(5); // MsgType.PEER_LIST or any
            out.writeLong(0); // Seq 0
            out.writeInt(10 * 1024 * 1024); // 10MB
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void testDuplicateSequenceNumber() {
        // This requires a full handshake first, then sending Seq 0 twice
        System.out.println("[ATTACK] Duplicate sequence number attack requires full handshake (Skipping for now).");
    }

    private void testInvalidHandshakeSignature() {
        try (Socket s = new Socket(targetHost, targetPort);
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                DataInputStream in = new DataInputStream(s.getInputStream())) {

            System.out.println("[ATTACK] Sending HELLO with valid ID...");
            out.writeInt(0); // HELLO
            out.writeInt(40);
            out.writeInt(1);
            out.writeInt(Config.NETWORK_ID);
            out.write(new byte[32]);
            out.flush();

            // Receive HELLO back
            in.readInt();
            in.readInt();
            in.readInt();
            in.readInt();
            in.readFully(new byte[32]);

            System.out.println("[ATTACK] Sending CHALLENGE with garbage signature...");
            out.writeInt(1); // CHALLENGE
            out.writeInt(33 + 64);
            out.write(new byte[33]); // Fake pubkey
            out.write(new byte[64]); // Fake signature
            out.flush();
        } catch (Exception ignored) {
        }
    }
}
