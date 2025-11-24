const crypto = require('crypto');
const fs = require('fs');
const keccak256 = require('keccak');

class Wallet {
    constructor(){
        this.privateKeyPem = null;
        this.publickeyPem = null;
        this.address = null;
    }
    generateKeyPair(passphrase = null){

        const privateKeyOptions = passphrase
            ? {type: "pkcs8",format: "pem", cipher: "aes-256-cbc", passphrase}
            : {type: "pcks8", format: "pem"};
        const {publicKey, privateKey} = crypto.generateKeyPairSync('ec',{
            namedCurve: "secp256k1",
            publicKeyEncoding: {type: "spki", format: "pem"},
            privateKeyEncoding: privateKeyOptions
        });
        
        this.privateKeyPem = privateKey;
        this.publicKeyPem = publicKey;
        this.address = Wallet.deriveAddress(publicKey);
    }

    signDigest(digestHex, passphrase = null) {
        const sign = crypto.createSign('SHA256');
        sign.update(digestHex);
        sign.end();
        return sign.sign({ key: this.privateKeyPem, passphrase }, 'hex');
    }

    verifyDigest(publicKeyPem, digestHex, signatureHex) {
        const verify = crypto.createVerify('SHA256');
        verify.update(digestHex); 
        verify.end();
        return verify.verify(publicKeyPem, signatureHex, 'hex');
    }

    signMessage(message) {
        if (!this.privateKeyPem) throw new Error("Private key not loaded.");

        const sign = crypto.createSign('SHA256');
        sign.update(message);
        sign.end();
        const signOptions = passphrase ? { key: this.privateKeyPem, passphrase } : this.privateKeyPem;
        const signature = sign.sign(signOptions, 'hex');
        return signature;
    }
    verifyMessage(message, signature) {
        if (!this.publicKeyPem) throw new Error("Public key not loaded.");
        const verify = crypto.createVerify('SHA256');
        verify.update(message);
        verify.end();
        return verify.verify(this.publicKeyPem, signature, 'hex');
    }
    static deriveAddress (publicKeyPem){
        const pubKeyObj = crypto.createPublicKey(publicKeyPem);
        const pubKeyDer = pubKeyObj.export({type: 'spki', format: 'der'});
        const hash = keccak256('keccak256').update(pubKeyDer).digest();
        const address = hash.subarray(hash.length - 20).toString('hex');
        return '0x' + address;
    }
}
module.exports = Wallet;

