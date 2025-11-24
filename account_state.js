class AccountState {
  constructor(obj = {}) {
    this.state = new Map(Object.entries(obj));
  }

  toJSON() { return Object.fromEntries(this.state); }

  getBalance(addr) { const a = this.state.get(addr); return a ? a.balance : 0; }
  getNonce(addr) { const a = this.state.get(addr); return a ? a.nonce : 0; }

  ensure(addr) { if (!this.state.has(addr)) this.state.set(addr, { balance: 0, nonce: 0 }); }

  credit(addr, amount) { this.ensure(addr); this.state.get(addr).balance += amount; }
  debit(addr, amount) { this.ensure(addr); if (this.state.get(addr).balance < amount) throw new Error('insufficient balance'); this.state.get(addr).balance -= amount; }

  incrementNonce(addr) { this.ensure(addr); this.state.get(addr).nonce += 1; }
  setNonce(addr, n) { this.ensure(addr); this.state.get(addr).nonce = n; }
}

module.exports = AccountState;
