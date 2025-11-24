const cfg = require('./config');

class Mempool {
  constructor(maxSize = cfg.mempoolMaxSize) {
    this.maxSize = maxSize;
    this.map = new Map(); // txid -> tx
  }

  add(tx) {
    if (!tx || !tx.id) throw new Error('Invalid tx');
    const now = Date.now();
    if (Math.abs(now - tx.timestamp) > 1000 * 60 * 60 * 24) throw new Error('tx timestamp out of range');

    if (this.map.has(tx.id)) throw new Error('tx already in mempool');

    for (const [id, existing] of this.map.entries()) {
      if (existing.type === 'account' && tx.type === 'account' && existing.from && existing.from === tx.from && existing.nonce === tx.nonce) {
        if (tx.fee <= existing.fee) throw new Error('replacement must have higher fee');
        this.map.delete(id);
      }
    }

    if (this.map.size >= this.maxSize) {

      let worstId = null; let worstFee = Infinity;
      for (const [id, t] of this.map.entries()) {
        const fee = t.fee || 0;
        if (fee < worstFee) { worstFee = fee; worstId = id; }
      }
      if ((tx.fee || 0) <= worstFee) throw new Error('mempool full and fee too low');
      this.map.delete(worstId);
    }

    this.map.set(tx.id, tx);
    return true;
  }

  remove(txid) { this.map.delete(txid); }

  getTop(n = 100) {
    return Array.from(this.map.values()).sort((a,b) => (b.fee || 0) - (a.fee || 0)).slice(0, n);
  }

  toArray() { return Array.from(this.map.values()); }
}

module.exports = Mempool;
