package com.hybrid.blockchain;

/**
 * Central configuration constants for HybridChain.
 * All values are read from environment variables or system properties at startup.
 * No defaults contain secrets.
 */
public final class Config {

    // ─── Core Chain Parameters ────────────────────────────────────────────────
    public static final int INITIAL_DIFFICULTY = 4;
    public static final int DIFFICULTY_ADJUSTMENT_INTERVAL = 10;
    public static long TARGET_BLOCK_TIME_MS = 60000; // 1 min target
    public static int MAX_TRANSACTIONS_PER_BLOCK = 2000;
    public static final int MAX_BLOCK_SIZE = 2 * 1024 * 1024; // 2 MB
    /** Legacy constant kept for backward compatibility; prefer Tokenomics.INITIAL_REWARD. */
    public static final long MINER_REWARD = 50;
    public static final long MINING_BATCH_LIMIT = 500_000;
    public static final double MIN_TRANSACTION_AMOUNT = 0.0001;
    public static final int MEMPOOL_LIMIT = 10_000;
    public static final int SIGNATURE_LENGTH = 128;
    public static final String HASH_ALGO = "SHA-256";
    public static final String KEY_ALGO = "EC";
    public static final String EC_CURVE = "secp256k1";
    public static final boolean PRETTY_JSON = true;
    public static final boolean STRICT_JSON = true;
    public static final boolean ENABLE_SMART_CONTRACTS = true;
    public static final long CONTRACT_EXECUTION_LIMIT = 10_000;
    public static final int MAX_CONTRACT_SIZE = 32 * 1024;
    public static boolean BYPASS_CONTRACT_AUDIT = false;

    // ─── Fee Market Prediction ────────────────────────────────────────────────
    // FIX 3: Gate FeeMarket.recordFeeDataPoint() so tests can reset history cleanly.
    /** When true, fee data points are recorded after each block for fee prediction. */
    public static final boolean FEE_HISTORY_ENABLED = getBooleanEnv("FEE_HISTORY_ENABLED", true);

    // ─── Quantum Security ─────────────────────────────────────────────────────
    // FIX 2: When true, any transaction missing a Dilithium signature is rejected.
    /** Require hybrid ECDSA+Dilithium signatures on every transaction. Default false for backward compat. */
    public static final boolean REQUIRE_QUANTUM_SIG = getBooleanEnv("REQUIRE_QUANTUM_SIG", false);

    // ─── Fee Market (EIP-1559 style) ─────────────────────────────────────────
    /** Starting base fee in smallest token units. */
    public static final long BASE_FEE_INITIAL = 0L;
    /** Denominator for max base-fee change per block (12.5% = 1/8). */
    public static final long BASE_FEE_MAX_CHANGE_DENOMINATOR = 8L;
    /** Target transaction count per block (half of maximum). */
    public static final int TARGET_GAS_PER_BLOCK = MAX_TRANSACTIONS_PER_BLOCK / 2;

    // ─── Convenience aliases used by tests ───────────────────────────────────
    /** Maximum gas (transactions) allowed in a block — alias for MAX_TRANSACTIONS_PER_BLOCK. */
    public static final int MAX_BLOCK_GAS = MAX_TRANSACTIONS_PER_BLOCK;
    /** Target gas (transactions) per block — alias for TARGET_GAS_PER_BLOCK. */
    public static final int TARGET_BLOCK_GAS = TARGET_GAS_PER_BLOCK;
    /** Maximum allowed timestamp drift in ms — alias for MAX_TIMESTAMP_DRIFT. */
    public static final long MAX_TIMESTAMP_DRIFT_MS = 300000; // alias

    // ─── Network & P2P ───────────────────────────────────────────────────────
    public static final int P2P_PORT = getIntEnv("P2P_PORT", 6001);
    public static final int API_PORT = getIntEnv("API_PORT", 8000);
    public static final long PEER_HEARTBEAT_INTERVAL_MS = 3000;
    public static final int MAX_PEERS = 50;
    public static final long MAX_TIMESTAMP_DRIFT = 300000; // 5 min
    public static final long MAX_NONCE_ATTEMPTS = Long.MAX_VALUE / 2;
    public static final int NETWORK_ID = getIntEnv("NETWORK_ID", 101);
    public static final String PROTOCOL_VERSION = getEnv("PROTOCOL_VERSION", "1.0.0");

    // ─── Node Identity ────────────────────────────────────────────────────────
    public static final String NODE_NAME = getEnv("NODE_NAME", "HybridJavaNode");
    public static String NODE_ID = getEnv("NODE_ID", "node-" + System.currentTimeMillis());
    public static final boolean IS_SEED = getBooleanEnv("IS_SEED", false);
    public static final String SEED_PEER = getEnv("SEED_PEER", null);
    public static final String STORAGE_PATH = getEnv("STORAGE_PATH", "./data");
    public static final boolean PRINT_STATS = true;

    // ─── Node Roles ───────────────────────────────────────────────────────────
    /**
     * Defines the operational role of this node in the network.
     * <ul>
     *   <li>VALIDATOR: participates in PBFT and proposes blocks</li>
     *   <li>OBSERVER: applies blocks, full state, never votes</li>
     *   <li>GATEWAY: accepts IoT submissions via MQTT/CoAP, relays to validators</li>
     *   <li>LIGHT: stores only block headers, verifies Merkle proofs</li>
     * </ul>
     */
    public enum NodeRole { VALIDATOR, OBSERVER, GATEWAY, LIGHT }

    /** The role this node operates in, configured via the NODE_ROLE environment variable. */
    public static final NodeRole NODE_ROLE = parseNodeRole(getEnv("NODE_ROLE", "OBSERVER"));

    /** UDP port for the CoAP server; only active when NODE_ROLE=GATEWAY. */
    public static final int COAP_PORT = getIntEnv("COAP_PORT", 5683);

    // ─── Storage Encryption ───────────────────────────────────────────────────
    public static final byte[] STORAGE_AES_KEY;
    static {
        String keyEnv = System.getProperty("STORAGE_AES_KEY");
        if (keyEnv == null || keyEnv.isEmpty()) {
            keyEnv = System.getenv("STORAGE_AES_KEY");
        }
        if (keyEnv != null && !keyEnv.isEmpty()) {
            STORAGE_AES_KEY = HexUtils.decode(keyEnv.trim());
        } else {
            // Check debug mode dynamically for initialization
            if (getBooleanEnv("DEBUG", false)) {
                STORAGE_AES_KEY = HexUtils.decode("00112233445566778899aabbccddeeff");
            } else {
                throw new RuntimeException("STORAGE_AES_KEY must be set in production mode!");
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String getEnv(String name, String def) {
        String val = System.getProperty(name);
        if (val == null) val = System.getenv(name);
        return val != null ? val : def;
    }

    private static int getIntEnv(String name, int def) {
        String val = System.getProperty(name);
        if (val == null) val = System.getenv(name);
        try {
            return val != null ? Integer.parseInt(val) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean getBooleanEnv(String name, boolean def) {
        String val = System.getProperty(name);
        if (val == null) val = System.getenv(name);
        if (val == null) return def;
        return "true".equalsIgnoreCase(val) || "1".equals(val) || "yes".equalsIgnoreCase(val);
    }

    private static NodeRole parseNodeRole(String val) {
        if (val == null) return NodeRole.OBSERVER;
        try {
            return NodeRole.valueOf(val.toUpperCase());
        } catch (IllegalArgumentException e) {
            return NodeRole.OBSERVER;
        }
    }

    /**
     * Returns the node's secp256k1 private key from the NODE_PRIVATE_KEY environment variable.
     *
     * @return the private key as BigInteger
     * @throws RuntimeException if the key is not set in non-debug mode
     */
    public static java.math.BigInteger getNodePrivateKey() {
        String keyHex = System.getProperty("NODE_PRIVATE_KEY");
        if (keyHex == null) keyHex = System.getenv("NODE_PRIVATE_KEY");

        if (keyHex == null || keyHex.isEmpty()) {
            if (isDebug()) return new java.math.BigInteger("1"); // Fallback for testing
            throw new RuntimeException("NODE_PRIVATE_KEY environment variable MUST be set for secure P2P.");
        }
        return new java.math.BigInteger(keyHex, 16);
    }

    /**
     * Returns whether debug mode is active.
     *
     * @return true if DEBUG=true/1/yes
     */
    public static boolean isDebug() {
        return getBooleanEnv("DEBUG", false);
    }

    /**
     * Returns true when runtime profile indicates production mode.
     */
    public static boolean isProductionProfile() {
        String profile = getEnv("SPRING_PROFILES_ACTIVE", "");
        String nodeEnv = getEnv("NODE_ENV", "");
        return profile.toLowerCase().contains("prod") || "production".equalsIgnoreCase(nodeEnv);
    }

    private Config() {}
}
