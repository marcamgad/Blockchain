package com.hybrid.blockchain.consensus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-092] The "async fast path" (RWA-BFT) let a validator COMMIT without a PREPARE quorum when a
 * committee chosen by NODE-LOCAL reputation had committed the previous height before a WALL-CLOCK
 * deadline. Committing without a prepared certificate leaves VIEW_CHANGE nothing to carry, so a
 * NEW_VIEW could replace a block that was already committed somewhere. It was off by default and
 * nothing enabled it; it is removed so that it cannot be switched on. (Replaces AsyncConsensusTest,
 * which tested the removed feature.)
 */
class NoFastPathTest {

    @Test
    @DisplayName("PBFTConsensus has no way to commit without a PREPARE quorum")
    void fastPathRemoved() {
        String[] names = Arrays.stream(PBFTConsensus.class.getMethods()).map(Method::getName).toArray(String[]::new);
        assertThat(names).doesNotContain("setAsyncEnabled", "isFastPathActive", "createAsyncCommitPath", "formCommittee");
        assertThat(Arrays.stream(PBFTConsensus.class.getDeclaredClasses()).map(Class::getSimpleName))
                .doesNotContain("AsyncCommitPath");
    }
}
