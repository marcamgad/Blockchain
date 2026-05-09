package com.hybrid.blockchain.anchoring;

import com.hybrid.blockchain.Block;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("severe")
public class AnchoringTest {

    private StateAnchoringManager anchoringManager;

    @BeforeEach
    void setUp() {
        anchoringManager = new StateAnchoringManager();
    }

    @Test
    @DisplayName("ANCHOR.1: Should anchor every 1000 blocks")
    void testPeriodicAnchoring() {
        Block b1000 = new Block(1000, System.currentTimeMillis(), new ArrayList<>(), "prev", 4, "root1000");
        anchoringManager.processBlock(b1000, "root1000");
        
        assertThat(anchoringManager.isAnchored(1000)).isTrue();
        assertThat(anchoringManager.getAnchorTx(1000)).startsWith("0x");
        
        Block b500 = new Block(500, System.currentTimeMillis(), new ArrayList<>(), "prev", 4, "root500");
        anchoringManager.processBlock(b500, "root500");
        assertThat(anchoringManager.isAnchored(500)).isFalse();
    }
}
