package com.hybrid.blockchain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MerklePatriciaTrieTest {

    private MerklePatriciaTrie mpt;

    @BeforeEach
    public void setup() {
        mpt = new MerklePatriciaTrie();
    }

    @Test
    public void testInsertUpdateDelete() {
        String key1 = "address1";
        byte[] val1 = "value1".getBytes(StandardCharsets.UTF_8);
        String key2 = "address2";
        byte[] val2 = "value2".getBytes(StandardCharsets.UTF_8);

        // Test Insert
        mpt.put(key1, val1);
        mpt.put(key2, val2);

        assertArrayEquals(val1, mpt.get(key1));
        assertArrayEquals(val2, mpt.get(key2));

        // Test Update
        byte[] val1_new = "value1_NEW".getBytes(StandardCharsets.UTF_8);
        mpt.put(key1, val1_new);
        assertArrayEquals(val1_new, mpt.get(key1));

        // Test Delete
        mpt.delete(key2);
        assertNull(mpt.get(key2));
        assertArrayEquals(val1_new, mpt.get(key1)); // key1 should still exist
        
        mpt.delete(key1);
        assertNull(mpt.get(key1));
    }

    @Test
    public void testHashEmptyTrie() {
        byte[] root = mpt.getRootHash();
        assertNotNull(root);
        assertEquals(32, root.length);
        assertArrayEquals(new byte[32], root);
    }

    @Test
    public void testStateRootChanges() {
        byte[] root1 = mpt.getRootHash();

        mpt.put("key1", "val1".getBytes());
        byte[] root2 = mpt.getRootHash();
        assertNotEquals(Crypto.bytesToHex(root1), Crypto.bytesToHex(root2));

        mpt.put("key2", "val2".getBytes());
        byte[] root3 = mpt.getRootHash();
        assertNotEquals(Crypto.bytesToHex(root2), Crypto.bytesToHex(root3));

        mpt.put("key1", "val1_new".getBytes());
        byte[] root4 = mpt.getRootHash();
        assertNotEquals(Crypto.bytesToHex(root3), Crypto.bytesToHex(root4));

        mpt.delete("key2");
        byte[] root5 = mpt.getRootHash();
        assertNotEquals(Crypto.bytesToHex(root4), Crypto.bytesToHex(root5));

        // Let's verify reverting to previous state gives same root
        mpt.put("key1", "val1".getBytes());
        byte[] root6 = mpt.getRootHash();
        assertEquals(Crypto.bytesToHex(root2), Crypto.bytesToHex(root6));
    }

    @Test
    public void testGenerateAndVerifyProof() {
        byte[] key = "my_secure_address".getBytes(StandardCharsets.UTF_8);
        byte[] value = "account_state_data".getBytes(StandardCharsets.UTF_8);

        // Put initial data
        mpt.put(key, value);
        mpt.put("other_key1", "other_val1".getBytes());
        mpt.put("other_key2", "other_val2".getBytes());

        byte[] rootHash = mpt.getRootHash();

        // 1. Generate Proof
        List<byte[]> proof = mpt.getAccountProof(key);
        assertNotNull(proof);
        assertFalse(proof.isEmpty());

        // 2. Verify Proof (Valid)
        boolean isValid = MerklePatriciaTrie.verifyAccountProof(key, value, proof, rootHash);
        assertTrue(isValid, "Proof should be valid for correct key-value pair");

        // 3. Verify Proof (Invalid Value)
        boolean isInvalidValue = MerklePatriciaTrie.verifyAccountProof(key, "wrong_data".getBytes(), proof, rootHash);
        assertFalse(isInvalidValue, "Proof should be invalid for incorrect value");

        // 4. Verify Proof (Invalid Root)
        byte[] fakeRoot = new byte[32];
        boolean isInvalidRoot = MerklePatriciaTrie.verifyAccountProof(key, value, proof, fakeRoot);
        assertFalse(isInvalidRoot, "Proof should be invalid for incorrect root hash");
        
        // 5. Verify non-existent key
        byte[] missingKey = "missing_key".getBytes();
        List<byte[]> missingProof = mpt.getAccountProof(missingKey);
        // A proof of non-existence verification could be supported, but our simple verify relies on value matching or returning false for non-existence if we assert a value.
        // We ensure value verify fails if expected true value.
        assertFalse(MerklePatriciaTrie.verifyAccountProof(missingKey, value, missingProof, rootHash));
    }
}
