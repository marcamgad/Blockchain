package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.api.IoTRestAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-081] Found by the live leader-crash scenario: after a restart from a snapshot the in-memory
 * chain list started at block 2, and GET /blocks/height/{h} returned list[h] (block 2 for height
 * 0), so the fault-injection checker reported a fork that did not exist.
 */
class BlockByHeightTest {

    @Test
    @DisplayName("GET /blocks/height/{h} returns the block whose index is h, even when the in-memory chain does not start at genesis")
    void heightIsTheBlockIndex() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture(2)) {        // PrunedBlockchain: keeps the last 2 blocks in memory
            for (int i = 0; i < 5; i++) f.commitAndApply(f.build(List.of(), 0));
            assertThat(f.chain.getChain().get(0).getIndex()).as("precondition: list does not start at genesis").isGreaterThan(0);

            IoTRestAPI api = new IoTRestAPI();
            Field fld = IoTRestAPI.class.getDeclaredField("blockchain");
            fld.setAccessible(true);
            fld.set(api, f.chain);
            for (int h = 0; h <= f.chain.getHeight(); h++) {
                ResponseEntity<?> r = api.getBlockByHeight(h);
                if (r.getStatusCode().value() == 200) {
                    assertThat(((Block) r.getBody()).getIndex()).as("block returned for height " + h).isEqualTo(h);
                }
            }
            int top = (int) f.chain.getHeight();
            assertThat(api.getBlockByHeight(top).getStatusCode().value()).isEqualTo(200);
            assertThat(api.getBlockByHeight(top + 1).getStatusCode().value()).isEqualTo(404);
            assertThat(api.getBlockByHeight(-1).getStatusCode().value()).isEqualTo(404);
        }
    }
}
