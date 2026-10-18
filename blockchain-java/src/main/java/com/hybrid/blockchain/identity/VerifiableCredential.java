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
        this.type = new ArrayList<>(Collections.singletonList("VerifiableCredential"));
    }

    public VerifiableCredential(String issuerDID, String subjectDID, Map<String, Object> claims) {
        this();
        this.id = "urn:uuid:" + UUID.randomUUID().toString();
        this.issuer = issuerDID;
        this.issuanceDate = java.time.Instant.now().toString();   // [HC-050] ISO-8601 UTC
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
        byte[] message = Crypto.hash(serializeForSigning());
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

        byte[] message = Crypto.hash(serializeForSigning());
        byte[] signature = Crypto.hexToBytes(proof.signatureValue);

        return Crypto.verify(message, signature, issuerPublicKey);
    }

    /**
     * Check if credential has expired
     */
    public boolean isExpired() {
        // [HC-050] This always returned false, so expired credentials stayed valid forever
        // (SSIManager.hasCredential trusts it). An unparseable expiry fails CLOSED.
        if (expirationDate == null) {
            return false;
        }
        try {
            return !java.time.Instant.now().isBefore(java.time.Instant.parse(expirationDate));
        } catch (java.time.format.DateTimeParseException e) {
            return true;
        }
    }

    /**
     * Set expiration date (milliseconds from now)
     */
    public void setExpiration(long durationMs) {
        // [HC-050] ISO-8601 UTC instead of Date.toString(), whose text depends on the JVM's
        // timezone/locale and cannot be parsed back reliably
        this.expirationDate = java.time.Instant.now().plusMillis(durationMs).toString();
    }

    /**
     * Serialize credential for signing (canonical form)
     */
    private byte[] serializeForSigning() {
        // [HC-050] growable buffer (a fixed 4 KB threw BufferOverflowException for large claims)
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

        // Serialize in deterministic order
        putString(buf, id);
        putString(buf, issuer);
        putString(buf, issuanceDate);
        // [HC-050] explicit presence flag: "no expiry" and "expiry" can no longer share an encoding
        buf.write(expirationDate != null ? 1 : 0);
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

        return buf.toByteArray();
    }

    private static void putString(java.io.ByteArrayOutputStream buf, String s) {
        byte[] bytes = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        buf.write(bytes.length >>> 24);
        buf.write(bytes.length >>> 16);
        buf.write(bytes.length >>> 8);
        buf.write(bytes.length);
        buf.write(bytes, 0, bytes.length);
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
