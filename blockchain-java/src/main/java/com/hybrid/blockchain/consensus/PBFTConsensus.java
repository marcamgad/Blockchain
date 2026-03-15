package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Consensus;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.Validator;
import java.util.*;
import java.util.concurrent.*;
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

    public interface PBFTMessenger {
        void broadcastPrepare(long sequenceNumber, String blockHash, String validatorId, byte[] signature);
        void broadcastCommit(long sequenceNumber, String blockHash, String validatorId, byte[] signature);
        void broadcastViewChange(long newView, long lastSeq, String validatorId, byte[] signature);
        void broadcastNewView(long newView, List<PBFTMessage> viewChanges, List<Block> recoveredBlocks, String validatorId);
    }

    private PBFTMessenger messenger;

    public void setMessenger(PBFTMessenger messenger) {
        this.messenger = messenger;
    }

    public enum Phase {
        PRE_PREPARE,
        PREPARE,
        COMMIT,
        COMMITTED,
        VIEW_CHANGE,
        NEW_VIEW
    }

    /**
     * PBFT message structure
     */
    public static class PBFTMessage {
        public Phase phase;
        public long viewNumber;
        public long sequenceNumber;
        public String blockHash;
        public String validatorId;
        public byte[] signature;

        public PBFTMessage(Phase phase, long viewNumber, long sequenceNumber, String blockHash, String validatorId) {
            this.phase = phase;
            this.viewNumber = viewNumber;
            this.sequenceNumber = sequenceNumber;
            this.blockHash = blockHash;
            this.validatorId = validatorId;
        }

        // Overload for View Change
        public PBFTMessage(Phase phase, long newView, long lastSeq, String validatorId) {
            this.phase = phase;
            this.viewNumber = newView;
            this.sequenceNumber = lastSeq;
            this.validatorId = validatorId;
            this.blockHash = "VIEW_CHANGE_PROOF";
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

    // Blocks pending consensus: sequenceNumber -> block
    private final Map<Long, Block> pendingBlocks = new ConcurrentHashMap<>();

    // View Change log: viewNumber -> validatorId -> VIEW_CHANGE message
    private final Map<Long, Map<String, PBFTMessage>> viewChangeLog = new ConcurrentHashMap<>();

    // Committed blocks
    private final Set<String> committedBlocks;

    // Last committed sequence number
    private long lastCommittedSeq = 0;

    // Slashed validators (double-signers)
    private final Set<String> slashedValidators = ConcurrentHashMap.newKeySet();

    // Consensus timer
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> currentTimerTask;
    private static final long CONSENSUS_TIMEOUT_MS = 15000; // 15 seconds for IoT stability

    private final String localValidatorId;
    private final BigInteger localPrivateKey;

    public PBFTConsensus(Map<String, byte[]> validators, String localValidatorId, BigInteger localPrivateKey) {
        if (validators.size() < 4) {
            throw new IllegalArgumentException("PBFT requires at least 4 validators (3f+1 where f=1)");
        }

        this.validators = new ConcurrentHashMap<>(validators);
        this.f = (validators.size() - 1) / 3;
        this.viewNumber = 0;
        this.localValidatorId = localValidatorId;
        this.localPrivateKey = localPrivateKey;
        this.messageLog = new ConcurrentHashMap<>();
        this.committedBlocks = ConcurrentHashMap.newKeySet();

        System.out.println("[PBFT] Initialized as " + localValidatorId + " with " + validators.size() + " validators, f=" + f);
        resetTimer();
    }

    private void resetTimer() {
        if (currentTimerTask != null) {
            currentTimerTask.cancel(false);
        }
        currentTimerTask = timer.schedule(() -> {
            System.out.println("[PBFT] Consensus timeout! Triggering view change...");
            triggerViewChange();
        }, CONSENSUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
        lastCommittedSeq = Math.max(lastCommittedSeq, sequenceNumber);
        System.out.println("[PBFT] Block " + blockHash + " committed with " + commitCount + " votes");
        resetTimer();

        return true;
    }

    /**
     * Add a VIEW_CHANGE vote from a validator
     */
    public void addViewChangeVote(long newView, long lastSeq, String validatorId, byte[] signature) {
        if (!validators.containsKey(validatorId)) {
            throw new SecurityException("Unknown validator: " + validatorId);
        }

        PBFTMessage msg = new PBFTMessage(Phase.VIEW_CHANGE, newView, lastSeq, validatorId);
        msg.signature = signature;

        if (!msg.verify(validators.get(validatorId))) {
            throw new SecurityException("Invalid VIEW_CHANGE signature from " + validatorId);
        }

        viewChangeLog.computeIfAbsent(newView, k -> new ConcurrentHashMap<>())
                .put(validatorId, msg);

        System.out.println("[PBFT] Added VIEW_CHANGE for view " + newView + " from " + validatorId);

        // Check for quorum
        if (viewChangeLog.get(newView).size() >= (2 * f + 1)) {
            processViewChange(newView);
        }
    }

    private void processViewChange(long newView) {
        if (this.viewNumber >= newView) return;

        String nextLeader = selectLeader(newView);
        System.out.println("[PBFT] View Change Quorum reached for view " + newView + ". Next leader: " + nextLeader);

        // If I am the next leader, broadcast NEW_VIEW
        // This would require my own validatorId and private key, which usually come from the node
        // For now, we update our view
        this.viewNumber = newView;
    }

    @Override
    public boolean isValidator(String validatorId) {
        return validators.containsKey(validatorId);
    }

    @Override
    public boolean verifyBlock(Block block, Validator validator) throws Exception {
        // For PBFT, we check if the block has reached quorum (committed phase)
        return committedBlocks.contains(block.getHash());
    }

    @Override
    public Block selectLeader(List<String> authorizedNodes, long round) {
        return null; // Not typically used this way in PBFT
    }

    @Override
    public List<Validator> getValidators() {
        List<Validator> list = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : validators.entrySet()) {
            list.add(new Validator(entry.getKey(), entry.getValue()));
        }
        return list;
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
                .compute(validatorId, (id, existing) -> {
                    if (existing != null && !existing.blockHash.equals(blockHash)) {
                        System.out.println("[PBFT] SLASH: Double PREPARE from " + id + " for different hashes: " + existing.blockHash + " AND " + blockHash);
                        slashedValidators.add(id);
                    }
                    return msg;
                });

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
                .compute(validatorId, (id, existing) -> {
                    if (existing != null && !existing.blockHash.equals(blockHash)) {
                        System.out.println("[PBFT] SLASH: Double COMMIT from " + id + " for different hashes: " + existing.blockHash + " AND " + blockHash);
                        slashedValidators.add(id);
                    }
                    return msg;
                });

        System.out.println("[PBFT] Added COMMIT vote from " + validatorId + " for block " + blockHash);
    }

    /**
     * Trigger view change (when leader is faulty)
     */
    public void triggerViewChange() {
        long nextView = viewNumber + 1;
        System.out.println("[PBFT] Initiating View Change to view " + nextView);
        
        PBFTMessage vcMsg = new PBFTMessage(Phase.VIEW_CHANGE, nextView, lastCommittedSeq, localValidatorId);
        if (localPrivateKey != null) {
            vcMsg.sign(localPrivateKey);
            if (messenger != null) {
                messenger.broadcastViewChange(nextView, lastCommittedSeq, localValidatorId, vcMsg.signature);
            }
        }
        
        // Add our own vote
        viewChangeLog.computeIfAbsent(nextView, k -> new ConcurrentHashMap<>())
                .put(localValidatorId, vcMsg);
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
    // Get slashed validators
    public Set<String> getSlashedValidators() {
        return Collections.unmodifiableSet(slashedValidators);
    }

    // Clear slashed status (e.g. after punishment)
    public void clearSlashedValidator(String id) {
        slashedValidators.remove(id);
    }

    public void setPendingBlock(long seq, Block b) { pendingBlocks.put(seq, b); }
    public Block getPendingBlock(long seq) { return pendingBlocks.get(seq); }
    public Block removePendingBlock(long seq) { return pendingBlocks.remove(seq); }
    public long getViewNumber() { return viewNumber; }
    public PBFTMessenger getMessenger() { return messenger; }

    // Test compatibility stubs
    public boolean onPrepare(String hash, String valId, byte[] sig) { return false; }
    public boolean onCommit(String hash, String valId, byte[] sig) { return false; }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("validators", validators.size());
        stats.put("maxFaultyNodes", f);
        stats.put("viewNumber", viewNumber);
        stats.put("currentLeader", getCurrentLeader());
        stats.put("committedBlocks", committedBlocks.size());
        return stats;
    }

    public void shutdown() {
        if (currentTimerTask != null) {
            currentTimerTask.cancel(true);
        }
        timer.shutdownNow();
    }
}
