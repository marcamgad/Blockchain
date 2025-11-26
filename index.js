const Blockchain = require('./blockchain');
const Transaction = require('./transaction');
const Wallet = require('./wallet');
const Storage = require('./storage');
const Mempool = require('./mempool');

// --- 1. Setup ---
console.log('--- 1. Setting up the Blockchain and Wallets ---');

// Create a new blockchain instance with storage and mempool
const storage = new Storage();
const mempool = new Mempool();
const myCoin = new Blockchain({ storage, mempool }); 

// Create two wallets (Alice and Bob)
const aliceWallet = new Wallet('alice-secret-passphrase');
aliceWallet.generateKeyPair('alice-secret-passphrase');

const bobWallet = new Wallet('bob-secret-passphrase');
bobWallet.generateKeyPair('bob-secret-passphrase');

// Create a wallet for the miner
const minerWallet = new Wallet('miner-passphrase');
minerWallet.generateKeyPair('miner-passphrase');

console.log(`Alice's Address: ${aliceWallet.address}`);
console.log(`Bob's Address: ${bobWallet.address}`);
console.log(`Miner's Address: ${minerWallet.address}`);

console.log('--- Initializing: The chain currently has only the Genesis Block. ---');
if (myCoin.chain.length > 0) {
    console.log(myCoin.chain[0]);
} else {
    console.log('Chain not initialized yet.');
}

// Helper to check address balances (Note: Your Blockchain class doesn't have a getBalance method,
// so this will only check for the reward amount after a block is mined.)
const checkBalance = (address) => {
    let balance = 0;
    for (const block of myCoin.chain) {
        for (const tx of block.transactions) {
            if (tx.from === address) {
                balance -= tx.amount;
            }
            if (tx.to === address) {
                balance += tx.amount;
            }
        }
    }
    return balance;
};

// --- 2. Mining the First Block (Initial Coins) ---
console.log('\n--- 2. Miner starts mining the first block... ---');

(async () => {
    try {
        // Initialize blockchain
        await myCoin.init();
        console.log('Blockchain initialized');
        console.log('Genesis Block:', myCoin.chain[0]);

        // --- 3. Creating and Signing a Transaction ---
        console.log('\n--- 3. Alice sends 50 coins to Bob ---');

        // First, give Alice coins via a reward
        const rewardTx = new Transaction({ type: 'account', from: null, to: aliceWallet.address, amount: 100, fee: 0, nonce: 0 });
        rewardTx.signature = null;
        rewardTx.publicKey = null;
        mempool.add(rewardTx);

        // Mine block with reward
        let block = await myCoin.createBlock(minerWallet.address);
        await myCoin.applyBlock(block);
        console.log('First Block Mined! Chain length:', myCoin.chain.length);

        // Now create transaction from Alice to Bob
        const tx1 = new Transaction({ type: 'account', from: aliceWallet.address, to: bobWallet.address, amount: 50, fee: 1, nonce: 1 });
        tx1.signTransaction(aliceWallet);
        mempool.add(tx1);
        console.log(`Transaction successfully added: ${aliceWallet.address} -> ${bobWallet.address}, Amount=50`);

        // --- 4. Mining the Second Block ---
        console.log('\n--- 4. Miner starts mining the second block... ---');
        block = await myCoin.createBlock(minerWallet.address);
        await myCoin.applyBlock(block);
        console.log('Second Block Mined! Chain length:', myCoin.chain.length);

        // Check balances
        const aliceBalance = myCoin.state.getBalance(aliceWallet.address);
        const bobBalance = myCoin.state.getBalance(bobWallet.address);
        const minerBalance = myCoin.state.getBalance(minerWallet.address);
        console.log(`Alice's Balance: ${aliceBalance}`);
        console.log(`Bob's Balance: ${bobBalance}`);
        console.log(`Miner's Balance: ${minerBalance}`);

        // --- 5. Chain Validation & Tampering Test ---
        console.log('\n--- 5. Chain Validation Check ---');
        console.log(`Is chain valid? ${myCoin.isChainValid() ? '✅ YES' : '❌ NO'}`);

        // 5b. Attempt to tamper with the chain
        if (myCoin.chain.length > 1) {
            console.log('\n--- Attempting to tamper with Block 1... ---');
            const tamperBlock = myCoin.chain[1];
            const tamperedTx = tamperBlock.transactions[0];

            if (tamperedTx) {
                const originalAmount = tamperedTx.amount;
                console.log(`Original amount in block 1: ${originalAmount}`);
                tamperedTx.amount = 999999; // Change the value!
                console.log(`Tampered amount in block 1: ${tamperedTx.amount}`);

                // Recalculate hash
                console.log('Recalculating hash of the tampered block...');
                tamperBlock.hash = tamperBlock.calculateHash();

                // Check if chain is still valid
                console.log(`Is chain valid after tampering? ${myCoin.isChainValid() ? '✅ YES' : '❌ NO'}`);

                // Revert tamper
                tamperedTx.amount = originalAmount;
                tamperBlock.hash = tamperBlock.calculateHash();
            }
        }

        console.log('\n--- Test Complete ---');
    } catch (error) {
        console.error('Error during blockchain test:', error.message);
        console.error(error);
    }
})();