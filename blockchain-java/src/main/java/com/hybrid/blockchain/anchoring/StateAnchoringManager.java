package com.hybrid.blockchain.anchoring;

import com.hybrid.blockchain.Blockchain;
import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages periodic state anchoring to external chains.
 * Provides long-term finality by committing HybridChain state roots to a public ledger.
 */
public class StateAnchoringManager {
    private static final Logger log = LoggerFactory.getLogger(StateAnchoringManager.class);
    
    private static final int ANCHOR_INTERVAL = 1000; // Anchor every 1000 blocks
    private final Map<Long, String> anchors = new ConcurrentHashMap<>(); // height -> anchorTxHash

    public StateAnchoringManager() {
        log.info("[ANCHORING] State anchoring manager initialized (Interval: {})", ANCHOR_INTERVAL);
    }

    /**
     * Checks if a block should be anchored and initiates the (simulated) cross-chain transaction.
     */
    public void processBlock(Block block, String stateRoot) {
        if (block.getIndex() > 0 && block.getIndex() % ANCHOR_INTERVAL == 0) {
            anchorState(block.getIndex(), stateRoot);
        }
    }

    private void anchorState(long height, String stateRoot) {
        // In a real system, this would call an Ethereum/Polygon JSON-RPC endpoint
        // to submit a 'commit(height, stateRoot)' transaction.
        
        String anchorTx = "0x" + HexUtils.encode(com.hybrid.blockchain.Crypto.hash(
                (height + ":" + stateRoot).getBytes()
        ));
        
        anchors.put(height, anchorTx);
        String shortRoot = stateRoot.length() > 10 ? stateRoot.substring(0, 10) : stateRoot;
        log.info("[ANCHORING] ANCHORED height {} with root {} to external chain. Tx: {}", 
                height, shortRoot + "...", anchorTx);
    }

    public String getAnchorTx(long height) {
        return anchors.get(height);
    }

    public boolean isAnchored(long height) {
        return anchors.containsKey(height);
    }
}
