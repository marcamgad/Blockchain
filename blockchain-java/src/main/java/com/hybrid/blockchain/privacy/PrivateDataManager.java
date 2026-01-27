package com.hybrid.blockchain.privacy;

import com.hybrid.blockchain.Crypto;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for Private Data Collections
 * Integrated with blockchain state to manage multiple private channels
 */
public class PrivateDataManager {

    private final Map<String, PrivateDataCollection> collections;

    public PrivateDataManager() {
        this.collections = new ConcurrentHashMap<>();
    }

    /**
     * Create a new private data collection
     */
    public PrivateDataCollection createCollection(
            String collectionId,
            List<String> authorizedMembers,
            String creator) {
        if (collections.containsKey(collectionId)) {
            throw new IllegalStateException("Collection already exists: " + collectionId);
        }

        // Creator must be in authorized members
        if (!authorizedMembers.contains(creator)) {
            throw new SecurityException("Creator must be in authorized members");
        }

        PrivateDataCollection collection = new PrivateDataCollection(collectionId, authorizedMembers);
        collections.put(collectionId, collection);

        System.out.println("[PrivateDataMgr] Created collection: " + collectionId +
                " with " + authorizedMembers.size() + " members");

        return collection;
    }

    /**
     * Get existing collection
     */
    public PrivateDataCollection getCollection(String collectionId) {
        PrivateDataCollection collection = collections.get(collectionId);
        if (collection == null) {
            throw new IllegalArgumentException("Collection not found: " + collectionId);
        }
        return collection;
    }

    /**
     * Check if collection exists
     */
    public boolean hasCollection(String collectionId) {
        return collections.containsKey(collectionId);
    }

    /**
     * Get all collections accessible by an address
     */
    public List<String> getCollectionsForMember(String memberAddress) {
        List<String> accessibleCollections = new ArrayList<>();

        for (Map.Entry<String, PrivateDataCollection> entry : collections.entrySet()) {
            if (entry.getValue().isAuthorized(memberAddress)) {
                accessibleCollections.add(entry.getKey());
            }
        }

        return accessibleCollections;
    }

    /**
     * Delete collection (requires all members to approve in production)
     */
    public void deleteCollection(String collectionId, String caller) {
        PrivateDataCollection collection = getCollection(collectionId);

        if (!collection.isAuthorized(caller)) {
            throw new SecurityException("Only authorized members can delete collection");
        }

        collections.remove(collectionId);
        System.out.println("[PrivateDataMgr] Deleted collection: " + collectionId);
    }

    /**
     * Get statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCollections", collections.size());

        int totalDataItems = collections.values().stream()
                .mapToInt(PrivateDataCollection::getDataCount)
                .sum();
        stats.put("totalDataItems", totalDataItems);

        return stats;
    }
}
