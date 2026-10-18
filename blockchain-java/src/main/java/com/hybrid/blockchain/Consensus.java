package com.hybrid.blockchain;
import java.util.List;

public interface Consensus {
    void shutdown();
    boolean validateBlock(Block block, List<Block> chain);
    Block selectLeader(List<String> authorizeNodes, long round);
    boolean isValidator(String validatorId);
    boolean verifyBlock(Block block, Validator validator) throws Exception;

    /**
     * [HC-065] Only the proposer's signature, for a block that has not been committed yet (a
     * proposal). Engines whose finality is the signature itself (PoA) use verifyBlock.
     */
    default boolean verifyProposerSignature(Block block, Validator validator) throws Exception {
        return verifyBlock(block, validator);
    }
    List<Validator> getValidators();
    void addValidator(String id, byte[] publicKey);
    java.util.Set<String> getSlashedValidators();
    void clearSlashedValidator(String validatorId);
    int getSlashCount(String validatorId);
    void removeValidator(String id);
}
