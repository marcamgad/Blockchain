package com.hybrid.blockchain.security;

import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.tools.KeygenTool;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * [O-3] Keys and secrets known to be public. Outside DEBUG mode the node refuses to start with any
 * of them.
 *
 * <p>Sources: (1) keys {@link KeygenTool} derives from the seeds that were published in this
 * repository; (2) the private key {@code 1} (old DEBUG fallback); (3) SHA-256 fingerprints of every
 * hex key or secret value found in this repository's git history or working tree (scan of
 * 2026-10-21, see docs/security/HISTORY_SECRET_SCAN.md). Only fingerprints are stored here, never the
 * values. A fingerprint is SHA-256 of the lowercase hex string ({@code BigInteger.toString(16)} for
 * private keys).</p>
 */
public final class CompromisedKeys {

    /** Seeds that appeared in this repository; every key derived from them is public. */
    static final String[] PUBLISHED_SEEDS = {"hybridchain-20node-seed", "hybridchain-key-seed"};
    private static final int DERIVATION_DEPTH = 64;

    private static final Set<String> NODE_KEY_FINGERPRINTS = Set.of(
            "0ae2e720615d78cac2657a541f62eb1842a48fe33f0828403386cf92d30ce0fe",
            "10e392b60e6c2753a52eb8524248dd78dda27588d6b934ecaf7b3f4ffebbc577",
            "1b8979285e73ec1aced920f53286d624325adfdd023e3812febd7c733a552e1b",
            "29ae6bf24f3d404a91c341b0888614000d944627760263c9675d6c9a4d956004",
            "2cb4d221a251067b531cca482a311e4cd3d9202fd9cd29d36cf92a8be35288db",
            "2e0e616fe2a132e50ae6d4749510b5b1dabc5d9c30ccafa1b65e8ede5ae4fe08",
            "2e283f80fac9d33bfb62c0fca21621161b109f1498634b080529c96e57ef1f2a",
            "3138bb9bc78df27c473ecfd1410f7bd45ebac1f59cf3ff9cfe4db77aab7aedd3",
            "3d9cac3dd903899b208bcdb2364548cf826b4bac8eb3e9d83147fed373c6d7e3",
            "44f1ec4b8739af1fb980d7f714f599ef2e17fd21c2123830de2f8d7f5af6667a",
            "5164ef4e3c4e50c80a99e18f776e0245e31c253f7d7ab01bab8d394048fceade",
            "58ff057dbd202f4223a8c0f43c192329a495b309f684c6c2a4c96884e40bbaef",
            "6032c0ad6488cf119395dba3c8b687fcd55fc3fedf263f7f054360ff8645f053",
            "60cfc1fc10f57dd5722758bc3e9d282bd1b7098f939ef2d33aab2935587fbeab",
            "66992651d8a428450b6129942541b9e07ba4d1e1b1dffa78dcaa97ac6b2a27db",
            "6855c971657abbcd818f6250eca0bdb29000f74a0faf5e0f846b02c1975e2e48",
            "73a457038e263562fb8b4f932c74d686ed5e11845f31977f40406b6bf3dbe7c1",
            "76ae7106ac00582f990f0b63d32b7c69e6471960dd79c66b1136370f4cee9b26",
            "776ef6dbb21fea2cdc6ba99a850c98471f212c569f986b5083ac1a031b3ab9dd",
            "7b8d416b8dcd95c3f0d9ec6b7f417d3619fcde7a2d5b0efa7db27239e2cb9380",
            "7cab6198987ca7d4db9f3d21e123e221b9021511226f5eb1069fb20eea608eb6",
            "7eeaf57e88c48f160db38187e97010ce328ef5b6a802bcdded91abd83f27b5cc",
            "832a512dd5fe0dd2b570111bb5bc2a8d527282d29ee6169f1a8f897ff8ea7200",
            "8381e261f574193459e3db639d564565699f8c8cd8fefb4a2540a3372f1a8b88",
            "845d93b96e533c5d76334001f4c5f53d9b762e197da589f49c8528577e257d73",
            "941d800023584a3515903445909f24cb73c8f1b62ec093507ff98f8d292334ee",
            "957334f75c89729459b6ee424051e733c94cdc84fc9d1fcc31f0910fcee533f2",
            "af497eb25f2a1d9c949c88ba848f946fc48b8943ef81400a159df06abfa0e0df",
            "b1580636a483e8f8afc973bb357456264ff7a83430659b625ae051f1e66baac1",
            "b4657e5355aa5acae10de1adcca34cf00faa719bcd9663ecb9095e1a4fccb52a",
            "bd85b486ab7e8fadea23502320ebc01ec74522b143cf618b1f73598a3ea255f6",
            "c66b38175fce09cf1f3b85a70ef684e2a77163f3221f5458f9aca53ffc6bfd97",
            "c8345ea39efe494ca29642ee858aebcad766b8edca23cf4f69712009e35c6114",
            "cc6ca49868a203cb085e7c9bdaa435f8417a9606a130d78426bf79e096052646",
            "d21482d5aeb0166b0a620b5aeef388952851e2f8a215c9ddf1b40fae28127de9",
            "d3875330897f5bef5ee5d24ef9c98f8b1fe4a6b7d8a1929a2bf8c0a90d53e788",
            "d3fd8d2915b784f15379f65ae4fb1a697e02a5e9181f4ce8d779d6959cf228d1",
            "d454452d8ef025d3fa7b48bb8b82edd7ac67d4007bee08b73089bc004fe7fa07",
            "d7b05c713bb3b354d7caa65ee87fb0972017e1d8b56ac2ecb313d40f4a7d2a26",
            "db5a79b1d084b0e400e4971a0af6aae3d8997803c5a282f6297e75eb190deacf",
            "dfda9f16b777acbf4362026f3910d28713c45640b215aadb315a4e81ce789a0c",
            "e187f9a5e47a73a9f2d3c1d9d6c4e057471a421e3aa54c0e1acd5ee398a41257",
            "e479ae9b7f16acd46fdc8f46889fd3c2d6caedcdc0bf5303428c2695500e8853",
            "f2b0239d49638164880418ac7e6d0ef0917bbceaf3bdc2e91877f795854398e8");

    private static final Set<String> SECRET_FINGERPRINTS = Set.of(
            "2a8abfa8cb9906290437854193ca6bca41d4d4e26d1d454bd66a35158095e737",
            "5947d7c33d783f94b3b4c1a96ebc8991ed28f1b069b71e03376cba8caa98a720",
            "a8ae6e6ee929abea3afcfc5258c8ccd6f85273e0d4626d26c7279f3250f77c8e");

    private static volatile Set<BigInteger> derived;

    private CompromisedKeys() {
    }

    private static String fingerprint(String lowercaseHex) {
        return HexUtils.encode(Crypto.hash(lowercaseHex.getBytes(StandardCharsets.UTF_8)));
    }

    private static Set<BigInteger> derivedFromPublishedSeeds() {
        Set<BigInteger> d = derived;
        if (d == null) {
            d = new HashSet<>();
            for (String seed : PUBLISHED_SEEDS) {
                for (int i = 1; i <= DERIVATION_DEPTH; i++) d.add(KeygenTool.derivePrivateKey(seed, i));
            }
            derived = d;
        }
        return d;
    }

    /** True iff this node private key is publicly known. */
    public static boolean isCompromisedNodeKey(BigInteger key) {
        if (key == null) return false;
        if (key.equals(BigInteger.ONE)) return true;
        if (NODE_KEY_FINGERPRINTS.contains(fingerprint(key.toString(16)))) return true;
        return derivedFromPublishedSeeds().contains(key);
    }

    /** True iff this secret (storage AES key, network CA seed) is publicly known. */
    public static boolean isCompromisedSecret(byte[] secret) {
        if (secret == null) return false;
        // Config's own DEBUG fallbacks are public by construction
        if (java.util.Arrays.equals(secret, HexUtils.decode("00112233445566778899aabbccddeeff"))) return true;
        if (java.util.Arrays.equals(secret, Crypto.hash("hybridchain-DEVELOPMENT-ONLY-network-ca-seed".getBytes(StandardCharsets.UTF_8)))) return true;
        return SECRET_FINGERPRINTS.contains(fingerprint(HexUtils.encode(secret).toLowerCase(Locale.ROOT)));
    }
}
