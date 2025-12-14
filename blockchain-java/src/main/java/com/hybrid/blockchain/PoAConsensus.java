package com.hybrid.blockchain;

import java.security.PrivateKey;
import java.security.Signature;
import java.util.List;

public class PoAConsensus {

    private final List<Validator> validators;

    public PoAConsensus(List<Validator> validators) {
        this.validators = validators;
    }

    // Check if the signer is a valid validator
    public boolean isValidator(String validatorId) {
        return validators.stream().anyMatch(v -> v.getId().equals(validatorId));
    }

    // Sign a block
    public void signBlock(Block block, Validator validator, PrivateKey key) throws Exception {
        if (!isValidator(validator.getId()))
            throw new Exception("Validator not authorized");

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(block.getHash().getBytes());
        byte[] signatureBytes = sig.sign();
        block.setValidatorId(validator.getId());
        block.setSignature(signatureBytes);
    }

    // Verify block signature
    public boolean verifyBlock(Block block, Validator validator) throws Exception {
        if (!isValidator(validator.getId())) return false;

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(validator.getPublicKey());
        sig.update(block.getHash().getBytes());
        return sig.verify(block.getSignature());
    }
    public List<Validator> getValidators(){return this.validators;}
}
