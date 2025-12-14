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

    public void addOutput(String txid, int index, String address, long amount) {
        String key = txid + ":" + index;
        map.put(key, new UTXO(address, amount));
    }

    public void spendOutput(String txid, int index) throws Exception {
        String key = txid + ":" + index;
        if (!map.containsKey(key)) {
            throw new Exception("UTXO not found or already spent");
        }
        map.remove(key);
    }

    public boolean isUnspent(String txid, int index) {
        String key = txid + ":" + index;
        return map.containsKey(key);
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
