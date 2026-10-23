package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-086] Network parameters were mutable statics. POST /api/v1/admin/config/update changed them
 * on one node only: a different block-size limit or nonce gap than the rest of the network, and a
 * block time that the proposer (scheduled once at start-up) never used but the API reported as
 * applied. Governance PARAMETER_CHANGE wrote TARGET_BLOCK_TIME_MS the same way. A network
 * parameter must change only through a protocol upgrade (docs/design/PROTOCOL_UPGRADES.md).
 */
class NetworkParameterImmutabilityTest {

    @ParameterizedTest
    @ValueSource(strings = {"TARGET_BLOCK_TIME_MS", "MAX_TRANSACTIONS_PER_BLOCK", "MAX_NONCE_GAP"})
    @DisplayName("network parameters are final")
    void networkParametersAreFinal(String field) throws Exception {
        assertThat(Modifier.isFinal(Config.class.getField(field).getModifiers())).as(field).isTrue();
    }
}
