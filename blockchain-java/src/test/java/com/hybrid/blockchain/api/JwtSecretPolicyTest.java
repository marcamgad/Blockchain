package com.hybrid.blockchain.api;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [HC-052] the public development JWT secret is never used outside explicit DEBUG mode. */
class JwtSecretPolicyTest {

    @Test
    @DisplayName("no JWT_SECRET and DEBUG off -> refuse to start (no forgeable admin tokens)")
    void refusesDevSecretOutsideDebug() {
        Assumptions.assumeTrue(System.getenv("JWT_SECRET") == null, "JWT_SECRET set in the environment");
        String debug = System.getProperty("DEBUG"), secret = System.getProperty("JWT_SECRET");
        try {
            System.setProperty("DEBUG", "false");
            System.clearProperty("JWT_SECRET");
            assertThatThrownBy(JwtManager::new).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET must be set");
        } finally {
            if (debug == null) System.clearProperty("DEBUG"); else System.setProperty("DEBUG", debug);
            if (secret != null) System.setProperty("JWT_SECRET", secret);
        }
    }
}
