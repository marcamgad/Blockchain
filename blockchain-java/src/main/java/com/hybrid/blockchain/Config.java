package com.hybrid.blockchain;

public final class Config {

    public static final int DIFFICULTY = 4;
    public static final int MAX_TRANSACTIONS_PER_BLOCK = 2000;
    public static final int MAX_BLOCK_SIZE = 2 * 1024 * 1024; 
    public static final double MINING_REWARD = 50.0;
    public static final long MINING_BATCH_LIMIT = 500_000;
    public static final double MIN_TRANSACTION_AMOUNT = 0.0001;

    public static final int MEMPOOL_LIMIT = 10_000;

    public static final int SIGNATURE_LENGTH = 128;
    public static final int P2P_PORT = 6001;

    public static final int API_PORT = 8000;
    public static final long PEER_HEARTBEAT_INTERVAL_MS = 3000;

    public static final int MAX_PEERS = 50;
    public static final String HASH_ALGO = "SHA-256";
    public static final String KEY_ALGO = "EC";
    public static final String EC_CURVE = "secp256r1";
    public static final boolean PRETTY_JSON = true;

    public static final boolean STRICT_JSON = true;
    public static final boolean ENABLE_SMART_CONTRACTS = true;

    public static final long CONTRACT_EXECUTION_LIMIT = 10_000;

    public static final int MAX_CONTRACT_SIZE = 32 * 1024;
    public static final String NODE_NAME = "HybridJavaNode";
    public static final String VERSION = "1.0.0";
    public static final boolean DEBUG = true;
    public static final boolean PRINT_STATS = true;

    public static final int NETWORK_ID = 101; // unique ID for this blockchain network


    private Config() {}
}
