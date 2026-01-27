package com.hybrid.blockchain;

import com.hybrid.blockchain.identity.DecentralizedIdentifier;
import com.hybrid.blockchain.identity.SSIManager;
import com.hybrid.blockchain.identity.VerifiableCredential;
import com.hybrid.blockchain.lifecycle.DeviceLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Self-Sovereign Identity (SSI) framework
 */
public class SSIIntegrationTest {

    private SSIManager ssiManager;
    private BigInteger ownerPrivateKey;
    private byte[] ownerPublicKey;
    private String ownerAddress;

    @BeforeEach
    public void setUp() {
        ssiManager = new SSIManager();
        ownerPrivateKey = new BigInteger("1234567890123456789012345678901234567890123456789012345678901234", 16);
        ownerPublicKey = Crypto.derivePublicKey(ownerPrivateKey);
        ownerAddress = Crypto.deriveAddress(ownerPublicKey);
    }

    @Test
    public void testDIDRegistration() {
        // Register a DID for a device
        String deviceId = "sensor-001";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(new BigInteger("11111111111111111111111111111111111111111111111111111111111111", 16));

        String did = ssiManager.registerDID(deviceId, devicePublicKey, ownerAddress);

        // Verify DID format
        assertEquals("did:iot:sensor-001", did);

        // Resolve DID
        DecentralizedIdentifier didDoc = ssiManager.resolveDID(did);
        assertNotNull(didDoc);
        assertEquals(ownerAddress, didDoc.getController());
        assertEquals(deviceId, didDoc.getDeviceId());

        // Verify lookup by device ID
        assertEquals(did, ssiManager.getDIDForDevice(deviceId));
    }

    @Test
    public void testDIDOwnership() {
        String deviceId = "sensor-002";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(new BigInteger("22222222222222222222222222222222222222222222222222222222222222", 16));

        String did = ssiManager.registerDID(deviceId, devicePublicKey, ownerAddress);

        // Verify ownership
        assertTrue(ssiManager.isOwner(did, ownerAddress));
        assertFalse(ssiManager.isOwner(did, "hb0000000000000000000000000000000000000000"));

        // Get all DIDs owned by address
        List<String> ownedDIDs = ssiManager.getDIDsOwnedBy(ownerAddress);
        assertTrue(ownedDIDs.contains(did));
    }

    @Test
    public void testVerifiableCredential() {
        // Setup: Register device DID
        String deviceId = "sensor-003";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(new BigInteger("33333333333333333333333333333333333333333333333333333333333333", 16));
        String deviceDID = ssiManager.registerDID(deviceId, devicePublicKey, ownerAddress);

        // Issuer DID
        String issuerDID = ssiManager.registerDID("manufacturer-001", ownerPublicKey, ownerAddress);

        // Issue a capability credential
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "TemperatureSensorCapability");
        claims.put("range", "0-100°C");
        claims.put("accuracy", "±0.5°C");

        VerifiableCredential vc = ssiManager.issueCredential(
                issuerDID,
                deviceDID,
                claims,
                ownerPrivateKey,
                ownerPublicKey);

        // Verify credential was issued
        assertNotNull(vc);
        assertEquals(issuerDID, vc.getIssuer());
        assertEquals(deviceDID, vc.getCredentialSubject().getId());

        // Verify credential signature
        assertTrue(vc.verify(ownerPublicKey));

        // Check device has credential
        assertTrue(ssiManager.hasCredential(deviceDID, "TemperatureSensorCapability"));
        assertFalse(ssiManager.hasCredential(deviceDID, "NonExistentCapability"));
    }

    @Test
    public void testDIDRevocation() {
        String deviceId = "sensor-004";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(new BigInteger("44444444444444444444444444444444444444444444444444444444444444", 16));

        String did = ssiManager.registerDID(deviceId, devicePublicKey, ownerAddress);

        // Revoke DID
        ssiManager.revokeDID(did, "Device compromised");

        // Verify DID is revoked (should throw exception)
        assertThrows(SecurityException.class, () -> {
            ssiManager.resolveDID(did);
        });

        // Verify device ID mapping is removed
        assertNull(ssiManager.getDIDForDevice(deviceId));
    }

    @Test
    public void testMultipleCredentials() {
        String deviceId = "sensor-005";
        byte[] devicePublicKey = Crypto
                .derivePublicKey(new BigInteger("55555555555555555555555555555555555555555555555555555555555555", 16));
        String deviceDID = ssiManager.registerDID(deviceId, devicePublicKey, ownerAddress);
        String issuerDID = ssiManager.registerDID("issuer-001", ownerPublicKey, ownerAddress);

        // Issue multiple credentials
        Map<String, Object> claims1 = new HashMap<>();
        claims1.put("type", "TemperatureSensor");
        ssiManager.issueCredential(issuerDID, deviceDID, claims1, ownerPrivateKey, ownerPublicKey);

        Map<String, Object> claims2 = new HashMap<>();
        claims2.put("type", "HumiditySensor");
        ssiManager.issueCredential(issuerDID, deviceDID, claims2, ownerPrivateKey, ownerPublicKey);

        // Verify device has both credentials
        List<VerifiableCredential> credentials = ssiManager.getCredentials(deviceDID);
        assertEquals(2, credentials.size());
    }

    @Test
    public void testDIDSignatureVerification() {
        String deviceId = "sensor-006";
        BigInteger devicePrivateKey = new BigInteger("66666666666666666666666666666666666666666666666666666666666666",
                16);
        byte[] devicePublicKey = Crypto.derivePublicKey(devicePrivateKey);

        String did = ssiManager.registerDID(deviceId, devicePublicKey, ownerAddress);

        // Sign a message with device key
        byte[] message = "Test message".getBytes();
        byte[] signature = Crypto.sign(message, devicePrivateKey);

        // Verify signature using DID
        assertTrue(ssiManager.verifyDIDSignature(did, message, signature));

        // Verify wrong signature fails
        byte[] wrongSignature = Crypto.sign("Wrong message".getBytes(), devicePrivateKey);
        assertFalse(ssiManager.verifyDIDSignature(did, message, wrongSignature));
    }

    @Test
    public void testSSIStats() {
        // Register multiple DIDs
        for (int i = 0; i < 5; i++) {
            String deviceId = "device-" + i;
            byte[] pubKey = Crypto.derivePublicKey(new BigInteger(String.valueOf(i + 1000)));
            ssiManager.registerDID(deviceId, pubKey, ownerAddress);
        }

        Map<String, Object> stats = ssiManager.getStats();
        assertEquals(5, stats.get("totalDIDs"));
        assertEquals(5, stats.get("activeDIDs"));
        assertEquals(0, stats.get("revokedDIDs"));
    }
}
