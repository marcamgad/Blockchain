// HybridChain — App.java — Marc Amgad
package com.hybrid;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.api.IoTRestAPI;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.security.CertificateAuthority;
import org.springframework.boot.SpringApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
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
        // [HC-003] The CA is derived from NETWORK_CA_SEED (same on every node), NOT from the
        // per-node disk-encryption key
        CertificateAuthority ca = new CertificateAuthority(Config.requireNetworkCaSeed());
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

        // [O-5] consensus follows the on-chain jail set; a double vote this node observes is
        // reported on-chain so that every node jails the validator, not only this one
        blockchain.syncJailedValidators();
        // [HC-088] an in-memory ML-DSA key for the node's own transactions (evidence): required by
        // REQUIRE_QUANTUM_SIG, not bound to the hb account (HC-079)
        final java.security.KeyPair nodeMldsa = com.hybrid.blockchain.security.QuantumResistantCrypto.generateDilithiumKeyPair(3);
        pbft.setEvidenceListener(ev -> {
            try {
                Transaction tx = com.hybrid.blockchain.consensus.EvidenceTransactions.build(ev, privKey, nodeMldsa,
                        blockchain.getNonce(nodeId) + 1, Math.max(1, blockchain.getCurrentBaseFee()));
                blockchain.addTransaction(tx);
                peerNode.broadcastTransaction(tx);
                log.warn("[SLASH] Submitted equivocation evidence against {} as tx {}", ev.validatorId, tx.getTxid());
            } catch (Exception e) {
                log.warn("[SLASH] Could not submit evidence against {}: {}", ev.validatorId, e.getMessage());
            }
        });

        // 13. Call peerNode.start() and peerNode.startPeerSync()
        peerNode.start();
        peerNode.startPeerSync();

        // 14. Connect to seed
        if (!Config.IS_SEED && Config.SEED_PEER != null) {
            String[] parts = Config.SEED_PEER.split(":");
            if (parts.length == 2) {
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);
                // [HC-080] retried while this node has no peer (the seed may start after us)
                peerNode.maintainSeedConnection(host, port, 10_000L);
            }
        }

        // 15. Start block proposer loop
        ScheduledExecutorService scheduler = startBlockProposer(blockchain, pbft, peerNode, nodeId, privKey);

        // TASK 2 — Seed node broadcasts its peer list every 30 s
        // so newly joined validators can discover each other.
        // [HC-084] EVERY node (not only the seed) announces itself and its peers, and dials the peers
        // it discovers, so the network becomes a mesh and survives the loss of the seed
        ScheduledExecutorService seedScheduler = null;
        peerNode.maintainMesh(30_000L);
        {
            seedScheduler = Executors.newSingleThreadScheduledExecutor();
            final ScheduledExecutorService ss = seedScheduler;
            ss.scheduleAtFixedRate(() -> {
                try {
                    peerNode.broadcastPeerList();
                    log.debug("[SEED] Broadcast peer list ({} known peers)", peerNode.getPeers().size());
                } catch (Throwable t) {   // keep the periodic task alive (see proposer)
                    log.warn("[SEED] Peer-list broadcast failed: {}", t.getMessage());
                }
            }, 30_000L, 30_000L, TimeUnit.MILLISECONDS);
            log.info("[P2P] Peer-list broadcast and mesh dialling enabled (every 30 s)");
        }

        // 16. Add graceful shutdown hook (now including the scheduler)
        addShutdownHook(new ScheduledExecutorService[]{scheduler, seedScheduler}, pbft, peerNode, blockchain, storage);

        // 17. Pass to IoTRestAPI
        IoTRestAPI.setNode(blockchain, peerNode, pbft);

        // 18. Start Spring Boot API

        // [HC-009] Config.API_PORT was never passed to Spring (which always used 8080), and an API
        // start failure left the node running with no API. Now API_PORT is Spring's default port
        // (an explicit SERVER_PORT / server.port still takes precedence) and a failure exits.
        SpringApplication api = new SpringApplication(IoTRestAPI.class);
        api.setDefaultProperties(java.util.Map.of("server.port", String.valueOf(Config.API_PORT)));
        try {
            api.run(args);
        } catch (Throwable t) {
            log.error("[API] REST API failed to start on port {}: {} - shutting the node down", Config.API_PORT, t.getMessage());
            System.exit(1);
        }
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
                if (blockchain.isPaused()) {
                    return;
                }
                // [O-1] after a view change that re-proposed a prepared block, the leader must not
                // propose a different block at that height
                if (pbft.getJustifiedHash(blockchain.getHeight() + 1) != null) {
                    return;
                }
                // [HC-087] one proposal per (view, height): while our block for the next height is not
                // committed, re-proposing (a new timestamp gives a new hash) made every validator sign
                // two PREPAREs at one height. The view change replaces a proposal that never commits.
                if (pbft.hasOwnVote(PBFTConsensus.Phase.PREPARE, pbft.getViewNumber(), blockchain.getHeight() + 1)) {
                    return;
                }
                if (nodeId.equals(pbft.getCurrentLeader())) {   // [HC-014] null-safe: no leader eligible -> skip slot
                    // Generate a new block
                    Block block = blockchain.createBlock(nodeId, Config.MAX_TRANSACTIONS_PER_BLOCK);

                    // Sign block header
                    // [HC-091] the proposer id is part of the signed digest: set it before signing
                    block.setValidatorId(nodeId);
                    block.sign(privKey);

                    long seq = block.getIndex();
                    String hash = block.getHash();
                    long view = pbft.getViewNumber();

                    // Generate prepare signature (self-vote)
                    PBFTConsensus.PBFTMessage prepMsg = new PBFTConsensus.PBFTMessage(
                            PBFTConsensus.Phase.PREPARE, view, seq, hash, nodeId);
                    prepMsg.sign(privKey);

                    if (!pbft.reserveOwnVote(PBFTConsensus.Phase.PREPARE, view, seq, hash)) return;   // [HC-087]
                    // Record and broadcast PREPARE
                    pbft.addPrepareVote(seq, hash, nodeId, prepMsg.signature);
                    pbft.getMessenger().broadcastPrepare(seq, hash, nodeId, prepMsg.signature);

                    // Store for commit phase
                    pbft.setPendingBlock(seq, block);

                    // Broadcast block to other nodes
                    peerNode.broadcastBlock(block);

                    log.info("[PROPOSER] Proposed block {} hash: {}", seq, hash);
                }
            } catch (Throwable t) {
                // catch Throwable: a periodic task that throws (incl. an Error) is cancelled
                // silently by the executor, and this node would never propose again
                log.error("[PROPOSER] Error in block production: {}", t.getMessage(), t);
            }
        }, Config.TARGET_BLOCK_TIME_MS, Config.TARGET_BLOCK_TIME_MS, TimeUnit.MILLISECONDS);

        return scheduler;
    }
}

