package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class AccountStateTest {

    @Test
    @DisplayName("New address balance defaults to zero")
    void newAddressBalanceZero() {
        AccountState state = new AccountState();
        assertEquals(0, state.getBalance("hb-new"), "Fresh addresses must have zero balance by default");
    }

    @Test
    @DisplayName("credit increases account balance")
    void creditIncreasesBalance() {
        AccountState state = new AccountState();
        state.credit("hb-a", 10);
        assertEquals(10, state.getBalance("hb-a"), "credit must increase account balance by credited amount");
    }

    @Test
    @DisplayName("debit decreases account balance")
    void debitDecreasesBalance() throws Exception {
        AccountState state = new AccountState();
        state.credit("hb-a", 10);
        state.debit("hb-a", 3);
        assertEquals(7, state.getBalance("hb-a"), "debit must reduce account balance by debited amount");
    }

    @Test
    @DisplayName("debit below zero throws")
    void debitBelowZeroThrows() {
        AccountState state = new AccountState();
        state.credit("hb-a", 2);
        Exception ex = assertThrows(Exception.class, () -> state.debit("hb-a", 3), "Debiting more than balance must throw");
        assertTrue(ex.getMessage().toLowerCase().contains("insufficient"), "Debit failure must explain insufficient balance");
    }

    @Test
    @DisplayName("Nonce starts at zero and increments by one")
    void nonceStartsAndIncrements() {
        AccountState state = new AccountState();
        assertEquals(0, state.getNonce("hb-a"), "Fresh account nonce must start at zero");
        state.incrementNonce("hb-a");
        assertEquals(1, state.getNonce("hb-a"), "incrementNonce must increase nonce by exactly one");
    }

    @Test
    @DisplayName("State root is deterministic for unchanged state")
    void stateRootDeterministic() {
        AccountState state = new AccountState();
        state.credit("hb-a", 5);
        String root1 = state.calculateStateRoot();
        String root2 = state.calculateStateRoot();
        assertEquals(root1, root2, "State root must be deterministic when state is unchanged");
    }

    @Test
    @DisplayName("State root changes after state mutation")
    void stateRootChangesAfterMutation() {
        AccountState state = new AccountState();
        String before = state.calculateStateRoot();
        state.credit("hb-a", 5);
        String after = state.calculateStateRoot();
        assertNotEquals(before, after, "State root must change when account state is mutated");
    }

    @Test
    @DisplayName("cloneState creates deep copy isolated from original")
    void cloneStateDeepCopy() {
        AccountState original = new AccountState();
        original.credit("hb-a", 10);
        AccountState clone = original.cloneState();
        clone.credit("hb-a", 5);

        assertEquals(10, original.getBalance("hb-a"), "Mutating cloned state must not affect original state balances");
        assertEquals(15, clone.getBalance("hb-a"), "Cloned state should reflect its own independent mutations");
    }

    @Test
    @DisplayName("AccountState toJSON/fromMap round-trip preserves core account data")
    void accountStateRoundTripPreservesData() {
        AccountState state = new AccountState();
        state.credit("hb-a", 100);
        state.incrementNonce("hb-a");
        state.putStorage("hb-a", 1L, 99L);

        Map<String, Object> json = state.toJSON();
        AccountState restored = AccountState.fromMap(json);

        assertEquals(state.getBalance("hb-a"), restored.getBalance("hb-a"), "Round-trip must preserve account balances");
        assertEquals(state.getNonce("hb-a"), restored.getNonce("hb-a"), "Round-trip must preserve account nonces");
        assertEquals(state.getAccountStorage("hb-a").get(1L), restored.getAccountStorage("hb-a").get(1L), "Round-trip must preserve account contract storage values");
    }

    @Test
    @DisplayName("PrivateDataManager survives AccountState serialization round-trip")
    void privateDataManagerRoundTrip() {
        AccountState state = new AccountState();
        state.getPrivateDataManager().createCollection("c1", java.util.List.of("hb-a"), "hb-a");

        AccountState restored = AccountState.fromMap(state.toJSON());
        assertTrue(restored.getPrivateDataManager().hasCollection("c1"), "PrivateDataManager collections must survive state serialization round-trip");
    }

    @Test
    @DisplayName("DeviceLifecycleManager survives AccountState serialization round-trip")
    void lifecycleRoundTrip() {
        AccountState state = new AccountState();
        byte[] mPub = Crypto.derivePublicKey(java.math.BigInteger.valueOf(77));
        state.getLifecycleManager().registerManufacturer("m1", mPub);

        AccountState restored = AccountState.fromMap(state.toJSON());
        assertNotNull(restored.getLifecycleManager().getStats(), "Lifecycle manager state object must survive round-trip and remain queryable");
    }

    @Test
    @DisplayName("SSIManager survives AccountState serialization round-trip")
    void ssiRoundTrip() {
        AccountState state = new AccountState();
        state.getSSIManager().registerDID("dev-1", Crypto.derivePublicKey(java.math.BigInteger.valueOf(88)), "owner-1");

        AccountState restored = AccountState.fromMap(state.toJSON());
        assertNotNull(restored.getSSIManager().getDIDForDevice("dev-1"), "SSI registry content must survive AccountState round-trip");
    }

    @Test
    @DisplayName("setBlockHeight influences state root via lifecycle metadata")
    void setBlockHeightAffectsStateRoot() {
        AccountState state = new AccountState();
        String before = state.calculateStateRoot();
        state.setBlockHeight(10);
        String after = state.calculateStateRoot();

        assertNotEquals(before, after, "Updating lifecycle block height should affect aggregated state root hash");
    }
}
