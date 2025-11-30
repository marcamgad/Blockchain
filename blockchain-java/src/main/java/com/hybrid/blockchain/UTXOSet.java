package com.hybrid.blockchain;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public class UTXOSet {

    private final Map<String, UTXO> map;

    public UTXOSet() {
        this.map = new HashMap<>();
    }

    public UTXOSet(Map<String, UTXO> mapObj) {
        this.map = new HashMap<>(mapObj);
    }

    // ------------------- JSON-like Conversion -------------------
    public Map<String, UTXO> toJSON() {
        return new HashMap<>(map);
    }

    // ------------------- Add / Spend -------------------
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

    // ------------------- Find Spendable UTXOs -------------------
    public Spendable findSpendable(String address, long amount) {
        long total = 0;
        List<UTXOEntry> utxos = new ArrayList<>();

        for (Map.Entry<String, UTXO> entry : map.entrySet()) {
            UTXO utxo = entry.getValue();
            if (utxo.getAddress().equals(address)) {
                utxos.add(new UTXOEntry(entry.getKey(), utxo.getAmount()));
                total += utxo.getAmount();
                if (total >= amount) break;
            }
        }

        return new Spendable(total, utxos);
    }

    // ------------------- Nested Classes -------------------
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
