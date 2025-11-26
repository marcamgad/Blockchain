const crypto = require('crypto');
const keccak256 = require('keccak');
const { v4: uuidv4 } = require('uuid');
const cfg = require('./config');

class Transaction {
    constructor(payload = {}) {

        this.id = uuidv4();  // Unique tx ID
        this.type = payload.type || 'account';
        this.networkId = payload.networkId || cfg.networkId;
        this.timestamp = payload.timestamp || Date.now();
        this.inputs = payload.inputs || {};
        this.outputs = payload.outputs || {};
        this.from = payload.from || null;
        this.to = payload.to || null;
        this.amount = payload.amount || 0;
        this.fee = payload.fee || 0;
        this.nonce = payload.nonce || 0;
        this.contract = payload.contract || null;
        this.signature = payload.signature || null;
        this.publicKey = payload.publicKey || null;
    }

    calculateHash() {
        return crypto.createHash('sha256')
            .update(JSON.stringify({
                id: this.id,
                fromAddress: this.fromAddress,
                toAddress: this.toAddress,
                amount: this.amount,
                fee: this.fee,
                timestamp: this.timestamp
            }))
            .digest('hex');
    }

    digest() {
        const base = {
            id: this.id,
            type: this.type,
            networkId: this.networkId,
            timestamp: this.timestamp
        };
        if (this.type === 'utxo') {
            base.inputs = this.inputs;
            base.outputs = this.outputs;
        } else if (this.type === 'account') {
            Object.assign(base, { from: this.from, to: this.to, amount: this.amount, fee: this.fee, nonce: this.nonce });
        } else if (this.type === 'contract') {
            Object.assign(base, { from: this.from, contract: this.contract });
        }
        return crypto.createHash('sha256').update(JSON.stringify(base)).digest('hex');
    }

    signTransaction(wallet, passphrase = null) {
        if (this.from && wallet.address !== this.from) {
            throw new Error("Wallet does not match from");
        }
        const digest = this.digest();
        this.signature = wallet.signDigest(digest, passphrase);
        this.publicKey = wallet.publicKeyPem;
        //const hashTx = this.calculateHash();
    }

    verify() {
        // For coinbase/reward (from null) return true
        if (this.type === 'account' && !this.from) return true;
        if (!this.signature || !this.publicKey) throw new Error('Missing signature/publicKey');
        const digest = this.digest();
        const verify = crypto.createVerify('SHA256');
        verify.update(digest); verify.end();
        return verify.verify(this.publicKey, this.signature, 'hex');
    }

    isValid(expectedReward = null) {
        if (this.fromAddress === null) {
            if (expectedReward !== null && this.amount !== expectedReward) return false;
            return true;
        }

        if (!this.signature || !this.publicKey) throw new Error('Transaction missing signature or public key');

        try {
            const verify = crypto.createVerify('SHA256');
            verify.update(this.calculateHash());
            verify.end();
            return verify.verify(this.publicKey, this.signature, 'hex');
        } catch (err) {
            console.error('Transaction verification failed:', err.message);
            return false;
        }
    }

    deriveAddress(publicKeyPem) {
        const pubKeyObj = crypto.createPublicKey(publicKeyPem);
        const pubKeyDer = pubKeyObj.export({ type: 'spki', format: 'der' });
        const hash = keccak256("keccak256").update(pubKeyDer).digest();
        const address = hash.slice(-20).toString('hex');
        return '0x' + address;
    }
}

module.exports = Transaction;
