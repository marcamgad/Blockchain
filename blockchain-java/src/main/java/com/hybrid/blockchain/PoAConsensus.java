package com.hybrid.blockchain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PoAConsensus implements Consensus {
    @Override
    public void shutdown() {} // No background resources to clean up in PoA


    private static final byte[] DOMAIN_PREFIX = "BLOCK\0".getBytes(StandardCharsets.UTF_8);
    private final List<Validator> validators;
    private final Map<Long, Map<String, String>> signedHashesByHeight;
    private final Set<String> slashedValidators;

    public PoAConsensus(List<Validator> validators) {
        this.validators = validators;
        this.signedHashesByHeight = new ConcurrentHashMap<>();
        this.slashedValidators = ConcurrentHashMap.newKeySet();
    }

    public boolean isValidator(String validatorId) {
        if (slashedValidators.contains(validatorId)) return false;
        return validators.stream().anyMatch(v -> v.getId().equals(validatorId));
    }

    private byte[] signingPayload(Block block) {
        byte[] body = block.serializeCanonical();
        byte[] payload = new byte[DOMAIN_PREFIX.length + body.length];
        System.arraycopy(DOMAIN_PREFIX, 0, payload, 0, DOMAIN_PREFIX.length);
        System.arraycopy(body, 0, payload, DOMAIN_PREFIX.length, body.length);
        return Crypto.hash(payload);
    }

    public void signBlock(Block block, Validator validator, BigInteger privateKey) throws Exception {
        if (!isValidator(validator.getId()))
            throw new Exception("Validator not authorized");

        byte[] msg = signingPayload(block);
        byte[] signatureBytes = Crypto.sign(msg, privateKey);

        block.setValidatorId(validator.getId());
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

        signedHashesByHeight
                .computeIfAbsent(height, k -> new ConcurrentHashMap<>())
                .compute(validatorId, (id, existingHash) -> {
                    if (existingHash != null && !existingHash.equals(blockHash)) {
                        slashedValidators.add(id);
                    }
                    return blockHash;
                });

        return true;
    }

    public List<Validator> getValidators() {
        return this.validators;
    }
    @Override
    public boolean validateBlock(Block block, List<Block> chain) {
        String vid = block.getValidatorId();
        if (vid == null) return false;
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
        return null; // Simplified
    }

    @Override
    public java.util.Set<String> getSlashedValidators() {
        return java.util.Collections.unmodifiableSet(slashedValidators);
    }

    @Override
    public void clearSlashedValidator(String validatorId) {
        slashedValidators.remove(validatorId);
    }
}
