package com.hybrid.blockchain;

import com.hybrid.blockchain.privacy.PrivateDataCollection;
import com.hybrid.blockchain.privacy.PrivateDataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Private Data Collections
 * Each test gets a fresh manager instance to avoid state leakage
 */
public class PrivateDataCollectionTest {

    private PrivateDataManager manager;
    private String member1;
    private String member2;
    private String nonMember;

    @BeforeEach
    public void setUp() {
        // Create fresh manager for each test (avoids state leakage)
        manager = new PrivateDataManager();

        // Initialize test addresses
        member1 = "hb1111111111111111111111111111111111111111";
        member2 = "hb2222222222222222222222222222222222222222";
        nonMember = "hb3333333333333333333333333333333333333333";
    }

    @Test
    public void testCreateCollection() {
        List<String> members = Arrays.asList(member1, member2);

        PrivateDataCollection collection = manager.createCollection("health-data", members, member1);

        assertNotNull(collection);
        assertEquals("health-data", collection.getCollectionId());
        assertTrue(collection.isAuthorized(member1));
        assertTrue(collection.isAuthorized(member2));
        assertFalse(collection.isAuthorized(nonMember));
    }

    @Test
    public void testWriteAndReadPrivateData() {
        List<String> members = Arrays.asList(member1, member2);
        PrivateDataCollection collection = manager.createCollection("sensor-data", members, member1);

        // Write data
        byte[] data = "Temperature: 25.5°C".getBytes();
        collection.writePrivateData("temp-001", data, member1);

        // Read data as authorized member
        byte[] retrieved = collection.readPrivateData("temp-001", member1);
        assertArrayEquals(data, retrieved);

        // Another member can also read
        byte[] retrieved2 = collection.readPrivateData("temp-001", member2);
        assertArrayEquals(data, retrieved2);
    }

    @Test
    public void testUnauthorizedAccess() {
        List<String> members = Arrays.asList(member1, member2);
        PrivateDataCollection collection = manager.createCollection("private-data", members, member1);

        byte[] data = "Secret data".getBytes();
        collection.writePrivateData("secret-001", data, member1);

        // Non-member cannot read
        assertThrows(SecurityException.class, () -> {
            collection.readPrivateData("secret-001", nonMember);
        });

        // Non-member cannot write
        assertThrows(SecurityException.class, () -> {
            collection.writePrivateData("secret-002", data, nonMember);
        });
    }

    @Test
    public void testDataIntegrityVerification() {
        List<String> members = Arrays.asList(member1);
        PrivateDataCollection collection = manager.createCollection("integrity-test", members, member1);

        byte[] data = "Original data".getBytes();
        collection.writePrivateData("data-001", data, member1);

        // Verify with correct data
        assertTrue(collection.verifyDataIntegrity("data-001", data));

        // Verify with wrong data
        byte[] wrongData = "Wrong data".getBytes();
        assertFalse(collection.verifyDataIntegrity("data-001", wrongData));
    }

    @Test
    public void testPublicHashAccess() {
        List<String> members = Arrays.asList(member1);
        PrivateDataCollection collection = manager.createCollection("hash-test", members, member1);

        byte[] data = "Test data".getBytes();
        collection.writePrivateData("data-001", data, member1);

        // Anyone can get public hash (even non-members)
        byte[] hash = collection.getPublicHash("data-001");
        assertNotNull(hash);
        assertEquals(32, hash.length); // SHA-256 hash
    }

    @Test
    public void testAddRemoveMember() {
        List<String> members = Arrays.asList(member1);
        PrivateDataCollection collection = manager.createCollection("member-test", members, member1);

        // Initially only member1 is authorized
        assertTrue(collection.isAuthorized(member1));
        assertFalse(collection.isAuthorized(member2));

        // Add member2
        collection.addAuthorizedMember(member2, member1);
        assertTrue(collection.isAuthorized(member2));

        // member2 can now read data
        byte[] data = "Test".getBytes();
        collection.writePrivateData("test", data, member1);
        byte[] retrieved = collection.readPrivateData("test", member2);
        assertArrayEquals(data, retrieved);

        // Remove member2
        collection.removeAuthorizedMember(member2, member1);
        assertFalse(collection.isAuthorized(member2));

        // member2 can no longer read
        assertThrows(SecurityException.class, () -> {
            collection.readPrivateData("test", member2);
        });
    }

    @Test
    public void testDeleteData() {
        List<String> members = Arrays.asList(member1);
        PrivateDataCollection collection = manager.createCollection("delete-test", members, member1);

        byte[] data = "Data to delete".getBytes();
        collection.writePrivateData("data-001", data, member1);

        // Verify data exists
        assertTrue(collection.hasData("data-001"));

        // Delete data
        collection.deleteData("data-001", member1);

        // Verify data is gone
        assertFalse(collection.hasData("data-001"));
        assertNull(collection.readPrivateData("data-001", member1));
    }

    @Test
    public void testMultipleCollections() {
        List<String> members1 = Arrays.asList(member1);
        List<String> members2 = Arrays.asList(member2);

        manager.createCollection("collection-1", members1, member1);
        manager.createCollection("collection-2", members2, member2);

        assertTrue(manager.hasCollection("collection-1"));
        assertTrue(manager.hasCollection("collection-2"));

        // Get collections for each member
        List<String> member1Collections = manager.getCollectionsForMember(member1);
        assertEquals(1, member1Collections.size());
        assertTrue(member1Collections.contains("collection-1"));

        List<String> member2Collections = manager.getCollectionsForMember(member2);
        assertEquals(1, member2Collections.size());
        assertTrue(member2Collections.contains("collection-2"));
    }

    @Test
    public void testCollectionStats() {
        List<String> members = Arrays.asList(member1, member2);
        PrivateDataCollection collection = manager.createCollection("stats-test", members, member1);

        // Write multiple data items
        collection.writePrivateData("data-1", "Data 1".getBytes(), member1);
        collection.writePrivateData("data-2", "Data 2".getBytes(), member1);
        collection.writePrivateData("data-3", "Data 3".getBytes(), member2);

        var stats = collection.getStats();
        assertEquals("stats-test", stats.get("collectionId"));
        assertEquals(2, stats.get("authorizedMembers"));
        assertEquals(3, stats.get("dataItems"));
    }

    @Test
    public void testManagerStats() {
        List<String> members = Arrays.asList(member1);

        manager.createCollection("col-1", members, member1);
        manager.createCollection("col-2", members, member1);

        PrivateDataCollection col1 = manager.getCollection("col-1");
        col1.writePrivateData("data-1", "Test".getBytes(), member1);
        col1.writePrivateData("data-2", "Test".getBytes(), member1);

        PrivateDataCollection col2 = manager.getCollection("col-2");
        col2.writePrivateData("data-3", "Test".getBytes(), member1);

        var stats = manager.getStats();
        assertEquals(2, stats.get("totalCollections"));
        assertEquals(3, stats.get("totalDataItems"));
    }
}
