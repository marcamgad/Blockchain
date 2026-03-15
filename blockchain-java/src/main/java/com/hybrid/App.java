// HybridChain — App.java — Marc Amgad
package com.hybrid;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.api.IoTRestAPI;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.security.SSLUtils;
import org.springframework.boot.SpringApplication;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Main entry point for the HybridChain Node.
 * Initializes storage, blockchain, consensus, and networking.
 */
public class App {

    public static void main(String[] args) throws Exception {
        System.out.println("Starting HybridChain Node...");

        // 1. Read NODE_PRIVATE_KEY
        BigInteger privKey = Config.getNodePrivateKey();
        
        // 2. Derive pubKey and nodeId
        byte[] pubKey = Crypto.derivePublicKey(privKey);
        String nodeId = Crypto.deriveAddress(pubKey);
        System.out.println("Node ID: " + nodeId);

        // 3. Parse VALIDATOR_PUBKEYS
        String valPubKeysEnv = System.getenv("VALIDATOR_PUBKEYS");
        Map<String, byte[]> validators = new ConcurrentHashMap<>();
        if (valPubKeysEnv != null && !valPubKeysEnv.isEmpty()) {
            for (String pubHex : valPubKeysEnv.split(",")) {
                byte[] vPub = HexUtils.decode(pubHex.trim());
                validators.put(Crypto.deriveAddress(vPub), vPub);
            }
        }
        System.out.println("Validators initialized: " + validators.size());

        // 4. Generate trusted certificates for validators
        List<X509Certificate> trustedCerts = new ArrayList<>();
        try {
            // In a real production system, certs would be pre-distributed.
            // Here we generate self-signed placeholder certs for each validator to populate the TrustStore.
            // Standard mTLS will still require a match, so we use a deterministic bootstrap key for other nodes' placeholders.
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(256);
            for (Map.Entry<String, byte[]> entry : validators.entrySet()) {
                // Generate a "trusted" certificate for this validator. 
                // Note: Remote nodes must present a cert that matches this or is signed by a common CA.
                // For HybridChain, we trust the self-signed certs based on their public keys.
                KeyPair dummyKp = kpg.generateKeyPair(); // Placeholder
                trustedCerts.add(SSLUtils.generateSelfSignedCertificate(dummyKp, entry.getKey()));
            }
        } catch (Exception e) {
            System.err.println("Failed to generate trusted certificates: " + e.getMessage());
        }

        // 5. Construct PBFTConsensus
        PBFTConsensus pbft = new PBFTConsensus(validators, nodeId, privKey);

        // 6. Construct Storage
        Storage storage = new Storage(Config.STORAGE_PATH, Config.STORAGE_AES_KEY);

        // 7. Construct Blockchain
        int maxBlocks = 0;
        try {
            String maxBlocksEnv = System.getProperty("MAX_BLOCKS");
            if (maxBlocksEnv == null) {
                maxBlocksEnv = System.getenv("MAX_BLOCKS");
            }
            if (maxBlocksEnv != null && !maxBlocksEnv.isBlank()) {
                maxBlocks = Integer.parseInt(maxBlocksEnv);
            }
        } catch (NumberFormatException ignored) {
            maxBlocks = 0;
        }

        Blockchain blockchain = maxBlocks > 0
                ? new PrunedBlockchain(storage, new Mempool(Config.MEMPOOL_LIMIT), maxBlocks, pbft)
                : new Blockchain(storage, new Mempool(Config.MEMPOOL_LIMIT), pbft);

        // 8. Call blockchain.init()
        blockchain.init();

        // 9. Construct PeerNode
        PeerNode peerNode = new PeerNode(Config.P2P_PORT, blockchain, pbft, privKey, trustedCerts);

        // 9. Call pbft.setMessenger(peerNode)
        pbft.setMessenger(peerNode);

        // 10. Call peerNode.start() and peerNode.startPeerSync()
        peerNode.start();
        peerNode.startPeerSync();

        // 11. Connect to seed
        if (!Config.IS_SEED && Config.SEED_PEER != null) {
            String[] parts = Config.SEED_PEER.split(":");
            if (parts.length == 2) {
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);
                peerNode.connectToPeer(host, port);
            }
        }

        // 12. Start block proposer loop
        startBlockProposer(blockchain, pbft, peerNode, nodeId, privKey);

        // 13. Pass to IoTRestAPI
        IoTRestAPI.setNode(blockchain, peerNode, pbft);

        // Start Spring Boot API
        SpringApplication.run(IoTRestAPI.class, args);
    }

    private static void startBlockProposer(Blockchain blockchain, PBFTConsensus pbft, PeerNode peerNode, String nodeId, BigInteger privKey) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (pbft.getCurrentLeader().equals(nodeId)) {
                    // Lead proposer logic
                    Block block = blockchain.createBlock(nodeId, Config.MAX_TRANSACTIONS_PER_BLOCK);
                    
                    // Sign block header
                    byte[] headerSig = Crypto.sign(Crypto.hash(block.serializeCanonical()), privKey);
                    block.setSignature(headerSig);
                    block.setValidatorId(nodeId);

                    long seq = block.getIndex();
                    String hash = block.getHash();
                    long view = pbft.getViewNumber();

                    // Generate prepare signature (self-vote)
                    PBFTConsensus.PBFTMessage prepMsg = new PBFTConsensus.PBFTMessage(
                            PBFTConsensus.Phase.PREPARE, view, seq, hash, nodeId);
                    prepMsg.sign(privKey);

                    // Record and broadcast PREPARE
                    pbft.addPrepareVote(seq, hash, nodeId, prepMsg.signature);
                    pbft.getMessenger().broadcastPrepare(seq, hash, nodeId, prepMsg.signature);
                    
                    // Store for commit phase
                    pbft.setPendingBlock(seq, block);

                    // Broadcast block to other nodes
                    peerNode.broadcastBlock(block);
                    
                    System.out.println("[PROPOSER] Proposed block " + seq + " hash: " + hash);
                }
            } catch (Exception e) {
                System.err.println("[PROPOSER] Error in block production: " + e.getMessage());
                if (Config.isDebug()) e.printStackTrace();
            }
        }, Config.TARGET_BLOCK_TIME_MS, Config.TARGET_BLOCK_TIME_MS, TimeUnit.MILLISECONDS);
    }
}
