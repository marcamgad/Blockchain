package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.p2p.P2PMessage;
import com.hybrid.blockchain.p2p.PeerManager;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.io.File;

public class GossipNetworkTest {

    private List<PeerNode> nodes;
    private List<java.math.BigInteger> nodeKeys;
    private static final int NODE_COUNT = 20;
    private static final int START_PORT = 10000;

    @BeforeEach
    public void setup() throws Exception {
        // Set mock private key for testing
        System.setProperty("NODE_PRIVATE_KEY", "abc1234567890abcdef1234567890abcdef1234567890abcdef1234567890abc");
        Config.DEBUG = true;

        nodes = new ArrayList<>();
        nodeKeys = new ArrayList<>();
        
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
            
            Blockchain bc = new Blockchain(new Storage("/tmp/data-" + i, aesKey), mempool, consensus);
            PeerNode node = new PeerNode(START_PORT + i, bc, consensus, privKey);
            node.start();
            nodes.add(node);
        }

        // Connect nodes in a ring topology to ensure a valid graph
        for (int i = 0; i < NODE_COUNT; i++) {
            int next = (i + 1) % NODE_COUNT;
            nodes.get(i).connectToPeer("localhost", START_PORT + next);
        }
        
        // Wait for handshakes
        Thread.sleep(2000);
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
        // Cleanup data directories
        for (int i = 0; i < NODE_COUNT; i++) {
            File dataDir = new File("/tmp/data-" + i);
            if (dataDir.exists()) {
                deleteDirectory(dataDir);
            }
        }
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    @Test
    public void testTransactionGossip() throws Exception {
        System.out.println("Testing transaction gossip across " + NODE_COUNT + " nodes...");
        
        // Create a transaction and inject it into Node 0
        java.math.BigInteger privKey = Config.getNodePrivateKey();
        byte[] pubKey = Crypto.derivePublicKey(privKey);
        Transaction tx = new Transaction.Builder()
            .from(Crypto.deriveAddress(pubKey))
            .to("0xBob")
            .amount(100)
            .sign(privKey, pubKey);
            
        nodes.get(0).broadcastTransaction(tx);

        // Wait for gossip propagation (should be fast in a ring with fan-out 3)
        Thread.sleep(5000);

        // Verify that all nodes received the transaction in their mempool
        for (int i = 0; i < NODE_COUNT; i++) {
            final String txid = tx.getId();
            boolean received = nodes.get(i).getBlockchain().getMempool().toArray().stream()
                .anyMatch(t -> t.getId().equals(txid));
            assertTrue(received, "Node " + i + " did not receive the transaction");
        }
        System.out.println("Gossip successful: transaction reached all nodes.");
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
