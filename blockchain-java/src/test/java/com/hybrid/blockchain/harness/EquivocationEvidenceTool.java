package com.hybrid.blockchain.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.security.QuantumResistantCrypto;
import com.hybrid.blockchain.api.AuthChallenges;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.util.Map;

/**
 * [O-2] Fault-injection helper (TEST code, not shipped in the node jar).
 *
 * <p>Simulates an equivocating validator: signs two conflicting PREPARE votes (same view and
 * height, different hashes) with that validator's key, wraps them in an EQUIVOCATION_EVIDENCE
 * transaction signed by a reporter, and submits it to a node over REST
 * {@code /api/v1/transactions/submit-signed}. It does NOT
 * exercise live double-voting on the wire; it checks that evidence is accepted and that every node
 * then agrees on the jail list.</p>
 *
 * <pre>java -cp target/test-classes;target/classes;&lt;deps&gt; com.hybrid.blockchain.harness.EquivocationEvidenceTool \
 *     &lt;cheaterPrivHex&gt; &lt;reporterPrivHex&gt; &lt;restBaseUrl&gt;</pre>
 *
 * <p>The endpoint needs a JWT for the sender: the tool logs in like any client, by signing a one-time
 * challenge ([HC-085] {@code /api/v1/auth/challenge} and {@code /api/v1/auth/token}). CoAP was used
 * before, but a VALIDATOR node does not start its CoAP server (GATEWAY only).</p>
 *
 * <p>Private keys are read from the command line, so run it only on a throw-away harness network.</p>
 */
public final class EquivocationEvidenceTool {

    private EquivocationEvidenceTool() {
    }

    /** Builds the evidence for conflicting PREPAREs by {@code cheater} at (view, seq). */
    public static PBFTConsensus.EquivocationEvidence evidence(BigInteger cheater, long view, long seq) {
        String id = Crypto.deriveAddress(Crypto.derivePublicKey(cheater));
        String a = HexUtils.encode(Crypto.hash(("harness-a-" + seq).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String b = HexUtils.encode(Crypto.hash(("harness-b-" + seq).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        PBFTConsensus.PBFTMessage ma = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.PREPARE, view, seq, a, id);
        ma.sign(cheater);
        PBFTConsensus.PBFTMessage mb = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.PREPARE, view, seq, b, id);
        mb.sign(cheater);
        PBFTConsensus.EquivocationEvidence ev = new PBFTConsensus.EquivocationEvidence();
        ev.phase = "PREPARE";
        ev.view = view;
        ev.seq = seq;
        ev.validatorId = id;
        ev.hashA = a;
        ev.signatureA = HexUtils.encode(ma.signature);
        ev.hashB = b;
        ev.signatureB = HexUtils.encode(mb.signature);
        return ev;
    }

    /** The signed EQUIVOCATION_EVIDENCE transaction: the same builder the node uses (HC-088). */
    public static Transaction transaction(PBFTConsensus.EquivocationEvidence ev, BigInteger reporter, long nonce, long fee)
            throws Exception {
        return com.hybrid.blockchain.consensus.EvidenceTransactions.build(ev, reporter,
                QuantumResistantCrypto.generateDilithiumKeyPair(3), nonce, fee);
    }

    /** [HC-085] challenge-response login: sign the node's one-time challenge with the account key. */
    @SuppressWarnings("unchecked")
    static String login(HttpClient http, ObjectMapper m, String rest, BigInteger key) throws Exception {
        byte[] pub = Crypto.derivePublicKey(key);
        String addr = Crypto.deriveAddress(pub);
        HttpResponse<String> c = http.send(HttpRequest.newBuilder(URI.create(rest + "/api/v1/auth/challenge"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(m.writeValueAsString(Map.of("address", addr)))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (c.statusCode() != 200) throw new IllegalStateException("challenge: HTTP " + c.statusCode() + " " + c.body());
        byte[] ch = HexUtils.decode((String) m.readValue(c.body(), Map.class).get("challenge"));
        Map<String, Object> req = Map.of("address", addr, "publicKey", HexUtils.encode(pub), "challenge", HexUtils.encode(ch),
                "signature", HexUtils.encode(Crypto.sign(AuthChallenges.digest(addr, ch), key)));
        HttpResponse<String> t = http.send(HttpRequest.newBuilder(URI.create(rest + "/api/v1/auth/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(m.writeValueAsString(req))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (t.statusCode() != 200) throw new IllegalStateException("login: HTTP " + t.statusCode() + " " + t.body());
        return (String) m.readValue(t.body(), Map.class).get("token");
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: <cheaterPrivHex> <reporterPrivHex> <restBaseUrl>");
            System.exit(2);
        }
        BigInteger cheater = new BigInteger(args[0], 16), reporter = new BigInteger(args[1], 16);
        String rest = args[2];
        String reporterAddr = Crypto.deriveAddress(Crypto.derivePublicKey(reporter));
        ObjectMapper m = new ObjectMapper();
        HttpClient http = HttpClient.newHttpClient();
        String token = login(http, m, rest, reporter);
        Map<String, Object> acct = m.readValue(http.send(HttpRequest.newBuilder(URI.create(rest + "/api/v1/accounts/" + reporterAddr))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString()).body(), Map.class);
        Map<String, Object> fee = m.readValue(http.send(HttpRequest.newBuilder(URI.create(rest + "/api/v1/fee/estimate")).build(),
                HttpResponse.BodyHandlers.ofString()).body(), Map.class);
        long nonce = ((Number) acct.getOrDefault("nonce", 0)).longValue() + 1;
        long baseFee = fee.values().stream().filter(v -> v instanceof Number).mapToLong(v -> ((Number) v).longValue()).max().orElse(1);
        Transaction tx = transaction(evidence(cheater, 0, 999_999L), reporter, nonce, Math.max(1, baseFee));
        byte[] json = m.writeValueAsBytes(tx);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(rest + "/api/v1/transactions/submit-signed"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println("EVIDENCE_SUBMITTED code=" + r.statusCode() + " body=" + r.body()
                + " cheater=" + Crypto.deriveAddress(Crypto.derivePublicKey(cheater)));
        if (r.statusCode() != 200) System.exit(1);   // run.sh records the phase as failed
    }
}
