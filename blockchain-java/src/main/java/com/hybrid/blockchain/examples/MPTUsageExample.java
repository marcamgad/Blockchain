package com.hybrid.blockchain.examples;

import com.hybrid.blockchain.AccountState;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.MerklePatriciaTrie;
import java.util.List;

/**
 * Demonstrates the usage of the Merkle Patricia Trie (MPT) for 
 * cryptographic state verification in the Hybrid IoT Blockchain.
 */
public class MPTUsageExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Hybrid IoT Blockchain: MPT Usage Example ===\n");

        // 1. Create a new AccountState (manages the MPT)
        AccountState state = new AccountState();

        // 2. Add some accounts
        String aliceAddr = "0xAlice";
        state.credit(aliceAddr, 1000);

        String bobAddr = "0xBob";
        state.credit(bobAddr, 500);

        // 3. Compute the State Root
        String stateRoot = state.calculateStateRoot();
        System.out.println("State Root: " + stateRoot);

        // 4. Generate a Merkle Proof for Alice's account
        System.out.println("\nGenerating Merkle Proof for " + aliceAddr + "...");
        List<byte[]> proof = state.getAccountProof(aliceAddr);
        System.out.println("Proof size: " + proof.size() + " nodes");

        // 5. Verify the Proof (Light Client Scenario)
        // A light client only needs the State Root and the Proof to verify Alice's state
        byte[] aliceKey = aliceAddr.getBytes();
        byte[] expectedValue = state.getSerializedAccount(aliceAddr);
        byte[] rootBytes = Crypto.hexToBytes(stateRoot);

        boolean isValid = MerklePatriciaTrie.verifyAccountProof(aliceKey, expectedValue, proof, rootBytes);
        
        System.out.println("Verification Result: " + (isValid ? "VALID" : "INVALID"));

        // 6. Practical verification of a value
        if (isValid) {
            System.out.println("Confirmed " + aliceAddr + " state is correctly reflected in root " + stateRoot);
        }

        // 7. Compact Proof for IoT Devices
        System.out.println("\nGenerating Compact Proof for " + aliceAddr + "...");
        byte[] compactProof = state.getCompactAccountProof(aliceAddr);
        System.out.println("Compact proof size: " + compactProof.length + " bytes");
        // To verify a compact proof, the client would first deserialize it back to a List<byte[]>
        
        // 8. Demonstration of Proof of Non-Existence
        String malloryAddr = "0xMallory";
        System.out.println("\nGenerating Proof of Non-Existence for " + malloryAddr + "...");
        List<byte[]> nonExistentProof = state.getAccountProof(malloryAddr);
        boolean nonExistentValid = MerklePatriciaTrie.verifyAccountProof(malloryAddr.getBytes(), null, nonExistentProof, rootBytes);
        System.out.println("Non-Existence Verification Result: " + (nonExistentValid ? "VALID (Account does not exist)" : "INVALID"));
    }
}
