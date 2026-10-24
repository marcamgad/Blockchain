package com.hybrid.blockchain.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.Crypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * [HC-093] Found by the HC-090 round-trip test. revokeDID removed the device -> DID mapping, but
 * SSIManager.fromMap rebuilt that mapping for EVERY DID in the registry, revoked ones included.
 * After a restart, a revoked device pointed at its old DID again: the restarted node computed a
 * different state root, and re-registering that device failed on it ("already has a DID") while
 * it succeeded on nodes that had not restarted.
 */
class DidRevocationPersistenceTest {

    @SuppressWarnings("unchecked")
    private static SSIManager restart(SSIManager m) throws Exception {
        ObjectMapper om = new ObjectMapper();
        return SSIManager.fromMap(om.readValue(om.writeValueAsBytes(m.toJSON()), Map.class));
    }

    @Test
    @DisplayName("a revoked device stays unmapped after a restart, and can be registered again on every node")
    void revocationSurvivesRestart() throws Exception {
        SSIManager live = new SSIManager();
        String did = live.registerDID("dev-1", Crypto.derivePublicKey(BigInteger.valueOf(7)), "hb" + "22".repeat(20), 1L);
        live.revokeDID(did, "compromised");
        assertThat(live.getDIDForDevice("dev-1")).isNull();

        SSIManager restarted = restart(live);
        assertThat(restarted.getDIDForDevice("dev-1")).as("mapping resurrected by the restart").isNull();
        assertThat(restarted.isDIDRevoked(did)).isTrue();
        assertThatCode(() -> restarted.registerDID("dev-1", Crypto.derivePublicKey(BigInteger.valueOf(8)), "hb" + "22".repeat(20), 2L))
                .doesNotThrowAnyException();
    }
}
