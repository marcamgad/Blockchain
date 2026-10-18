package com.hybrid.blockchain.lifecycle;

import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.identity.SSIManager;
import com.hybrid.blockchain.identity.VerifiableCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Device Lifecycle Manager for IoT devices.
 * Manages device states: PROVISIONING → ACTIVE → SUSPENDED → REVOKED →
 * DECOMMISSIONED
 *
 * Enforces:
 * - Manufacturer attestation at provisioning
 * - Secure firmware updates with audit trails
 * - Capability revocation on device revocation
 */
public class DeviceLifecycleManager {
    private static final Logger log = LoggerFactory.getLogger(DeviceLifecycleManager.class);


    public enum DeviceStatus {
        PROVISIONING, // Registered but not activated
        ACTIVE, // Operational
        SUSPENDED, // Temporarily disabled
        REVOKED, // Permanently disabled (security)
        DECOMMISSIONED // Removed from network
    }

    /**
     * Complete device record stored on-chain
     */
    public static class DeviceRecord {
        private String deviceId;
        private String did; // Decentralized identifier
        private DeviceStatus status;
        private String owner;
        private String manufacturer;
        private String model;
        private String firmwareVersion;
        private byte[] attestationSignature; // Manufacturer signature
        private long registrationBlock;
        private long lastActivityBlock;
        private double reputationScore = com.hybrid.blockchain.reputation.ReputationEngine.INITIAL_SCORE;
        private List<FirmwareUpdate> firmwareHistory;
        private Map<String, String> metadata;
        private byte[] pufProof;    // PAPER-IMPL: P1-C — ZKP ownership proof
        private String pufNullifier; // PAPER-IMPL: P1-C — Unique nullifier from ZKP
        private boolean pufEnabled = true; // Flag for PUF identity enforcement
        /** [HC-047] the device public key the manufacturer attested at provisioning */
        private byte[] attestedPublicKey;

        public byte[] getAttestedPublicKey() { return attestedPublicKey; }
        public void setAttestedPublicKey(byte[] k) { this.attestedPublicKey = k == null ? null : k.clone(); }

        public DeviceRecord() {
            this.firmwareHistory = new ArrayList<>();
            this.metadata = new HashMap<>();
        }

        public DeviceRecord(String deviceId, String manufacturer, String model) {
            this();
            this.deviceId = deviceId;
            this.manufacturer = manufacturer;
            this.model = model;
            this.status = DeviceStatus.PROVISIONING;
        }

        public DeviceRecord cloneRecord() {
            DeviceRecord copy = new DeviceRecord();
            copy.deviceId = this.deviceId;
            copy.did = this.did;
            copy.status = this.status;
            copy.owner = this.owner;
            copy.manufacturer = this.manufacturer;
            copy.model = this.model;
            copy.firmwareVersion = this.firmwareVersion;
            copy.attestationSignature = this.attestationSignature != null ? this.attestationSignature.clone() : null;
            copy.registrationBlock = this.registrationBlock;
            copy.lastActivityBlock = this.lastActivityBlock;
            copy.reputationScore = this.reputationScore;
            // deep copy lists and maps
            copy.firmwareHistory.addAll(this.firmwareHistory);
            copy.metadata.putAll(this.metadata);
            copy.pufProof = this.pufProof != null ? this.pufProof.clone() : null;
            copy.pufNullifier = this.pufNullifier;
            copy.pufEnabled = this.pufEnabled;
            copy.attestedPublicKey = this.attestedPublicKey != null ? this.attestedPublicKey.clone() : null;
            return copy;
        }

        // Getters
        public String getDeviceId() {
            return deviceId;
        }

        public String getDid() {
            return did;
        }

        public DeviceStatus getStatus() {
            return status;
        }

        public String getOwner() {
            return owner;
        }

        public String getManufacturer() {
            return manufacturer;
        }

        public String getModel() {
            return model;
        }

        public String getFirmwareVersion() {
            return firmwareVersion;
        }

        public byte[] getAttestationSignature() {
            return attestationSignature;
        }

        public long getRegistrationBlock() {
            return registrationBlock;
        }

        public long getLastActivityBlock() {
            return lastActivityBlock;
        }

        public List<FirmwareUpdate> getFirmwareHistory() {
            return firmwareHistory;
        }

        public Map<String, String> getMetadata() {
            return metadata;
        }

        public byte[] getPufProof() { return pufProof; }
        public String getPufNullifier() { return pufNullifier; }
        public boolean isPufEnabled() { return pufEnabled; }
        public void setPufEnabled(boolean enabled) { this.pufEnabled = enabled; }

        // Setters
        public void setDid(String did) {
            this.did = did;
        }

        public void setStatus(DeviceStatus status) {
            this.status = status;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public void setFirmwareVersion(String version) {
            this.firmwareVersion = version;
        }

        public void setAttestationSignature(byte[] sig) {
            this.attestationSignature = sig;
        }

        public double getReputationScore() {
            return reputationScore;
        }

        public void setReputationScore(double score) {
            this.reputationScore = score;
        }

        public void setRegistrationBlock(long block) {
            this.registrationBlock = block;
        }

        public void setLastActivityBlock(long block) {
            this.lastActivityBlock = block;
        }

        public void addMetadata(String key, String value) {
            this.metadata.put(key, value);
        }

        public void addFirmwareUpdate(FirmwareUpdate update) {
            this.firmwareHistory.add(update);
            this.firmwareVersion = update.getVersion();
        }

        public void setPufIdentity(byte[] proof, String nullifier) {
            this.pufProof = proof;
            this.pufNullifier = nullifier;
        }
    }

    /**
     * Firmware update record
     */
    public static class FirmwareUpdate {
        private String version;
        private byte[] hash; // SHA-256 hash of firmware
        private long blockHeight;
        private String updatedBy; // Address that initiated update
        private byte[] commitment; // P1-D: ZK commitment to firmware integrity

        public FirmwareUpdate(String version, byte[] hash, long blockHeight, String updatedBy, byte[] commitment) {
            this.version = version;
            this.hash = hash;
            this.blockHeight = blockHeight;
            this.updatedBy = updatedBy;
            this.commitment = commitment;
        }

        public byte[] getCommitment() {
            return commitment;
        }

        public String getVersion() {
            return version;
        }

        public byte[] getHash() {
            return hash;
        }

        public long getBlockHeight() {
            return blockHeight;
        }

        public String getUpdatedBy() {
            return updatedBy;
        }
    }

    // Device registry
    private final Map<String, DeviceRecord> deviceRegistry;

    // Trusted manufacturers (address -> public key)
    private final Map<String, byte[]> trustedManufacturers;

    // SSI Manager reference
    private SSIManager ssiManager;

    // Current blockchain height (injected)
    private long currentBlockHeight;

    // FIX 4: Storage reference for persisting reputation scores on-chain.
    // May be null in lightweight / test contexts; reputation then falls back to in-memory only.
    private com.hybrid.blockchain.Storage storage;

    public void restore(DeviceLifecycleManager other) {
        this.deviceRegistry.clear();
        for (Map.Entry<String, DeviceRecord> entry : other.deviceRegistry.entrySet()) {
            this.deviceRegistry.put(entry.getKey(), entry.getValue().cloneRecord());
        }
        this.trustedManufacturers.clear();
        this.trustedManufacturers.putAll(other.trustedManufacturers);
        this.currentBlockHeight = other.currentBlockHeight;
        this.storage = other.storage;
    }

    public DeviceLifecycleManager(SSIManager ssiManager) {
        this.deviceRegistry = new ConcurrentHashMap<>();
        this.trustedManufacturers = new ConcurrentHashMap<>();
        this.ssiManager = ssiManager;
        this.currentBlockHeight = 0;
    }

    /**
     * Constructs a DeviceLifecycleManager with a Storage back-end for reputation persistence.
     *
     * @param ssiManager the SSI manager for DID operations
     * @param storage    the storage instance (may be null for in-memory-only mode)
     */
    public DeviceLifecycleManager(SSIManager ssiManager, com.hybrid.blockchain.Storage storage) {
        this(ssiManager);
        this.storage = storage;
    }

    /**
     * Injects the storage reference (called after construction when storage is available).
     *
     * @param storage the blockchain storage instance
     */
    public void setStorage(com.hybrid.blockchain.Storage storage) {
        this.storage = storage;
    }

    /**
     * Register a trusted manufacturer
     */
    public void registerManufacturer(String manufacturerId, byte[] publicKey) {
        trustedManufacturers.put(manufacturerId, publicKey);
        log.info("[Lifecycle] Registered manufacturer: {}", manufacturerId);
    }

    /**
     * Provision a new device (initial registration)
     * Requires manufacturer attestation
     */
    public DeviceRecord provisionDevice(String deviceId, String manufacturer, String model, byte[] devicePublicKey, byte[] manufacturerSignature) {
        return provisionDevice(deviceId, manufacturer, model, devicePublicKey, manufacturerSignature, true);
    }

    public DeviceRecord provisionDevice(
            String deviceId,
            String manufacturer,
            String model,
            byte[] devicePublicKey,
            byte[] manufacturerSignature,
            boolean enablePUF) {
        // Check if device already exists
        if (deviceRegistry.containsKey(deviceId)) {
            throw new IllegalStateException("Device already provisioned: " + deviceId);
        }

        // Verify manufacturer is trusted
        if (!trustedManufacturers.containsKey(manufacturer)) {
            throw new SecurityException("Manufacturer not trusted: " + manufacturer);
        }

        // Verify manufacturer attestation
        if (!verifyManufacturerAttestation(deviceId, manufacturer, model, devicePublicKey, manufacturerSignature)) {
            throw new SecurityException("Invalid manufacturer attestation for device: " + deviceId);
        }

        // Create device record
        DeviceRecord record = new DeviceRecord(deviceId, manufacturer, model);
        record.setAttestationSignature(manufacturerSignature);
        record.setAttestedPublicKey(devicePublicKey);   // [HC-047] bind activation to this key
        record.setRegistrationBlock(currentBlockHeight);
        record.setLastActivityBlock(currentBlockHeight);
        record.setStatus(DeviceStatus.PROVISIONING);
        record.setPufEnabled(enablePUF);

        // PAPER-IMPL: P1-C — PUF identity.
        // [HC-049] The nullifier must be a deterministic function of the transaction. It used to be
        // the hash of a freshly generated ZK proof (random nonces) computed INSIDE the state
        // transition, using a device private key "simulated" on the node: every validator stored a
        // different nullifier, so PUF-device telemetry was valid on one node and invalid on another.
        // The nullifier is now a public identifier bound to the attested key; authenticity of
        // readings still comes from the ownership proof the device produces with its own key.
        if (enablePUF) {
            record.setPufIdentity(null, pufNullifier(deviceId, devicePublicKey));
        }
        // Store in registry
        deviceRegistry.put(deviceId, record);

        log.info("[Lifecycle] Provisioned device: {} by {}", deviceId, manufacturer);

        return record;
    }

    /**
     * Simplified registration for internal/test use (no attestation required)
     */
    public void registerDevice(String deviceId, String manufacturer, String model) {
        DeviceRecord record = new DeviceRecord(deviceId, manufacturer, model);
        record.setStatus(DeviceStatus.ACTIVE);
        record.setRegistrationBlock(currentBlockHeight);
        record.setLastActivityBlock(currentBlockHeight);
        deviceRegistry.put(deviceId, record);
    }

    /**
     * Activate device (assign to owner and create DID) - Simplified overload for non-consensus use.
     */
    public void activateDevice(String deviceId, String owner, byte[] devicePublicKey) {
        activateDevice(deviceId, owner, devicePublicKey, System.currentTimeMillis());
    }

    /**
     * Activate device (assign to owner and create DID)
     */
    /** [HC-049] Deterministic, public PUF nullifier for a provisioned device. */
    public static String pufNullifier(String deviceId, byte[] devicePublicKey) {
        return com.hybrid.blockchain.HexUtils.encode(com.hybrid.blockchain.Crypto.hashWithDomain(
                "HC-PUF-NULLIFIER-v1", deviceId.getBytes(java.nio.charset.StandardCharsets.UTF_8), devicePublicKey));
    }

    /** [HC-047] Digest the DEVICE signs to consent to being activated for {@code owner}. */
    public static byte[] activationDigest(String deviceId, String owner) {
        return com.hybrid.blockchain.Crypto.hashWithDomain("HC-DEVICE-ACTIVATE-v1", deviceId, owner);
    }

    /**
     * [HC-047] Activation as executed by an IOT_MANAGEMENT transaction. Before, any sender could
     * activate any PROVISIONING device, name any owner and register any key in its DID (the key
     * telemetry is later authenticated with, HC-013). Now: the sender must be the owner it claims;
     * the key must be the one the manufacturer attested at provisioning; and the device itself must
     * sign {@link #activationDigest} with that key, proving physical possession.
     */
    public void activateDevice(String deviceId, String owner, byte[] devicePublicKey, long timestamp,
                               String caller, byte[] deviceSignature) {
        DeviceRecord record = getDeviceRecord(deviceId);
        if (owner == null || !owner.equals(caller))
            throw new SecurityException("Activation must be sent by the claimed owner");
        byte[] attested = record.getAttestedPublicKey();
        if (attested == null || !Arrays.equals(attested, devicePublicKey))
            throw new SecurityException("Activation key is not the manufacturer-attested device key: " + deviceId);
        if (deviceSignature == null
                || !com.hybrid.blockchain.Crypto.verify(activationDigest(deviceId, owner), deviceSignature, devicePublicKey))
            throw new SecurityException("Missing or invalid device activation signature: " + deviceId);
        activateDevice(deviceId, owner, devicePublicKey, timestamp);
    }

    public void activateDevice(String deviceId, String owner, byte[] devicePublicKey, long timestamp) {
        DeviceRecord record = getDeviceRecord(deviceId);

        // Verify state transition
        if (record.getStatus() != DeviceStatus.PROVISIONING) {
            throw new IllegalStateException("Device not in PROVISIONING state: " + deviceId);
        }

        // Assign owner
        record.setOwner(owner);

        // Create DID through SSI Manager
        String did = ssiManager.registerDID(deviceId, devicePublicKey, owner, timestamp);
        record.setDid(did);

        // Transition to ACTIVE
        record.setStatus(DeviceStatus.ACTIVE);
        record.setLastActivityBlock(currentBlockHeight);

        log.info("[Lifecycle] Activated device: {} DID: {} Owner: {}", deviceId, did, owner);
    }

    /**
     * Update device firmware
     * Only owner can update firmware
     */
    public void updateFirmware(String deviceId, String newVersion, byte[] firmwareHash, String caller, com.hybrid.blockchain.privacy.ZKProofSystem.FirmwareProof proof) {
        DeviceRecord record = getDeviceRecord(deviceId);

        // Verify device is active
        if (record.getStatus() != DeviceStatus.ACTIVE) {
            throw new IllegalStateException("Device not active: " + deviceId);
        }

        // Verify caller is owner
        if (!caller.equals(record.getOwner())) {
            throw new SecurityException("Only device owner can update firmware: " + deviceId);
        }

        // [HC-074] A FirmwareProof is a commitment that can only be checked with the device secret,
        // so it cannot prove anything to a validator. It used to count as "VERIFIED" whenever its
        // hash matched the one being installed (i.e. always, for whoever built it). It is now
        // recorded but never treated as verification; see updateFirmwareSigned.
        applyFirmwareUpdate(record, deviceId, newVersion, firmwareHash, caller,
                false, proof != null ? proof.getCommitment() : null);
    }

    /**
     * [HC-074] Digest a manufacturer signs to vouch for a firmware image for one device.
     */
    public static byte[] firmwareDigest(String deviceId, String version, byte[] firmwareHash) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
            out.write("HYBRIDCHAIN-FIRMWARE-v1\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (byte[] f : new byte[][]{deviceId.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    version.getBytes(java.nio.charset.StandardCharsets.UTF_8), firmwareHash}) {
                out.writeInt(f.length);
                out.write(f);
            }
            out.flush();
            return com.hybrid.blockchain.Crypto.hash(bos.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * [HC-074] Firmware update whose integrity is checked: the device's (trusted) manufacturer
     * signed {@link #firmwareDigest}. Without a valid signature the update is still applied by
     * the owner but marked UNVERIFIED and the reputation penalty applies.
     */
    public void updateFirmwareSigned(String deviceId, String newVersion, byte[] firmwareHash, String caller,
            byte[] manufacturerSignature) {
        DeviceRecord record = getDeviceRecord(deviceId);
        if (record.getStatus() != DeviceStatus.ACTIVE) {
            throw new IllegalStateException("Device not active: " + deviceId);
        }
        if (!caller.equals(record.getOwner())) {
            throw new SecurityException("Only device owner can update firmware: " + deviceId);
        }
        byte[] mfKey = trustedManufacturers.get(record.getManufacturer());
        boolean verified = manufacturerSignature != null && mfKey != null && firmwareHash != null
                && com.hybrid.blockchain.Crypto.verify(firmwareDigest(deviceId, newVersion, firmwareHash),
                        manufacturerSignature, mfKey);
        applyFirmwareUpdate(record, deviceId, newVersion, firmwareHash, caller, verified, null);
    }

    private void applyFirmwareUpdate(DeviceRecord record, String deviceId, String newVersion, byte[] firmwareHash,
            String caller, boolean integrityVerified, byte[] commitment) {
        if (!integrityVerified) {
            log.warn("[Lifecycle] ZK-Firmware integrity proof missing or invalid for device {}. Applying penalty.", deviceId);
            double penalty = record.getReputationScore() * 0.20;
            record.setReputationScore(record.getReputationScore() - penalty);
            if (storage != null) {
                com.hybrid.blockchain.reputation.ReputationEngine.writeScore(deviceId, record.getReputationScore(), storage);
            }
        }

        // Create firmware update record with commitment
        FirmwareUpdate update = new FirmwareUpdate(newVersion, firmwareHash, currentBlockHeight, caller, commitment);
        record.addFirmwareUpdate(update);
        record.setLastActivityBlock(currentBlockHeight);

        log.info("[Lifecycle] Updated firmware for device: {} to version: {} (Integrity: {})",
                deviceId, newVersion, integrityVerified ? "VERIFIED" : "UNVERIFIED");
    }

    /**
     * Legacy updateFirmware for backward compatibility.
     */
    public void updateFirmware(String deviceId, String newVersion, byte[] firmwareHash, String caller) {
        updateFirmware(deviceId, newVersion, firmwareHash, caller, null);
    }

    /**
     * Suspend device (temporary disable)
     */
    public void suspendDevice(String deviceId, String caller, String reason) {
        DeviceRecord record = getDeviceRecord(deviceId);

        // Verify caller is owner
        if (!caller.equals(record.getOwner())) {
            throw new SecurityException("Only device owner can suspend device: " + deviceId);
        }

        // Verify valid state transition
        if (record.getStatus() != DeviceStatus.ACTIVE) {
            throw new IllegalStateException("Can only suspend ACTIVE devices: " + deviceId);
        }

        record.setStatus(DeviceStatus.SUSPENDED);
        record.setLastActivityBlock(currentBlockHeight);
        record.addMetadata("suspensionReason", reason);
        record.addMetadata("suspendedAt", String.valueOf(currentBlockHeight));

        log.info("[Lifecycle] Suspended device: {} Reason: {}", deviceId, reason);
    }

    /**
     * Resume suspended device
     */
    public void resumeDevice(String deviceId, String caller) {
        DeviceRecord record = getDeviceRecord(deviceId);

        // Verify caller is owner
        if (!caller.equals(record.getOwner())) {
            throw new SecurityException("Only device owner can resume device: " + deviceId);
        }

        // Verify valid state transition
        if (record.getStatus() != DeviceStatus.SUSPENDED) {
            throw new IllegalStateException("Device not suspended: " + deviceId);
        }

        record.setStatus(DeviceStatus.ACTIVE);
        record.setLastActivityBlock(currentBlockHeight);
        record.addMetadata("resumedAt", String.valueOf(currentBlockHeight));

        log.info("[Lifecycle] Resumed device: {}", deviceId);
    }

    /**
     * Revoke device (permanent disable for security reasons)
     */
    public void revokeDevice(String deviceId, String caller, String reason) {
        DeviceRecord record = getDeviceRecord(deviceId);

        // Verify caller is owner or admin
        if (!caller.equals(record.getOwner())) {
            // In production, check if caller is admin
            throw new SecurityException("Only device owner can revoke device: " + deviceId);
        }

        // Can revoke from any state except DECOMMISSIONED
        if (record.getStatus() == DeviceStatus.DECOMMISSIONED) {
            throw new IllegalStateException("Cannot revoke decommissioned device: " + deviceId);
        }

        record.setStatus(DeviceStatus.REVOKED);
        record.setLastActivityBlock(currentBlockHeight);
        record.addMetadata("revocationReason", reason);
        record.addMetadata("revokedAt", String.valueOf(currentBlockHeight));

        // Revoke DID
        if (record.getDid() != null) {
            ssiManager.revokeDID(record.getDid(), reason);
        }

        log.info("[Lifecycle] REVOKED device: {} Reason: {}", deviceId, reason);
    }

    /**
     * Decommission device (remove from network)
     */
    public void decommissionDevice(String deviceId, String caller) {
        DeviceRecord record = getDeviceRecord(deviceId);

        // Verify caller is owner
        if (!caller.equals(record.getOwner())) {
            throw new SecurityException("Only device owner can decommission device: " + deviceId);
        }

        // Can only decommission REVOKED or SUSPENDED devices
        if (record.getStatus() != DeviceStatus.REVOKED && record.getStatus() != DeviceStatus.SUSPENDED) {
            throw new IllegalStateException("Can only decommission REVOKED or SUSPENDED devices: " + deviceId);
        }

        record.setStatus(DeviceStatus.DECOMMISSIONED);
        record.setLastActivityBlock(currentBlockHeight);
        record.addMetadata("decommissionedAt", String.valueOf(currentBlockHeight));

        log.info("[Lifecycle] Decommissioned device: {}", deviceId);
    }

    /**
     * Verify manufacturer attestation signature
     */
    private boolean verifyManufacturerAttestation(
            String deviceId,
            String manufacturer,
            String model,
            byte[] devicePublicKey,
            byte[] signature) {
        byte[] manufacturerPublicKey = trustedManufacturers.get(manufacturer);
        if (manufacturerPublicKey == null) {
            return false;
        }

        // Message format: deviceId || manufacturer || model || devicePublicKey
        byte[] message = (deviceId + manufacturer + model).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] combined = new byte[message.length + devicePublicKey.length];
        System.arraycopy(message, 0, combined, 0, message.length);
        System.arraycopy(devicePublicKey, 0, combined, message.length, devicePublicKey.length);

        return Crypto.verify(Crypto.hash(combined), signature, manufacturerPublicKey);
    }

    /**
     * Get device record, refreshing the reputation score from storage on every read.
     *
     * @param deviceId the device identifier
     * @return the DeviceRecord with an up-to-date reputation score
     * @throws IllegalArgumentException if the device is not found
     */
    public DeviceRecord getDeviceRecord(String deviceId) {
        if (deviceId == null) return null;
        DeviceRecord record = deviceRegistry.get(deviceId);
        if (record == null) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }
        // FIX 4: Always refresh reputation from storage so the value reflects
        // any updates made by other components (e.g. slashing, cross-block penalties).
        if (storage != null) {
            double stored = com.hybrid.blockchain.reputation.ReputationEngine.readScore(deviceId, storage);
            record.setReputationScore(stored);
        }
        return record;
    }

    /**
     * Check if device is operational
     */
    public boolean isDeviceOperational(String deviceId) {
        try {
            DeviceRecord record = getDeviceRecord(deviceId);
            return record.getStatus() == DeviceStatus.ACTIVE;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Updates device reputation based on activity outcome, persisting the new score to storage.
     *
     * @param deviceId the device identifier
     * @param success  true if the activity (e.g. telemetry) was anomaly-free
     */
    public void recordDeviceActivity(String deviceId, boolean success) {
        try {
            DeviceRecord record = deviceRegistry.get(deviceId);
            if (record == null) return;

            // Read current score (from storage if available, else in-memory)
            double oldScore = storage != null
                    ? com.hybrid.blockchain.reputation.ReputationEngine.readScore(deviceId, storage)
                    : record.getReputationScore();

            // Apply inactivity penalty first
            double penalisedScore = oldScore;
            if (currentBlockHeight > record.getLastActivityBlock()) {
                long inactivity = currentBlockHeight - record.getLastActivityBlock();
                penalisedScore = com.hybrid.blockchain.reputation.ReputationEngine
                        .applyInactivityPenalty(penalisedScore, inactivity);
            }

            // Apply success/failure delta and persist
            double newScore;
            if (storage != null) {
                // FIX 4: updateScore reads from storage, writes back — atomic for this thread
                // Temporarily write penalised score so updateScore builds on it
                com.hybrid.blockchain.reputation.ReputationEngine.writeScore(deviceId, penalisedScore, storage);
                newScore = com.hybrid.blockchain.reputation.ReputationEngine
                        .updateScore(deviceId, success, storage);
            } else {
                newScore = com.hybrid.blockchain.reputation.ReputationEngine
                        .calculateNewScore(penalisedScore, success);
            }

            record.setReputationScore(newScore);
            record.setLastActivityBlock(currentBlockHeight);
            log.debug("[REPUTATION] Device {}: {:.4f} -> {:.4f} (success={})",
                    deviceId, oldScore, newScore, success);
        } catch (Exception e) {
            log.warn("[REPUTATION] Failed to record activity for {}: {}", deviceId, e.getMessage());
        }
    }

    /**
     * Report an anomaly detected by AI to trigger reputation penalty.
     */
    public void reportAnomaly(String deviceId) {
        recordDeviceActivity(deviceId, false);
    }

    /**
     * Get all devices owned by an address
     */
    public List<DeviceRecord> getDevicesOwnedBy(String owner) {
        List<DeviceRecord> devices = new ArrayList<>();
        for (DeviceRecord record : deviceRegistry.values()) {
            if (owner.equals(record.getOwner())) {
                devices.add(record);
            }
        }
        return devices;
    }

    public int getDeviceReputation(String deviceId) {
        DeviceRecord record = deviceRegistry.get(deviceId);
        return record != null ? (int) record.getReputationScore() : 0;
    }

    /**
     * Set current blockchain height (called by blockchain)
     */
    public void setCurrentBlockHeight(long height) {
        this.currentBlockHeight = height;
    }

    public long getCurrentBlockHeight() {
        return currentBlockHeight;
    }

    /**
     * Get statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();

        Map<DeviceStatus, Long> statusCounts = new EnumMap<>(DeviceStatus.class);
        for (DeviceStatus status : DeviceStatus.values()) {
            statusCounts.put(status, 0L);
        }

        for (DeviceRecord record : deviceRegistry.values()) {
            statusCounts.put(record.getStatus(), statusCounts.get(record.getStatus()) + 1);
        }

        stats.put("totalDevices", deviceRegistry.size());
        stats.put("statusCounts", statusCounts);
        stats.put("trustedManufacturers", trustedManufacturers.size());

        return stats;
    }

    /**
     * Restore state from a map (loaded from blockchain storage)
     */
    public static DeviceLifecycleManager fromMap(Map<String, Object> json, SSIManager ssiManager) {
        DeviceLifecycleManager manager = new DeviceLifecycleManager(ssiManager);
        if (json == null) return manager;

        if (json.containsKey("trustedManufacturers")) {
            Map<String, String> mfs = (Map<String, String>) json.get("trustedManufacturers");
            for (Map.Entry<String, String> entry : mfs.entrySet()) {
                manager.trustedManufacturers.put(entry.getKey(), com.hybrid.blockchain.HexUtils.decode(entry.getValue()));
            }
        }

        Map<String, Map<String, Object>> devices = (Map<String, Map<String, Object>>) json.get("devices");
        if (devices != null) {
            for (Map.Entry<String, Map<String, Object>> entry : devices.entrySet()) {
                Map<String, Object> data = entry.getValue();
                DeviceRecord record = new DeviceRecord(
                    (String) data.get("deviceId"),
                    (String) data.get("manufacturer"),
                    (String) data.get("model")
                );
                record.setDid((String) data.get("did"));
                String statusStr = (String) data.get("status");
                if (statusStr != null && statusStr.contains(":")) statusStr = statusStr.substring(statusStr.lastIndexOf(":") + 1);
                record.setStatus(statusStr != null ? DeviceStatus.valueOf(statusStr) : DeviceStatus.PROVISIONING);
                record.setOwner((String) data.get("owner"));
                record.setFirmwareVersion((String) data.get("firmwareVersion"));
                record.setRegistrationBlock(((Number) data.get("registrationBlock")).longValue());
                if (data.containsKey("reputationScore")) {
                    record.setReputationScore(((Number) data.get("reputationScore")).doubleValue());
                }
                // [HC-048] restore the fields toJSON used to drop
                if (data.get("lastActivityBlock") instanceof Number)
                    record.setLastActivityBlock(((Number) data.get("lastActivityBlock")).longValue());
                if (data.get("pufNullifier") != null) record.setPufIdentity(null, (String) data.get("pufNullifier"));
                if (data.get("pufEnabled") instanceof Boolean) record.setPufEnabled((Boolean) data.get("pufEnabled"));
                if (data.get("attestedPublicKey") != null)
                    record.setAttestedPublicKey(com.hybrid.blockchain.HexUtils.decode((String) data.get("attestedPublicKey")));
                if (data.get("attestationSignature") != null)
                    record.setAttestationSignature(com.hybrid.blockchain.HexUtils.decode((String) data.get("attestationSignature")));
                if (data.get("metadata") instanceof Map) {
                    for (Map.Entry<String, Object> me : ((Map<String, Object>) data.get("metadata")).entrySet())
                        record.addMetadata(me.getKey(), me.getValue() == null ? null : String.valueOf(me.getValue()));   // null stays null
                }
                if (data.get("firmwareHistory") instanceof List) {
                    for (Map<String, Object> m : (List<Map<String, Object>>) data.get("firmwareHistory")) {
                        record.addFirmwareUpdate(new FirmwareUpdate((String) m.get("version"),
                                m.get("hash") == null ? null : com.hybrid.blockchain.HexUtils.decode((String) m.get("hash")),
                                ((Number) m.get("blockHeight")).longValue(), (String) m.get("updatedBy"),
                                m.get("commitment") == null ? null : com.hybrid.blockchain.HexUtils.decode((String) m.get("commitment"))));
                    }
                }

                manager.deviceRegistry.put(entry.getKey(), record);
            }
        }

        Object heightObj = json.get("currentBlockHeight");
        if (heightObj instanceof Number) {
            manager.currentBlockHeight = ((Number) heightObj).longValue();
        }

        return manager;
    }

    /**
     * Serialize state for blockchain storage
     */
    private static String hexOrNull(byte[] b) {
        return b == null ? null : com.hybrid.blockchain.HexUtils.bytesToHex(b);
    }

    public Map<String, Object> toJSON() {
        Map<String, Object> json = new HashMap<>();

        Map<String, Map<String, Object>> devices = new HashMap<>();
        for (Map.Entry<String, DeviceRecord> entry : deviceRegistry.entrySet()) {
            DeviceRecord record = entry.getValue();
            Map<String, Object> deviceData = new HashMap<>();
            deviceData.put("deviceId", record.getDeviceId());
            deviceData.put("did", record.getDid());
            deviceData.put("status", record.getStatus().name());
            deviceData.put("owner", record.getOwner());
            deviceData.put("manufacturer", record.getManufacturer());
            deviceData.put("model", record.getModel());
            deviceData.put("firmwareVersion", record.getFirmwareVersion());
            deviceData.put("registrationBlock", record.getRegistrationBlock());
            deviceData.put("reputationScore", record.getReputationScore());
            // [HC-048] every consensus-relevant field (state root, clones, restarts)
            deviceData.put("lastActivityBlock", record.getLastActivityBlock());
            deviceData.put("pufNullifier", record.getPufNullifier());
            deviceData.put("pufEnabled", record.isPufEnabled());
            deviceData.put("attestedPublicKey", hexOrNull(record.getAttestedPublicKey()));
            deviceData.put("attestationSignature", hexOrNull(record.getAttestationSignature()));
            deviceData.put("metadata", new java.util.TreeMap<>(record.getMetadata()));
            List<Map<String, Object>> fw = new ArrayList<>();
            for (FirmwareUpdate u : record.getFirmwareHistory()) {
                Map<String, Object> m = new HashMap<>();
                m.put("version", u.getVersion());
                m.put("hash", hexOrNull(u.getHash()));
                m.put("blockHeight", u.getBlockHeight());
                m.put("updatedBy", u.getUpdatedBy());
                m.put("commitment", hexOrNull(u.getCommitment()));
                fw.add(m);
            }
            deviceData.put("firmwareHistory", fw);
            devices.put(entry.getKey(), deviceData);
        }
        Map<String, String> mfs = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : trustedManufacturers.entrySet()) {
            mfs.put(entry.getKey(), com.hybrid.blockchain.HexUtils.bytesToHex(entry.getValue()));
        }
        json.put("trustedManufacturers", mfs);

        json.put("devices", devices);
        json.put("currentBlockHeight", currentBlockHeight);
        return json;
    }
}
