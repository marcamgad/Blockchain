const crypto = require("crypto");
const canonicalize = require('canonicalize');
const Transaction = require('./transaction');

class Block{
    constructor(index, timestamp, transactions = [], prevHash='', difficulty = 1, nonce = 0){
        if(!Array.isArray(transactions)){
            throw new Error('Transactions must be an array');
        }
        this.index = index;
        this.timestamp = timestamp;
        this.transactions = transactions;
        this.prevHash = prevHash;
        this.nonce = nonce;
        this.difficulty = difficulty;
        this.hash = this.calculateHash();
    }

    calculateHash(){
        
        const txs = this.transactions.map(tx => {
            return {
                id: tx.id,
                type: tx.type,
                networkId: tx.networkId,
                timestamp: tx.timestamp,
                inputs: tx.inputs,
                outputs: tx.outputs,
                from: tx.from,
                to: tx.to,
                amount: tx.amount,
                fee: tx.fee,
                nonce: tx.nonce,
                contract: tx.contract, 
                signature: tx.signature, 
                publicKey: tx.publicKey
            };
        });

        // const data = canonicalize({
        //     index: this.index,
        //     timestamp: this.timestamp,
        //     transactions: this.transactions.map(tx => ({
        //         hash: tx.calculateHash(),
        //         publicKey: tx.publicKey
        //     })),
        //     prevHash: this.prevHash,
        //     nonce: this.nonce
        // });
        const payload = canonicalize({
            index: this.index, 
            timestamp: this.timestamp, 
            transactions: txs, 
            prevHash: this.prevHash, 
            nonce: this.nonce, 
            difficulty: this.difficulty
        });
        return crypto.createHash('sha256').update(payload).digest('hex');
    }
    mine(difficulty, maxNonce = 1_000_000){

        const target = '0'.repeat(difficulty);

        while(this.hash.substring(0,difficulty) !== target){
            if(this.nonce >= maxNonce){
                throw new Error('Nonce limit exceeded');
            }
            this.nonce++;
            this.hash = this.calculateHash();
        }
        return {hash: this.hash, nonce:this.nonce };
    }

    haveValidTransactions(){
        try {
            return this.transactions.every(tx => tx instanceof Transaction && tx.verify());
        } catch (error) {
            console.error('Invalid transaction detected:', error.message);
            return false;
        }
    }
}

module.exports = Block;