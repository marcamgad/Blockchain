package com.hybrid.blockchain.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import com.hybrid.blockchain.security.QuantumResistantCrypto;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.MediaTypeRegistry;

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
 * transaction signed by a reporter, and submits it to a node over CoAP {@code /tx}. It does NOT
 * exercise live double-voting on the wire; it checks that evidence is accepted and that every node
 * then agrees on the jail list.</p>
 *
 * <pre>java -cp target/test-classes;target/classes;&lt;deps&gt; com.hybrid.blockchain.harness.EquivocationEvidenceTool \
 *     &lt;cheaterPrivHex&gt; &lt;reporterPrivHex&gt; &lt;restBaseUrl&gt; &lt;coapTxUri&gt;</pre>
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

    /** The signed EQUIVOCATION_EVIDENCE transaction (hybrid-signed, so it passes REQUIRE_QUANTUM_SIG). */
    public static Transaction transaction(PBFTConsensus.EquivocationEvidence ev, BigInteger reporter, long nonce, long fee)
            throws Exception {
        byte[] pub = Crypto.derivePublicKey(reporter);
        KeyPair pq = QuantumResistantCrypto.generateDilithiumKeyPair(3);
        return new Transaction.Builder().type(Transaction.Type.EQUIVOCATION_EVIDENCE)
                .from(Crypto.deriveAddress(pub))
                .data(new ObjectMapper().writeValueAsBytes(ev)).fee(fee).nonce(nonce)
                .signHybrid(reporter, pub, pq.getPrivate(), pq.getPublic());
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: <cheaterPrivHex> <reporterPrivHex> <restBaseUrl> <coapTxUri>");
            System.exit(2);
        }
        BigInteger cheater = new BigInteger(args[0], 16), reporter = new BigInteger(args[1], 16);
        String rest = args[2];
        String reporterAddr = Crypto.deriveAddress(Crypto.derivePublicKey(reporter));
        ObjectMapper m = new ObjectMapper();
        HttpClient http = HttpClient.newHttpClient();
        Map<String, Object> acct = m.readValue(http.send(HttpRequest.newBuilder(URI.create(rest + "/api/v1/accounts/" + reporterAddr)).build(),
                HttpResponse.BodyHandlers.ofString()).body(), Map.class);
        Map<String, Object> fee = m.readValue(http.send(HttpRequest.newBuilder(URI.create(rest + "/api/v1/fee/estimate")).build(),
                HttpResponse.BodyHandlers.ofString()).body(), Map.class);
        long nonce = ((Number) acct.getOrDefault("nonce", 0)).longValue() + 1;
        long baseFee = fee.values().stream().filter(v -> v instanceof Number).mapToLong(v -> ((Number) v).longValue()).max().orElse(1);
        Transaction tx = transaction(evidence(cheater, 0, 999_999L), reporter, nonce, Math.max(1, baseFee));
        byte[] json = m.writeValueAsBytes(tx);
        CoapResponse r = new CoapClient(args[3]).post(json, MediaTypeRegistry.APPLICATION_JSON);
        System.out.println("EVIDENCE_SUBMITTED code=" + (r == null ? "none" : r.getCode()) + " body=" + (r == null ? "" : r.getResponseText())
                + " cheater=" + Crypto.deriveAddress(Crypto.derivePublicKey(cheater)));
    }
}
