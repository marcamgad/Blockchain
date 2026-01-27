package com.hybrid.blockchain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PoAConsensus {

    private static final byte[] DOMAIN_PREFIX = "BLOCK\0".getBytes(StandardCharsets.UTF_8);
    private final List<Validator> validators;

    public PoAConsensus(List<Validator> validators) {
        this.validators = validators;
    }

    public boolean isValidator(String validatorId) {
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
        return Crypto.verify(msg, block.getSignature(), validator.getPublicKey());
    }

    public List<Validator> getValidators() {
        return this.validators;
    }
}
