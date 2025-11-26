const Block = require('./block');
const Transaction = require('./transaction');
const UTXOSet = require('./utxo');
const AccountState = require('./account_state');
const cfg = require('./config');
const { adjustDifficulty } = require('./difficulty');
const Mempool = require('./mempool');
const Storage = require('./storage');

class Blockchain {
    constructor({ storage, mempool } = {}){
        this.storage = storage || new Storage();
        this.mempool = mempool || new Mempool();
        this.chain = [];
        this.utxo = new UTXOSet();
        this.state = new AccountState();
        this.difficulty = cfg.initialDifficulty;
        // this.pendingTransaction = [];
        // this.miningReward = reward;
    }

    async init() {
        // Load tip & reconstruct minimal state (real nodes would reindex from genesis)
        const tipHash = await this.storage.loadTipHash();
        if (!tipHash) {
        // create genesis
        const genesisBlock = new Block(0, Date.now(), [], '0', 0, this.difficulty);
        genesisBlock.hash = genesisBlock.calculateHash();
        await this.storage.saveBlock(genesisBlock.hash, genesisBlock);
        this.chain = [genesisBlock];
        await this.storage.saveUTXO(this.utxo.toJSON());
        await this.storage.saveState(this.state.toJSON());
        await this.storage.putMeta('difficulty', this.difficulty);
        } else {
        // simplified: load tip block only
        const tipBlock = await this.storage.loadBlockByHash(tipHash);
        this.chain = [tipBlock];
        const utxoObj = await this.storage.loadUTXO();
        this.utxo = new UTXOSet(utxoObj);
        const stateObj = await this.storage.loadState();
        this.state = new AccountState(stateObj);
        const diff = await this.storage.getMeta('difficulty');
        if (diff) this.difficulty = diff;
        }
    }

    createGenesisBlock(){
        return new Block(0, Date.now(), [], "0");
    }

    getLatestBlock(){
        return this.chain[this.chain.length - 1];
    }

    // Validate tx according to its type
    validateTransaction(tx) {
        if (!tx.verify()) throw new Error('signature invalid');
        if (tx.networkId !== cfg.networkId) throw new Error('wrong networkId');

        if (tx.type === 'utxo') {
        // ensure all inputs are unspent
        for (const inp of tx.inputs) {
            if (!this.utxo.isUnspent(inp.txid, inp.index)) throw new Error('UTXO input not available');
        }
        // amount invariants left for application step
        return true;
        } else if (tx.type === 'account') {
        // Skip nonce check for coinbase (reward) transactions
        if (tx.from === null) return true;
        const balance = this.state.getBalance(tx.from);
        const expectedNonce = this.state.getNonce(tx.from) + 1;
        if (tx.nonce !== expectedNonce) throw new Error(`invalid nonce: expected ${expectedNonce} got ${tx.nonce}`);
        if (balance < (tx.amount + tx.fee)) throw new Error('insufficient funds');
        return true;
        } else if (tx.type === 'contract') {
        // contract validation minimal; VM will do heavy checks
        return true;
        }
        throw new Error('unknown tx type');
    }

    // Apply block: update UTXO and account state and persist block
  async applyBlock(block) {
    // Basic checks
    if (block.prevHash !== this.getLatestBlock().hash) throw new Error('block does not chain to tip');

    // verify PoW
    if (!block.hash.startsWith('0'.repeat(block.difficulty))) throw new Error('block PoW invalid');

    // validate txs inside
    for (const tx of block.transactions) {
      this.validateTransaction(tx);
    }

    // apply txs
    for (const tx of block.transactions) {
      if (tx.type === 'utxo') {
        // spend inputs
        tx.inputs.forEach(inp => this.utxo.spendOutput(inp.txid, inp.index));
        // add outputs
        tx.outputs.forEach((out, idx) => this.utxo.addOutput(tx.id, idx, out.address, out.amount));
      } else if (tx.type === 'account') {
        // debit sender
        if (tx.from) {
          this.state.debit(tx.from, tx.amount + tx.fee);
          this.state.incrementNonce(tx.from);
        }
        // credit recipient
        this.state.credit(tx.to, tx.amount);
        // miner reward: fee will be given to miner tx in block creation
      } else if (tx.type === 'contract') {
        // run VM (synchronous call for now)
        // VM should charge gas, alter state, emit logs / events
        // For brevity we assume contract succeeded and state changes are applied via VM externally
      }
    }

    // Append block to chain and persist
    this.chain.push(block);
    await this.storage.saveBlock(block.hash, block);
    await this.storage.saveUTXO(this.utxo.toJSON());
    await this.storage.saveState(this.state.toJSON());

    // difficulty adjust
    if ((this.chain.length - 1) % cfg.difficultyAdjustmentInterval === 0) {
      this.difficulty = adjustDifficulty(this.chain, this.difficulty);
      await this.storage.putMeta('difficulty', this.difficulty);
    }
  }

  // Mining selection + block creation
  async createBlock(minerAddress, maxTx = cfg.maxBlockTxs) {
    // choose transactions from mempool by fee
    const candidateTxs = this.mempool.getTop(maxTx);
    const txsToInclude = [];

    // validate and pick
    for (const tx of candidateTxs) {
      try {
        this.validateTransaction(tx);
        txsToInclude.push(tx);
      } catch (e) {
        // skip invalid tx
      }
    }

    // miner reward tx (account type)
    const rewardTx = new (require('./transaction'))({ type: 'account', from: null, to: minerAddress, amount: cfg.minerReward, fee: 0, nonce: 0 });
    rewardTx.signature = null; rewardTx.publicKey = null; // coinbase
    txsToInclude.push(rewardTx);

    const newBlock = new Block(this.chain.length, Date.now(), txsToInclude, this.getLatestBlock().hash, this.difficulty, 0);
    newBlock.mine(this.difficulty, cfg.maxNonceAttempts);
    return newBlock;
  }

    minePendingTransactions(minerAddress){

        let totalFees = 0;
        this.pendingTransaction.forEach(tx => totalFees += tx.fee);

        const rewardTx = new Transaction(null, minerAddress, this.miningReward + totalFees);
        //this.pendingTransaction.push(rewardTx);
        const transactionsToMine = [rewardTx, ...this.pendingTransactions];

         const block = new Block(
            this.chain.length,
            Date.now(),
            this.pendingTransaction,
            this.getLatestBlock().hash
        );

        block.mine(this.difficulty);
        this.chain.push(block);
        this.pendingTransaction = [];
        this.difficulty = this.adjustDifficulty(block);
    }

    addTransaction(tx){
        if(!tx.toAddress) throw new Error("Missing destination");
        if(!tx.isValid()) throw new Error("Invalid Transaction");

        const currentBalance = this.getBalance(tx.fromAddress);
        if(currentBalance < tx.amount + tx.fee)
            throw new Error('Insufficient funds');
        this.pendingTransaction.push(tx);
    }

    getBalance(address){
        let balance = 0;

        for(const block of this.chain){
            for(const tx of block.transactions){
                if(tx.fromAddress === address)
                    balance -= (tx.amount + tx.fee);
                if(tx.toAddress === address)
                    balance += tx.amount;
            }
        }
        for(const tx of this.pendingTransaction)
            if(tx.fromAddress === address) balance -= (tx.amount + tx.fee);       
        return balance;
    }

    isChainValid(){
        for(let i = 1; i<this.chain.length; i++){
            const current = this.chain[i];
            const previous = this.chain[i-1];

            if (!current.haveValidTransactions()) return false;
            if (current.hash !== current.calculateHash()) return false;
            if (current.prevHash !== previous.hash) return false;
        }
        return true;
    }
}
module.exports = Blockchain;