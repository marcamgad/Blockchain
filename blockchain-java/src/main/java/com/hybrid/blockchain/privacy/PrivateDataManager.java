package com.hybrid.blockchain.privacy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for Private Data Collections
 * Integrated with blockchain state to manage multiple private channels
 */
public class PrivateDataManager {
    private static final Logger log = LoggerFactory.getLogger(PrivateDataManager.class);

    private final Map<String, PrivateDataCollection> collections;

    public PrivateDataManager() {
        this.collections = new ConcurrentHashMap<>();
    }

    public void restore(PrivateDataManager other) {
        this.collections.clear();
        this.collections.putAll(other.collections);
    }

    /**
     * Create a new private data collection
     */
    public PrivateDataCollection createCollection(
            String collectionId,
            List<String> authorizedMembers) {
        // Use first member as creator if not specified
        String creator = authorizedMembers.isEmpty() ? "SYSTEM" : authorizedMembers.get(0);
        return createCollection(collectionId, authorizedMembers, creator);
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

        if (!authorizedMembers.contains(creator)) {
            throw new SecurityException("Creator must be in authorized members");
        }

        PrivateDataCollection collection = new PrivateDataCollection(collectionId, authorizedMembers);
        collections.put(collectionId, collection);

        log.info("[PrivateDataMgr] Created collection: {} with {} members", collectionId, authorizedMembers.size());

        return collection;
    }

    public void addData(String collectionId, String key, byte[] data) {
        PrivateDataCollection collection = getCollection(collectionId);
        // Using a dummy caller for manager-level convenience method (which assumes authorization is checked upstream)
        String dummyCaller = collection.getAuthorizedMembers().get(0);
        collection.writePrivateData(key, data, dummyCaller);
    }

    public byte[] getData(String caller, String collectionId, String key) {
        return getCollection(collectionId).readPrivateData(key, caller);
    }

    public byte[] getDataHash(String collectionId, String key) {
        return getCollection(collectionId).getPublicHash(key);
    }

    public void addMember(String collectionId, String address) {
        PrivateDataCollection collection = getCollection(collectionId);
        String dummyCaller = collection.getAuthorizedMembers().get(0);
        collection.addAuthorizedMember(address, dummyCaller);
    }

    public PrivateDataCollection getCollection(String collectionId) {
        PrivateDataCollection collection = collections.get(collectionId);
        if (collection == null) {
            throw new IllegalArgumentException("Collection not found: " + collectionId);
        }
        return collection;
    }

    public boolean hasCollection(String collectionId) {
        return collections.containsKey(collectionId);
    }

    public boolean isMember(String collectionId, String address) {
        return getCollection(collectionId).isAuthorized(address);
    }

    public List<String> getCollectionsForMember(String memberAddress) {
        List<String> accessibleCollections = new ArrayList<>();

        for (Map.Entry<String, PrivateDataCollection> entry : collections.entrySet()) {
            if (entry.getValue().isAuthorized(memberAddress)) {
                accessibleCollections.add(entry.getKey());
            }
        }

        return accessibleCollections;
    }

    public void deleteCollection(String collectionId, String caller) {
        PrivateDataCollection collection = getCollection(collectionId);

        if (!collection.isAuthorized(caller)) {
            throw new SecurityException("Only authorized members can delete collection");
        }

        collections.remove(collectionId);
        log.info("[PrivateDataMgr] Deleted collection: {}", collectionId);
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCollections", collections.size());

        int totalDataItems = collections.values().stream()
                .mapToInt(PrivateDataCollection::getDataCount)
                .sum();
        stats.put("totalDataItems", totalDataItems);

        return stats;
    }

    public Map<String, Object> toJSON() {
        Map<String, Object> json = new HashMap<>();
        Map<String, Object> serializedCollections = new HashMap<>();

        for (Map.Entry<String, PrivateDataCollection> entry : collections.entrySet()) {
            PrivateDataCollection collection = entry.getValue();
            Map<String, Object> collectionJson = new HashMap<>();
            collectionJson.put("authorizedMembers", collection.getAuthorizedMembers());
            collectionJson.put("collectionKey", Base64.getEncoder().encodeToString(collection.exportCollectionKeyRaw()));
            collectionJson.put("privateData", collection.exportPrivateDataBase64());
            collectionJson.put("publicHashes", collection.exportPublicHashesBase64());
            serializedCollections.put(entry.getKey(), collectionJson);
        }

        json.put("collections", serializedCollections);
        return json;
    }

    @SuppressWarnings("unchecked")
    public static PrivateDataManager fromMap(Map<String, Object> json) {
        PrivateDataManager manager = new PrivateDataManager();
        if (json == null) {
            return manager;
        }

        Map<String, Object> serializedCollections = (Map<String, Object>) json.get("collections");
        if (serializedCollections == null) {
            return manager;
        }

        for (Map.Entry<String, Object> entry : serializedCollections.entrySet()) {
            Map<String, Object> collectionJson = (Map<String, Object>) entry.getValue();
            List<String> authorizedMembers = (List<String>) collectionJson.getOrDefault("authorizedMembers", Collections.emptyList());
            byte[] keyBytes = Base64.getDecoder().decode((String) collectionJson.get("collectionKey"));

            Map<String, byte[]> privateData = new HashMap<>();
            Map<String, Object> privateDataRaw = (Map<String, Object>) collectionJson.getOrDefault("privateData", Collections.emptyMap());
            for (Map.Entry<String, Object> pd : privateDataRaw.entrySet()) {
                privateData.put(pd.getKey(), Base64.getDecoder().decode((String) pd.getValue()));
            }

            Map<String, byte[]> publicHashes = new HashMap<>();
            Map<String, Object> publicHashesRaw = (Map<String, Object>) collectionJson.getOrDefault("publicHashes", Collections.emptyMap());
            for (Map.Entry<String, Object> ph : publicHashesRaw.entrySet()) {
                publicHashes.put(ph.getKey(), Base64.getDecoder().decode((String) ph.getValue()));
            }

            PrivateDataCollection collection = PrivateDataCollection.fromState(
                    entry.getKey(),
                    authorizedMembers,
                    keyBytes,
                    privateData,
                    publicHashes);
            manager.collections.put(entry.getKey(), collection);
        }

        return manager;
    }
}
