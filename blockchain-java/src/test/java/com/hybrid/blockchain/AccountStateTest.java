package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
public class AccountStateTest {

    @Test
    @DisplayName("Invariant: New account must have zero balance and zero nonce")
    void testInitialAccountState() {
        AccountState state = new AccountState();
        assertThat(state.getBalance("alice")).isEqualTo(0L);
        assertThat(state.getNonce("alice")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Invariant: Crediting and debiting account updates balance correctly")
    void testCreditAndDebit() throws Exception {
        AccountState state = new AccountState();
        state.credit("alice", 100);
        assertThat(state.getBalance("alice")).isEqualTo(100L);
        
        state.debit("alice", 30);
        assertThat(state.getBalance("alice")).isEqualTo(70L);
    }

    @Test
    @DisplayName("Security: Debiting more than available balance must fail")
    void testInsufficientBalance() {
        AccountState state = new AccountState();
        state.credit("bob", 50);
        
        assertThatThrownBy(() -> state.debit("bob", 100))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("Insufficient balance");
        
        // Balance should remain unchanged after failure
        assertThat(state.getBalance("bob")).isEqualTo(50L);
    }

    @Test
    @DisplayName("Security: Negative credits or debits must fail")
    void testNegativeOperations() {
        AccountState state = new AccountState();
        
        // In the original code, this was also checked. Let's make sure it throws.
        assertThatThrownBy(() -> state.credit("charlie", -10))
            .isInstanceOf(IllegalArgumentException.class);
            
        state.credit("charlie", 100);
        
        assertThatThrownBy(() -> state.debit("charlie", -50))
            .isInstanceOf(IllegalArgumentException.class);
            
        assertThat(state.getBalance("charlie")).isEqualTo(100L);
    }

    @Test
    @DisplayName("Invariant: Nonce tracking must be atomic and strict")
    void testNonceTracking() {
        AccountState state = new AccountState();
        
        state.incrementNonce("dave");
        assertThat(state.getNonce("dave")).isEqualTo(1L);
        
        state.setNonce("dave", 5);
        assertThat(state.getNonce("dave")).isEqualTo(5L);
        
        state.incrementNonce("dave");
        assertThat(state.getNonce("dave")).isEqualTo(6L);
    }

    @Test
    @DisplayName("Invariant: State serialization must be deterministic and reversible")
    void testSerialization() {
        AccountState state = new AccountState();
        state.credit("eve", 1000);
        state.incrementNonce("eve");
        
        var json = state.toJSON();
        AccountState restored = AccountState.fromMap(json);
        
        assertThat(restored.getBalance("eve")).isEqualTo(1000L);
        assertThat(restored.getNonce("eve")).isEqualTo(1L);
        assertThat(state.calculateStateRoot()).isEqualTo(restored.calculateStateRoot());
    }

    @Test
    @DisplayName("Invariant: State root must change deterministically upon state updates")
    void testStateRootChanges() throws Exception {
        AccountState state = new AccountState();
        String root1 = state.calculateStateRoot();
        
        state.credit("frank", 100);
        String root2 = state.calculateStateRoot();
        assertThat(root2).isNotEqualTo(root1);
        
        state.debit("frank", 50);
        String root3 = state.calculateStateRoot();
        assertThat(root3).isNotEqualTo(root2);
    }
}
