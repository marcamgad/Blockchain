package com.hybrid.blockchain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PoAConsensus implements Consensus {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PoAConsensus.class);

    @Override
    public void shutdown() {} // No background resources to clean up in PoA


    private static final byte[] DOMAIN_PREFIX = "BLOCK\0".getBytes(StandardCharsets.UTF_8);
    // Thread-safe: this class is invoked from network/consensus threads. A
    // CopyOnWriteArrayList keeps reads (isValidator/selectLeader — the hot path)
    // lock-free while add/remove happen rarely, and rules out the
    // ConcurrentModificationException/visibility races a plain ArrayList allowed.
    private final List<Validator> validators;
    private final Map<Long, Map<String, String>> signedHashesByHeight;
    private final Set<String> slashedValidators;
    private final Map<String, Integer> slashCounts;

    public PoAConsensus(List<Validator> validators) {
        this.validators = new CopyOnWriteArrayList<>(validators);
        this.signedHashesByHeight = new ConcurrentHashMap<>();
        this.slashedValidators = ConcurrentHashMap.newKeySet();
        this.slashCounts = new ConcurrentHashMap<>();
    }

    public boolean isValidator(String validatorId) {
        if (slashedValidators.contains(validatorId)) return false;
        return validators.stream().anyMatch(v -> v.getId().equals(validatorId));
    }

    private byte[] signingPayload(Block block) {
        // Bind the block content AND its declared producer into the signed message,
        // so provenance is cryptographically committed even though the block *hash*
        // (serializeCanonical) is content-only. Without this, a relayed block could be
        // re-attributed to a different validator id while its signature still verified.
        byte[] body = block.serializeCanonical();
        byte[] vid = block.getValidatorId() == null
                ? new byte[0]
                : block.getValidatorId().getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[DOMAIN_PREFIX.length + vid.length + 1 + body.length];
        int off = 0;
        System.arraycopy(DOMAIN_PREFIX, 0, payload, off, DOMAIN_PREFIX.length);
        off += DOMAIN_PREFIX.length;
        System.arraycopy(vid, 0, payload, off, vid.length);
        off += vid.length;
        payload[off++] = 0; // separator between id and body
        System.arraycopy(body, 0, payload, off, body.length);
        return Crypto.hash(payload);
    }

    public void signBlock(Block block, Validator validator, BigInteger privateKey) throws Exception {
        if (!isValidator(validator.getId()))
            throw new Exception("Validator not authorized");

        // Set the producer id before computing the payload so the signature commits to it.
        block.setValidatorId(validator.getId());
        byte[] msg = signingPayload(block);
        byte[] signatureBytes = Crypto.sign(msg, privateKey);
        block.setSignature(signatureBytes);
    }

    public boolean verifyBlock(Block block, Validator validator) throws Exception {
        if (!isValidator(validator.getId()))
            return false;
        if (block.getSignature() == null)
            return false;

        byte[] msg = signingPayload(block);
        boolean verified = Crypto.verify(msg, block.getSignature(), validator.getPublicKey());
        if (!verified) {
            return false;
        }

        String validatorId = validator.getId();
        long height = block.getIndex();
        String blockHash = block.getHash();

        boolean[] equivocated = {false};
        signedHashesByHeight
                .computeIfAbsent(height, k -> new ConcurrentHashMap<>())
                .compute(validatorId, (id, existingHash) -> {
                    if (existingHash != null && !existingHash.equals(blockHash)) {
                        slashedValidators.add(id);
                        slashCounts.merge(id, 1, Integer::sum);
                        equivocated[0] = true;
                        return existingHash; // keep the first block seen at this height
                    }
                    return blockHash;
                });

        // A byzantine validator that just equivocated must have THIS conflicting block
        // rejected, not merely recorded for future slashing — otherwise the second
        // (still cryptographically valid) block would be accepted by the caller.
        return !equivocated[0];
    }

    public List<Validator> getValidators() {
        return this.validators;
    }
    /**
     * The validator that is scheduled to produce the block at {@code height} under the
     * deterministic round-robin over the current active (non-slashed) validator set.
     * Returns {@code null} when there is no eligible validator.
     */
    public String expectedLeaderId(long height) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (Validator v : validators) {
            if (isValidator(v.getId())) candidates.add(v.getId());
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(String::compareTo);
        return candidates.get((int) Math.floorMod(height, candidates.size()));
    }

    /** True when {@code block} was produced by the validator scheduled for its height. */
    public boolean isScheduledLeader(Block block) {
        String expected = expectedLeaderId(block.getIndex());
        return expected != null && expected.equals(block.getValidatorId());
    }

    @Override
    public boolean validateBlock(Block block, List<Block> chain) {
        String vid = block.getValidatorId();
        if (vid == null) return false;
        // Enforce the round-robin schedule: only the validator whose turn it is may
        // produce the block at this height. Without this check the schedule is
        // decorative and any authorized validator can produce a block out of turn,
        // defeating PoA and inviting constant forking.
        if (!isScheduledLeader(block)) return false;
        return validators.stream().filter(v -> v.getId().equals(vid)).findFirst()
            .map(v -> {
                try {
                    return verifyBlock(block, v);
                } catch (Exception e) {
                    return false;
                }
            })
            .orElse(false);
    }

    @Override
    public Block selectLeader(List<String> authorizeNodes, long round) {
        List<String> candidates = new java.util.ArrayList<>();
        if (authorizeNodes != null && !authorizeNodes.isEmpty()) {
            for (String nodeId : authorizeNodes) {
                if (isValidator(nodeId)) {
                    candidates.add(nodeId);
                }
            }
        }

        if (candidates.isEmpty()) {
            for (Validator v : validators) {
                if (isValidator(v.getId())) {
                    candidates.add(v.getId());
                }
            }
        }

        if (candidates.isEmpty()) {
            // No eligible validator (all removed or slashed): surface this loudly.
            // The "system-default" descriptor never matches a real validator, so block
            // production will stall — an operator needs a clear signal why.
            log.error("[PoAConsensus] No eligible validators for round {} (all removed or slashed)"
                    + " — block production is stalled.", round);
            Block fallback = new Block(0, 0L, java.util.Collections.emptyList(), "", 0, "");
            fallback.setValidatorId("system-default");
            return fallback;
        }

        candidates.sort(String::compareTo);
        String leaderId = candidates.get((int) Math.floorMod(round, candidates.size()));

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
    public void addValidator(String id, byte[] publicKey) {
        Validator newValidator = new Validator(id, publicKey);
        if (!validators.stream().anyMatch(v -> v.getId().equals(id))) {
            validators.add(newValidator);
        }
    }

    @Override
    public java.util.Set<String> getSlashedValidators() {
        return java.util.Collections.unmodifiableSet(slashedValidators);
    }

    @Override
    public void clearSlashedValidator(String validatorId) {
        slashedValidators.remove(validatorId);
    }

    @Override
    public int getSlashCount(String validatorId) {
        return slashCounts.getOrDefault(validatorId, 0);
    }

    @Override
    public void removeValidator(String id) {
        validators.removeIf(v -> v.getId().equals(id));
        signedHashesByHeight.values().forEach(m -> m.remove(id));
        slashedValidators.remove(id);
        slashCounts.remove(id);
    }
}
