package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-090] The identity registry, device registry, nullifiers and jail list entered the state root
 * as Jackson-serialised JSON, so a change in Jackson's output (number formatting, escaping, sort
 * settings; Spring Boot 4 moves to Jackson 3) would change state roots and split a network of
 * mixed versions. State root v2 hashes a canonical binary encoding of those trees instead.
 */
class StateRootV2Test {

    /** A state that touches every part of the root. */
    static AccountState sample(boolean reverseRevocations) {
        AccountState s = new AccountState();
        s.setBlockHeight(42);
        s.credit("hb" + "11".repeat(20), 1000);
        String did1 = s.getSSIManager().registerDID("dev-1", Crypto.derivePublicKey(java.math.BigInteger.valueOf(7)), "hb" + "22".repeat(20), 1_700_000_000_000L);
        String did2 = s.getSSIManager().registerDID("dev-2", Crypto.derivePublicKey(java.math.BigInteger.valueOf(8)), "hb" + "22".repeat(20), 1_700_000_000_001L);
        String did3 = s.getSSIManager().registerDID("dev-3", Crypto.derivePublicKey(java.math.BigInteger.valueOf(9)), "hb" + "22".repeat(20), 1_700_000_000_002L);
        for (String d : reverseRevocations ? new String[]{did3, did2, did1} : new String[]{did1, did2, did3}) {
            s.getSSIManager().revokeDID(d, "test");
        }
        s.getLifecycleManager().registerDevice("dev-1", "acme", "sensor-v1");
        s.recordNullifier("ab".repeat(32));
        s.jail("hb" + "33".repeat(20));
        s.recordReading(77L, 21_500L);
        return s;
    }

    @Test
    @DisplayName("the root survives persistence (toJSON -> fromMap), as on a restart")
    void survivesRoundTrip() throws Exception {
        AccountState s = sample(false);
        String root = s.calculateStateRoot();
        byte[] json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(s.toJSON());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> raw = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, java.util.Map.class);
        assertThat(AccountState.fromMap(raw).calculateStateRoot()).isEqualTo(root);
    }

    @Test
    @DisplayName("the order in which DIDs were revoked does not matter")
    void setOrderIndependent() {
        assertThat(sample(true).calculateStateRoot()).isEqualTo(sample(false).calculateStateRoot());
    }

    @Test
    @DisplayName("golden value: a library upgrade must not change this root (if it does, the network forks)")
    void golden() {
        assertThat(sample(false).calculateStateRoot()).isEqualTo(GOLDEN);
    }

    /** Pinned when state root v2 was introduced (HC-090). Change only together with a protocol upgrade. */
    static final String GOLDEN = "dc9628cc255d4f4848ba8722021f32c12cc22f41080a8d4b99503390a839c71d";
}
