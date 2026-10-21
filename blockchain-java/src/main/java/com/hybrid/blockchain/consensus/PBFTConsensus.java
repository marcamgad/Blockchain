package com.hybrid.blockchain.consensus;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.Consensus;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.Validator;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Practical Byzantine Fault Tolerance (PBFT) consensus implementation.
 *
 * <p>Three-phase protocol: PRE-PREPARE → PREPARE → COMMIT</p>
 *
 * <p><b>Bug 1 fix:</b> {@link #validateBlock} now only checks the block's leader
 * identity and cryptographic signature. It does NOT count votes — that check was
 * always reading an empty map and would have permanently blocked finality.
 * Block application only happens in {@code PeerNode.applyBlockAtSequence()} after
 * real quorum via {@link #addPrepareVote}/{@link #addCommitVote} network messages,
 * followed by an explicit {@link #markCommitted} call.</p>
 *
 * <p><b>Feature 2 — Reputation-weighted leader selection:</b> each validator's
 * reputation score drifts based on consensus outcomes:
 * <ul>
 *   <li>+{@value #REP_BLOCK_PROPOSED} on successful block proposal (markCommitted)</li>
 *   <li>{@value #REP_MISSED_SLOT} on view-change timeout (current leader penalised)</li>
 *   <li>{@value #REP_INVALID_BLOCK} when slashed for double-signing</li>
 * </ul>
 * Leader selection uses a deterministic LCG hash of the view number to pick from a
 * weighted distribution, ensuring every honest node independently computes the same
 * leader.</p>
 */
public class PBFTConsensus implements Consensus {
    private static final Logger log = LoggerFactory.getLogger(PBFTConsensus.class);

    // ── reputation deltas ────────────────────────────────────────────────────
    public static final double REP_BLOCK_PROPOSED = +0.02;
    public static final double REP_MISSED_SLOT    = -0.10;
    public static final double REP_INVALID_BLOCK  = -0.50;
    private static final double REP_MIN           =  0.01; // floor to stay in rotation

    // ── messenger interface ──────────────────────────────────────────────────

    public interface PBFTMessenger {
        void broadcastPrepare(long sequenceNumber, String blockHash, String validatorId, byte[] signature);
        void broadcastCommit(long sequenceNumber, String blockHash, String validatorId, byte[] signature);
        void broadcastViewChange(long newView, long lastSeq, String validatorId, byte[] signature);
        void broadcastNewView(long newView, List<PBFTMessage> viewChanges, List<Block> recoveredBlocks, String validatorId);

        /** [O-1] VIEW_CHANGE carrying this node's prepared proof (null-safe default: plain VIEW_CHANGE). */
        default void broadcastViewChangeProof(ViewChangeProof vc) {
            broadcastViewChange(vc.newView, vc.lastSeq, vc.validatorId, HexUtils.decode(vc.signature));
        }

        /** [O-1] NEW_VIEW with the view-change quorum and the re-proposed block (default: not sent). */
        default void broadcastNewViewProof(NewViewProof nv) {
        }
    }

    private PBFTMessenger messenger;
    public void setMessenger(PBFTMessenger messenger) { this.messenger = messenger; }

    // ── phase enum ───────────────────────────────────────────────────────────

    public enum Phase { PRE_PREPARE, PREPARE, COMMIT, COMMITTED, VIEW_CHANGE, NEW_VIEW }

    // ── PBFT message ─────────────────────────────────────────────────────────

    public static class PBFTMessage {
        public Phase  phase;
        public long   viewNumber;
        public long   sequenceNumber;
        public String blockHash;
        public String validatorId;
        public byte[] signature;

        public PBFTMessage(Phase phase, long viewNumber, long sequenceNumber, String blockHash, String validatorId) {
            this.phase          = phase;
            this.viewNumber     = viewNumber;
            this.sequenceNumber = sequenceNumber;
            this.blockHash      = blockHash;
            this.validatorId    = validatorId;
        }



        public void sign(BigInteger privateKey) {
            this.signature = Crypto.sign(serializeForSigning(), privateKey);
        }

        public boolean verify(byte[] publicKey) {
            return Crypto.verify(serializeForSigning(), signature, publicKey);
        }

        /**
         * [HC-066] Length-prefixed, domain-separated encoding. It used to be the plain string
         * concatenation phase+view+seq+hash+id, which is ambiguous: (view=1, seq=23) and
         * (view=12, seq=3) produced the same bytes, so one signed vote verified for both.
         */
        private byte[] serializeForSigning() {
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
                out.write(SIGNING_DOMAIN);
                writeField(out, phase.name());
                out.writeLong(viewNumber);
                out.writeLong(sequenceNumber);
                writeField(out, blockHash);
                writeField(out, validatorId);
                out.flush();
                return Crypto.hash(bos.toByteArray());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void writeField(java.io.DataOutputStream out, String s) throws java.io.IOException {
            if (s == null) {
                out.writeInt(-1);
                return;
            }
            byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(b.length);
            out.write(b);
        }

        private static final byte[] SIGNING_DOMAIN =
                "HYBRIDCHAIN-PBFT-v2\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── state ────────────────────────────────────────────────────────────────

    /** Validator set: address → compressed public key. */
    private final Map<String, byte[]>  validators;
    /**
     * Reputation scores as EXACT fixed-point integers, address → score × {@link #REP_SCALE}.
     * [E3] Stored as scaled longs rather than doubles so that accumulating reputation is
     * associative: see docs/formal/pbft_leader_model.md Remark 7.1.
     */
    private final Map<String, Long>    validatorReputation = new ConcurrentHashMap<>();

    /** Fixed-point scale: a reputation of 1.0 is stored as REP_SCALE. */
    public static final long REP_SCALE     = 1_000_000L;
    /** REP_MIN expressed in the fixed-point scale. */
    public static final long REP_MIN_SCALED = (long) (REP_MIN * REP_SCALE);

    private static long toScaled(double v)   { return Math.round(v * REP_SCALE); }
    private static double fromScaled(long v) { return (double) v / REP_SCALE; }

    private final int    f;            // max faulty nodes
    private       long   viewNumber;

    /** sequenceNumber → phase → validatorId → message */
    private final Map<Long, Map<Phase, Map<String, PBFTMessage>>> messageLog;

    /** Async fast-path trackers: seq → path */
    private final Map<Long, AsyncCommitPath> asyncPaths = new ConcurrentHashMap<>();
    private volatile long lastFastPathSeq = -1;

    /** Blocks awaiting quorum: seq → block. */
    private final Map<Long, Block>     pendingBlocks  = new ConcurrentHashMap<>();

    /** View-change votes: newView → validatorId → message. */
    private final Map<Long, Map<String, PBFTMessage>> viewChangeLog = new ConcurrentHashMap<>();

    /** Blocks that have reached quorum and been applied to the chain. */
    private final Set<String>          committedBlocks;

    private long                       lastCommittedSeq = 0;
    private final Set<String>          slashedValidators = ConcurrentHashMap.newKeySet();
    /**
     * [O-5] Validators jailed by on-chain evidence, mirrored from the state after each block. Unlike
     * {@link #slashedValidators} (what THIS node happened to observe), every node holds the same
     * set, so it may decide certificate validity, leader election and vote acceptance.
     */
    private final Set<String>          jailedValidators = ConcurrentHashMap.newKeySet();
    private volatile java.util.function.Consumer<EquivocationEvidence> evidenceListener;

    private final String               localValidatorId;
    private final BigInteger           localPrivateKey;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?>             currentTimerTask;
    private long                       timeoutMs = 15_000;

    // ── constructor ──────────────────────────────────────────────────────────

    public PBFTConsensus(Map<String, byte[]> validators, String localValidatorId, BigInteger localPrivateKey) {
        if (validators.size() < 4)
            throw new IllegalArgumentException("PBFT requires at least 4 validators (3f+1 where f=1)");

        this.validators        = new ConcurrentHashMap<>(validators);
        this.f                 = (validators.size() - 1) / 3;
        this.viewNumber        = 0;
        this.localValidatorId  = localValidatorId;
        this.localPrivateKey   = localPrivateKey;
        this.messageLog        = new ConcurrentHashMap<>();
        this.committedBlocks   = ConcurrentHashMap.newKeySet();

        initReputation();
        log.info("[PBFT] Initialized as {} with {} validators (f={})", localValidatorId, validators.size(), f);
        resetTimer();
    }

    public void setTimeout(long ms) {
        this.timeoutMs = ms;
        resetTimer();
    }

    // ── ZK-gated leader eligibility ──────────────────────────────────────────
    // A validator proves reputation ≥ minEligibleReputation via a Pedersen threshold
    // proof (ZkEligibilityGate) instead of publishing its plaintext score. Verified
    // proofs mark the validator eligible; ineligible validators get zero selection
    // weight. This keeps the *predicate* verifiable while hiding the *value*.
    //
    // Determinism note: selection stays deterministic across honest nodes as long as
    // every node has seen the same set of eligibility proofs (they are gossiped /
    // anchored like other consensus messages). Disabled by default so existing
    // deployments and tests are unaffected.
    private volatile boolean zkEligibilityRequired = false;
    private volatile double  minEligibleReputation = 0.5;
    private final Map<String, Boolean> zkEligible = new ConcurrentHashMap<>();

    /** Enables/disables the ZK eligibility gate for leader selection. */
    public void setZkEligibilityRequired(boolean required) { this.zkEligibilityRequired = required; }

    public boolean isZkEligibilityRequired() { return zkEligibilityRequired; }

    /** Sets the reputation bar a validator must prove it meets to be leader-eligible. */
    public void setMinEligibleReputation(double minReputation) { this.minEligibleReputation = minReputation; }

    public double getMinEligibleReputation() { return minEligibleReputation; }

    /**
     * Submits a zero-knowledge proof that {@code validatorId}'s reputation meets the
     * eligibility bar, without revealing the score. The proof is bound to the required
     * threshold and rejected outright for slashed validators.
     *
     * @return true if the proof verified and the validator is now leader-eligible
     */
    public boolean submitEligibilityProof(String validatorId,
            com.hybrid.blockchain.privacy.ZKProofSystem.ThresholdProof proof) {
        if (!validators.containsKey(validatorId)) return false;
        boolean ok = com.hybrid.blockchain.reputation.ZkEligibilityGate.verify(
                proof, minEligibleReputation, slashedValidators.contains(validatorId));
        if (ok) {
            zkEligible.put(validatorId, Boolean.TRUE);
            log.info("[PBFT] Validator {} proved leader eligibility (score hidden)", validatorId);
        } else {
            zkEligible.remove(validatorId);
            log.warn("[PBFT] Validator {} failed ZK eligibility verification", validatorId);
        }
        return ok;
    }

    /** True when the validator currently holds a verified eligibility proof. */
    public boolean isZkEligible(String validatorId) {
        return Boolean.TRUE.equals(zkEligible.get(validatorId));
    }

    /** Clears a validator's eligibility (e.g. after slashing or a new epoch). */
    public void revokeEligibility(String validatorId) {
        zkEligible.remove(validatorId);
    }

    // PAPER-IMPL: P1-A — RWA-BFT (Sensors 2025, DOI:10.3390/s25020413)
    // Two-layer async BFT: high-rep committee can skip PREPARE (optimistic fast path)
    private volatile boolean asyncEnabled = false;

    public void setAsyncEnabled(boolean enabled) { this.asyncEnabled = enabled; }
    public boolean isAsyncEnabled() { return asyncEnabled; }

    /**
     * Forms the high-reputation committee (layer-2) for async BFT.
     * Only validators with reputation >= minRepThreshold are eligible.
     *
     * @paper RWA-BFT Sensors 2025, DOI:10.3390/s25020413 — two-layer BFT with reputation filtering
     * @gap   HybridChain previously ran single-layer PBFT; all validators participated regardless of rep
     */
    public Map<String, byte[]> formCommittee(int minRepThreshold) {
        Map<String, byte[]> committee = new LinkedHashMap<>();
        List<String> sorted = new ArrayList<>(validators.keySet());
        Collections.sort(sorted);
        for (String id : sorted) {
            double rep = fromScaled(validatorReputation.getOrDefault(id, REP_SCALE));
            if (rep >= minRepThreshold) {
                committee.put(id, validators.get(id));
            }
        }
        log.debug("[PBFT] Committee formed: {}/{} validators with rep>={}",
                committee.size(), validators.size(), minRepThreshold);
        return committee;
    }

    /**
     * Factory method: create an async commit path for the current round.
     *
     * @param minRepThreshold minimum reputation to be in the fast-path committee
     */
    public AsyncCommitPath createAsyncCommitPath(int minRepThreshold) {
        return new AsyncCommitPath(formCommittee(minRepThreshold), timeoutMs);
    }

    /**
     * Tracks the async fast-path state for a single consensus round.
     * When ALL committee members send COMMIT within {@code timeoutMs/2},
     * the next round may skip PREPARE entirely (optimistic path), achieving
     * up to 2.3x PBFT throughput as shown in RWA-BFT benchmarks.
     *
     * @paper RWA-BFT Sensors 2025, DOI:10.3390/s25020413 — async BFT layer-2 fast path
     * @novel Integrated with HybridChain's per-validator reputation scoring for dynamic committee formation
     */
    public class AsyncCommitPath {
        private final Set<String> committee;
        private final Set<String> fastCommits = ConcurrentHashMap.newKeySet();
        private final long fastPathDeadline;
        private volatile boolean fastPathActivated = false;

        public AsyncCommitPath(Map<String, byte[]> committee, long timeoutMs) {
            this.committee = new HashSet<>(committee.keySet());
            this.fastPathDeadline = System.currentTimeMillis() + timeoutMs / 2;
        }

        /**
         * Record a commit vote from a committee member.
         * @return true if the fast path was just activated by this vote
         */
        public boolean recordCommit(String validatorId) {
            if (!committee.contains(validatorId)) return false;
            fastCommits.add(validatorId);
            if (!fastPathActivated
                    && System.currentTimeMillis() <= fastPathDeadline
                    && fastCommits.containsAll(committee)) {
                fastPathActivated = true;
                log.info("[PBFT-ASYNC] Fast path ACTIVATED: all {} committee members committed within half-timeout",
                        committee.size());
            }
            return fastPathActivated;
        }

        public boolean isFastPathActivated() { return fastPathActivated; }
        public int getCommitCount() { return fastCommits.size(); }
        public int getCommitteeSize() { return committee.size(); }
        public Set<String> getCommittee() { return Collections.unmodifiableSet(committee); }
    }

    private void initReputation() {
        for (String id : validators.keySet()) {
            validatorReputation.put(id, REP_SCALE);
        }
    }

    public void penalizeValidator(String validatorId, double delta) {
        long scaledDelta = toScaled(delta);
        validatorReputation.compute(validatorId, (k, v) -> {
            long current = (v == null) ? REP_SCALE : v;
            return Math.max(REP_MIN_SCALED, current + scaledDelta);
        });
    }


    // ── timer ────────────────────────────────────────────────────────────────

    private void resetTimer() {
        if (currentTimerTask != null) currentTimerTask.cancel(false);
        currentTimerTask = timer.schedule(() -> {
            log.warn("[PBFT] Consensus timeout — triggering view change");
            triggerViewChange();
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    // ── Consensus interface implementation ───────────────────────────────────

    /**
     * BUG 1 FIX — validateBlock() now ONLY checks:
     * <ol>
     *   <li>Block already committed (fast path).</li>
     *   <li>Block is from the current view's leader.</li>
     *   <li>Leader's signature on the block is valid.</li>
     * </ol>
     * Vote counting has been removed entirely; it was reading an always-empty map.
     * Actual quorum logic is enforced by {@code PeerNode.applyBlockAtSequence()}
     * which calls {@link #markCommitted} only after real network votes.
     */
    @Override
    public boolean validateBlock(Block block, List<Block> chain) {
        if (validators.size() < 4) {
            throw new IllegalStateException("Consensus unsafe: validator set is below 3f+1 minimum");
        }
        String blockHash = block.getHash();

        // Fast path: already passed through the full PBFT commit path
        if (committedBlocks.contains(blockHash)) return true;

        // Verify leader identity
        String leader = selectLeader(viewNumber);
        if (!block.getValidatorId().equals(leader)) {
            log.warn("[PBFT] Block not from current leader. Expected: {}, Got: {}",
                    leader, block.getValidatorId());
            return false;
        }

        // Verify leader signature
        byte[] leaderPubKey = validators.get(leader);
        if (leaderPubKey == null || block.getSignature() == null) {
            log.warn("[PBFT] Missing pubkey or signature for leader {}", leader);
            return false;
        }

        if (!Crypto.verify(Crypto.hash(block.serializeCanonical()), block.getSignature(), leaderPubKey)) {
            log.warn("[PBFT] Invalid leader signature on block {}", blockHash);
            return false;
        }

        // Structurally valid PRE-PREPARE — quorum enforcement is in PeerNode
        return true;
    }

    @Override
    public boolean isValidator(String validatorId) {
        return validators.containsKey(validatorId);
    }

    /**
     * Returns true iff this block has gone through the full PBFT commit path.
     * Called by {@code Blockchain.applyBlockInternal()} — requires
     * {@link #markCommitted} to have been called BEFORE {@code applyBlock()}.
     */
    /**
     * [HC-046] A block is final only with a quorum behind it. Before, a block that had not gone
     * through this node's commit path was accepted on the CURRENT leader's signature alone - one
     * validator could finalise any block, and blocks from earlier views (sync) were refused.
     *
     * <p>Now: the proposer's signature must always verify, AND either this node observed the
     * commit quorum itself ({@link #markCommitted}) or the block carries a valid commit
     * certificate from {@link #quorumSize()} distinct validators. In a single-validator network
     * the quorum is 1, so the proposer's signature is the whole quorum.</p>
     */
    @Override
    public boolean verifyBlock(Block block, Validator validator) {
        if (!verifyProposerSignature(block, validator)) return false;
        if (committedBlocks.contains(block.getHash())) return true;
        if (quorumSize() <= 1) return true;
        return verifyCommitCertificate(block);
    }

    /** [HC-065] proposer signature only: what a proposal must satisfy before anyone votes on it */
    @Override
    public boolean verifyProposerSignature(Block block, Validator validator) {
        if (block == null || block.getValidatorId() == null || block.getSignature() == null) return false;
        byte[] proposerKey = validators.get(block.getValidatorId());
        if (proposerKey == null) return false;
        return Crypto.verify(Crypto.hash(block.serializeCanonical()), block.getSignature(), proposerKey);
    }

    /**
     * [HC-046] True iff the block's certificate holds valid COMMIT signatures (for this index and
     * hash) from at least {@link #quorumSize()} distinct, known, non-slashed validators.
     */
    public boolean verifyCommitCertificate(Block block) {
        List<Block.CommitSignature> cert = block.getCommitCertificate();
        if (cert == null || cert.isEmpty() || cert.size() > 4 * validators.size()) return false;
        Set<String> valid = new HashSet<>();
        for (Block.CommitSignature cs : cert) {
            if (cs == null || cs.validatorId == null || cs.signature == null || valid.contains(cs.validatorId)) continue;
            // [O-5] only the on-chain jail set: a locally observed slash differs between nodes, and
            // made nodes disagree on whether a certificate is valid (i.e. which blocks are final)
            if (jailedValidators.contains(cs.validatorId)) continue;
            byte[] key = validators.get(cs.validatorId);
            if (key == null) continue;
            PBFTMessage m = new PBFTMessage(Phase.COMMIT, cs.view, block.getIndex(), block.getHash(), cs.validatorId);
            try {
                m.signature = com.hybrid.blockchain.HexUtils.decode(cs.signature);
            } catch (Exception e) {
                continue;
            }
            if (m.verify(key)) valid.add(cs.validatorId);
        }
        return valid.size() >= quorumSize();
    }

    /** [HC-046] The COMMIT votes this node holds for (seq, blockHash), as a certificate. */
    public List<Block.CommitSignature> buildCommitCertificate(long seq, String blockHash) {
        List<Block.CommitSignature> cert = new ArrayList<>();
        Map<Phase, Map<String, PBFTMessage>> seqLog = messageLog.get(seq);
        if (seqLog == null || seqLog.get(Phase.COMMIT) == null) return cert;
        for (PBFTMessage m : seqLog.get(Phase.COMMIT).values()) {
            if (m != null && blockHash.equals(m.blockHash) && m.signature != null) {
                cert.add(new Block.CommitSignature(m.validatorId, m.viewNumber, com.hybrid.blockchain.HexUtils.encode(m.signature)));
            }
        }
        return cert;
    }

    @Override
    public Block selectLeader(List<String> authorizedNodes, long round) {
        String leaderId = selectLeader(round);
        if (leaderId == null) return null;

        Block descriptor = new Block(
                0,
                0L,
                java.util.Collections.emptyList(),
                "",
                0,
                "");
        descriptor.setValidatorId(leaderId);
        return descriptor;
    }

    @Override
    public List<Validator> getValidators() {
        List<Validator> list = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : validators.entrySet())
            list.add(new Validator(e.getKey(), e.getValue()));
        return list;
    }

    @Override
    public void addValidator(String id, byte[] publicKey) {
        validators.put(id, publicKey);
        validatorReputation.put(id, REP_SCALE);
        log.info("[CONSENSUS] Added new validator: {}", id);
    }

    @Override
    public void removeValidator(String id) {
        validators.remove(id);
        validatorReputation.remove(id);
        log.info("[CONSENSUS] Removed validator: {}", id);
    }

    // ── FEATURE 2: Reputation-weighted leader selection ──────────────────────

    /**
     * Selects the leader for the given view using a reputation-weighted
     * deterministic selection. The view number is hashed with an LCG to produce
     * a stable, uniform target value, then mapped to the cumulative weight
     * distribution. All honest nodes with identical reputation maps will compute
     * the same leader.
     */
    public String selectLeader(long view) {
        List<String> sorted = new ArrayList<>(validators.keySet());
        Collections.sort(sorted); // deterministic ordering

        if (sorted.isEmpty()) return null;
        if (sorted.size() == 1) return sorted.get(0);

        // [E3] Weights are EXACT scaled integers, and the running sum is integer
        // addition — which is associative. Accumulating `double` weights was not:
        // two correct nodes applying the same event multiset in different orders could
        // differ in the last ulp and tip the `cumulative >= target` comparison, electing
        // different leaders (docs/formal/pbft_leader_model.md Remark 7.1 / defect E3).
        long total = 0;
        long[] weights = new long[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            String valId = sorted.get(i);
            // [E2] PredictiveThreatScorer is deliberately NOT consulted here. Its score is
            // derived from node-local wall-clock observations (E_ℓ); letting it zero a
            // weight reintroduces the divergence of Theorem 1 through a second channel
            // (Remark 6.3). The scorer remains available for monitoring/alerting.
            // Re-admitting it into selection requires the commit-then-use construction.
            if (jailedValidators.contains(valId)) {
                weights[i] = 0L;   // [O-5] jailed on-chain: never leader
            } else if (zkEligibilityRequired && !isZkEligible(valId)) {
                // No verified zero-knowledge eligibility proof → not leader-eligible.
                // (Eligibility proofs are consensus-visible artifacts, not local state.)
                weights[i] = 0L;
            } else {
                weights[i] = Math.max(REP_MIN_SCALED, validatorReputation.getOrDefault(valId, REP_SCALE));
            }
            total += weights[i];
        }

        if (total <= 0L) {
            if (zkEligibilityRequired) {
                log.warn("[PBFT] No ZK-eligible validators for view {} — cannot select a leader", view);
            }
            return null;
        }

        // LCG hash of view for deterministic, uniform-ish target in [0, total).
        // phi depends ONLY on the view, so it is identical at every correct node; the one
        // remaining floating-point operation is a single deterministic multiply (a single
        // IEEE-754 op is order-independent, so it does not reintroduce the E3 defect).
        long h = view * 6364136223846793005L + 1442695040888963407L;
        double phi = (h & Long.MAX_VALUE) / (double) Long.MAX_VALUE;
        double target = phi * (double) total;

        long cumulative = 0;
        for (int i = 0; i < sorted.size(); i++) {
            cumulative += weights[i];
            if ((double) cumulative >= target) return sorted.get(i);
        }
        return sorted.get((int)(Math.abs(view) % sorted.size()));
    }

    // ── FEATURE 2: Reputation management ────────────────────────────────────

    /**
     * Adjusts a validator's reputation by {@code delta}, clamped to ≥ {@value #REP_MIN}.
     *
     * <p><b>Contract (enforced as of E1):</b> this must only be called for
     * CONSENSUS-ORDERED events — a committed block, a quorum-certified view change, or
     * message-evidenced slashing — so that every correct node applies the same multiset of
     * updates. Calling it from a node-local trigger (e.g. a wall-clock timeout that has not
     * yet been certified by 2f+1) breaks Leader Agreement; see
     * docs/formal/pbft_leader_model.md Theorem 1.
     *
     * <p>[E4] Because the clamp {@code max(REP_MIN, ·)} is order-dependent (unlike the
     * addition itself, which E3 made associative), correct nodes must also apply these
     * events in the canonical committed order. That holds automatically once every input is
     * chain-derived.
     */
    public void updateReputation(String validatorId, double delta) {
        if (!validators.containsKey(validatorId)) return;
        long scaledDelta = toScaled(delta);
        validatorReputation.merge(validatorId, scaledDelta,
                (old, d) -> Math.max(REP_MIN_SCALED, old + d));
        // Threat model is fed for MONITORING only — it no longer influences selectLeader (E2).
        com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().recordActivity(validatorId, delta, viewNumber);
        log.debug("[PBFT] Reputation {} → {}", validatorId,
                String.format("%.4f", fromScaled(validatorReputation.get(validatorId))));
    }

    /** Read-only view of all validator reputation scores (converted from fixed-point). */
    public Map<String, Double> getReputationMap() {
        Map<String, Double> out = new LinkedHashMap<>();
        validatorReputation.forEach((k, v) -> out.put(k, fromScaled(v)));
        return Collections.unmodifiableMap(out);
    }

    // ── Vote collection ──────────────────────────────────────────────────────

    /** Adds a PREPARE vote from a validator. */
    public void addPrepareVote(long seq, String blockHash, String validatorId, byte[] signature) {
        ensureKnownValidator(validatorId);

        // [FEATURE B1] Warn on high threat score
        if (com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().predictThreatScore(validatorId) > 0.5) {
            log.warn("[AI-THREAT] High threat score validator {} sent PREPARE vote for seq {}", validatorId, seq);
        }

        PBFTMessage msg = new PBFTMessage(Phase.PREPARE, viewNumber, seq, blockHash, validatorId);
        msg.signature = signature;
        if (!msg.verify(validators.get(validatorId))) {
            com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().recordActivity(validatorId, -0.15, viewNumber);
            throw new SecurityException("Invalid PREPARE signature from " + validatorId);
        }

        Map<String, PBFTMessage> phaseVotes = messageLog.computeIfAbsent(seq, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(Phase.PREPARE, k -> new ConcurrentHashMap<>());
        PBFTMessage existing = phaseVotes.get(validatorId);
        if (existing != null) {
            com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().recordActivity(validatorId, -0.05, viewNumber);
        }

        phaseVotes.compute(validatorId, (id, current) -> {
                      if (existing != null && existing.viewNumber == msg.viewNumber && !existing.blockHash.equals(blockHash)) {
                          log.error("[PBFT] SLASH: double-PREPARE from {} ({} vs {})",
                                  id, existing.blockHash, blockHash);
                          reportEquivocation(existing, msg);
                          slashedValidators.add(id);
                          updateReputation(id, REP_INVALID_BLOCK);
                      }
                      return msg;
                  });
        log.debug("[PBFT] PREPARE from {} seq={}", validatorId, seq);
    }

    /** Adds a COMMIT vote from a validator. */
    public void addCommitVote(long seq, String blockHash, String validatorId, byte[] signature) {
        ensureKnownValidator(validatorId);

        // [FEATURE B1] Warn on high threat score
        if (com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().predictThreatScore(validatorId) > 0.5) {
            log.warn("[AI-THREAT] High threat score validator {} sent COMMIT vote for seq {}", validatorId, seq);
        }

        PBFTMessage msg = new PBFTMessage(Phase.COMMIT, viewNumber, seq, blockHash, validatorId);
        msg.signature = signature;
        if (!msg.verify(validators.get(validatorId))) {
            com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().recordActivity(validatorId, -0.15, viewNumber);
            throw new SecurityException("Invalid COMMIT signature from " + validatorId);
        }

        Map<String, PBFTMessage> phaseVotes = messageLog.computeIfAbsent(seq, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(Phase.COMMIT, k -> new ConcurrentHashMap<>());
        PBFTMessage existing = phaseVotes.get(validatorId);
        if (existing != null) {
            com.hybrid.blockchain.ai.PredictiveThreatScorer.getInstance().recordActivity(validatorId, -0.05, viewNumber);
        }

        phaseVotes.compute(validatorId, (id, current) -> {
                      if (existing != null && existing.viewNumber == msg.viewNumber && !existing.blockHash.equals(blockHash)) {
                          log.error("[PBFT] SLASH: double-COMMIT from {} ({} vs {})",
                                  id, existing.blockHash, blockHash);
                          reportEquivocation(existing, msg);
                          slashedValidators.add(id);
                          updateReputation(id, REP_INVALID_BLOCK);
                      }
                      return msg;
                  });

        // Track fast-path committee commits
        if (asyncEnabled) {
            AsyncCommitPath path = asyncPaths.computeIfAbsent(seq, k -> createAsyncCommitPath(0)); // Default minRep=0 for tracking
            if (path.recordCommit(validatorId)) {
                lastFastPathSeq = seq;
            }
        }
        log.debug("[PBFT] COMMIT from {} seq={}", validatorId, seq);
    }

    /** Returns true if the optimistic fast-path is active for this sequence. */
    public boolean isFastPathActive(long seq) {
        return asyncEnabled && lastFastPathSeq == seq - 1;
    }

    /**
     * Checks whether phase has reached 2f+1 votes <b>for a single block hash</b>.
     *
     * <p>[S7-01] Previously this counted distinct <i>voters</i> irrespective of which block
     * they voted for, so 2f+1 validators voting for 2f+1 DIFFERENT blocks satisfied it.
     * That is not a commit certificate: classical PBFT requires 2f+1 messages for the same
     * (view, seq, digest). Because {@code PeerNode.applyBlockAtSequence} applies the locally
     * pending block once this returns true, the old behaviour could apply a block that no
     * quorum had actually voted for. Found by randomized fault injection
     * (ByzantineFaultInjectionTest).
     */
    public boolean hasQuorum(long sequenceNumber, Phase phase) {
        return getQuorumHash(sequenceNumber, phase) != null;
    }

    /**
     * Returns the block hash that has reached 2f+1 votes in this phase, or {@code null}
     * if no single hash has. Callers that act on a quorum MUST check the block they are
     * about to apply matches this hash.
     */
    public String getQuorumHash(long sequenceNumber, Phase phase) {
        Map<Phase, Map<String, PBFTMessage>> seqLog = messageLog.get(sequenceNumber);
        if (seqLog == null) return null;
        Map<String, PBFTMessage> votes = seqLog.get(phase);
        if (votes == null) return null;

        Map<String, Integer> byHash = new HashMap<>();
        for (PBFTMessage m : votes.values()) {
            if (m != null && m.blockHash != null) {
                byHash.merge(m.blockHash, 1, Integer::sum);
            }
        }
        int needed = quorumSize();   // [HC-002]
        for (Map.Entry<String, Integer> e : byHash.entrySet()) {
            if (e.getValue() >= needed) return e.getKey();
        }
        return null;
    }

    // ── Bug 1: markCommitted ─────────────────────────────────────────────────

    /**
     * Called by {@code PeerNode.applyBlockAtSequence()} BEFORE applying the block
     * to the chain, so that {@link #verifyBlock} returns {@code true} when
     * {@code Blockchain.applyBlockInternal()} calls it.
     *
     * <p>Also credits the block proposer (+{@value #REP_BLOCK_PROPOSED} reputation).</p>
     *
     * @param blockHash  the committed block's hash
     * @param seq        its sequence (index) number
     * @param proposerId the validator that proposed the block
     */
    public void markCommitted(String blockHash, long seq, String proposerId) {
        committedBlocks.add(blockHash);
        lastCommittedSeq = Math.max(lastCommittedSeq, seq);
        justifiedHashes.remove(seq);
        updateReputation(proposerId, REP_BLOCK_PROPOSED);
        log.info("[PBFT] Block {} committed at seq {} (proposer={})", blockHash, seq, proposerId);
        resetTimer();
    }

    // ── View change ──────────────────────────────────────────────────────────

    public void addViewChangeVote(long newView, long lastSeq, String validatorId, byte[] signature) {
        ensureKnownValidator(validatorId);

        PBFTMessage msg = new PBFTMessage(Phase.VIEW_CHANGE, newView, lastSeq, "VIEW_CHANGE_PROOF", validatorId); // [FIX A4]
        msg.signature = signature;
        if (!msg.verify(validators.get(validatorId)))
            throw new SecurityException("Invalid VIEW_CHANGE signature from " + validatorId);

        viewChangeLog.computeIfAbsent(newView, k -> new ConcurrentHashMap<>())
                     .put(validatorId, msg);
        log.info("[PBFT] VIEW_CHANGE for view {} from {}", newView, validatorId);

        if (viewChangeLog.get(newView).size() >= quorumSize())   // [HC-002]
            processViewChange(newView);
    }

    private void processViewChange(long newView) {
        if (viewNumber >= newView) return;
        // [E1] Penalise the leader of the view being abandoned HERE, at the 2f+1
        // quorum boundary — this is a consensus-ordered event, so every correct node
        // that reaches it applies the identical update. Applying it in
        // triggerViewChange() (on the local timer) is what broke Leader Agreement:
        // see docs/formal/pbft_leader_model.md Theorem 1 and §9 item E1.
        String faultyLeader = selectLeader(viewNumber);
        if (faultyLeader != null) {
            updateReputation(faultyLeader, REP_MISSED_SLOT);
            log.info("[PBFT] View-change quorum for {} — penalised abandoned leader {}", newView, faultyLeader);
        }
        String nextLeader = selectLeader(newView);
        log.info("[PBFT] View {} quorum reached. Next leader: {}", newView, nextLeader);
        this.viewNumber = newView;
        // [O-1] the new leader tells everyone which block (if any) must be re-proposed
        if (localValidatorId != null && localValidatorId.equals(nextLeader) && localPrivateKey != null) {
            NewViewProof nv = buildNewView(newView);
            if (nv != null && messenger != null) messenger.broadcastNewViewProof(nv);
        }
    }

    public void triggerViewChange() {
        long nextView = viewNumber + 1;
        // [E1] NO reputation penalty here. A local timer expiry is a locally-observed
        // event (E_ℓ): correct nodes time out at different moments under partial
        // synchrony, so penalising here makes their reputation maps — and therefore
        // their elected leaders — diverge. The penalty is applied in processViewChange()
        // once 2f+1 VIEW_CHANGE messages certify the change.
        log.info("[PBFT] Triggering view change {} → {} (penalty deferred to quorum)", viewNumber, nextView);

        PBFTMessage vcMsg = new PBFTMessage(Phase.VIEW_CHANGE, nextView, lastCommittedSeq, "VIEW_CHANGE_PROOF", localValidatorId); // [FIX A4]
        if (localPrivateKey != null) {
            vcMsg.sign(localPrivateKey);
            ViewChangeProof vc = new ViewChangeProof(nextView, lastCommittedSeq, localValidatorId,
                    HexUtils.encode(vcMsg.signature), localPreparedProof(lastCommittedSeq + 1));
            viewChangeProofs.computeIfAbsent(nextView, k -> new ConcurrentHashMap<>()).put(localValidatorId, vc);
            if (messenger != null)
                messenger.broadcastViewChangeProof(vc);
        }
        viewChangeLog.computeIfAbsent(nextView, k -> new ConcurrentHashMap<>())
                     .put(localValidatorId, vcMsg);

        // Quorum can already be satisfied if remote VIEW_CHANGE votes arrived first.
        if (viewChangeLog.get(nextView).size() >= quorumSize()) {   // [HC-002]
            processViewChange(nextView);
        }
    }

    // ── [O-1] NEW_VIEW: carrying prepared blocks across a view change ─────────
    //
    // Without this, a leader that failed after some honest nodes had sent COMMIT for block A
    // could stall the chain: those nodes never COMMIT a different block at that height, and the
    // next leader did not know A existed. Standard PBFT fixes this with NEW_VIEW:
    //  1. every VIEW_CHANGE carries the block its sender saw PREPARED at the next height, with
    //     the 2f+1 PREPARE signatures that prove it (a PreparedProof);
    //  2. the new leader, holding 2f+1 VIEW_CHANGEs, re-proposes the prepared block with the
    //     highest view (or nothing, if no valid proof exists) in a signed NEW_VIEW;
    //  3. every node re-derives that choice from the same VIEW_CHANGEs and rejects a NEW_VIEW
    //     that proposes anything else; the chosen hash becomes "justified" for that height, which
    //     is what allows a node to COMMIT again in the new view.
    // Safety: if A was committed anywhere, 2f+1 validators prepared it, so every 2f+1 VIEW_CHANGE
    // set contains at least one honest proof of A and the rule re-selects A.

    /** One validator's signed vote for (view, seq, hash): used for PREPARE proofs. */
    public static class PreparedProof {
        public long seq;
        public String blockHash;
        public List<Block.CommitSignature> prepares;   // validatorId, view, PREPARE signature
        public Block block;

        public PreparedProof() {
        }

        public PreparedProof(long seq, String blockHash, List<Block.CommitSignature> prepares, Block block) {
            this.seq = seq;
            this.blockHash = blockHash;
            this.prepares = prepares;
            this.block = block;
        }

        /** Highest view among the prepare signatures (the view the block was prepared in). */
        public long view() {
            long v = -1;
            if (prepares != null) for (Block.CommitSignature p : prepares) if (p != null) v = Math.max(v, p.view);
            return v;
        }
    }

    public static class ViewChangeProof {
        public long newView;
        public long lastSeq;
        public String validatorId;
        public String signature;        // hex, over PBFTMessage(VIEW_CHANGE, newView, lastSeq, "VIEW_CHANGE_PROOF", id)
        public PreparedProof prepared;  // may be null

        public ViewChangeProof() {
        }

        public ViewChangeProof(long newView, long lastSeq, String validatorId, String signature, PreparedProof prepared) {
            this.newView = newView;
            this.lastSeq = lastSeq;
            this.validatorId = validatorId;
            this.signature = signature;
            this.prepared = prepared;
        }
    }

    public static class NewViewProof {
        public long newView;
        public String leaderId;
        public List<ViewChangeProof> viewChanges;
        public Block block;             // the re-proposed block, or null when nothing was prepared
        public String signature;        // hex, over PBFTMessage(NEW_VIEW, newView, seq, hashOrEmpty, leaderId)

        public NewViewProof() {
        }
    }

    /** View-change messages with their proofs: newView -> validatorId -> message. */
    private final Map<Long, Map<String, ViewChangeProof>> viewChangeProofs = new ConcurrentHashMap<>();
    /** Heights whose block was re-proposed by a valid NEW_VIEW: seq -> hash. */
    private final Map<Long, String> justifiedHashes = new ConcurrentHashMap<>();
    /** Views for which a NEW_VIEW was already accepted. */
    private final Set<Long> acceptedNewViews = ConcurrentHashMap.newKeySet();

    /** The PREPARE votes this node holds for (seq, blockHash). */
    public List<Block.CommitSignature> buildPrepareCertificate(long seq, String blockHash) {
        List<Block.CommitSignature> cert = new ArrayList<>();
        Map<Phase, Map<String, PBFTMessage>> seqLog = messageLog.get(seq);
        if (seqLog == null || seqLog.get(Phase.PREPARE) == null) return cert;
        for (PBFTMessage m : seqLog.get(Phase.PREPARE).values()) {
            if (m != null && blockHash.equals(m.blockHash) && m.signature != null) {
                cert.add(new Block.CommitSignature(m.validatorId, m.viewNumber,
                        com.hybrid.blockchain.HexUtils.encode(m.signature)));
            }
        }
        return cert;
    }

    /** This node's proof for the block prepared at {@code seq}, or null. */
    public PreparedProof localPreparedProof(long seq) {
        String hash = getQuorumHash(seq, Phase.PREPARE);
        Block block = pendingBlocks.get(seq);
        if (hash == null || block == null || !hash.equals(block.getHash())) return null;
        return new PreparedProof(seq, hash, buildPrepareCertificate(seq, hash), block);
    }

    /** True iff the block is well-formed, signed by its proposer and carries 2f+1 valid PREPAREs. */
    public boolean verifyPreparedProof(PreparedProof p) {
        if (p == null || p.block == null || p.blockHash == null || p.prepares == null) return false;
        if (p.prepares.size() > 4 * validators.size()) return false;
        if (p.block.getIndex() != p.seq || !p.blockHash.equals(p.block.getHash())
                || !p.blockHash.equals(p.block.calculateHash())) return false;
        if (!verifyProposerSignature(p.block, null)) return false;
        Set<String> valid = new HashSet<>();
        for (Block.CommitSignature cs : p.prepares) {
            if (cs == null || cs.validatorId == null || cs.signature == null || valid.contains(cs.validatorId)) continue;
            byte[] key = validators.get(cs.validatorId);
            if (key == null) continue;
            PBFTMessage m = new PBFTMessage(Phase.PREPARE, cs.view, p.seq, p.blockHash, cs.validatorId);
            try {
                m.signature = com.hybrid.blockchain.HexUtils.decode(cs.signature);
            } catch (Exception e) {
                continue;
            }
            if (m.verify(key)) valid.add(cs.validatorId);
        }
        return valid.size() >= quorumSize();
    }

    private boolean verifyViewChangeSignature(ViewChangeProof vc) {
        if (vc == null || vc.validatorId == null || vc.signature == null) return false;
        byte[] key = validators.get(vc.validatorId);
        if (key == null) return false;
        PBFTMessage m = new PBFTMessage(Phase.VIEW_CHANGE, vc.newView, vc.lastSeq, "VIEW_CHANGE_PROOF", vc.validatorId);
        try {
            m.signature = com.hybrid.blockchain.HexUtils.decode(vc.signature);
        } catch (Exception e) {
            return false;
        }
        return m.verify(key);
    }

    /**
     * Records a VIEW_CHANGE received from the network. An attached prepared proof that does not
     * verify is dropped (the vote still counts); a bad signature rejects the message.
     */
    public void addViewChange(ViewChangeProof vc) {
        if (!verifyViewChangeSignature(vc))
            throw new SecurityException("Invalid VIEW_CHANGE signature from " + (vc == null ? null : vc.validatorId));
        if (vc.prepared != null && !verifyPreparedProof(vc.prepared)) {
            log.warn("[PBFT] VIEW_CHANGE from {} carried an invalid prepared proof; ignoring the proof", vc.validatorId);
            vc.prepared = null;
        }
        viewChangeProofs.computeIfAbsent(vc.newView, k -> new ConcurrentHashMap<>()).put(vc.validatorId, vc);
        addViewChangeVote(vc.newView, vc.lastSeq, vc.validatorId, com.hybrid.blockchain.HexUtils.decode(vc.signature));
    }

    /** The height a new view resumes at, from a set of view changes. */
    private static long resumeSeq(Collection<ViewChangeProof> vcs) {
        long max = 0;
        for (ViewChangeProof vc : vcs) max = Math.max(max, vc.lastSeq);
        return max + 1;
    }

    /**
     * Deterministic choice: among valid proofs for the resume height, the one prepared in the
     * highest view (ties broken by hash so every node picks the same one); null if none.
     */
    private PreparedProof choosePrepared(Collection<ViewChangeProof> vcs) {
        long seq = resumeSeq(vcs);
        PreparedProof best = null;
        for (ViewChangeProof vc : vcs) {
            PreparedProof p = vc.prepared;
            if (p == null || p.seq != seq || !verifyPreparedProof(p)) continue;
            if (best == null || p.view() > best.view()
                    || (p.view() == best.view() && p.blockHash.compareTo(best.blockHash) < 0)) {
                best = p;
            }
        }
        return best;
    }

    private PBFTMessage newViewMessage(long newView, long seq, String hash, String leaderId) {
        return new PBFTMessage(Phase.NEW_VIEW, newView, seq, hash == null ? "" : hash, leaderId);
    }

    /** Built by the leader of {@code newView} from the view changes it holds; null without a quorum. */
    public NewViewProof buildNewView(long newView) {
        Map<String, ViewChangeProof> held = viewChangeProofs.get(newView);
        if (held == null) return null;
        List<ViewChangeProof> valid = new ArrayList<>();
        for (ViewChangeProof vc : held.values()) if (vc.newView == newView && verifyViewChangeSignature(vc)) valid.add(vc);
        if (valid.size() < quorumSize()) return null;
        PreparedProof chosen = choosePrepared(valid);
        NewViewProof nv = new NewViewProof();
        nv.newView = newView;
        nv.leaderId = localValidatorId;
        nv.viewChanges = valid;
        nv.block = chosen == null ? null : chosen.block;
        PBFTMessage m = newViewMessage(newView, resumeSeq(valid), chosen == null ? null : chosen.blockHash, localValidatorId);
        m.sign(localPrivateKey);
        nv.signature = com.hybrid.blockchain.HexUtils.encode(m.signature);
        return nv;
    }

    /**
     * Verifies a NEW_VIEW and, if valid, moves to that view. Checks: sender is the leader of the
     * view, 2f+1 distinct valid VIEW_CHANGEs for it, the leader's signature, and that the
     * re-proposed block is exactly the one the choice rule gives (or none when it gives none).
     * On success the chosen block becomes the pending, justified block for its height.
     *
     * @return the re-proposed block (to PREPARE in the new view), or null when the leader is free
     *         to propose a new one
     * @throws SecurityException if the NEW_VIEW is not valid
     */
    public Block acceptNewView(NewViewProof nv) {
        if (nv == null || nv.leaderId == null || nv.viewChanges == null)
            throw new SecurityException("Malformed NEW_VIEW");
        if (nv.newView < viewNumber)
            throw new SecurityException("Stale NEW_VIEW for view " + nv.newView);
        if (acceptedNewViews.contains(nv.newView)) return null;   // duplicate delivery (gossip)
        Map<String, ViewChangeProof> distinct = new HashMap<>();
        for (ViewChangeProof vc : nv.viewChanges) {
            if (vc == null || vc.newView != nv.newView || distinct.containsKey(vc.validatorId)) continue;
            if (!verifyViewChangeSignature(vc)) continue;
            if (vc.prepared != null && !verifyPreparedProof(vc.prepared)) vc.prepared = null;
            distinct.put(vc.validatorId, vc);
        }
        if (distinct.size() < quorumSize())
            throw new SecurityException("NEW_VIEW carries " + distinct.size() + " valid view changes, need " + quorumSize());
        // The 2f+1 VIEW_CHANGEs certify the view change itself, so apply it first: it adjusts the
        // abandoned leader's reputation, which the leader election for the new view depends on.
        if (viewNumber < nv.newView) processViewChange(nv.newView);
        if (!nv.leaderId.equals(selectLeader(nv.newView)))
            throw new SecurityException("NEW_VIEW for view " + nv.newView + " not from its leader");
        long seq = resumeSeq(distinct.values());
        PreparedProof chosen = choosePrepared(distinct.values());
        String chosenHash = chosen == null ? null : chosen.blockHash;
        PBFTMessage m = newViewMessage(nv.newView, seq, chosenHash, nv.leaderId);
        try {
            m.signature = com.hybrid.blockchain.HexUtils.decode(nv.signature);
        } catch (Exception e) {
            throw new SecurityException("NEW_VIEW signature malformed");
        }
        if (!m.verify(validators.get(nv.leaderId)))
            throw new SecurityException("Invalid NEW_VIEW signature from " + nv.leaderId);
        if (chosen == null) {
            if (nv.block != null) throw new SecurityException("NEW_VIEW proposes a block although none was prepared");
        } else if (nv.block == null || !chosenHash.equals(nv.block.getHash()) || !chosenHash.equals(nv.block.calculateHash())) {
            throw new SecurityException("NEW_VIEW does not re-propose the prepared block " + chosenHash);
        }
        acceptedNewViews.add(nv.newView);
        if (chosen == null) return null;
        justifiedHashes.put(seq, chosenHash);
        pendingBlocks.put(seq, chosen.block);
        log.info("[PBFT] NEW_VIEW {} accepted: re-proposing block {} at seq {}", nv.newView, chosenHash, seq);
        return chosen.block;
    }

    /** The block hash a valid NEW_VIEW re-proposed for {@code seq}, or null. */
    public String getJustifiedHash(long seq) {
        return justifiedHashes.get(seq);
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public String getCurrentLeader()   { return selectLeader(viewNumber); }
    public int    getValidatorCount()  { return validators.size(); }
    public int    getMaxFaultyNodes()  { return maxFaulty(validators.size()); }

    /** Largest f with n >= 3f + 1. */
    public static int maxFaulty(int n) { return (n - 1) / 3; }

    /**
     * [HC-002] Quorum size for n validators: ceil((n + f + 1) / 2), f = floor((n - 1) / 3).
     *
     * <p>Two quorums of size q overlap in at least 2q - n validators; safety needs that overlap to
     * contain an honest one, i.e. 2q - n >= f + 1. The previous fixed 2f + 1 equals this bound only
     * when n = 3f + 1: for n = 5 (f = 1) it gave 3, and two 3-sets overlap in a single (possibly
     * faulty) node, so two honest replicas could commit different blocks. Computed from the CURRENT
     * validator set, because add/removeValidator change n (f used to be fixed at construction).
     */
    public static int quorumSize(int n) {
        int f = maxFaulty(n);
        return (n + f + 2) / 2;   // integer ceil((n + f + 1) / 2)
    }

    public int quorumSize() { return quorumSize(validators.size()); }
    public long   getViewNumber()      { return viewNumber; }
    public PBFTMessenger getMessenger(){ return messenger; }

    public Set<String> getSlashedValidators()      { return Collections.unmodifiableSet(slashedValidators); }
    public void        clearSlashedValidator(String id) { slashedValidators.remove(id); }
    public int         getSlashCount(String id) { return slashedValidators.contains(id) ? 1 : 0; }

    public void setPendingBlock(long seq, Block b)  { pendingBlocks.put(seq, b); }
    public Block getPendingBlock(long seq)           { return pendingBlocks.get(seq); }
    public Block removePendingBlock(long seq)        { return pendingBlocks.remove(seq); }

    // Test compatibility stubs
    public boolean onPrepare(String hash, String valId, byte[] sig) { return false; }
    public boolean onCommit(String hash, String valId, byte[] sig)  { return false; }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("validators",       validators.size());
        stats.put("maxFaultyNodes",   getMaxFaultyNodes());
        stats.put("quorumSize",       quorumSize());
        stats.put("viewNumber",       viewNumber);
        stats.put("currentLeader",    getCurrentLeader());
        stats.put("committedBlocks",  committedBlocks.size());
        stats.put("reputation",       new HashMap<>(getReputationMap()));
        return stats;
    }

    @Override
    public void shutdown() {
        if (currentTimerTask != null) currentTimerTask.cancel(true);
        timer.shutdownNow();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void ensureKnownValidator(String id) {
        if (!validators.containsKey(id))
            throw new SecurityException("Unknown validator: " + id);
        if (jailedValidators.contains(id))
            throw new SecurityException("Validator is jailed: " + id);   // [O-5]
    }

    // ── [O-5] on-chain equivocation evidence ─────────────────────────────────

    /** Two votes of one validator for different blocks at the same (phase, view, seq). */
    public static class EquivocationEvidence {
        public String phase;          // PREPARE or COMMIT
        public long view;
        public long seq;
        public String validatorId;
        public String hashA;
        public String signatureA;     // hex
        public String hashB;
        public String signatureB;     // hex

        public EquivocationEvidence() {
        }
    }

    /** Called with evidence whenever this node observes a double vote (wired to submit a tx). */
    public void setEvidenceListener(java.util.function.Consumer<EquivocationEvidence> listener) {
        this.evidenceListener = listener;
    }

    private void reportEquivocation(PBFTMessage a, PBFTMessage b) {
        java.util.function.Consumer<EquivocationEvidence> l = evidenceListener;
        if (l == null || a.signature == null || b.signature == null) return;
        EquivocationEvidence ev = new EquivocationEvidence();
        ev.phase = a.phase.name();
        ev.view = a.viewNumber;
        ev.seq = a.sequenceNumber;
        ev.validatorId = a.validatorId;
        ev.hashA = a.blockHash;
        ev.signatureA = HexUtils.encode(a.signature);
        ev.hashB = b.blockHash;
        ev.signatureB = HexUtils.encode(b.signature);
        try {
            l.accept(ev);
        } catch (Exception e) {
            log.warn("[PBFT] Could not submit equivocation evidence: {}", e.getMessage());
        }
    }

    /**
     * True iff the evidence proves equivocation: a known validator signed two PREPAREs (or two
     * COMMITs) for DIFFERENT hashes at the same view and sequence. Depends only on the validator
     * set, so every node reaches the same verdict.
     */
    public boolean verifyEvidence(EquivocationEvidence ev) {
        if (ev == null || ev.validatorId == null || ev.hashA == null || ev.hashB == null
                || ev.signatureA == null || ev.signatureB == null || ev.hashA.equals(ev.hashB)) return false;
        Phase phase;
        try {
            phase = Phase.valueOf(ev.phase);
        } catch (Exception e) {
            return false;
        }
        if (phase != Phase.PREPARE && phase != Phase.COMMIT) return false;
        byte[] key = validators.get(ev.validatorId);
        if (key == null) return false;
        try {
            PBFTMessage a = new PBFTMessage(phase, ev.view, ev.seq, ev.hashA, ev.validatorId);
            a.signature = HexUtils.decode(ev.signatureA);
            PBFTMessage b = new PBFTMessage(phase, ev.view, ev.seq, ev.hashB, ev.validatorId);
            b.signature = HexUtils.decode(ev.signatureB);
            return a.verify(key) && b.verify(key);
        } catch (Exception e) {
            return false;
        }
    }

    /** Mirror of the on-chain jail set (called after every accepted block and at start-up). */
    public void setJailedValidators(Collection<String> jailed) {
        jailedValidators.retainAll(jailed);
        jailedValidators.addAll(jailed);
    }

    public Set<String> getJailedValidators() {
        return Collections.unmodifiableSet(jailedValidators);
    }
}
