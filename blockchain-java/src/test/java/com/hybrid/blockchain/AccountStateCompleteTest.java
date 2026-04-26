package com.hybrid.blockchain;

import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for AccountState including balance arithmetic, nonces,
 * state root calculation, cloning/merging, and serialization.
 */
@Tag("unit")
public class AccountStateCompleteTest {

    private AccountState state;

    @BeforeEach
    void setUp() {
        state = new AccountState();
    }

    @Test
    @DisplayName("A1.1-1.2 — credit and debit arithmetic")
    void testBalanceArithmetic() {
        String addr = "alice";
        state.credit(addr, 1000L);
        assertThat(state.getBalance(addr)).isEqualTo(1000L);
        
        state.debit(addr, 400L);
        assertThat(state.getBalance(addr)).isEqualTo(600L);
        
        // Law 4: Meaningful assertion on debit below 0
        // Standard account balance behavior is clamping at 0 or throwing
        // HybridChain implementation (checked) throws IllegalArgumentException
        assertThatThrownBy(() -> state.debit(addr, 601L))
                .as("Debit below balance should throw")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A1.3-1.5 — nonces and defaults")
    void testNoncesAndDefaults() {
        String addr = "bob";
        assertThat(state.getNonce(addr)).as("Default nonce is 0").isEqualTo(0L);
        assertThat(state.getBalance(addr)).as("Default balance is 0").isEqualTo(0L);
        
        state.incrementNonce(addr);
        assertThat(state.getNonce(addr)).isEqualTo(1L);
        state.incrementNonce(addr);
        assertThat(state.getNonce(addr)).isEqualTo(2L);
    }

    @Test
    @DisplayName("A1.6 — setCode + getCode")
    void testCodeStorage() {
        String addr = "contract";
        byte[] code = { (byte)OpCode.PUSH.getByte(), 1, 2, 3 };
        state.setCode(addr, code);
        assertThat(state.getAccount(addr).getCode()).containsExactly(code);
    }

    @Test
    @DisplayName("A1.7 — Token balances")
    void testTokenBalances() {
        String addr = "alice";
        String token = "IOT";
        assertThat(state.getTokenBalance(addr, token)).isEqualTo(0L);
        
        state.setTokenBalance(addr, token, 500L);
        assertThat(state.getTokenBalance(addr, token)).isEqualTo(500L);
    }

    @Test
    @DisplayName("A1.8 — cloneState deep copy")
    void testStateClone() {
        state.credit("alice", 100L);
        AccountState clone = state.cloneState();
        
        clone.credit("alice", 50L);
        assertThat(clone.getBalance("alice")).isEqualTo(150L);
        assertThat(state.getBalance("alice")).as("Original state should be immutable to clone changes").isEqualTo(100L);
    }

    @Test
    @DisplayName("A1.9 — merge changes")
    void testStateMerge() {
        state.credit("alice", 100L);
        state.credit("bob", 200L);
        
        AccountState delta = new AccountState();
        delta.credit("alice", 150L); // Final desired state for alice is 150 (initial 100 + delta 50)
        delta.credit("charlie", 300L); // new account
        
        state.merge(delta);
        
        assertThat(state.getBalance("alice")).isEqualTo(150L);
        assertThat(state.getBalance("bob")).as("Pre-existing unchanged account preserved").isEqualTo(200L);
        assertThat(state.getBalance("charlie")).as("New account merged in").isEqualTo(300L);
    }

    @Test
    @DisplayName("A1.10-1.11 — State root calculation")
    void testStateRoot() {
        state.credit("a", 100);
        String root1 = state.calculateStateRoot();
        assertThat(root1).isNotNull().hasSize(64);
        
        state.credit("b", 1);
        String root2 = state.calculateStateRoot();
        assertThat(root2).as("Root must change when balance changes").isNotEqualTo(root1);
        
        // Determinism check
        AccountState state2 = new AccountState();
        state2.credit("a", 100);
        state2.credit("b", 1);
        assertThat(state2.calculateStateRoot()).as("Same state must result in same root").isEqualTo(root2);
    }

    @Test
    @DisplayName("A1.12 — Serialization round-trip")
    void testSerializationRoundTrip() {
        state.credit("alice", 100L);
        state.incrementNonce("alice");
        state.setTokenBalance("alice", "T", 10);
        state.setCode("c", new byte[]{1});
        state.setBlockHeight(1234);
        
        Map<String, Object> json = state.toJSON();
        AccountState restored = AccountState.fromMap(json);
        
        assertThat(restored.getBalance("alice")).isEqualTo(100L);
        assertThat(restored.getNonce("alice")).isEqualTo(1L);
        assertThat(restored.getTokenBalance("alice", "T")).isEqualTo(10L);
        assertThat(restored.getAccount("c").getCode()).containsExactly((byte)1);
        assertThat(restored.getBlockHeight()).isEqualTo(1234);
    }

    @Test
    @DisplayName("A1.14 — getAllAddresses")
    void testGetAllAddresses() {
        state.credit("a", 1);
        state.ensure("b");
        state.credit("a", 5);
        
        Set<String> addrs = state.getAllAddresses();
        assertThat(addrs).containsExactlyInAnyOrder("a", "b");
    }
}
