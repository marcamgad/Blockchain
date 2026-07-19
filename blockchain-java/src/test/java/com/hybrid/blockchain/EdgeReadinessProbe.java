package com.hybrid.blockchain;

import com.hybrid.blockchain.privacy.ZKProofSystem;
import com.hybrid.blockchain.reputation.ZkEligibilityGate;
import com.hybrid.blockchain.security.QuantumResistantCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;

/**
 * [BENCHMARK] Per-operation cost of the critical path, for edge-device sizing.
 *
 * <p>Run this ON the target device to get real numbers:
 * {@code mvn -o test -Dtest=EdgeReadinessProbe}
 *
 * <p>Results are written to {@code docs/benchmarks/edge_probe_<os>_<arch>.txt} so a
 * developer-machine run and a Raspberry Pi run can be compared directly.
 */
@Tag("benchmark")
public class EdgeReadinessProbe {

    private static long timeOp(String label, int iters, Runnable op) {
        for (int i = 0; i < Math.max(1, iters / 10); i++) op.run();   // warm up
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) op.run();
        long ns = (System.nanoTime() - t0) / iters;
        System.out.printf("[EDGE] %-34s %10.3f ms/op%n", label, ns / 1_000_000.0);
        return ns;
    }

    @Test
    @DisplayName("[BENCHMARK] per-operation cost for edge sizing")
    void probe() throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("Edge readiness probe\n")
           .append("os.name=").append(System.getProperty("os.name"))
           .append("  os.arch=").append(System.getProperty("os.arch"))
           .append("  java=").append(System.getProperty("java.version"))
           .append("  cores=").append(Runtime.getRuntime().availableProcessors())
           .append("\nmaxHeapMB=").append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
           .append("\n\n");

        BigInteger priv = new BigInteger("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", 16);
        byte[] pub = Crypto.derivePublicKey(priv);
        byte[] msg = Crypto.hash("edge-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        long ecdsaSign = timeOp("ECDSA sign", 200, () -> Crypto.sign(msg, priv));
        byte[] sig = Crypto.sign(msg, priv);
        long ecdsaVerify = timeOp("ECDSA verify", 200, () -> Crypto.verify(msg, sig, pub));
        long sha = timeOp("SHA-256 (1KB)", 5000, () -> Crypto.hash(new byte[1024]));

        // Post-quantum: REQUIRE_QUANTUM_SIG defaults TRUE in production, so every
        // transaction pays this cost on top of ECDSA.
        long dilKeygen = timeOp("Dilithium keygen", 10, () -> {
            try { QuantumResistantCrypto.generateDilithiumKeyPair(2); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        KeyPair dk = QuantumResistantCrypto.generateDilithiumKeyPair(2);
        long dilSign = timeOp("Dilithium sign", 50, () -> {
            try { QuantumResistantCrypto.signDilithium(msg, dk.getPrivate()); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        byte[] dsig = QuantumResistantCrypto.signDilithium(msg, dk.getPrivate());
        long dilVerify = timeOp("Dilithium verify", 50, () -> {
            try { QuantumResistantCrypto.verifyDilithium(msg, dsig, dk.getPublic()); }
            catch (Exception e) { throw new RuntimeException(e); }
        });

        // Zero-knowledge: the heaviest operation in the system by far.
        long zkProve = timeOp("ZK threshold proof (create)", 3,
                () -> ZkEligibilityGate.prove(0.90, 0.50));
        ZKProofSystem.ThresholdProof p = ZkEligibilityGate.prove(0.90, 0.50);
        long zkVerify = timeOp("ZK threshold proof (verify)", 3, p::verify);

        out.append(String.format("%-34s %10.3f ms%n", "ECDSA sign",        ecdsaSign / 1e6));
        out.append(String.format("%-34s %10.3f ms%n", "ECDSA verify",      ecdsaVerify / 1e6));
        out.append(String.format("%-34s %10.3f ms%n", "SHA-256 (1KB)",     sha / 1e6));
        out.append(String.format("%-34s %10.3f ms%n", "Dilithium keygen",  dilKeygen / 1e6));
        out.append(String.format("%-34s %10.3f ms%n", "Dilithium sign",    dilSign / 1e6));
        out.append(String.format("%-34s %10.3f ms%n", "Dilithium verify",  dilVerify / 1e6));
        out.append(String.format("%-34s %10.3f ms%n", "ZK proof create",   zkProve / 1e6));
        out.append(String.format("%-34s %10.3f ms%n", "ZK proof verify",   zkVerify / 1e6));

        Path dir = Paths.get("docs", "benchmarks");
        Files.createDirectories(dir);
        String tag = (System.getProperty("os.name") + "_" + System.getProperty("os.arch"))
                .replaceAll("[^A-Za-z0-9._-]", "");
        Files.writeString(dir.resolve("edge_probe_" + tag + ".txt"), out.toString());
        System.out.println("[EDGE] written to docs/benchmarks/edge_probe_" + tag + ".txt");
        System.out.println(out);
    }
}
