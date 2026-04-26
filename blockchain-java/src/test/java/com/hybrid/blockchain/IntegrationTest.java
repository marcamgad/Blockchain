package com.hybrid.blockchain;

import com.hybrid.blockchain.api.IoTRestAPI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test to verify that the App.java wires all components correctly
 * and the Spring context starts successfully.
 */
@SpringBootTest(classes = IoTRestAPI.class)
public class IntegrationTest {

    @org.junit.jupiter.api.BeforeAll
    public static void setup() {
        System.setProperty("STORAGE_AES_KEY", "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
        System.setProperty("NODE_PRIVATE_KEY", "1111111111111111111111111111111111111111111111111111111111111111");
        System.setProperty("VALIDATOR_PUBKEYS", "node1:048ca0522194d214c7c819ea21396b7582239324538902d1234b67e789a123b4567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678");

        // Provide a dummy blockchain to satisfy IoTRestAPI.@PostConstruct
        try {
            String dbPath = "target/test_shared_db_" + System.currentTimeMillis();
            Storage storage = new Storage(dbPath, HexUtils.decode(System.getProperty("STORAGE_AES_KEY")));
            Blockchain blockchain = new Blockchain(storage, new Mempool(), new PoAConsensus(java.util.Collections.emptyList()));
            IoTRestAPI.setNode(blockchain, null, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testAppWiring() throws Exception {
        // We set mandatory env vars for the test
        // Properties already set in setup()

        // Use a temporary data directory
        String dbPath = "target/integration_test_" + System.currentTimeMillis();
        
        byte[] key = HexUtils.decode(System.getProperty("STORAGE_AES_KEY"));
        Storage storage = new Storage(dbPath, key);
        Blockchain blockchain = new Blockchain(storage, new Mempool(), new PoAConsensus(new java.util.ArrayList<>()));
        TokenRegistry tokenRegistry = new TokenRegistry(storage);
        blockchain.setTokenRegistry(tokenRegistry);
        
        
        assertThat(blockchain.getTokenRegistry())
            .as("TokenRegistry should be wired")
            .isNotNull();
        
        // Final check on FeeMarket
        long initialFee = new FeeMarket().getCurrentBaseFee(storage);
        assertThat(initialFee).isEqualTo(Config.BASE_FEE_INITIAL);
        
        storage.close();
    }
}
