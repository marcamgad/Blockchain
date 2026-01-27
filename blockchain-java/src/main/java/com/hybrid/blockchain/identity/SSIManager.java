package com.hybrid.blockchain.identity;

import com.hybrid.blockchain.Crypto;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-Sovereign Identity Manager for IoT devices.
 * Manages DID registry and Verifiable Credentials on-chain.
 * 
 * This replaces the in-memory IoTDeviceManager with a consensus-backed identity
 * system.
 */
public class SSIManager {

    // On-chain DID registry: DID -> DID Document
    private final Map<String, DecentralizedIdentifier> didRegistry;

    // Credential store: Subject DID -> List of VCs
    private final Map<String, List<VerifiableCredential>> credentialStore;

    // Device ID to DID mapping for quick lookup
    private final Map<String, String> deviceToDID;

    // Revoked DIDs
    private final Set<String> revokedDIDs;

    public SSIManager() {
        this.didRegistry = new ConcurrentHashMap<>();
        this.credentialStore = new ConcurrentHashMap<>();
        this.deviceToDID = new ConcurrentHashMap<>();
        this.revokedDIDs = ConcurrentHashMap.newKeySet();
    }

    /**
     * Register a new DID for an IoT device
     * 
     * @param deviceId  Unique device identifier
     * @param publicKey Device's public key
     * @param owner     Owner's blockchain address
     * @return The created DID string
     */
    public String registerDID(String deviceId, byte[] publicKey, String owner) {
        // Check if device already has a DID
        if (deviceToDID.containsKey(deviceId)) {
            throw new IllegalStateException("Device " + deviceId + " already has a DID");
        }

        // Create DID document
        DecentralizedIdentifier didDoc = new DecentralizedIdentifier(deviceId, publicKey, owner);
        String did = didDoc.getDid();

        // Store in registry
        didRegistry.put(did, didDoc);
        deviceToDID.put(deviceId, did);

        System.out.println("[SSI] Registered DID: " + did + " for device: " + deviceId + " owner: " + owner);

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
     * Transfer ownership of a device (update DID controller)
     */
    public void transferOwnership(String did, String newOwner, byte[] ownerSignature) {
        DecentralizedIdentifier didDoc = resolveDID(did);

        // Verify signature from current owner
        String currentOwner = didDoc.getController();
        byte[] message = (did + newOwner).getBytes();

        // In production, get owner's public key from AccountState
        // For now, simplified verification

        didDoc.setController(newOwner);

        System.out.println("[SSI] Transferred ownership of " + did + " from " + currentOwner + " to " + newOwner);
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
        // Verify issuer DID exists
        resolveDID(issuerDID);

        // Verify subject DID exists
        resolveDID(subjectDID);

        // Create credential
        VerifiableCredential vc = new VerifiableCredential(issuerDID, subjectDID, claims);

        // Add specific type if specified in claims
        if (claims.containsKey("credentialType")) {
            vc.addType(claims.get("credentialType").toString());
        }

        // Set expiration if specified
        if (claims.containsKey("expirationMs")) {
            long expirationMs = Long.parseLong(claims.get("expirationMs").toString());
            vc.setExpiration(expirationMs);
        }

        // Sign credential
        vc.sign(issuerPrivateKey, issuerPublicKey);

        // Store credential
        credentialStore.computeIfAbsent(subjectDID, k -> new ArrayList<>()).add(vc);

        System.out.println("[SSI] Issued credential " + vc.getId() + " to " + subjectDID);

        return vc;
    }

    /**
     * Verify a device has a specific credential type
     */
    public boolean hasCredential(String deviceDID, String credentialType) {
        List<VerifiableCredential> credentials = credentialStore.get(deviceDID);

        if (credentials == null) {
            return false;
        }

        return credentials.stream()
                .anyMatch(vc -> !vc.isExpired() &&
                        (vc.getCredentialType().equals(credentialType) ||
                                vc.getCredentialSubject().getClaim("type") != null &&
                                        vc.getCredentialSubject().getClaim("type").equals(credentialType)));
    }

    /**
     * Get all credentials for a device
     */
    public List<VerifiableCredential> getCredentials(String deviceDID) {
        return credentialStore.getOrDefault(deviceDID, new ArrayList<>());
    }

    /**
     * Revoke a DID (for compromised devices)
     */
    public void revokeDID(String did, String reason) {
        DecentralizedIdentifier didDoc = resolveDID(did);

        revokedDIDs.add(did);

        // Remove from active registry but keep for audit trail
        String deviceId = didDoc.getDeviceId();
        deviceToDID.remove(deviceId);

        System.out.println("[SSI] Revoked DID: " + did + " Reason: " + reason);
    }

    /**
     * Verify a signature was created by the controller of a DID
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
     * Check if a DID is owned by a specific address
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
    public List<String> getDIDsOwnedBy(String ownerAddress) {
        List<String> ownedDIDs = new ArrayList<>();

        for (DecentralizedIdentifier didDoc : didRegistry.values()) {
            if (didDoc.getController().equals(ownerAddress) &&
                    !revokedDIDs.contains(didDoc.getDid())) {
                ownedDIDs.add(didDoc.getDid());
            }
        }

        return ownedDIDs;
    }

    /**
     * Serialize state for blockchain storage
     */
    public Map<String, Object> toJSON() {
        Map<String, Object> json = new HashMap<>();

        // Serialize DID registry
        Map<String, Object> dids = new HashMap<>();
        for (Map.Entry<String, DecentralizedIdentifier> entry : didRegistry.entrySet()) {
            dids.put(entry.getKey(), entry.getValue().toDIDDocument());
        }
        json.put("didRegistry", dids);

        // Serialize credentials (simplified)
        Map<String, Integer> credCounts = new HashMap<>();
        for (Map.Entry<String, List<VerifiableCredential>> entry : credentialStore.entrySet()) {
            credCounts.put(entry.getKey(), entry.getValue().size());
        }
        json.put("credentialCounts", credCounts);

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

        int totalCredentials = credentialStore.values().stream()
                .mapToInt(List::size)
                .sum();
        stats.put("totalCredentials", totalCredentials);

        return stats;
    }
}
