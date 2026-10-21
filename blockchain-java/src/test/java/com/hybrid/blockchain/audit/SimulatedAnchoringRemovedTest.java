package com.hybrid.blockchain.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Item 7] StateAnchoringManager logged "ANCHORED ... to external chain" with a fabricated
 * transaction hash and sent nothing. It is removed; nothing in the node may claim an anchor.
 */
class SimulatedAnchoringRemovedTest {

    @Test
    @DisplayName("the simulated anchoring component no longer exists")
    void removed() {
        assertThatThrownBy(() -> Class.forName("com.hybrid.blockchain.anchoring.StateAnchoringManager"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("no main source logs a fabricated 'ANCHORED' claim")
    void noFabricatedAnchorLog() throws Exception {
        Path main = Paths.get("src", "main", "java");
        try (Stream<Path> files = Files.walk(main)) {
            assertThat(files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).contains("ANCHORED");
                } catch (Exception e) {
                    return false;
                }
            })).isEmpty();
        }
    }
}
