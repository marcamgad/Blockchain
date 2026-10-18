package com.hybrid.blockchain;

import java.util.*;

public class UTXOSet {
    private final Map<String, UTXO> map;

    public UTXOSet() {
        this.map = new HashMap<>();
    }

    public UTXOSet(Map<String, UTXO> mapObj) {
        this.map = new HashMap<>(mapObj);
    }

    public static UTXOSet fromMap(Map<String, Object> raw) {
        Map<String, UTXO> m = new HashMap<>();
        if (raw == null) return new UTXOSet(m);
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> u = (Map<String, Object>) e.getValue();
            String addr = (String) u.get("address");
            long amt = Utils.safeLong(u.get("amount"));
            m.put(e.getKey(), new UTXO(addr, amt));
        }
        return new UTXOSet(m);
    }

    public Map<String, Object> toJSON() {
        Map<String, Object> json = new HashMap<>();
        for (Map.Entry<String, UTXO> entry : map.entrySet()) {
            Map<String, Object> uJson = new LinkedHashMap<>();
            uJson.put("address", entry.getValue().getAddress());
            uJson.put("amount", entry.getValue().getAmount());
            json.put(entry.getKey(), uJson);
        }
        return json;
    }

    public UTXOSet cloneUtxo() {
        return new UTXOSet(new HashMap<>(this.map));
    }

    public void addOutput(String txid, int index, String address, long amount) {
        // [HC-018] a UTXO is always a positive amount and is created exactly once
        if (amount <= 0) throw new IllegalArgumentException("UTXO amount must be positive, got " + amount);
        String key = txid + ":" + index;
        if (map.containsKey(key)) throw new IllegalStateException("UTXO already exists: " + key);
        map.put(key, new UTXO(address, amount));
    }

    /**
     * [HC-035] Canonical commitment to the UTXO set: SHA-256 over entries sorted by key, each
     * length-prefixed (key, address) plus the amount. Replaces hashing HashMap.toString(), whose
     * order depends on table capacity / insertion history and so differed between validators
     * (e.g. one that had restarted), making checkpoint signatures disagree.
     */
    public byte[] canonicalHash() {
        java.util.List<String> keys = new java.util.ArrayList<>(map.keySet());
        java.util.Collections.sort(keys);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
        try {
            out.writeInt(keys.size());
            for (String k : keys) {
                UTXO u = map.get(k);
                byte[] kb = k.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] ab = (u.getAddress() == null ? "" : u.getAddress()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.writeInt(kb.length); out.write(kb);
                out.writeInt(ab.length); out.write(ab);
                out.writeLong(u.getAmount());
            }
            out.flush();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return Crypto.hash(bos.toByteArray());
    }

    /** [HC-018] Owner address of an unspent output, or null if it does not exist. */
    public String getOwner(String txid, int index) {
        UTXO u = map.get(txid + ":" + index);
        return u == null ? null : u.getAddress();
    }

    /** Overload for UTXOSetCompleteTest compat. */
    public void addOutput(String txid, int index, UTXOOutput output) {
        addOutput(txid, index, output.address, output.amount);
    }

    public boolean spendOutput(String txid, int index) {
        String key = txid + ":" + index;
        if (!map.containsKey(key)) {
            throw new IllegalArgumentException("UTXO not found: " + key);
        }
        map.remove(key);
        return true;
    }

    public boolean isUnspent(String txid, int index) {
        String key = txid + ":" + index;
        return map.containsKey(key);
    }

    public long getAmount(String txid, int index) {
        String key = txid + ":" + index;
        UTXO utxo = map.get(key);
        if (utxo == null) {
            return -1;
        }
        return utxo.getAmount();
    }

    public Spendable findSpendable(String address, long amount) {
        long total = 0;
        List<UTXOEntry> utxos = new ArrayList<>();
        for (Map.Entry<String, UTXO> entry : map.entrySet()) {
            UTXO u = entry.getValue();
            if (u.getAddress().equals(address)) {
                utxos.add(new UTXOEntry(entry.getKey(), u.getAmount()));
                total += u.getAmount();
                if (total >= amount) break;
            }
        }
        return new Spendable(total, utxos);
    }

    public long getBalance(String address) {
        long balance = 0;
        for (UTXO u : map.values()) {
            if (u.getAddress().equals(address)) balance += u.getAmount();
        }
        return balance;
    }

    public static class UTXO {
        private final String address;
        private final long amount;

        public UTXO(String address, long amount) {
            this.address = address;
            this.amount = amount;
        }

        public String getAddress() {
            return address;
        }

        public long getAmount() {
            return amount;
        }
    }

    public static class UTXOOutput {
        public final String address;
        public final long amount;
        public UTXOOutput(String address, long amount) {
            this.address = address;
            this.amount = amount;
        }
    }

    public static class UTXOEntry {
        private final String key;
        private final long amount;

        public UTXOEntry(String key, long amount) {
            this.key = key;
            this.amount = amount;
        }

        public String getKey() {
            return key;
        }

        public long getAmount() {
            return amount;
        }
    }

    public static class Spendable {
        private final long total;
        private final List<UTXOEntry> utxos;

        public Spendable(long total, List<UTXOEntry> utxos) {
            this.total = total;
            this.utxos = utxos;
        }

        public long getTotal() {
            return total;
        }

        public List<UTXOEntry> getUtxos() {
            return utxos;
        }
    }
}
