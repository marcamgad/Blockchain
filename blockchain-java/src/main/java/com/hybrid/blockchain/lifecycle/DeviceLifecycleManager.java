package com.hybrid.blockchain.lifecycle;

import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.identity.SSIManager;
import com.hybrid.blockchain.identity.VerifiableCredential;
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
        private List<FirmwareUpdate> firmwareHistory;
        private Map<String, String> metadata;

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
    }

    /**
     * Firmware update record
     */
    public static class FirmwareUpdate {
        private String version;
        private byte[] hash; // SHA-256 hash of firmware
        private long blockHeight;
        private String updatedBy; // Address that initiated update

        public FirmwareUpdate(String version, byte[] hash, long blockHeight, String updatedBy) {
            this.version = version;
            this.hash = hash;
            this.blockHeight = blockHeight;
            this.updatedBy = updatedBy;
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

    public DeviceLifecycleManager(SSIManager ssiManager) {
        this.deviceRegistry = new ConcurrentHashMap<>();
        this.trustedManufacturers = new ConcurrentHashMap<>();
        this.ssiManager = ssiManager;
        this.currentBlockHeight = 0;
    }

    /**
     * Register a trusted manufacturer
     */
    public void registerManufacturer(String manufacturerId, byte[] publicKey) {
        trustedManufacturers.put(manufacturerId, publicKey);
        System.out.println("[Lifecycle] Registered manufacturer: " + manufacturerId);
    }

    /**
     * Provision a new device (initial registration)
     * Requires manufacturer attestation
     */
    public DeviceRecord provisionDevice(
            String deviceId,
            String manufacturer,
            String model,
            byte[] devicePublicKey,
            byte[] manufacturerSignature) {
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
        record.setRegistrationBlock(currentBlockHeight);
        record.setLastActivityBlock(currentBlockHeight);
        record.setStatus(DeviceStatus.PROVISIONING);

        // Store in registry
        deviceRegistry.put(deviceId, record);

        System.out.println("[Lifecycle] Provisioned device: " + deviceId + " by " + manufacturer);

        return record;
    }

    /**
     * Activate device (assign to owner and create DID)
     */
    public void activateDevice(String deviceId, String owner, byte[] devicePublicKey) {
        DeviceRecord record = getDeviceRecord(deviceId);

        // Verify state transition
        if (record.getStatus() != DeviceStatus.PROVISIONING) {
            throw new IllegalStateException("Device not in PROVISIONING state: " + deviceId);
        }

        // Assign owner
        record.setOwner(owner);

        // Create DID through SSI Manager
        String did = ssiManager.registerDID(deviceId, devicePublicKey, owner);
        record.setDid(did);

        // Transition to ACTIVE
        record.setStatus(DeviceStatus.ACTIVE);
        record.setLastActivityBlock(currentBlockHeight);

        System.out.println("[Lifecycle] Activated device: " + deviceId + " DID: " + did + " Owner: " + owner);
    }

    /**
     * Update device firmware
     * Only owner can update firmware
     */
    public void updateFirmware(String deviceId, String newVersion, byte[] firmwareHash, String caller) {
        DeviceRecord record = getDeviceRecord(deviceId);

        // Verify device is active
        if (record.getStatus() != DeviceStatus.ACTIVE) {
            throw new IllegalStateException("Device not active: " + deviceId);
        }

        // Verify caller is owner
        if (!caller.equals(record.getOwner())) {
            throw new SecurityException("Only device owner can update firmware: " + deviceId);
        }

        // Create firmware update record
        FirmwareUpdate update = new FirmwareUpdate(newVersion, firmwareHash, currentBlockHeight, caller);
        record.addFirmwareUpdate(update);
        record.setLastActivityBlock(currentBlockHeight);

        System.out.println("[Lifecycle] Updated firmware for device: " + deviceId + " to version: " + newVersion);
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

        System.out.println("[Lifecycle] Suspended device: " + deviceId + " Reason: " + reason);
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

        System.out.println("[Lifecycle] Resumed device: " + deviceId);
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

        System.out.println("[Lifecycle] REVOKED device: " + deviceId + " Reason: " + reason);
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

        System.out.println("[Lifecycle] Decommissioned device: " + deviceId);
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
        byte[] message = (deviceId + manufacturer + model).getBytes();
        byte[] combined = new byte[message.length + devicePublicKey.length];
        System.arraycopy(message, 0, combined, 0, message.length);
        System.arraycopy(devicePublicKey, 0, combined, message.length, devicePublicKey.length);

        return Crypto.verify(Crypto.hash(combined), signature, manufacturerPublicKey);
    }

    /**
     * Get device record
     */
    public DeviceRecord getDeviceRecord(String deviceId) {
        DeviceRecord record = deviceRegistry.get(deviceId);
        if (record == null) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
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

    /**
     * Set current blockchain height (called by blockchain)
     */
    public void setCurrentBlockHeight(long height) {
        this.currentBlockHeight = height;
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
     * Serialize state for blockchain storage
     */
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
            devices.put(entry.getKey(), deviceData);
        }

        json.put("devices", devices);
        return json;
    }
}
