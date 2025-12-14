package com.hybrid.blockchain;
import com.hybrid.blockchain.Block;
import java.util.List;

public interface Consensus {
    boolean validateBlock(Block block, List<Block> chain);
    Block selectLeader(List<String> authorizeNodes, long round);
}   
