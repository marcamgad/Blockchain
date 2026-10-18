package com.hybrid.blockchain;

import java.util.ArrayList;
import java.util.List;

/**
 * A simple Merkle Tree implementation for computing state and transaction roots.
 *
 * <p>[HC-030] Construction (v2):
 * <ul>
 *   <li>leaf node  = H(0x00 || leaf)</li>
 *   <li>inner node = H(0x01 || left || right)</li>
 *   <li>an odd node at the end of a level is PROMOTED unchanged to the next level</li>
 * </ul>
 * The previous version duplicated the last node of an odd level (the Bitcoin CVE-2012-2459
 * pattern), so the lists [a,b,c] and [a,b,c,c] had the same root and therefore the same block
 * hash. Distinct leaf/inner tags additionally rule out presenting an inner node as a leaf.
 * Changing this changes every transaction root: it is a consensus (hard-fork) change.
 */
public class MerkleTree {

    private static final byte LEAF_TAG = 0x00;
    private static final byte NODE_TAG = 0x01;

    /**
     * Computes the Merkle Root of a list of byte arrays (leaf hashes).
     *
     * @param leaves the leaves, in order
     * @return the 32-byte root (32 zero bytes for an empty list)
     */
    public static byte[] computeRoot(List<byte[]> leaves) {
        if (leaves == null || leaves.isEmpty()) {
            return new byte[32]; // Empty tree root (32 bytes of zeros)
        }
        List<byte[]> level = new ArrayList<>(leaves.size());
        for (byte[] leaf : leaves) {
            level.add(Crypto.hash(tagged(LEAF_TAG, leaf, null)));
        }
        while (level.size() > 1) {
            List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                if (i + 1 == level.size()) {
                    next.add(level.get(i));            // promote, never duplicate
                } else {
                    next.add(Crypto.hash(tagged(NODE_TAG, level.get(i), level.get(i + 1))));
                }
            }
            level = next;
        }
        return level.get(0);
    }

    private static byte[] tagged(byte tag, byte[] a, byte[] b) {
        int len = 1 + a.length + (b == null ? 0 : b.length);
        byte[] out = new byte[len];
        out[0] = tag;
        System.arraycopy(a, 0, out, 1, a.length);
        if (b != null) System.arraycopy(b, 0, out, 1 + a.length, b.length);
        return out;
    }
}
