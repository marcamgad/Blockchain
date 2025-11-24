class UTXOSet {
    constructor(mapObj ={}) {
        this.map = new Map(Object.entries(mapObj));
    }

    toJSON() { return Object.fromEntries(this.map); }

    addOutput(txid, index, address, amount){
        const key = `${txid}:${index}`;
        this.map.set(key, {address, amount});
    }

    spendOutput(txid, index) {
        const key = `${txid}:${index}`;
        if (!this.map.has(key)) throw new Error('UTXO not found or already spent');
        this.map.delete(key);
    }

    isUnspent(txid, index) {
        return this.map.has(`${txid}:${index}`);
    }
    
    findSpendable(address, amount) {
        let total = 0;
        const utxos = [];
        for (const [key, val] of this.map.entries()) {
            if (val.address === address) {
                utxos.push({ key, amount: val.amount });
                total += val.amount;
                if (total >= amount) break;
            }
        }
        return { total, utxos };
    }
}

module.exports = UTXOSet;