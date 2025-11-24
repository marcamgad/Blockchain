const Blockchain = require('./blockchain');
const Transaction = require('./transaction');
const Wallet = require('./wallet');

// --- 1. Setup ---
console.log('--- 1. Setting up the Blockchain and Wallets ---');

// Create a new blockchain instance with a low difficulty for fast testing
const myCoin = new Blockchain(2); 

// Create two wallets (Alice and Bob)
const aliceWallet = new Wallet('alice-secret-passphrase');
aliceWallet.generateKeyPair();

const bobWallet = new Wallet('bob-secret-passphrase');
bobWallet.generateKeyPair();

// Create a wallet for the miner
const minerWallet = new Wallet('miner-passphrase');
minerWallet.generateKeyPair();

console.log(`Alice's Address: ${aliceWallet.address}`);
console.log(`Bob's Address: ${bobWallet.address}`);
console.log(`Miner's Address: ${minerWallet.address}`);

console.log('--- Initializing: The chain currently has only the Genesis Block. ---');
console.log(myCoin.chain[0]);

// Helper to check address balances (Note: Your Blockchain class doesn't have a getBalance method,
// so this will only check for the reward amount after a block is mined.)
const checkBalance = (address) => {
    let balance = 0;
    for (const block of myCoin.chain) {
        for (const tx of block.transactions) {
            if (tx.fromAddress === address) {
                balance -= tx.amount;
            }
            if (tx.toAddress === address) {
                balance += tx.amount;
            }
        }
    }
    return balance;
};

// --- 2. Mining the First Block (Initial Coins) ---
console.log('\n--- 2. Miner starts mining the first block... ---');

// The pending transaction list is empty, but the miner rewards itself.
myCoin.minePendingTransactions(minerWallet);

console.log('First Block Mined! Chain length:', myCoin.chain.length);
console.log(`Miner Balance (after first block): ${checkBalance(minerWallet.address)} (Reward: ${myCoin.miningReward})`);


// --- 3. Creating and Signing a Transaction ---
console.log('\n--- 3. Alice sends 50 coins to Bob ---');

// Create a transaction: Alice sends 50 coins to Bob
const tx1 = new Transaction(aliceWallet.address, bobWallet.address, 50);

// Sign the transaction with Alice's private key
try {
    tx1.signTransaction(aliceWallet);
    myCoin.addTransaction(tx1);
    console.log(`Transaction successfully added to pending pool: Amount=${tx1.amount}`);
} catch (error) {
    console.error(`Error adding transaction: ${error.message}`);
}

// Add another transaction (e.g., Bob sending 10 coins back, though he has no balance yet, this will be invalid in a real system)
const tx2 = new Transaction(bobWallet.address, aliceWallet.address, 10);
try {
    tx2.signTransaction(bobWallet);
    // Note: We expect this to fail validation in a real system due to lack of funds, 
    // but your current implementation only checks the signature.
    myCoin.addTransaction(tx2);
    console.log(`Second Transaction successfully added to pending pool: Amount=${tx2.amount}`);
} catch (error) {
    console.error(`Error adding second transaction (Expected error if balance checks were implemented): ${error.message}`);
}

// --- 4. Mining the Second Block ---
console.log('\n--- 4. Miner starts mining the second block... ---');

// The miner mines the block containing Alice's and Bob's transactions, and gets a new reward transaction.
myCoin.minePendingTransactions(minerWallet);

console.log('Second Block Mined! Chain length:', myCoin.chain.length);
console.log(`Miner Balance (after second block): ${checkBalance(minerWallet.address)} (Total Reward: 200)`);
console.log(`Alice's Balance: ${checkBalance(aliceWallet.address)}`);
console.log(`Bob's Balance: ${checkBalance(bobWallet.address)}`);


// --- 5. Chain Validation & Tampering Test ---
console.log('\n--- 5. Chain Validation Check ---');

// 5a. Check if the chain is currently valid
console.log(`Is chain valid? ${myCoin.isChainValid() ? '✅ YES' : '❌ NO'}`);

// 5b. Attempt to tamper with the chain (e.g., change the amount in a transaction in the second block)
const tamperBlock = myCoin.chain[2];
const tamperedTx = tamperBlock.transactions.find(tx => tx.toAddress === bobWallet.address);

if (tamperedTx) {
    console.log('\n--- Attempting to tamper with Block 2... ---');
    console.log(`Original amount in block 2: ${tamperedTx.amount}`);
    tamperedTx.amount = 999999; // Change the value!
    console.log(`Tampered amount in block 2: ${tamperedTx.amount}`);

    // Since the transaction's hash is based on the amount, the block hash is now invalid!
    console.log('Recalculating hash of the tampered block...');
    tamperBlock.hash = tamperBlock.calculateHash(); 
    
    // The previous hash linkage is still valid, but the current hash is wrong.
    console.log(`Is chain valid after tampering? ${myCoin.isChainValid() ? '✅ YES' : '❌ NO'}`);

    // Revert tamper for sanity (optional)
    tamperedTx.amount = 50;
    tamperBlock.hash = tamperBlock.calculateHash(); 
}

console.log('\n--- Test Complete ---');