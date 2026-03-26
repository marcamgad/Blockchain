package com.hybrid.blockchain.identity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SSIManager {
    private static final Logger log = LoggerFactory.getLogger(SSIManager.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    // On-chain DID registry: DID -> DID Document
    private Map<String, DecentralizedIdentifier> didRegistry;

    // Credential store: Subject DID -> List of VCs
    private Map<String, List<VerifiableCredential>> credentialStore;

    // Device ID to DID mapping for quick lookup
    private Map<String, String> deviceToDID;

    // Revoked DIDs
    private Set<String> revokedDIDs;

    public void restore(SSIManager other) {
        this.didRegistry.clear();
        this.didRegistry.putAll(other.didRegistry);
        this.credentialStore.clear();
        this.credentialStore.putAll(other.credentialStore);
        this.deviceToDID.clear();
        this.deviceToDID.putAll(other.deviceToDID);
        this.revokedDIDs.clear();
        this.revokedDIDs.addAll(other.revokedDIDs);
    }

    public SSIManager() {
        this.didRegistry = new ConcurrentHashMap<>();
        this.credentialStore = new ConcurrentHashMap<>();
        this.deviceToDID = new ConcurrentHashMap<>();
        this.revokedDIDs = ConcurrentHashMap.newKeySet();
    }

    /**
     * Register a new DID for an IoT device
     */
    public String registerDID(String deviceId, byte[] publicKey, String owner) {
        if (deviceToDID.containsKey(deviceId)) {
            throw new IllegalStateException("Device " + deviceId + " already has a DID");
        }

        DecentralizedIdentifier didDoc = new DecentralizedIdentifier(deviceId, publicKey, owner);
        String did = didDoc.getDid();

        didRegistry.put(did, didDoc);
        deviceToDID.put(deviceId, did);

        log.info("[SSI] Registered DID: {} for device: {}", did, deviceId);
        return did;
    }

    /**
     * Resolve a DID to its DID Document
     */
    public DecentralizedIdentifier resolveDID(String did) {
        if (revokedDIDs.contains(did)) {
            throw new SecurityException("DID has been revoked: " + did);
        }

        DecentralizedIdentifier didDoc = didRegistry.get(did);
        if (didDoc == null) {
            throw new IllegalArgumentException("DID not found: " + did);
        }

        return didDoc;
    }

    /**
     * Get DID for a device ID
     */
    public String getDIDForDevice(String deviceId) {
        return deviceToDID.get(deviceId);
    }

    /**
     * Transfer ownership of a device with strict signature verification.
     */
    public void transferOwnership(String did, String newOwner, byte[] signature) {
        DecentralizedIdentifier didDoc = resolveDID(did);
        // Verification: Current owner must sign the intent to transfer
        // Message is: transfer:<did>:<newOwner>
        byte[] message = ("transfer:" + did + ":" + newOwner).getBytes();

        if (!didDoc.verifySignature(message, signature)) {
            throw new SecurityException("Invalid ownership transfer signature for " + did);
        }

        didDoc.setController(newOwner);
        log.info("[SSI] Transferred ownership of {} to {}", did, newOwner);
    }

    /**
     * Issue a Verifiable Credential to a device
     */
    public VerifiableCredential issueCredential(
            String issuerDID,
            String subjectDID,
            Map<String, Object> claims,
            BigInteger issuerPrivateKey,
            byte[] issuerPublicKey) {
        
        resolveDID(issuerDID);
        resolveDID(subjectDID);

        VerifiableCredential vc = new VerifiableCredential(issuerDID, subjectDID, claims);

        if (claims.containsKey("credentialType")) {
            vc.addType(claims.get("credentialType").toString());
        } else if (claims.containsKey("type")) {
            vc.addType(claims.get("type").toString());
        }

        if (claims.containsKey("expirationMs")) {
            long expirationMs = Long.parseLong(claims.get("expirationMs").toString());
            vc.setExpiration(expirationMs);
        }

        vc.sign(issuerPrivateKey, issuerPublicKey);
        credentialStore.computeIfAbsent(subjectDID, k -> new ArrayList<>()).add(vc);

        return vc;
    }

    /**
     * Verify a device has a specific credential type
     */
    public boolean hasCredential(String deviceDID, String credentialType) {
        List<VerifiableCredential> credentials = credentialStore.get(deviceDID);
        if (credentials == null) return false;

        return credentials.stream()
                .anyMatch(vc -> !vc.isExpired() && vc.getCredentialType().equals(credentialType));
    }

    /**
     * Get all credentials for a device
     */
    public List<VerifiableCredential> getCredentials(String deviceDID) {
        return credentialStore.getOrDefault(deviceDID, new ArrayList<>());
    }

    /**
     * Check if an address is the owner of a DID
     */
    public boolean isOwner(String did, String address) {
        try {
            DecentralizedIdentifier didDoc = resolveDID(did);
            return didDoc.getController().equals(address);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get all DIDs owned by an address
     */
    public List<String> getDIDsOwnedBy(String address) {
        List<String> owned = new ArrayList<>();
        for (Map.Entry<String, DecentralizedIdentifier> entry : didRegistry.entrySet()) {
            if (entry.getValue().getController().equals(address)) {
                owned.add(entry.getKey());
            }
        }
        return owned;
    }

    /**
     * Verify a signature using a DID's public key
     */
    public boolean verifyDIDSignature(String did, byte[] message, byte[] signature) {
        try {
            DecentralizedIdentifier didDoc = resolveDID(did);
            return didDoc.verifySignature(message, signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Revoke a DID
     */
    public void revokeDID(String did, String reason) {
        DecentralizedIdentifier didDoc = resolveDID(did);
        revokedDIDs.add(did);
        deviceToDID.remove(didDoc.getDeviceId());
        log.info("[SSI] Revoked DID: {} Reason: {}", did, reason);
    }

    /**
     * Check if a DID is revoked
     */
    public boolean isDIDRevoked(String did) {
        return revokedDIDs.contains(did);
    }

    /**
     * Restore state from a map (loaded from blockchain storage)
     */
    @SuppressWarnings("unchecked")
    public static SSIManager fromMap(Map<String, Object> json) {
        SSIManager manager = new SSIManager();
        if (json == null) return manager;

        try {
            // Restore DID registry
            if (json.containsKey("didRegistry")) {
                Map<String, Object> registryMap = (Map<String, Object>) json.get("didRegistry");
                for (Map.Entry<String, Object> entry : registryMap.entrySet()) {
                    DecentralizedIdentifier didDoc = mapper.convertValue(entry.getValue(), DecentralizedIdentifier.class);
                    manager.didRegistry.put(entry.getKey(), didDoc);
                    if (didDoc != null && didDoc.getDeviceId() != null && didDoc.getDid() != null) {
                        manager.deviceToDID.put(didDoc.getDeviceId(), didDoc.getDid());
                    }
                }
            }

            if (json.containsKey("deviceToDID")) {
                Map<String, String> mappings = mapper.convertValue(json.get("deviceToDID"), new TypeReference<Map<String, String>>() {});
                manager.deviceToDID.putAll(mappings);
            }

            // Restore Credentials
            if (json.containsKey("credentialStore")) {
                Map<String, Object> storeMap = (Map<String, Object>) json.get("credentialStore");
                for (Map.Entry<String, Object> entry : storeMap.entrySet()) {
                    String jsonStr = mapper.writeValueAsString(entry.getValue());
                    List<VerifiableCredential> vcs = mapper.readValue(jsonStr, new TypeReference<List<VerifiableCredential>>() {});
                    manager.credentialStore.put(entry.getKey(), vcs);
                }
            }

            // Restore Revoked DIDs
            if (json.containsKey("revokedDIDs")) {
                List<String> revoked = (List<String>) json.get("revokedDIDs");
                manager.revokedDIDs.addAll(revoked);
            }

        } catch (Exception e) {
            log.error("[SSI] Failed to restore SSIManager state: {}", e.getMessage());
        }

        return manager;
    }

    /**
     * Serialize state for blockchain storage
     */
    public Map<String, Object> toJSON() {
        Map<String, Object> json = new HashMap<>();

        // Serialize DID registry (convert objects to maps for JSON generic storage)
        Map<String, Object> dids = new HashMap<>();
        for (Map.Entry<String, DecentralizedIdentifier> entry : didRegistry.entrySet()) {
            dids.put(entry.getKey(), entry.getValue());
        }
        json.put("didRegistry", dids);

        // Serialize credentials
        json.put("credentialStore", credentialStore);

        // Device to DID map
        json.put("deviceToDID", deviceToDID);

        // Revoked DIDs
        json.put("revokedDIDs", new ArrayList<>(revokedDIDs));

        return json;
    }

    /**
     * Get statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalDIDs", didRegistry.size());
        stats.put("revokedDIDs", revokedDIDs.size());
        stats.put("activeDIDs", didRegistry.size() - revokedDIDs.size());
        int totalCredentials = credentialStore.values().stream().mapToInt(List::size).sum();
        stats.put("totalCredentials", totalCredentials);
        return stats;
    }
}
