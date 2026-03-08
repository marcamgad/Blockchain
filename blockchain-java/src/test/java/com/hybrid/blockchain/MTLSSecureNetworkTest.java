package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class MTLSSecureNetworkTest {

    @Test
    public void testMTLSHandshake() throws Exception {
        deleteDir(new File("test-data-1"));
        deleteDir(new File("test-data-2"));

        Map<String, byte[]> validators = new HashMap<>();
        for (int i = 1; i <= 4; i++) {
            BigInteger priv = BigInteger.valueOf(i * 100);
            byte[] pub = Crypto.derivePublicKey(priv);
            validators.put(Crypto.deriveAddress(pub), pub);
        }

        // Setup Node 1
        BigInteger priv1 = BigInteger.valueOf(100);
        byte[] pub1 = Crypto.derivePublicKey(priv1);
        String addr1 = Crypto.deriveAddress(pub1);
        
        Blockchain bc1 = new Blockchain(new Storage("test-data-1", Config.STORAGE_AES_KEY), new Mempool(), null);
        PBFTConsensus pbft1 = new PBFTConsensus(validators, addr1, priv1);
        PeerNode node1 = new PeerNode(9001, bc1, pbft1);
        node1.start();

        // Setup Node 2
        BigInteger priv2 = BigInteger.valueOf(200);
        byte[] pub2 = Crypto.derivePublicKey(priv2);
        String addr2 = Crypto.deriveAddress(pub2);
        
        Blockchain bc2 = new Blockchain(new Storage("test-data-2", Config.STORAGE_AES_KEY), new Mempool(), null);
        PBFTConsensus pbft2 = new PBFTConsensus(validators, addr2, priv2);
        PeerNode node2 = new PeerNode(9002, bc2, pbft2);
        node2.start();

        // Connect Node 2 to Node 1
        node2.connectToPeer("localhost", 9001);

        // Wait for handshake
        Thread.sleep(3000);

        // Verify peers are connected
        assertFalse(node2.getPeers().isEmpty(), "Node 2 should have connected to Node 1");
        
        node1.shutdown();
        node2.shutdown();
    }

    private void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }
}
