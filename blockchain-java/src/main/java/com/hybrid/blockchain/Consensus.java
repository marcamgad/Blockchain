package com.hybrid.blockchain;
import java.util.List;

public interface Consensus {
    void shutdown();
    boolean validateBlock(Block block, List<Block> chain);
    Block selectLeader(List<String> authorizeNodes, long round);
    boolean isValidator(String validatorId);
    boolean verifyBlock(Block block, Validator validator) throws Exception;
    List<Validator> getValidators();
    void addValidator(String id, byte[] publicKey);
    java.util.Set<String> getSlashedValidators();
    void clearSlashedValidator(String validatorId);
}
