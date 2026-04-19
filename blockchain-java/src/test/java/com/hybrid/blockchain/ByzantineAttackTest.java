package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ByzantineAttackTest {

    @Test
    @DisplayName("Severe: Byzantine validator sending malformed block must be rejected")
    void testMalformedBlockByzantine() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            
            // Fabricate a block with invalid state root (Byzantine lie)
            Block malformed = new Block(
                chain.getHeight() + 1,
                System.currentTimeMillis(),
                new ArrayList<>(),
                chain.getLatestBlock().getHash(),
                chain.getDifficulty(),
                "FAKE_ROOT_HASH"
            );
            
            malformed.setValidatorId("ValidatorA");
            malformed.setSignature(new byte[64]);
            
            // Applying should fail because of state root mismatch during execution
            assertThatThrownBy(() -> chain.applyBlock(malformed))
                .isInstanceOf(Exception.class);
        }
    }

    @Test
    @DisplayName("Severe: Double Signing must result in immediate banning")
    void testDoubleSigningBan() throws Exception {
        TestKeyPair v1 = new TestKeyPair(1);
        Validator val = new Validator(v1.getAddress(), v1.getPublicKey());
        
        // n=1, f=0 for simplicity
        PoAConsensus poa = new PoAConsensus(Collections.singletonList(val));
        
        Block b1 = new Block(1, 1000L, new ArrayList<>(), "0000", 0, "root");
        b1.setValidatorId(v1.getAddress());
        poa.signBlock(b1, val, v1.getPrivateKey());
        poa.verifyBlock(b1, val);
        
        Block b2 = new Block(1, 2000L, new ArrayList<>(), "0000", 0, "root"); // Same height
        b2.setValidatorId(v1.getAddress());
        poa.signBlock(b2, val, v1.getPrivateKey());
        poa.verifyBlock(b2, val);
        
        assertThat(poa.getSlashedValidators()).contains(v1.getAddress());
        assertThat(poa.isValidator(v1.getAddress())).isFalse();
    }
}
