package com.hybrid.blockchain;

import java.util.ArrayList;
import java.util.List;

/**
 * A simple Merkle Tree implementation for computing state and transaction roots.
 */
public class MerkleTree {

    /**
     * Computes the Merkle Root of a list of byte arrays (leaf hashes).
     * If the number of leaves is odd, the last leaf is duplicated to balance the tree.
     * 
     * @param leaves The hashes of the leaves.
     * @return The Merkle root hash.
     */
    public static byte[] computeRoot(List<byte[]> leaves) {
        if (leaves == null || leaves.isEmpty()) {
            return new byte[32]; // Empty tree root (32 bytes of zeros)
        }

        List<byte[]> currentLevel = new ArrayList<>(leaves);

        while (currentLevel.size() > 1) {
            List<byte[]> nextLevel = new ArrayList<>();
            for (int i = 0; i < currentLevel.size(); i += 2) {
                byte[] left = currentLevel.get(i);
                byte[] right = (i + 1 < currentLevel.size()) ? currentLevel.get(i + 1) : left;

                byte[] combined = new byte[left.length + right.length];
                System.arraycopy(left, 0, combined, 0, left.length);
                System.arraycopy(right, 0, combined, left.length, right.length);
                
                nextLevel.add(Crypto.hash(combined));
            }
            currentLevel = nextLevel;
        }

        return currentLevel.get(0);
    }
}
