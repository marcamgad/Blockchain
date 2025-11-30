package com.hybrid.blockchain;

import java.util.HashMap;
import java.util.Map;

public class AccountState {

    private final Map<String, Account> state;

    public AccountState() {
        this.state = new HashMap<>();
    }

    public AccountState(Map<String, Account> obj) {
        this.state = new HashMap<>(obj);
    }

    public Map<String, Account> toJSON() {
        return new HashMap<>(state);
    }

    public long getBalance(String addr) {
        Account acc = state.get(addr);
        return acc != null ? acc.getBalance() : 0;
    }

    public long getNonce(String addr) {
        Account acc = state.get(addr);
        return acc != null ? acc.getNonce() : 0;
    }

    private void ensure(String addr) {
        state.putIfAbsent(addr, new Account(0, 0));
    }

    public void credit(String addr, long amount) {
        ensure(addr);
        state.get(addr).credit(amount);
    }

    public void debit(String addr, long amount) throws Exception {
        ensure(addr);
        Account acc = state.get(addr);
        if (acc.getBalance() < amount) {
            throw new Exception("Insufficient balance");
        }
        acc.debit(amount);
    }

    public void incrementNonce(String addr) {
        ensure(addr);
        state.get(addr).incrementNonce();
    }

    public void setNonce(String addr, long n) {
        ensure(addr);
        state.get(addr).setNonce(n);
    }

    public static class Account {
        private long balance;
        private long nonce;

        public Account(long balance, long nonce) {
            this.balance = balance;
            this.nonce = nonce;
        }

        public long getBalance() {
            return balance;
        }

        public long getNonce() {
            return nonce;
        }

        public void credit(long amount) {
            balance += amount;
        }

        public void debit(long amount) {
            balance -= amount;
        }

        public void incrementNonce() {
            nonce += 1;
        }

        public void setNonce(long n) {
            nonce = n;
        }
    }
}
