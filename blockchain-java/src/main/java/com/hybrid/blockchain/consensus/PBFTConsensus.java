package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Consensus;
import com.hybrid.blockchain.Crypto;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.math.BigInteger;

/**
 * Practical Byzantine Fault Tolerance (PBFT) consensus implementation.
 * 
 * Provides:
 * - Byzantine fault tolerance (tolerates f = (n-1)/3 faulty nodes)
 * - Deterministic finality (no probabilistic confirmation)
 * - Fast consensus for private validator sets
 * 
 * Three-phase protocol:
 * 1. PRE-PREPARE: Leader proposes block
 * 2. PREPARE: Validators vote on proposal
 * 3. COMMIT: Validators commit to block
 */
public class PBFTConsensus implements Consensus {

    public enum Phase {
        PRE_PREPARE,
        PREPARE,
        COMMIT,
        COMMITTED
    }

    /**
     * PBFT message structure
     */
    public static class PBFTMessage {
        Phase phase;
        long viewNumber;
        long sequenceNumber;
        String blockHash;
        String validatorId;
        byte[] signature;

        public PBFTMessage(Phase phase, long viewNumber, long sequenceNumber, String blockHash, String validatorId) {
            this.phase = phase;
            this.viewNumber = viewNumber;
            this.sequenceNumber = sequenceNumber;
            this.blockHash = blockHash;
            this.validatorId = validatorId;
        }

        public void sign(BigInteger privateKey) {
            byte[] message = serializeForSigning();
            this.signature = Crypto.sign(message, privateKey);
        }

        public boolean verify(byte[] publicKey) {
            byte[] message = serializeForSigning();
            return Crypto.verify(message, signature, publicKey);
        }

        private byte[] serializeForSigning() {
            String data = phase.name() + viewNumber + sequenceNumber + blockHash + validatorId;
            return Crypto.hash(data.getBytes());
        }
    }

    // Validator set (address -> public key)
    private final Map<String, byte[]> validators;

    // Maximum faulty nodes: f = (n-1)/3
    private final int f;

    // Current view number
    private long viewNumber;

    // Message log: sequenceNumber -> phase -> validatorId -> message
    private final Map<Long, Map<Phase, Map<String, PBFTMessage>>> messageLog;

    // Committed blocks
    private final Set<String> committedBlocks;

    public PBFTConsensus(Map<String, byte[]> validators) {
        if (validators.size() < 4) {
            throw new IllegalArgumentException("PBFT requires at least 4 validators (3f+1 where f=1)");
        }

        this.validators = new ConcurrentHashMap<>(validators);
        this.f = (validators.size() - 1) / 3;
        this.viewNumber = 0;
        this.messageLog = new ConcurrentHashMap<>();
        this.committedBlocks = ConcurrentHashMap.newKeySet();

        System.out.println("[PBFT] Initialized with " + validators.size() + " validators, f=" + f);
    }

    @Override
    public boolean validateBlock(Block block, List<Block> chain) {
        String blockHash = block.getHash();
        long sequenceNumber = block.getIndex();

        // Check if already committed
        if (committedBlocks.contains(blockHash)) {
            return true;
        }

        // Get message log for this sequence number
        Map<Phase, Map<String, PBFTMessage>> seqLog = messageLog.computeIfAbsent(
                sequenceNumber, k -> new ConcurrentHashMap<>());

        // Phase 1: PRE-PREPARE (verify leader proposed this block)
        String leader = selectLeader(viewNumber);
        if (!block.getValidatorId().equals(leader)) {
            System.out.println(
                    "[PBFT] Block not from current leader. Expected: " + leader + ", Got: " + block.getValidatorId());
            return false;
        }

        // Verify block signature
        byte[] leaderPubKey = validators.get(leader);
        if (leaderPubKey == null || block.getSignature() == null) {
            return false;
        }

        if (!Crypto.verify(Crypto.hash(block.serializeCanonical()), block.getSignature(), leaderPubKey)) {
            System.out.println("[PBFT] Invalid leader signature");
            return false;
        }

        // Phase 2: PREPARE - collect prepare votes
        Map<String, PBFTMessage> prepareVotes = seqLog.computeIfAbsent(
                Phase.PREPARE, k -> new ConcurrentHashMap<>());

        // Simulate collecting prepare votes (in real implementation, this would be
        // network communication)
        // For now, assume we have enough votes if block is valid
        int prepareCount = prepareVotes.size();
        int requiredVotes = 2 * f + 1;

        if (prepareCount < requiredVotes) {
            // In production, this would wait for votes from network
            System.out.println("[PBFT] Insufficient prepare votes: " + prepareCount + "/" + requiredVotes);
            return false;
        }

        // Phase 3: COMMIT - collect commit votes
        Map<String, PBFTMessage> commitVotes = seqLog.computeIfAbsent(
                Phase.COMMIT, k -> new ConcurrentHashMap<>());

        int commitCount = commitVotes.size();

        if (commitCount < requiredVotes) {
            System.out.println("[PBFT] Insufficient commit votes: " + commitCount + "/" + requiredVotes);
            return false;
        }

        // Block is committed
        committedBlocks.add(blockHash);
        System.out.println("[PBFT] Block " + blockHash + " committed with " + commitCount + " votes");

        return true;
    }

    @Override
    public Block selectLeader(List<String> authorizedNodes, long round) {
        // This method signature is from the interface, but we override with our own
        return null;
    }

    /**
     * Select leader for current view (round-robin)
     */
    public String selectLeader(long view) {
        List<String> validatorList = new ArrayList<>(validators.keySet());
        Collections.sort(validatorList); // Deterministic ordering
        int leaderIndex = (int) (view % validatorList.size());
        return validatorList.get(leaderIndex);
    }

    /**
     * Add a prepare vote for a block
     */
    public void addPrepareVote(long sequenceNumber, String blockHash, String validatorId, byte[] signature) {
        if (!validators.containsKey(validatorId)) {
            throw new SecurityException("Unknown validator: " + validatorId);
        }

        PBFTMessage msg = new PBFTMessage(Phase.PREPARE, viewNumber, sequenceNumber, blockHash, validatorId);
        msg.signature = signature;

        // Verify signature
        if (!msg.verify(validators.get(validatorId))) {
            throw new SecurityException("Invalid signature from validator: " + validatorId);
        }

        messageLog.computeIfAbsent(sequenceNumber, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(Phase.PREPARE, k -> new ConcurrentHashMap<>())
                .put(validatorId, msg);

        System.out.println("[PBFT] Added PREPARE vote from " + validatorId + " for block " + blockHash);
    }

    /**
     * Add a commit vote for a block
     */
    public void addCommitVote(long sequenceNumber, String blockHash, String validatorId, byte[] signature) {
        if (!validators.containsKey(validatorId)) {
            throw new SecurityException("Unknown validator: " + validatorId);
        }

        PBFTMessage msg = new PBFTMessage(Phase.COMMIT, viewNumber, sequenceNumber, blockHash, validatorId);
        msg.signature = signature;

        // Verify signature
        if (!msg.verify(validators.get(validatorId))) {
            throw new SecurityException("Invalid signature from validator: " + validatorId);
        }

        messageLog.computeIfAbsent(sequenceNumber, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(Phase.COMMIT, k -> new ConcurrentHashMap<>())
                .put(validatorId, msg);

        System.out.println("[PBFT] Added COMMIT vote from " + validatorId + " for block " + blockHash);
    }

    /**
     * Trigger view change (when leader is faulty)
     */
    public void triggerViewChange() {
        viewNumber++;
        String newLeader = selectLeader(viewNumber);
        System.out.println("[PBFT] View change to view " + viewNumber + ", new leader: " + newLeader);
    }

    /**
     * Get current leader
     */
    public String getCurrentLeader() {
        return selectLeader(viewNumber);
    }

    /**
     * Get validator count
     */
    public int getValidatorCount() {
        return validators.size();
    }

    /**
     * Get maximum faulty nodes
     */
    public int getMaxFaultyNodes() {
        return f;
    }

    /**
     * Check if we have quorum for a phase
     */
    public boolean hasQuorum(long sequenceNumber, Phase phase) {
        Map<Phase, Map<String, PBFTMessage>> seqLog = messageLog.get(sequenceNumber);
        if (seqLog == null) {
            return false;
        }

        Map<String, PBFTMessage> votes = seqLog.get(phase);
        if (votes == null) {
            return false;
        }

        return votes.size() >= (2 * f + 1);
    }

    /**
     * Get statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("validators", validators.size());
        stats.put("maxFaultyNodes", f);
        stats.put("viewNumber", viewNumber);
        stats.put("currentLeader", getCurrentLeader());
        stats.put("committedBlocks", committedBlocks.size());
        return stats;
    }
}
