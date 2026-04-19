package com.hybrid.blockchain.privacy;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("Privacy")
public class PrivateDataManagerTest {

    private PrivateDataManager manager;
    private final String alice = "0xAlice";
    private final String bob = "0xBob";
    private final String mallory = "0xMallory";
    private final String collId = "health-collection";

    @BeforeEach
    public void setup() {
        manager = new PrivateDataManager();
        manager.createCollection(collId, Arrays.asList(alice, bob), alice);
    }

    @Test
    @DisplayName("C6.1: Authorized Access to Private Data")
    public void testAuthorizedAccessToPrivateData() {
        PrivateDataCollection coll = manager.getCollection(collId);
        byte[] data = "sensitive-heart-rate-90".getBytes();
        
        // Alice writes
        coll.writePrivateData("hr-001", data, alice);
        
        // Bob reads
        byte[] readData = coll.readPrivateData("hr-001", bob);
        assertThat(readData).isEqualTo(data);
        
        // Bob verifies integrity
        assertThat(coll.verifyDataIntegrity("hr-001", data)).isTrue();
    }

    @Test
    @DisplayName("C6.2: Unauthorized Access Rejected")
    public void testUnauthorizedAccessRejected() {
        PrivateDataCollection coll = manager.getCollection(collId);
        byte[] data = "sensitive-heart-rate-90".getBytes();
        
        // Alice writes
        coll.writePrivateData("hr-001", data, alice);
        
        // Mallory (stranger) tries to read
        assertThrows(SecurityException.class, () -> 
            coll.readPrivateData("hr-001", mallory)
        );
        
        // Mallory tries to write
        assertThrows(SecurityException.class, () -> 
            coll.writePrivateData("hr-002", "fake-data".getBytes(), mallory)
        );
    }
}
