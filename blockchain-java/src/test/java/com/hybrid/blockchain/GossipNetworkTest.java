package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.p2p.P2PMessage;
import com.hybrid.blockchain.p2p.PeerManager;
import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

public class GossipNetworkTest {

    private List<PeerNode> nodes;
    private List<java.math.BigInteger> nodeKeys;
    private static final int NODE_COUNT = 20;
    private List<Integer> allocatedPorts;
    private File runDataRoot;

    @BeforeEach
    public void setup() throws Exception {
        // Set mock private key for testing
        System.setProperty("NODE_PRIVATE_KEY", "abc1234567890abcdef1234567890abcdef1234567890abcdef1234567890abc");
        System.setProperty("DEBUG", "true");

        nodes = new ArrayList<>();
        nodeKeys = new ArrayList<>();
        runDataRoot = new File("/tmp/gossip-test-" + UUID.randomUUID());
        runDataRoot.mkdirs();

        // PBFT requires 4 validators
        Map<String, byte[]> validators = new HashMap<>();
        List<String> valIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            byte[] pub = new byte[33];
            new java.security.SecureRandom().nextBytes(pub);
            String id = Crypto.deriveAddress(pub);
            validators.put(id, pub);
            valIds.add(id);
        }

        allocatedPorts = new ArrayList<>();
        for (int i = 0; i < NODE_COUNT; i++) {
            try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
                ss.setReuseAddress(true);
                allocatedPorts.add(ss.getLocalPort());
            }
        }

        for (int i = 0; i < NODE_COUNT; i++) {
            // Generate unique key for each node
            byte[] keyBytes = new byte[32];
            new java.security.SecureRandom().nextBytes(keyBytes);
            java.math.BigInteger privKey = new java.math.BigInteger(1, keyBytes);
            nodeKeys.add(privKey);

            // Use 16-byte key as required by Storage
            byte[] aesKey = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);

            Mempool mempool = new Mempool();
            // Assign a validator ID to the first 4 nodes, dummy for others
            String localId = (i < 4) ? valIds.get(i) : "node-" + i;
            PBFTConsensus consensus = new PBFTConsensus(validators, localId, privKey);

            File nodeDir = new File(runDataRoot, "data-" + i);
            Blockchain bc = new Blockchain(new Storage(nodeDir.getAbsolutePath(), aesKey), mempool, consensus);

            // Pre-fund a shared test account in all nodes for gossip testing
            bc.getAccountState().credit("0xAlice", 10000L);

            PeerNode node = new PeerNode(allocatedPorts.get(i), bc, consensus, privKey);
            node.start();
            nodes.add(node);
        }

        // Connect nodes in a ring topology to ensure a valid graph
        for (int i = 0; i < NODE_COUNT; i++) {
            int next = (i + 1) % NODE_COUNT;
            nodes.get(i).connectToPeer("localhost", allocatedPorts.get(next));
        }

        // Wait for handshakes using polling
        long start = System.currentTimeMillis();
        boolean allConnected = false;
        while (System.currentTimeMillis() - start < 15000) {
            allConnected = true;
            for (PeerNode n : nodes) {
                if (n.getPeers().isEmpty()) {
                    allConnected = false;
                    break;
                }
            }
            if (allConnected)
                break;
            Thread.sleep(500);
        }
        assertTrue(allConnected, "Nodes failed to connect within timeout");
    }

    @AfterEach
    public void teardown() throws Exception {
        if (nodes != null) {
            for (PeerNode node : nodes) {
                try {
                    node.shutdown();
                } catch (Exception e) {
                    System.err.println("Error shutting down node: " + e.getMessage());
                }
            }
        }
        if (runDataRoot != null && runDataRoot.exists()) {
            deleteDirectory(runDataRoot);
        }
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory())
                    deleteDirectory(f);
                else
                    f.delete();
            }
        }
        dir.delete();
    }

    @Test
    public void testTransactionGossip() throws Exception {
        System.out.println("Testing transaction gossip across " + NODE_COUNT + " nodes...");

        TestKeyPair sender = new TestKeyPair(999);
        for (PeerNode n : nodes) {
            n.getBlockchain().getAccountState().credit(sender.getAddress(), 1000L);
        }

        Transaction tx = TestTransactionFactory.createAccountTransfer(sender, "0xBob", 10, 1, 1);
        final String txid = tx.getId();

        // Ensure node 0 has it in mempool too, as it is the sender
        nodes.get(0).getBlockchain().addTransaction(tx);

        // Broadcast
        nodes.get(0).broadcastTransaction(tx);

        System.out.println("Waiting for propagation of transaction " + txid + "...");

        long start = System.currentTimeMillis();
        boolean allFinished = false;
        while (System.currentTimeMillis() - start < 15000) {
            allFinished = true;
            for (int i = 0; i < NODE_COUNT; i++) {
                boolean received = nodes.get(i).getBlockchain().getMempool().contains(txid);
                if (!received) {
                    allFinished = false;
                    break;
                }
            }
            if (allFinished)
                break;
            Thread.sleep(500);
        }

        for (int i = 0; i < NODE_COUNT; i++) {
            boolean hasTx = nodes.get(i).getBlockchain().getMempool().contains(txid);
            assertTrue(hasTx, "Node " + i + " did not receive the transaction");
        }
    }

    @Test
    public void testPeerScoringAndBan() throws Exception {
        PeerNode target = nodes.get(0);
        PeerNode malicious = nodes.get(1);
        String malId = malicious.getLocalAddress();

        // Simulate malcontent relaying invalid signatures
        for (int i = 0; i < 60; i++) {
            target.getPeerManager().updatePeerScore(malId, -1.0);
        }

        // Verify that the peer is removed once score drops below threshold
        assertNull(target.getPeerManager().getPeer(malId), "Malicious peer should have been removed");
        System.out.println("Peer scoring successful: malicious node banned.");
    }
}
