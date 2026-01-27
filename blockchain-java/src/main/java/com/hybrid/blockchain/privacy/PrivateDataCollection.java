package com.hybrid.blockchain.privacy;

import com.hybrid.blockchain.Crypto;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private Data Collection for confidential IoT data
 * 
 * Allows authorized members to read/write encrypted data while maintaining
 * public hashes for integrity verification.
 * 
 * Use cases:
 * - Private sensor readings (health data, location, etc.)
 * - Confidential device configurations
 * - Secure inter-device communication
 */
public class PrivateDataCollection {

    private final String collectionId;
    private final List<String> authorizedMembers; // Addresses allowed to access
    private final Map<String, byte[]> privateData; // Encrypted data
    private final Map<String, byte[]> publicHashes; // Public hashes for verification
    private final SecretKey collectionKey; // Shared encryption key

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Create a new private data collection
     * 
     * @param collectionId      Unique identifier
     * @param authorizedMembers List of addresses allowed to access
     */
    public PrivateDataCollection(String collectionId, List<String> authorizedMembers) {
        this.collectionId = collectionId;
        this.authorizedMembers = new ArrayList<>(authorizedMembers);
        this.privateData = new ConcurrentHashMap<>();
        this.publicHashes = new ConcurrentHashMap<>();
        this.collectionKey = generateCollectionKey();
    }

    /**
     * Write private data (only authorized members)
     * 
     * @param key    Data key
     * @param data   Raw data (will be encrypted)
     * @param caller Address of caller
     */
    public void writePrivateData(String key, byte[] data, String caller) {
        if (!isAuthorized(caller)) {
            throw new SecurityException("Caller not authorized for collection: " + collectionId);
        }

        // Encrypt data
        byte[] encrypted = encrypt(data);
        privateData.put(key, encrypted);

        // Store public hash for verification
        byte[] hash = Crypto.hash(data);
        publicHashes.put(key, hash);

        System.out.println("[PrivateData] Written to collection " + collectionId + " key: " + key);
    }

    /**
     * Read private data (only authorized members)
     * 
     * @param key    Data key
     * @param caller Address of caller
     * @return Decrypted data
     */
    public byte[] readPrivateData(String key, String caller) {
        if (!isAuthorized(caller)) {
            throw new SecurityException("Caller not authorized for collection: " + collectionId);
        }

        byte[] encrypted = privateData.get(key);
        if (encrypted == null) {
            return null;
        }

        return decrypt(encrypted);
    }

    /**
     * Verify data integrity without revealing content
     * Anyone can verify, but only authorized members can read
     * 
     * @param key         Data key
     * @param claimedData Data to verify
     * @return true if hash matches
     */
    public boolean verifyDataIntegrity(String key, byte[] claimedData) {
        byte[] publicHash = publicHashes.get(key);
        if (publicHash == null) {
            return false;
        }

        byte[] claimedHash = Crypto.hash(claimedData);
        return Arrays.equals(publicHash, claimedHash);
    }

    /**
     * Get public hash for a key (anyone can access)
     */
    public byte[] getPublicHash(String key) {
        return publicHashes.get(key);
    }

    /**
     * Check if data exists
     */
    public boolean hasData(String key) {
        return privateData.containsKey(key);
    }

    /**
     * Add authorized member
     */
    public void addAuthorizedMember(String address, String caller) {
        if (!isAuthorized(caller)) {
            throw new SecurityException("Only authorized members can add new members");
        }

        if (!authorizedMembers.contains(address)) {
            authorizedMembers.add(address);
            System.out.println("[PrivateData] Added member " + address + " to collection " + collectionId);
        }
    }

    /**
     * Remove authorized member
     */
    public void removeAuthorizedMember(String address, String caller) {
        if (!isAuthorized(caller)) {
            throw new SecurityException("Only authorized members can remove members");
        }

        authorizedMembers.remove(address);
        System.out.println("[PrivateData] Removed member " + address + " from collection " + collectionId);
    }

    /**
     * Check if address is authorized
     */
    public boolean isAuthorized(String address) {
        return authorizedMembers.contains(address);
    }

    /**
     * Get collection ID
     */
    public String getCollectionId() {
        return collectionId;
    }

    /**
     * Get authorized members (read-only)
     */
    public List<String> getAuthorizedMembers() {
        return new ArrayList<>(authorizedMembers);
    }

    /**
     * Get number of data items
     */
    public int getDataCount() {
        return privateData.size();
    }

    /**
     * Delete data
     */
    public void deleteData(String key, String caller) {
        if (!isAuthorized(caller)) {
            throw new SecurityException("Caller not authorized for collection: " + collectionId);
        }

        privateData.remove(key);
        publicHashes.remove(key);
        System.out.println("[PrivateData] Deleted from collection " + collectionId + " key: " + key);
    }

    // Encryption/Decryption

    private byte[] encrypt(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, collectionKey);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private byte[] decrypt(byte[] encrypted) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, collectionKey);
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    private static SecretKey generateCollectionKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256, RANDOM);
            return keyGen.generateKey();
        } catch (Exception e) {
            throw new RuntimeException("Key generation failed", e);
        }
    }

    /**
     * Export collection key (for sharing with new members)
     * Only authorized members can export
     */
    public byte[] exportKey(String caller) {
        if (!isAuthorized(caller)) {
            throw new SecurityException("Caller not authorized to export key");
        }
        return collectionKey.getEncoded();
    }

    /**
     * Create collection from existing key
     */
    public static PrivateDataCollection fromKey(
            String collectionId,
            List<String> authorizedMembers,
            byte[] keyBytes) {
        PrivateDataCollection collection = new PrivateDataCollection(collectionId, authorizedMembers);
        // Replace generated key with provided key
        try {
            java.lang.reflect.Field keyField = PrivateDataCollection.class.getDeclaredField("collectionKey");
            keyField.setAccessible(true);
            SecretKey key = new SecretKeySpec(keyBytes, "AES");
            keyField.set(collection, key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set collection key", e);
        }
        return collection;
    }

    /**
     * Get statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("collectionId", collectionId);
        stats.put("authorizedMembers", authorizedMembers.size());
        stats.put("dataItems", privateData.size());
        return stats;
    }
}
