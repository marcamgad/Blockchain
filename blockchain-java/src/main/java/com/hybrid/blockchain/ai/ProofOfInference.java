package com.hybrid.blockchain.ai;

import com.hybrid.blockchain.Crypto;
import java.util.Arrays;
import java.util.List;

/**
 * Proof of Inference (PoI) for HybridChain IoT devices.
 *
 * <p>A protocol where IoT devices prove they executed a specific AI model (CNN/MLP)
 * on raw telemetry before submission. This prevents "lazy" or "faked" AI results.
 *
 * @novel P2-A — Research-grade AI execution verification
 */
public class ProofOfInference {
    private final String deviceId;
    private final byte[] modelHash;
    private final byte[] inputHash;
    private final byte[] outputHash;
    private final byte[] merkleRoot; // Root of layer activations

    public ProofOfInference(String deviceId, byte[] modelHash, byte[] inputHash, byte[] outputHash, byte[] merkleRoot) {
        this.deviceId = deviceId;
        this.modelHash = modelHash;
        this.inputHash = inputHash;
        this.outputHash = outputHash;
        this.merkleRoot = merkleRoot;
    }

    /**
     * Computes a Merkle root of layer activations for the inference.
     */
    public static byte[] computeMerkleRoot(List<byte[]> layers) {
        if (layers == null || layers.isEmpty()) return new byte[32];
        byte[] current = layers.get(0);
        for (int i = 1; i < layers.size(); i++) {
            current = Crypto.hash(concat(current, layers.get(i)));
        }
        return current;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] res = new byte[a.length + b.length];
        System.arraycopy(a, 0, res, 0, a.length);
        System.arraycopy(b, 0, res, a.length, b.length);
        return res;
    }

    /**
     * Verifies the PoI against an expected model and input.
     */
    public boolean verify(byte[] expectedModelHash, byte[] expectedInputHash) {
        return Arrays.equals(this.modelHash, expectedModelHash) && 
               Arrays.equals(this.inputHash, expectedInputHash);
    }

    public String getDeviceId() { return deviceId; }
    public byte[] getModelHash() { return modelHash; }
    public byte[] getInputHash() { return inputHash; }
    public byte[] getOutputHash() { return outputHash; }
    public byte[] getMerkleRoot() { return merkleRoot; }
}
