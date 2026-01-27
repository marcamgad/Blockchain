package com.hybrid.blockchain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AccountState class.
 */
public class SimpleAccountStateTest {

    @Test
    public void testAccountStateCreation() {
        AccountState state = new AccountState();
        assertNotNull(state);
    }

    @Test
    public void testInitialBalance() {
        AccountState state = new AccountState();
        assertEquals(0, state.getBalance("alice"));
    }

    @Test
    public void testCreditAccount() {
        AccountState state = new AccountState();
        state.credit("alice", 100);
        assertEquals(100, state.getBalance("alice"));
    }

    @Test
    public void testDebitAccount() throws Exception {
        AccountState state = new AccountState();
        state.credit("alice", 100);
        state.debit("alice", 30);
        assertEquals(70, state.getBalance("alice"));
    }

    @Test
    public void testDebitInsufficientBalance() {
        AccountState state = new AccountState();
        state.credit("alice", 50);
        assertThrows(Exception.class, () -> state.debit("alice", 100));
    }

    @Test
    public void testDebitNegativeAmount() {
        AccountState state = new AccountState();
        state.credit("alice", 100);
        assertThrows(Exception.class, () -> state.debit("alice", -50));
    }

    @Test
    public void testNonceIncrement() {
        AccountState state = new AccountState();
        assertEquals(0, state.getNonce("alice"));
        state.incrementNonce("alice");
        assertEquals(1, state.getNonce("alice"));
    }

    @Test
    public void testNonceSet() {
        AccountState state = new AccountState();
        state.setNonce("alice", 5);
        assertEquals(5, state.getNonce("alice"));
    }

    @Test
    public void testMultipleAccounts() throws Exception {
        AccountState state = new AccountState();
        state.credit("alice", 100);
        state.credit("bob", 50);
        
        assertEquals(100, state.getBalance("alice"));
        assertEquals(50, state.getBalance("bob"));
        
        state.debit("alice", 25);
        assertEquals(75, state.getBalance("alice"));
        assertEquals(50, state.getBalance("bob"));
    }

    @Test
    public void testAccountStateToJSON() {
        AccountState state = new AccountState();
        state.credit("alice", 100);
        state.incrementNonce("alice");
        
        var json = state.toJSON();
        assertNotNull(json);
        assertTrue(json.containsKey("alice"));
    }

    @Test
    public void testAccountStateFromMap() {
        var aliceData = new java.util.HashMap<String, Object>();
        aliceData.put("balance", 100L);
        aliceData.put("nonce", 5L);
        
        var rawState = new java.util.HashMap<String, Object>();
        rawState.put("alice", aliceData);
        
        AccountState loaded = AccountState.fromMap(rawState);
        assertEquals(100, loaded.getBalance("alice"));
        assertEquals(5, loaded.getNonce("alice"));
    }

    @Test
    public void testLargeAmounts() throws Exception {
        AccountState state = new AccountState();
        long largeAmount = 1_000_000_000_000L;
        
        state.credit("alice", largeAmount);
        assertEquals(largeAmount, state.getBalance("alice"));
        
        state.debit("alice", largeAmount);
        assertEquals(0, state.getBalance("alice"));
    }

    @Test
    public void testZeroOperations() throws Exception {
        AccountState state = new AccountState();
        state.credit("alice", 0);
        assertEquals(0, state.getBalance("alice"));
        
        state.credit("alice", 100);
        state.debit("alice", 0);
        assertEquals(100, state.getBalance("alice"));
    }
}
