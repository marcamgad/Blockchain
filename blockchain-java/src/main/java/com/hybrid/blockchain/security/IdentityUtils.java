package com.hybrid.blockchain.security;

import com.hybrid.blockchain.Crypto;
import java.util.regex.Pattern;

/**
 * Utility for Sovereign Identity (DID) management.
 * Standard format: did:hybrid:<hex_address>
 */
public class IdentityUtils {

    public static final String SCHEME = "did:hybrid:";
    private static final Pattern DID_PATTERN = Pattern.compile("^did:hybrid:0x[a-fA-F0-9]{40}$");

    /**
     * Converts a blockchain address to a Sovereign Identity DID.
     */
    public static String addressToDID(String address) {
        if (address == null) return null;
        if (address.startsWith(SCHEME)) return address;
        String cleanAddress = address.startsWith("0x") ? address : "0x" + address;
        return SCHEME + cleanAddress;
    }

    /**
     * Extracts the address from a DID.
     */
    public static String didToAddress(String did) {
        if (did == null) return null;
        if (!did.startsWith(SCHEME)) return did;
        return did.substring(SCHEME.length());
    }

    /**
     * Validates if the string is a properly formatted HybridChain DID.
     */
    public static boolean isValidDID(String did) {
        return did != null && DID_PATTERN.matcher(did).matches();
    }
    
    /**
     * Derives a DID directly from a public key.
     */
    public static String publicKeyToDID(byte[] publicKey) {
        return addressToDID(Crypto.deriveAddress(publicKey));
    }
}
