package com.hybrid.blockchain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A production-grade Merkle Patricia Trie (MPT) implementation in Java.
 * Supports accounts and smart contract storage, stateRoot computation,
 * insertion, deletion, and Merkle proof generation/verification.
 * Follows Ethereum-style structure: Leaf, Extension, and Branch nodes.
 */
/**
 * Merkle Patricia Trie (MPT) implementation for cryptographic state verification.
 * 
 * The MPT combines the characteristics of a Merkle Tree (security via hashing)
 * and a Patricia Trie (efficiency via path compression). It is used to store
 * the blockchain state, where the root hash provides a unique fingerprint
 * of the entire state at a given block height.
 * 
 * This implementation supports:
 * - Insert, Update, and Delete operations.
 * - Leaf, Extension, and Branch node types for path compression.
 * - Merkle Proof generation for light client verification.
 */
public class MerklePatriciaTrie {
    private Node root;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public MerklePatriciaTrie() {
        this.root = null;
    }

    public void put(String key, byte[] value) {
        put(key.getBytes(StandardCharsets.UTF_8), value);
    }

    public void put(byte[] keyBytes, byte[] value) {
        lock.writeLock().lock();
        try {
            byte[] nibbles = toNibbles(Crypto.hash(keyBytes)); // Secure trie (keys are hashed)
            root = insert(root, nibbles, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void putRawKey(byte[] pathNibbles, byte[] value) {
        lock.writeLock().lock();
        try {
            root = insert(root, pathNibbles, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public byte[] get(String key) {
        return get(Crypto.hash(key.getBytes(StandardCharsets.UTF_8)));
    }

    public byte[] get(byte[] keyBytes) {
        lock.readLock().lock();
        try {
            byte[] nibbles = toNibbles(keyBytes);
            return search(root, nibbles);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void delete(String key) {
        delete(key.getBytes(StandardCharsets.UTF_8));
    }

    public void delete(byte[] keyBytes) {
        lock.writeLock().lock();
        try {
            byte[] nibbles = toNibbles(Crypto.hash(keyBytes));
            root = remove(root, nibbles);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public byte[] getRootHash() {
        lock.readLock().lock();
        try {
            if (root == null) return new byte[32];
            byte[] hash = root.hash();
            if (hash.length < 32) {
                return Crypto.hash(hash); // Ensure 32 bytes root
            }
            return hash;
        } finally {
            lock.readLock().unlock();
        }
    }

    // --- Merkle Proofs ---

    public List<byte[]> getAccountProof(String key) {
        return getAccountProof(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates a Merkle Proof for a given key.
     * 
     * @param keyBytes The key to generate a proof for.
     * @return A list of serialized nodes forming the path from root to the key.
     */
    public List<byte[]> getAccountProof(byte[] keyBytes) {
        lock.readLock().lock();
        try {
            List<byte[]> proof = new ArrayList<>();
            getProof(root, toNibbles(Crypto.hash(keyBytes)), proof);
            return proof;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Generates a compact Merkle Proof for a given key as a single byte array.
     * Useful for resource-constrained IoT devices.
     * 
     * @param keyBytes The key to generate a proof for.
     * @return A single byte array containing all nodes in the proof.
     */
    public byte[] getCompactAccountProof(byte[] keyBytes) {
        List<byte[]> proof = getAccountProof(keyBytes);
        int totalLen = 4;
        for (byte[] node : proof) totalLen += 4 + node.length;
        
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        buf.putInt(proof.size());
        for (byte[] node : proof) {
            buf.putInt(node.length).put(node);
        }
        return buf.array();
    }

    private void getProof(Node node, byte[] path, List<byte[]> proof) {
        if (node == null) return;
        proof.add(node.serialize());
        if (node instanceof LeafNode) {
            return; // Target reached
        } else if (node instanceof ExtensionNode) {
            ExtensionNode ext = (ExtensionNode) node;
            if (startsWith(path, ext.path)) {
                getProof(ext.child, slice(path, ext.path.length, path.length), proof);
            }
        } else if (node instanceof BranchNode) {
            BranchNode branch = (BranchNode) node;
            if (path.length == 0) {
                return;
            } else {
                getProof(branch.children[path[0]], slice(path, 1, path.length), proof);
            }
        }
    }

    /**
     * Verifies a Merkle Proof against a known state root.
     * 
     * @param keyBytes The key being verified.
     * @param expectedValue The expected value associated with the key (null if verifying non-existence).
     * @param proof The list of serialized MPT nodes.
     * @param stateRoot The trusted state root hash.
     * @return true if the proof is valid, false otherwise.
     */
    public static boolean verifyAccountProof(byte[] keyBytes, byte[] expectedValue, List<byte[]> proof, byte[] stateRoot) {
        if (proof == null || proof.isEmpty()) {
            return Arrays.equals(stateRoot, new byte[32]) && expectedValue == null;
        }

        byte[] expectedHash = stateRoot;
        byte[] path = toNibbles(Crypto.hash(keyBytes));

        for (int i = 0; i < proof.size(); i++) {
            byte[] nodeRaw = proof.get(i);
            
            // Verify structural hash matches expected hash from parent
            byte[] nodeHash = nodeRaw.length >= 32 ? Crypto.hash(nodeRaw) : nodeRaw;
            if (i == 0 && expectedHash.length == 32) {
                 nodeHash = Crypto.hash(nodeRaw);
            }

            if (!Arrays.equals(nodeHash, expectedHash)) {
                return false;
            }

            Node node = deserializeNode(nodeRaw);
            if (node instanceof LeafNode) {
                LeafNode leaf = (LeafNode) node;
                if (Arrays.equals(leaf.path, path) && Arrays.equals(leaf.value, expectedValue)) {
                    return true;
                }
                return false;
            } else if (node instanceof ExtensionNode) {
                ExtensionNode ext = (ExtensionNode) node;
                if (!startsWith(path, ext.path)) return false;
                path = slice(path, ext.path.length, path.length);
                expectedHash = tryGetHash(ext.child);
            } else if (node instanceof BranchNode) {
                BranchNode branch = (BranchNode) node;
                if (path.length == 0) {
                    if (Arrays.equals(branch.value, expectedValue)) return true;
                    return false;
                }
                byte nibble = path[0];
                path = slice(path, 1, path.length);
                if (branch.children[nibble] == null) return false;
                expectedHash = tryGetHash(branch.children[nibble]);
            }
        }
        return false;
    }

    // --- Core Operations ---

    private Node insert(Node node, byte[] path, byte[] value) {
        if (node == null) {
            return new LeafNode(path, value);
        }

        if (node instanceof LeafNode) {
            LeafNode leaf = (LeafNode) node;
            int match = commonPrefix(leaf.path, path);

            if (match == leaf.path.length && match == path.length) {
                return new LeafNode(path, value); // Update value
            }

            BranchNode branch = new BranchNode();
            if (match == leaf.path.length) {
                branch.value = leaf.value;
            } else {
                branch.children[leaf.path[match]] = new LeafNode(slice(leaf.path, match + 1, leaf.path.length), leaf.value);
            }

            if (match == path.length) {
                branch.value = value;
            } else {
                branch.children[path[match]] = new LeafNode(slice(path, match + 1, path.length), value);
            }

            if (match > 0) {
                return new ExtensionNode(slice(leaf.path, 0, match), branch);
            }
            return branch;
        }

        if (node instanceof ExtensionNode) {
            ExtensionNode ext = (ExtensionNode) node;
            int match = commonPrefix(ext.path, path);

            if (match == ext.path.length) {
                ext.child = insert(ext.child, slice(path, match, path.length), value);
                return ext;
            }

            BranchNode branch = new BranchNode();
            branch.children[ext.path[match]] = ext.path.length - match == 1 ? ext.child :
                    new ExtensionNode(slice(ext.path, match + 1, ext.path.length), ext.child);

            if (match == path.length) {
                branch.value = value;
            } else {
                branch.children[path[match]] = new LeafNode(slice(path, match + 1, path.length), value);
            }

            if (match > 0) {
                return new ExtensionNode(slice(ext.path, 0, match), branch);
            }
            return branch;
        }

        if (node instanceof BranchNode) {
            BranchNode branch = (BranchNode) node;
            if (path.length == 0) {
                branch.value = value;
            } else {
                branch.children[path[0]] = insert(branch.children[path[0]], slice(path, 1, path.length), value);
            }
            return branch;
        }

        return null;
    }

    private byte[] search(Node node, byte[] path) {
        if (node == null) return null;

        if (node instanceof LeafNode) {
            LeafNode leaf = (LeafNode) node;
            if (Arrays.equals(leaf.path, path)) return leaf.value;
            return null;
        }

        if (node instanceof ExtensionNode) {
            ExtensionNode ext = (ExtensionNode) node;
            if (startsWith(path, ext.path)) {
                return search(ext.child, slice(path, ext.path.length, path.length));
            }
            return null;
        }

        if (node instanceof BranchNode) {
            BranchNode branch = (BranchNode) node;
            if (path.length == 0) return branch.value;
            return search(branch.children[path[0]], slice(path, 1, path.length));
        }

        return null;
    }

    private Node remove(Node node, byte[] path) {
        if (node == null) return null;

        if (node instanceof LeafNode) {
            LeafNode leaf = (LeafNode) node;
            if (Arrays.equals(leaf.path, path)) return null;
            return node;
        }

        if (node instanceof ExtensionNode) {
            ExtensionNode ext = (ExtensionNode) node;
            if (startsWith(path, ext.path)) {
                ext.child = remove(ext.child, slice(path, ext.path.length, path.length));
                if (ext.child == null) return null;
                if (ext.child instanceof LeafNode) {
                    LeafNode childLeaf = (LeafNode) ext.child;
                    return new LeafNode(concat(ext.path, childLeaf.path), childLeaf.value);
                }
                if (ext.child instanceof ExtensionNode) {
                    ExtensionNode childExt = (ExtensionNode) ext.child;
                    return new ExtensionNode(concat(ext.path, childExt.path), childExt.child);
                }
                return ext;
            }
            return node;
        }

        if (node instanceof BranchNode) {
            BranchNode branch = (BranchNode) node;
            if (path.length == 0) {
                branch.value = null;
            } else {
                branch.children[path[0]] = remove(branch.children[path[0]], slice(path, 1, path.length));
            }

            // Compact branch if needed
            int numChildren = 0;
            int lastIdx = -1;
            for (int i = 0; i < 16; i++) {
                if (branch.children[i] != null) {
                    numChildren++;
                    lastIdx = i;
                }
            }

            if (numChildren == 0) {
                return branch.value == null ? null : new LeafNode(new byte[0], branch.value);
            } else if (numChildren == 1 && branch.value == null) {
                Node child = branch.children[lastIdx];
                byte[] pathPrefix = new byte[]{(byte) lastIdx};
                if (child instanceof LeafNode) {
                    LeafNode leaf = (LeafNode) child;
                    return new LeafNode(concat(pathPrefix, leaf.path), leaf.value);
                } else if (child instanceof ExtensionNode) {
                    ExtensionNode ext = (ExtensionNode) child;
                    return new ExtensionNode(concat(pathPrefix, ext.path), ext.child);
                } else {
                    return new ExtensionNode(pathPrefix, child);
                }
            }
            return branch;
        }

        return null;
    }

    // --- Helpers ---

    private static byte[] tryGetHash(Node node) {
        if (node == null) return new byte[0];
        return node.hash();
    }

    public static byte[] toNibbles(byte[] bytes) {
        byte[] nibbles = new byte[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            nibbles[i * 2] = (byte) ((bytes[i] >> 4) & 0x0F);
            nibbles[i * 2 + 1] = (byte) (bytes[i] & 0x0F);
        }
        return nibbles;
    }

    private static int commonPrefix(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        int i = 0;
        while (i < len && a[i] == b[i]) i++;
        return i;
    }

    private static byte[] slice(byte[] arr, int start, int end) {
        byte[] res = new byte[end - start];
        System.arraycopy(arr, start, res, 0, res.length);
        return res;
    }

    private static boolean startsWith(byte[] arr, byte[] prefix) {
        if (arr.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (arr[i] != prefix[i]) return false;
        }
        return true;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] res = new byte[a.length + b.length];
        System.arraycopy(a, 0, res, 0, a.length);
        System.arraycopy(b, 0, res, a.length, b.length);
        return res;
    }

    // --- Serialization ---
    // Simple custom serialization replacing RLP for standard Java compatibility
    
    interface Node {
        byte[] serialize();
        byte[] hash();
    }

    static class LeafNode implements Node {
        byte[] path;
        byte[] value;

        LeafNode(byte[] path, byte[] value) {
            this.path = path;
            this.value = value;
        }

        public byte[] serialize() {
            byte[] encodedPath = packNibbles(path, true);
            ByteBuffer buf = ByteBuffer.allocate(1 + 4 + encodedPath.length + 4 + value.length);
            buf.put((byte) 1); // Type 1: Leaf
            buf.putInt(encodedPath.length).put(encodedPath);
            buf.putInt(value.length).put(value);
            return buf.array();
        }

        public byte[] hash() {
            byte[] s = serialize();
            return s.length >= 32 ? Crypto.hash(s) : s;
        }
    }

    static class ExtensionNode implements Node {
        byte[] path;
        Node child;

        ExtensionNode(byte[] path, Node child) {
            this.path = path;
            this.child = child;
        }

        public byte[] serialize() {
            byte[] encodedPath = packNibbles(path, false);
            byte[] childHash = child.hash();
            ByteBuffer buf = ByteBuffer.allocate(1 + 4 + encodedPath.length + 4 + childHash.length);
            buf.put((byte) 2); // Type 2: Extension
            buf.putInt(encodedPath.length).put(encodedPath);
            buf.putInt(childHash.length).put(childHash);
            return buf.array();
        }

        public byte[] hash() {
            byte[] s = serialize();
            return s.length >= 32 ? Crypto.hash(s) : s;
        }
    }

    static class BranchNode implements Node {
        Node[] children = new Node[16];
        byte[] value;

        public byte[] serialize() {
            int mask = 0;
            List<byte[]> presentHashes = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                if (children[i] != null) {
                    mask |= (1 << i);
                    presentHashes.add(children[i].hash());
                }
            }
            byte[] v = value == null ? new byte[0] : value;
            
            int totalLen = 1 + 2; // Type + Mask
            for (byte[] h : presentHashes) totalLen += 4 + h.length;
            totalLen += 4 + v.length;

            ByteBuffer buf = ByteBuffer.allocate(totalLen);
            buf.put((byte) 3); // Type 3: Branch
            buf.putShort((short) mask);
            for (byte[] h : presentHashes) {
                buf.putInt(h.length).put(h);
            }
            buf.putInt(v.length).put(v);
            return buf.array();
        }

        public byte[] hash() {
            byte[] s = serialize();
            return s.length >= 32 ? Crypto.hash(s) : s;
        }
    }

    private static byte[] packNibbles(byte[] nibbles, boolean isLeaf) {
        int len = nibbles.length;
        boolean odd = len % 2 != 0;
        int pLen = odd ? (len / 2 + 1) : (len / 2 + 1);
        byte[] packed = new byte[pLen];
        int prefix = isLeaf ? 2 : 0;
        if (odd) prefix |= 1;

        if (odd) {
            packed[0] = (byte) ((prefix << 4) | nibbles[0]);
            for (int i = 1; i < len; i += 2) {
                packed[(i / 2) + 1] = (byte) ((nibbles[i] << 4) | nibbles[i + 1]);
            }
        } else {
            packed[0] = (byte) (prefix << 4);
            for (int i = 0; i < len; i += 2) {
                packed[(i / 2) + 1] = (byte) ((nibbles[i] << 4) | nibbles[i + 1]);
            }
        }
        return packed;
    }

    private static byte[] unpackNibbles(byte[] packed) {
        if (packed.length == 0) return new byte[0];
        int prefix = (packed[0] & 0xFF) >> 4;
        boolean odd = (prefix & 1) != 0;
        int len = odd ? (packed.length * 2 - 1) : (packed.length * 2 - 2);
        byte[] nibbles = new byte[len];
        if (odd) {
            nibbles[0] = (byte) (packed[0] & 0x0F);
            for (int i = 1; i < packed.length; i++) {
                nibbles[i * 2 - 1] = (byte) ((packed[i] >> 4) & 0x0F);
                nibbles[i * 2] = (byte) (packed[i] & 0x0F);
            }
        } else {
            for (int i = 1; i < packed.length; i++) {
                nibbles[i * 2 - 2] = (byte) ((packed[i] >> 4) & 0x0F);
                nibbles[i * 2 - 1] = (byte) (packed[i] & 0x0F);
            }
        }
        return nibbles;
    }

    private static Node deserializeNode(byte[] raw) {
        ByteBuffer buf = ByteBuffer.wrap(raw);
        byte type = buf.get();
        if (type == 1) { // Leaf
            byte[] path = new byte[buf.getInt()];
            buf.get(path);
            byte[] val = new byte[buf.getInt()];
            buf.get(val);
            return new LeafNode(unpackNibbles(path), val);
        } else if (type == 2) { // Extension
            byte[] path = new byte[buf.getInt()];
            buf.get(path);
            byte[] childHash = new byte[buf.getInt()];
            buf.get(childHash);
            // We return a mock extension node that holds just the childHash for verification purposes
            return new ExtensionNode(unpackNibbles(path), new MockHashNode(childHash));
        } else if (type == 3) { // Branch
            BranchNode branch = new BranchNode();
            int mask = buf.getShort() & 0xFFFF;
            for (int i = 0; i < 16; i++) {
                if (((mask >> i) & 1) != 0) {
                    int len = buf.getInt();
                    byte[] childHash = new byte[len];
                    buf.get(childHash);
                    branch.children[i] = new MockHashNode(childHash);
                }
            }
            int vLen = buf.getInt();
            if (vLen > 0) {
                branch.value = new byte[vLen];
                buf.get(branch.value);
            }
            return branch;
        }
        throw new RuntimeException("Invalid node type");
    }

    static class MockHashNode implements Node {
        byte[] hash;
        MockHashNode(byte[] hash) { this.hash = hash; }
        public byte[] serialize() { return hash; }
        public byte[] hash() { return hash; }
    }

    // Compatibility methods for previous HashMap interface
    public Map<String, byte[]> toMap() {
        lock.readLock().lock();
        try {
            // Because original keys are hashed natively internally, toMap isn't natively possible unless
            // we maintain an auxiliary data structure. Given this is for storage serialization, we return empty map
            // and rely on `AccountState` directly instead for exporting or importing raw state, 
            // since `AccountState` manages the `state` mapping.
            return new HashMap<>();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void fromMap(Map<String, byte[]> map) {
        lock.writeLock().lock();
        try {
            root = null;
            for (Map.Entry<String, byte[]> entry : map.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
