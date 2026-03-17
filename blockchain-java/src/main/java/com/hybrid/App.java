// HybridChain — App.java — Marc Amgad
package com.hybrid;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.api.IoTRestAPI;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.security.SSLUtils;
import com.hybrid.blockchain.security.CertificateAuthority;
import org.springframework.boot.SpringApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Main entry point for the HybridChain Node.
 * Initializes storage, blockchain, consensus, and networking.
 * 
 * This class orchestrates the startup sequence:
 * 1. Reads configuration and cryptographic keys
 * 2. Initializes the Certificate Authority for mTLS
 * 3. Sets up storage and blockchain state
 * 4. Starts PBFT consensus and P2P networking
 * 5. Begins block proposal and state reconciliation
 */
public class App {
    
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting HybridChain Node...");

        // 1. Read NODE_PRIVATE_KEY
        BigInteger privKey = Config.getNodePrivateKey();
        
        // 2. Derive pubKey and nodeId
        byte[] pubKey = Crypto.derivePublicKey(privKey);
        String nodeId = Crypto.deriveAddress(pubKey);
        log.info("Node ID: {}", nodeId);

        // 3. Parse VALIDATOR_PUBKEYS
        String valPubKeysEnv = System.getenv("VALIDATOR_PUBKEYS");
        Map<String, byte[]> validators = new ConcurrentHashMap<>();
        if (valPubKeysEnv != null && !valPubKeysEnv.isEmpty()) {
            for (String pubHex : valPubKeysEnv.split(",")) {
                byte[] vPub = HexUtils.decode(pubHex.trim());
                validators.put(Crypto.deriveAddress(vPub), vPub);
            }
        }
        log.info("Validators initialized: {}", validators.size());

        // 4. Initialize Certificate Authority for mTLS
        // The CA is deterministically derived from STORAGE_AES_KEY, ensuring consistency across nodes
        CertificateAuthority ca = new CertificateAuthority(Config.STORAGE_AES_KEY);
        X509Certificate caCert = ca.getCACertificate();
        log.info("[mTLS] CA initialized with root certificate CN={}", caCert.getSubjectX500Principal().getName());

        // 5. Generate node's self-signed EC keypair for conversion to node cert
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC", "BC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair nodeKeyPair = keyGen.generateKeyPair();

        // 6. Issue a node certificate from the CA
        X509Certificate nodeCert = ca.issueNodeCertificate(nodeId, nodeKeyPair.getPublic(), (java.security.PrivateKey) nodeKeyPair.getPrivate());
        log.info("[mTLS] Node certificate issued CN={}", nodeCert.getSubjectX500Principal().getName());

        // 7. Construct PBFTConsensus
        PBFTConsensus pbft = new PBFTConsensus(validators, nodeId, privKey);

        // 8. Construct Storage
        Storage storage = new Storage(Config.STORAGE_PATH, Config.STORAGE_AES_KEY);

        // 9. Construct Blockchain
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

        // 10. Call blockchain.init()
        blockchain.init();

        // 11. Construct PeerNode with CA-signed certificate
        PeerNode peerNode = new PeerNode(Config.P2P_PORT, blockchain, pbft, privKey, nodeKeyPair, ca);

        // 12. Call pbft.setMessenger(peerNode)
        pbft.setMessenger(peerNode);

        // 13. Call peerNode.start() and peerNode.startPeerSync()
        peerNode.start();
        peerNode.startPeerSync();

        // 14. Add graceful shutdown hook
        addShutdownHook(new ScheduledExecutorService[]{}, pbft, peerNode, blockchain, storage);

        // 15. Connect to seed
        if (!Config.IS_SEED && Config.SEED_PEER != null) {
            String[] parts = Config.SEED_PEER.split(":");
            if (parts.length == 2) {
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);
                peerNode.connectToPeer(host, port);
            }
        }

        // 16. Start block proposer loop
        ScheduledExecutorService scheduler = startBlockProposer(blockchain, pbft, peerNode, nodeId, privKey);

        // 17. Pass to IoTRestAPI
        IoTRestAPI.setNode(blockchain, peerNode, pbft);

        // 18. Start Spring Boot API

        SpringApplication.run(IoTRestAPI.class, args);
    }

    /**
     * Add graceful shutdown hook to cleanly stop all components on system shutdown.
     * 
     * @param schedulers array of ScheduledExecutorService instances to shut down
     * @param pbft the PBFT consensus instance
     * @param peerNode the P2P node instance
     * @param blockchain the blockchain instance
     * @param storage the storage instance
     */
    private static void addShutdownHook(ScheduledExecutorService[] schedulers, PBFTConsensus pbft, PeerNode peerNode, Blockchain blockchain, Storage storage) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[SHUTDOWN] Graceful shutdown initiated...");
            
            // Stop block proposer scheduler
            for (ScheduledExecutorService scheduler : schedulers) {
                if (scheduler != null && !scheduler.isShutdown()) {
                    scheduler.shutdownNow();
                    log.debug("[SHUTDOWN] Block proposer scheduler shut down");
                }
            }
            
            // Stop PBFT consensus
            if (pbft != null) {
                try {
                    pbft.shutdown();
                    log.debug("[SHUTDOWN] PBFT consensus shut down");
                } catch (Exception ignored) {
                    log.warn("[SHUTDOWN] Error shutting down PBFT: {}", ignored.getMessage());
                }
            }
            
            // Stop peer node
            if (peerNode != null) {
                try {
                    peerNode.stop();
                    log.debug("[SHUTDOWN] P2P node stopped");
                } catch (Exception ignored) {
                    log.warn("[SHUTDOWN] Error stopping P2P node: {}", ignored.getMessage());
                }
            }
            
            // Close blockchain and storage
            if (blockchain != null) {
                try {
                    blockchain.shutdown();
                    log.debug("[SHUTDOWN] Blockchain shut down");
                } catch (Exception ignored) {
                    log.warn("[SHUTDOWN] Error shutting down blockchain: {}", ignored.getMessage());
                }
            }
            
            if (storage != null) {
                try {
                    storage.close();
                    log.debug("[SHUTDOWN] Storage closed");
                } catch (Exception ignored) {
                    log.warn("[SHUTDOWN] Error closing storage: {}", ignored.getMessage());
                }
            }
            
            log.info("[SHUTDOWN] Complete.");
        }, "shutdown-hook"));
    }

    /**
     * Start the block proposer loop that generates new blocks when this node is the leader.
     * 
     * @param blockchain the blockchain instance
     * @param pbft the PBFT consensus instance
     * @param peerNode the P2P node for broadcasting
     * @param nodeId this node's identifier
     * @param privKey this node's private key for signing blocks
     * @return the ScheduledExecutorService managing the proposer loop
     */
    private static ScheduledExecutorService startBlockProposer(Blockchain blockchain, PBFTConsensus pbft, PeerNode peerNode, String nodeId, BigInteger privKey) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (pbft.getCurrentLeader().equals(nodeId)) {
                    // Generate a new block
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
                    
                    log.info("[PROPOSER] Proposed block {} hash: {}", seq, hash);
                }
            } catch (Exception e) {
                log.error("[PROPOSER] Error in block production: {}", e.getMessage(), e);
            }
        }, Config.TARGET_BLOCK_TIME_MS, Config.TARGET_BLOCK_TIME_MS, TimeUnit.MILLISECONDS);
        
        return scheduler;
    }
}

