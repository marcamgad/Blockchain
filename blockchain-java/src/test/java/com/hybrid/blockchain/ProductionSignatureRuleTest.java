package com.hybrid.blockchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [HC-015] The test suite runs with -DREQUIRE_QUANTUM_SIG=false (the opposite of the production
 * default). REQUIRE_QUANTUM_SIG is a static final read at class load, so the PRODUCTION rule is
 * checked here in a fresh JVM started with REQUIRE_QUANTUM_SIG=true.
 */
class ProductionSignatureRuleTest {

    /** Runs inside the forked JVM. Prints one line per check. */
    public static final class Probe {
        public static void main(String[] a) throws Exception {
            System.out.println("REQUIRE=" + Config.REQUIRE_QUANTUM_SIG);
            TestKeyPair ec = new TestKeyPair(8101);
            KeyPair pq = com.hybrid.blockchain.security.QuantumResistantCrypto.generateDilithiumKeyPair(3);

            Transaction ecOnly = new Transaction.Builder().type(Transaction.Type.ACCOUNT).from(ec.getAddress())
                    .to("bob").amount(1).fee(1).nonce(1).sign(ec.getPrivateKey(), ec.getPublicKey());
            System.out.println("EC_ONLY=" + ecOnly.verify());

            Transaction hybrid = new Transaction.Builder().type(Transaction.Type.ACCOUNT).from(ec.getAddress())
                    .to("bob").amount(1).fee(1).nonce(1)
                    .signHybrid(ec.getPrivateKey(), ec.getPublicKey(), pq.getPrivate(), pq.getPublic());
            System.out.println("HYBRID=" + hybrid.verify());

            // the PQ signature must cover the content: a different amount with the same signatures fails
            Transaction tampered = new Transaction(hybrid.getType(), hybrid.getFrom(), hybrid.getTo(), 999,
                    hybrid.getFee(), hybrid.getNonce(), hybrid.getTimestamp(), hybrid.getNetworkId(), hybrid.getData(),
                    hybrid.getValidUntilBlock(), hybrid.getInputs(), hybrid.getOutputs(), hybrid.getPubKey(),
                    hybrid.getSignature(), hybrid.getDilithiumPublicKey(), hybrid.getDilithiumSignature());
            System.out.println("TAMPERED=" + tampered.verify());

            // a PQ signature by a different ML-DSA key must fail
            KeyPair other = com.hybrid.blockchain.security.QuantumResistantCrypto.generateDilithiumKeyPair(3);
            Transaction foreignPq = new Transaction(hybrid.getType(), hybrid.getFrom(), hybrid.getTo(), hybrid.getAmount(),
                    hybrid.getFee(), hybrid.getNonce(), hybrid.getTimestamp(), hybrid.getNetworkId(), hybrid.getData(),
                    hybrid.getValidUntilBlock(), hybrid.getInputs(), hybrid.getOutputs(), hybrid.getPubKey(),
                    hybrid.getSignature(), other.getPublic().getEncoded(), hybrid.getDilithiumSignature());
            System.out.println("FOREIGN_PQ=" + foreignPq.verify());
        }
    }

    @Test
    @DisplayName("with REQUIRE_QUANTUM_SIG=true: EC-only rejected, EC+ML-DSA accepted, tampering rejected")
    void productionRule() throws Exception {
        List<String> cmd = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java",
                "-DREQUIRE_QUANTUM_SIG=true", "-DDEBUG=true",
                "-DSTORAGE_AES_KEY=00112233445566778899aabbccddeeff",
                "-cp", System.getProperty("java.class.path"), Probe.class.getName()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        }
        assertThat(p.waitFor()).as(String.join("\n", out)).isZero();
        assertThat(out).contains("REQUIRE=true", "EC_ONLY=false", "HYBRID=true", "TAMPERED=false", "FOREIGN_PQ=false");
    }
}
