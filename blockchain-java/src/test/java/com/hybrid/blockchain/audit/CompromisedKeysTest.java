package com.hybrid.blockchain.audit;

import com.hybrid.blockchain.Config;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.security.CompromisedKeys;
import com.hybrid.blockchain.tools.KeygenTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** [O-3] Publicly known keys are recognised and refused outside DEBUG. */
class CompromisedKeysTest {

    /** Runs in a separate JVM WITHOUT -DDEBUG=true (production behaviour). */
    public static class Probe {
        public static void main(String[] args) {
            try {
                BigInteger k = Config.getNodePrivateKey();
                System.out.println("ACCEPTED " + (k.signum() > 0));
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null) root = root.getCause();
                System.out.println("REFUSED " + root.getMessage());
            }
        }
    }

    private static List<String> runProbe(String nodeKeyHex) throws Exception {
        byte[] aes = new byte[16];
        new SecureRandom().nextBytes(aes);
        List<String> cmd = List.of(System.getProperty("java.home") + "/bin/java",
                "-DSTORAGE_AES_KEY=" + HexUtils.encode(aes), "-DNODE_PRIVATE_KEY=" + nodeKeyHex,
                "-cp", System.getProperty("java.class.path"), Probe.class.getName());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        }
        p.waitFor();
        return out;
    }

    @Test
    @DisplayName("keys from published seeds and the old DEBUG fallback are recognised; random keys are not")
    void recognition() {
        assertThat(CompromisedKeys.isCompromisedNodeKey(KeygenTool.derivePrivateKey("hybridchain-20node-seed", 3))).isTrue();
        assertThat(CompromisedKeys.isCompromisedNodeKey(KeygenTool.derivePrivateKey("hybridchain-key-seed", 1))).isTrue();
        assertThat(CompromisedKeys.isCompromisedNodeKey(BigInteger.ONE)).isTrue();
        assertThat(CompromisedKeys.isCompromisedNodeKey(new BigInteger(255, new SecureRandom()).setBit(254))).isFalse();
        byte[] fresh = new byte[16];
        new SecureRandom().nextBytes(fresh);
        assertThat(CompromisedKeys.isCompromisedSecret(fresh)).isFalse();
        assertThat(CompromisedKeys.isCompromisedSecret(HexUtils.decode("00112233445566778899aabbccddeeff")))
                .as("the DEBUG fallback AES key").isTrue();
    }

    @Test
    @DisplayName("production mode refuses a compromised node key and accepts a fresh one")
    void productionRefusal() throws Exception {
        String published = KeygenTool.derivePrivateKey("hybridchain-20node-seed", 2).toString(16);
        assertThat(runProbe(published)).anyMatch(l -> l.startsWith("REFUSED") && l.contains("publicly known"));
        String fresh = new BigInteger(255, new SecureRandom()).setBit(254).toString(16);
        assertThat(runProbe(fresh)).contains("ACCEPTED true");
    }
}
