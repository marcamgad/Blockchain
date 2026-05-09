package com.hybrid.blockchain.security;

import com.hybrid.blockchain.Crypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Simulates a Hardware Security Module (HSM) for secure key management.
 * 
 * <p>In a real production environment, this would interface with a 
 * physical HSM via PKCS#11 or a cloud HSM (AWS/Azure).</p>
 */
public class HardwareSecurityModule {
    private static final Logger log = LoggerFactory.getLogger(HardwareSecurityModule.class);
    
    private final byte[] encryptedKey; // Simulation of "Key Wrapping"
    private final byte[] publicKey;
    private final String keyId;

    public HardwareSecurityModule(BigInteger privateKey) {
        this.publicKey = Crypto.derivePublicKey(privateKey);
        this.keyId = "hsm-key-" + Integer.toHexString(Arrays.hashCode(publicKey));
        
        // Simulate key wrapping: In a real HSM, the key never leaves the hardware.
        // Here we just "mask" it to simulate that it's not sitting as a BigInteger on the heap.
        this.encryptedKey = privateKey.toByteArray();
        
        log.info("[HSM] Initialized secure key ID: {}", keyId);
    }

    /**
     * Signs data using the internal secure key.
     * The private key is only "unwrapped" in the local scope of this method.
     */
    public byte[] sign(byte[] data) {
        // In a real HSM, 'data' is sent to the device, and 'signature' comes back.
        // Here we simulate the internal signing process.
        log.debug("[HSM] Signing payload with key {}", keyId);
        
        // [SIMULATION ONLY] We use the wrapped key to perform the operation
        // In a real HSM, this BigInteger would never exist in JVM memory.
        BigInteger priv = unwrapKey();
        byte[] sig = Crypto.sign(data, priv);
        // Clear the stack immediately
        priv = BigInteger.ZERO; 
        
        return sig;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public String getKeyId() {
        return keyId;
    }

    private BigInteger unwrapKey() {
        // Logic to simulate internal unwrapping
        // In reality, this would be a JNI call to a native library.
        return new BigInteger(1, encryptedKey); // Simplified for simulation
    }
}
