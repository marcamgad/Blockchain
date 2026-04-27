package com.hybrid.blockchain;

import com.hybrid.blockchain.api.IoTRestAPI;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for the HybridChain Node.
 *
 * <p>Wires all modules (Consensus, Networking, Storage, API) and manages the
 * application lifecycle, including:</p>
 * <ul>
 *   <li><b>Bug 2 fix</b> — adds a {@link ScheduledExecutorService} block-proposer
 *       that fires the PBFT leader to create, sign and broadcast blocks.</li>
 *   <li><b>Bug 6 fix</b> — {@code localId} is the cryptographic address derived
 *       from the node's private key, <em>not</em> the {@code NODE_ID} env label.</li>
 *   <li><b>Task 2</b> — seed nodes periodically broadcast their peer list so that
 *       new validators auto-discover the cluster.</li>
 * </ul>
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        try {
            log.info("=== HybridChain Node starting (label={}) ===", Config.NODE_ID);

            if (Config.BYPASS_CONTRACT_AUDIT && Config.isProductionProfile()) {
                throw new IllegalStateException(
                        "BYPASS_CONTRACT_AUDIT must be false in production profile");
            }

            // ── 1. Keys & cryptographic identity ────────────────────────────────
            // BUG 6 FIX: localId must be the crypto address derived from the private
            // key, NOT the NODE_ID env-var label (e.g. "validator-1").
            // PBFT leader selection is keyed by crypto address; using the label
            // caused leader-identity mismatch and permanent block skipping.
            BigInteger privateKey = Config.getNodePrivateKey();
            byte[]     localPub   = Crypto.derivePublicKey(privateKey);
            String     localId    = Crypto.deriveAddress(localPub);  // "hb..." address
            log.info("Node crypto address: {}  (label: {})", localId, Config.NODE_ID);

            // ── 2. Storage & mempool ─────────────────────────────────────────────
            Storage storage = new Storage(Config.STORAGE_PATH, Config.STORAGE_AES_KEY);
            Mempool mempool = new Mempool(Config.MEMPOOL_LIMIT);

            // ── 3. Validator set from VALIDATOR_PUBKEYS env ──────────────────────
            Map<String, byte[]> validatorSet = new HashMap<>();
            String valPubKeysEnv = System.getenv("VALIDATOR_PUBKEYS");
            if (valPubKeysEnv != null && !valPubKeysEnv.isBlank()) {
                for (String pubHex : valPubKeysEnv.split(",")) {
                    pubHex = pubHex.trim();
                    if (pubHex.isEmpty()) continue;
                    byte[] pub = HexUtils.decode(pubHex);
                    validatorSet.put(Crypto.deriveAddress(pub), pub); // key = crypto address
                }
                log.info("Loaded {} validators from VALIDATOR_PUBKEYS", validatorSet.size());
            } else {
                // Single-validator debug mode: this node is its own validator set
                validatorSet.put(localId, localPub);
                log.warn("VALIDATOR_PUBKEYS not set — single-validator debug mode");
            }

            // ── 4. PBFT consensus ────────────────────────────────────────────────
            PBFTConsensus consensus = new PBFTConsensus(validatorSet, localId, privateKey);

            // ── 5. Blockchain ────────────────────────────────────────────────────
            Blockchain blockchain = new Blockchain(storage, mempool, consensus);
            TokenRegistry tokenRegistry = new TokenRegistry(storage);
            blockchain.setTokenRegistry(tokenRegistry);

            // Monitoring & auditing
            com.hybrid.blockchain.monitoring.BlockchainMonitor monitor =
                    new com.hybrid.blockchain.monitoring.BlockchainMonitor(Config.NODE_ID);
            com.hybrid.blockchain.audit.AuditLogger auditLogger =
                    new com.hybrid.blockchain.audit.AuditLogger(Config.NODE_ID);
            com.hybrid.blockchain.api.EventBus eventBus =
                    new com.hybrid.blockchain.api.EventBus();
            blockchain.setMonitor(monitor);
            blockchain.setAuditLogger(auditLogger);
            blockchain.setEventBus(eventBus);

            // ── 6. P2P networking ─────────────────────────────────────────────────
            PeerNode peerNode = new PeerNode(Config.P2P_PORT, blockchain, consensus, privateKey);
            consensus.setMessenger(peerNode);
            blockchain.setPeerNode(peerNode);

            // ── 7. Bootstrap chain & start P2P server ────────────────────────────
            blockchain.init();
            peerNode.start();                     // bind server socket — was missing before!
            peerNode.startPeerSync();

            log.info("P2P server listening on port {}", Config.P2P_PORT);

            // ── 8. Connect to seed peer (non-seed nodes only) ────────────────────
            if (!Config.IS_SEED && Config.SEED_PEER != null && !Config.SEED_PEER.isBlank()) {
                String[] parts = Config.SEED_PEER.split(":");
                if (parts.length == 2) {
                    String seedHost = parts[0].trim();
                    int    seedPort = Integer.parseInt(parts[1].trim());
                    peerNode.connectToPeer(seedHost, seedPort);
                    log.info("Initiated connection to seed peer: {}", Config.SEED_PEER);
                }
            }

            // ── 9. BUG 2 FIX — Block-proposer scheduler ──────────────────────────
            // A VALIDATOR node proposes a block whenever it is the PBFT leader for
            // the current view.  The check is strictly local: if localId != leader,
            // the scheduler returns immediately without touching the chain.
            if (Config.NODE_ROLE == Config.NodeRole.VALIDATOR) {
                ScheduledExecutorService blockProposer = Executors.newSingleThreadScheduledExecutor(
                        r -> { Thread t = new Thread(r, "block-proposer"); t.setDaemon(true); return t; });

                blockProposer.scheduleAtFixedRate(() -> {
                    try {
                        if (blockchain.isPaused()) return;

                        String leader = consensus.selectLeader(consensus.getViewNumber());
                        if (!localId.equals(leader)) return; // not our slot

                        Block block = blockchain.createBlock(localId, Config.MAX_TRANSACTIONS_PER_BLOCK);

                        // Sign the block with our private key
                        byte[] sig = Crypto.sign(Crypto.hash(block.serializeCanonical()), privateKey);
                        block.setSignature(sig);
                        // Recalculate hash after signature is set
                        block.setHash(block.calculateHash());

                        peerNode.broadcastBlock(block);
                        log.info("[PROPOSER] Proposed block index={} hash={}", block.getIndex(), block.getHash());

                    } catch (Exception e) {
                        log.error("[PROPOSER] Block proposal failed: {}", e.getMessage(), e);
                    }
                }, Config.TARGET_BLOCK_TIME_MS, Config.TARGET_BLOCK_TIME_MS, TimeUnit.MILLISECONDS);

                log.info("[PROPOSER] Block-proposer started (interval={}ms)", Config.TARGET_BLOCK_TIME_MS);
            }

            // ── 10. TASK 2 — Seed node broadcasts peer list every 30 s ───────────
            // New joiners use the seed's peer list to bootstrap discovery.
            if (Config.IS_SEED) {
                ScheduledExecutorService seedBroadcast = Executors.newSingleThreadScheduledExecutor(
                        r -> { Thread t = new Thread(r, "seed-peer-broadcast"); t.setDaemon(true); return t; });

                seedBroadcast.scheduleAtFixedRate(() -> {
                    try {
                        peerNode.broadcastPeerList(); // package-private — accessible within com.hybrid.blockchain
                        log.debug("[SEED] Broadcast peer list ({} known peers)", peerNode.getPeers().size());
                    } catch (Exception e) {
                        log.warn("[SEED] Peer broadcast failed: {}", e.getMessage());
                    }
                }, 30_000L, 30_000L, TimeUnit.MILLISECONDS);

                log.info("[SEED] Peer-list broadcast enabled (every 30 s)");
            }

            // ── 11. REST API ──────────────────────────────────────────────────────
            IoTRestAPI.setNode(blockchain, peerNode, consensus);
            SpringApplication.run(IoTRestAPI.class, args);

            log.info("HybridChain Node operational | P2P:{} API:{} ID:{}",
                    Config.P2P_PORT, Config.API_PORT, localId);

        } catch (Exception e) {
            LoggerFactory.getLogger(App.class).error("CRITICAL: HybridChain Node failed to start", e);
            System.exit(1);
        }
    }
}
