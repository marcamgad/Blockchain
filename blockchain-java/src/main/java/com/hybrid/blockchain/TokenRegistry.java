package com.hybrid.blockchain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native token registry for HybridChain multi-token support.
 * Supports registering new token types, minting, burning, and transferring tokens
 * between accounts. Token balances are tracked in {@link AccountState.Account#tokenBalances}
 * alongside the native chain token under the key {@code "native"}.
 *
 * <p>Token metadata is persisted in {@link Storage} under the key prefix {@code "token:"}.
 */
public class TokenRegistry {

    private static final Logger log = LoggerFactory.getLogger(TokenRegistry.class);
    private static final String KEY_PREFIX = "token:";

    private final Storage storage;

    /** In-memory cache of token metadata. */
    private final Map<String, TokenInfo> tokenCache = new ConcurrentHashMap<>();

    /**
     * Constructs a TokenRegistry backed by the given storage.
     *
     * @param storage the storage instance for persisting token metadata
     */
    public TokenRegistry(Storage storage) {
        this.storage = storage;
    }

    /**
     * Immutable metadata for a registered token type.
     */
    public static class TokenInfo {
        public final String tokenId;
        public final String name;
        public final String symbol;
        public final int decimals;
        public final long maxSupply;
        public final String owner;
        public volatile long totalMinted;

        public TokenInfo(String tokenId, String name, String symbol, int decimals, long maxSupply, String owner) {
            this.tokenId = tokenId;
            this.name = name;
            this.symbol = symbol;
            this.decimals = decimals;
            this.maxSupply = maxSupply;
            this.owner = owner;
            this.totalMinted = 0;
        }

        public long getTotalMinted() {
            return totalMinted;
        }
    }

    /**
     * Registers a new token type. Throws if a token with this ID already exists.
     *
     * @param tokenId   unique identifier for the token (e.g., "MYTOKEN")
     * @param name      human-readable token name
     * @param symbol    ticker symbol
     * @param decimals  number of decimal places
     * @param maxSupply maximum mintable supply (0 = unlimited)
     * @param owner     owner address that controls minting/burning
     * @throws Exception if the tokenId is already registered
     */
    public synchronized void registerToken(String tokenId, String name, String symbol,
                                            int decimals, long maxSupply, String owner) throws Exception {
        registerToken(tokenId, name, symbol, decimals, maxSupply, owner, false);
    }

    public synchronized void registerToken(String tokenId, String name, String symbol,
                                            int decimals, long maxSupply, String owner, boolean isSimulation) throws Exception {
        TokenInfo existing = getTokenInfo(tokenId);
        if (existing != null) {
            if (existing.name.equals(name) && existing.symbol.equals(symbol) && 
                existing.decimals == decimals && existing.maxSupply == maxSupply && 
                existing.owner.equals(owner)) {
                return; // Idempotent
            }
            throw new Exception("Token already registered with different metadata: " + tokenId);
        }
        TokenInfo info = new TokenInfo(tokenId, name, symbol, decimals, maxSupply, owner);
        if (!isSimulation) {
            tokenCache.put(tokenId, info);
            persistToken(info);
            log.info("[TokenRegistry] Registered token: {} ({})", name, tokenId);
        }
    }

    /**
     * Mints {@code amount} tokens of the given type to the recipient address.
     * Enforces the {@code maxSupply} cap if set.
     *
     * @param state   the account state to credit
     * @param tokenId the token identifier
     * @param to      the recipient address
     * @param amount  the amount to mint
     * @throws Exception if the token does not exist or max supply would be exceeded
     */
    public synchronized void mintToken(AccountState state, String tokenId, String to, long amount) throws Exception {
        mintToken(state, tokenId, to, amount, false);
    }

    public synchronized void mintToken(AccountState state, String tokenId, String to, long amount, boolean isSimulation) throws Exception {
        TokenInfo info = requireToken(tokenId);
        if (info.maxSupply > 0 && info.totalMinted + amount > info.maxSupply) {
            throw new Exception("Minting would exceed max supply for token: " + tokenId);
        }
        state.creditToken(to, tokenId, amount);
        if (!isSimulation) {
            info.totalMinted += amount;
            persistToken(info);
            log.info("[TokenRegistry] Minted {} of {} to {}", amount, tokenId, to);
        }
    }

    /**
     * Burns {@code amount} tokens of the given type from the sender address.
     *
     * @param state   the account state to debit
     * @param tokenId the token identifier
     * @param from    the address to burn from
     * @param amount  the amount to burn
     * @throws Exception if the token does not exist or the balance is insufficient
     */
    public synchronized void burnToken(AccountState state, String tokenId, String from, long amount) throws Exception {
        burnToken(state, tokenId, from, amount, false);
    }

    public synchronized void burnToken(AccountState state, String tokenId, String from, long amount, boolean isSimulation) throws Exception {
        requireToken(tokenId);
        state.debitToken(from, tokenId, amount);
        if (!isSimulation) {
            TokenInfo info = tokenCache.get(tokenId);
            if (info != null && info.totalMinted >= amount) {
                info.totalMinted -= amount;
                persistToken(info);
            }
            log.info("[TokenRegistry] Burned {} of {} from {}", amount, tokenId, from);
        }
    }

    /**
     * Transfers {@code amount} tokens from sender to recipient.
     *
     * @param state   the account state to update
     * @param tokenId the token identifier
     * @param from    the sender address
     * @param to      the recipient address
     * @param amount  the amount to transfer
     * @throws Exception if the token does not exist or the sender has insufficient balance
     */
    public synchronized void transferToken(AccountState state, String tokenId, String from, String to, long amount) throws Exception {
        requireToken(tokenId);
        state.debitToken(from, tokenId, amount);
        state.creditToken(to, tokenId, amount);
        log.info("[TokenRegistry] Transferred {} of {} from {} to {}", amount, tokenId, from, to);
    }

    /**
     * Returns the token balance of an address for a given token.
     *
     * @param state   the account state to query
     * @param address the account address
     * @param tokenId the token identifier
     * @return the balance, or 0 if the address or token is not found
     */
    public long getBalance(AccountState state, String address, String tokenId) {
        return state.getTokenBalance(address, tokenId);
    }

    /**
     * Returns the metadata for a registered token.
     *
     * @param tokenId the token identifier
     * @return the TokenInfo, or null if not found
     */
    public TokenInfo getTokenInfo(String tokenId) {
        TokenInfo cached = tokenCache.get(tokenId);
        if (cached != null) return cached;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = storage.get(KEY_PREFIX + tokenId, Map.class);
            if (raw == null) return null;
            TokenInfo info = deserializeToken(raw);
            tokenCache.put(tokenId, info);
            return info;
        } catch (Exception e) {
            log.warn("[TokenRegistry] Failed to load token {}: {}", tokenId, e.getMessage());
            return null;
        }
    }

    /**
     * Returns whether a token with the given ID is registered.
     *
     * @param tokenId the token identifier
     * @return true if the token exists
     */
    public boolean tokenExists(String tokenId) {
        return getTokenInfo(tokenId) != null;
    }

    private TokenInfo requireToken(String tokenId) throws Exception {
        TokenInfo info = getTokenInfo(tokenId);
        if (info == null) {
            throw new Exception("Unknown token: " + tokenId);
        }
        return info;
    }

    private void persistToken(TokenInfo info) {
        try {
            Map<String, Object> raw = new HashMap<>();
            raw.put("tokenId", info.tokenId);
            raw.put("name", info.name);
            raw.put("symbol", info.symbol);
            raw.put("decimals", info.decimals);
            raw.put("maxSupply", info.maxSupply);
            raw.put("owner", info.owner);
            raw.put("totalMinted", info.totalMinted);
            storage.put(KEY_PREFIX + info.tokenId, raw);
        } catch (Exception e) {
            log.error("[TokenRegistry] Failed to persist token {}: {}", info.tokenId, e.getMessage());
        }
    }

    private TokenInfo deserializeToken(Map<String, Object> raw) {
        String tokenId = (String) raw.get("tokenId");
        String name = (String) raw.get("name");
        String symbol = (String) raw.get("symbol");
        int decimals = ((Number) raw.get("decimals")).intValue();
        long maxSupply = ((Number) raw.get("maxSupply")).longValue();
        String owner = (String) raw.get("owner");
        long totalMinted = raw.containsKey("totalMinted") ? ((Number) raw.get("totalMinted")).longValue() : 0L;
        TokenInfo info = new TokenInfo(tokenId, name, symbol, decimals, maxSupply, owner);
        info.totalMinted = totalMinted;
        return info;
    }
}
