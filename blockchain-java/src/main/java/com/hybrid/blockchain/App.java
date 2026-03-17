package com.hybrid.blockchain;

import com.hybrid.blockchain.api.IoTRestAPI;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;

import java.math.BigInteger;
import java.util.*;

/**
 * Main Entry Point for the HybridChain Node.
 * Responsible for wiring all modules (Consensus, Networking, Storage, API)
 * and managing the application lifecycle.
 */
public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        try {
            log.info("Starting HybridChain Node...");

            // 1. Initialize Storage & Configuration
            Storage storage = new Storage(Config.STORAGE_PATH, Config.STORAGE_AES_KEY);
            Mempool mempool = new Mempool(Config.MEMPOOL_LIMIT);

            // 2. Initialize Consensus (PBFT for validators)
            String localId = Config.NODE_ID;
            BigInteger privateKey = Config.getNodePrivateKey();
            
            // Register initial validators from config if needed
            Map<String, byte[]> validatorSet = new HashMap<>();
            String valPubKeysEnv = System.getenv("VALIDATOR_PUBKEYS");
            if (valPubKeysEnv != null && !valPubKeysEnv.isEmpty()) {
                for (String pubHex : valPubKeysEnv.split(",")) {
                    byte[] pub = HexUtils.decode(pubHex.trim());
                    validatorSet.put(Crypto.deriveAddress(pub), pub);
                }
            } else {
                // Fallback for debug/development
                byte[] localPub = Crypto.derivePublicKey(privateKey);
                validatorSet.put(localId, localPub);
            }

            PBFTConsensus consensus = new PBFTConsensus(validatorSet, localId, privateKey);

            // 3. Initialize Blockchain
            Blockchain blockchain = new Blockchain(storage, mempool, consensus);
            TokenRegistry tokenRegistry = new TokenRegistry(storage);
            blockchain.setTokenRegistry(tokenRegistry);
            
            // 4. Initialize Networking (P2P)
            PeerNode peerNode = new PeerNode(Config.P2P_PORT, blockchain, consensus, privateKey);
            consensus.setMessenger(peerNode);
            blockchain.setPeerNode(peerNode);

            // 5. Initialize API and Event Bus
            com.hybrid.blockchain.api.EventBus eventBus = new com.hybrid.blockchain.api.EventBus();
            blockchain.setEventBus(eventBus);

            // 6. Bootstrap Blockchain
            blockchain.init();

            // 7. Inject dependencies into REST API static context
            IoTRestAPI.setNode(blockchain, peerNode, consensus);

            // 8. Launch Spring Boot API
            SpringApplication.run(IoTRestAPI.class, args);

            log.info("HybridChain Node is now operational! P2P: {}, API: {}", Config.P2P_PORT, Config.API_PORT);

        } catch (Exception e) {
            log.error("CRITICAL: Failed to start HybridChain Node", e);
            System.exit(1);
        }
    }
}
