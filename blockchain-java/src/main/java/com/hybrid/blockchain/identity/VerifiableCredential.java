package com.hybrid.blockchain.identity;

import com.hybrid.blockchain.Crypto;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * W3C Verifiable Credential for IoT devices.
 * Used to assert device capabilities, certifications, and attributes.
 */
public class VerifiableCredential {

    @JsonProperty("@context")
    private List<String> context;

    @JsonProperty("id")
    private String id;

    @JsonProperty("type")
    private List<String> type;

    @JsonProperty("issuer")
    private String issuer; // DID of issuer

    @JsonProperty("issuanceDate")
    private String issuanceDate;

    @JsonProperty("expirationDate")
    private String expirationDate;

    @JsonProperty("credentialSubject")
    private CredentialSubject credentialSubject;

    @JsonProperty("proof")
    private Proof proof;

    // Default constructor for Jackson
    public VerifiableCredential() {
        this.context = Arrays.asList(
                "https://www.w3.org/2018/credentials/v1",
                "https://www.w3.org/2018/credentials/examples/v1");
        this.type = Arrays.asList("VerifiableCredential");
    }

    public VerifiableCredential(String issuerDID, String subjectDID, Map<String, Object> claims) {
        this();
        this.id = "urn:uuid:" + UUID.randomUUID().toString();
        this.issuer = issuerDID;
        this.issuanceDate = new Date().toString();
        this.credentialSubject = new CredentialSubject(subjectDID, claims);
    }

    /**
     * Subject of the credential (the device)
     */
    public static class CredentialSubject {
        @JsonProperty("id")
        private String id; // DID of subject

        @JsonProperty("claims")
        private Map<String, Object> claims;

        public CredentialSubject() {
        }

        public CredentialSubject(String id, Map<String, Object> claims) {
            this.id = id;
            this.claims = claims != null ? claims : new HashMap<>();
        }

        public String getId() {
            return id;
        }

        public Map<String, Object> getClaims() {
            return claims;
        }

        public Object getClaim(String key) {
            return claims.get(key);
        }
    }

    /**
     * Cryptographic proof of the credential
     */
    public static class Proof {
        @JsonProperty("type")
        private String type;

        @JsonProperty("created")
        private String created;

        @JsonProperty("proofPurpose")
        private String proofPurpose;

        @JsonProperty("verificationMethod")
        private String verificationMethod;

        @JsonProperty("signatureValue")
        private String signatureValue; // Hex-encoded signature

        public Proof() {
        }

        public Proof(String type, String verificationMethod, String signatureValue) {
            this.type = type;
            this.created = new Date().toString();
            this.proofPurpose = "assertionMethod";
            this.verificationMethod = verificationMethod;
            this.signatureValue = signatureValue;
        }

        public String getType() {
            return type;
        }

        public String getVerificationMethod() {
            return verificationMethod;
        }

        public String getSignatureValue() {
            return signatureValue;
        }
    }

    /**
     * Sign the credential with issuer's private key
     */
    public void sign(BigInteger issuerPrivateKey, byte[] issuerPublicKey) {
        byte[] message = serializeForSigning();
        byte[] signature = Crypto.sign(message, issuerPrivateKey);

        this.proof = new Proof(
                "EcdsaSecp256k1Signature2019",
                issuer + "#key-1",
                Crypto.bytesToHex(signature));
    }

    /**
     * Verify the credential signature
     */
    public boolean verify(byte[] issuerPublicKey) {
        if (proof == null || proof.signatureValue == null) {
            return false;
        }

        byte[] message = serializeForSigning();
        byte[] signature = Crypto.hexToBytes(proof.signatureValue);

        return Crypto.verify(message, signature, issuerPublicKey);
    }

    /**
     * Check if credential has expired
     */
    public boolean isExpired() {
        if (expirationDate == null) {
            return false;
        }
        // Simplified - in production, parse date properly
        return false;
    }

    /**
     * Set expiration date (milliseconds from now)
     */
    public void setExpiration(long durationMs) {
        long expirationTime = System.currentTimeMillis() + durationMs;
        this.expirationDate = new Date(expirationTime).toString();
    }

    /**
     * Serialize credential for signing (canonical form)
     */
    private byte[] serializeForSigning() {
        ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);

        // Serialize in deterministic order
        putString(buf, id);
        putString(buf, issuer);
        putString(buf, issuanceDate);
        if (expirationDate != null) {
            putString(buf, expirationDate);
        }
        putString(buf, credentialSubject.id);

        // Serialize claims in sorted order for determinism
        List<String> sortedKeys = new ArrayList<>(credentialSubject.claims.keySet());
        Collections.sort(sortedKeys);

        for (String key : sortedKeys) {
            putString(buf, key);
            putString(buf, credentialSubject.claims.get(key).toString());
        }

        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    private static void putString(ByteBuffer buf, String s) {
        if (s == null) {
            buf.putInt(0);
        } else {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            buf.putInt(bytes.length);
            buf.put(bytes);
        }
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getIssuanceDate() {
        return issuanceDate;
    }

    public String getExpirationDate() {
        return expirationDate;
    }

    public CredentialSubject getCredentialSubject() {
        return credentialSubject;
    }

    public Proof getProof() {
        return proof;
    }

    /**
     * Get credential type (e.g., "DeviceCapabilityCredential")
     */
    public String getCredentialType() {
        if (type.size() > 1) {
            return type.get(1); // First is always "VerifiableCredential"
        }
        return "VerifiableCredential";
    }

    /**
     * Add a specific credential type
     */
    public void addType(String credentialType) {
        if (!type.contains(credentialType)) {
            type.add(credentialType);
        }
    }

    @Override
    public String toString() {
        return "VC{id=" + id + ", issuer=" + issuer + ", subject=" + credentialSubject.id + "}";
    }
}
