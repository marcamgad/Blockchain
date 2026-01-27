package com.hybrid.blockchain.identity;

import com.hybrid.blockchain.Crypto;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

/**
 * W3C DID (Decentralized Identifier) implementation for IoT devices.
 * Format: did:iot:<deviceId>
 * 
 * Compliant with W3C DID Core specification.
 */
public class DecentralizedIdentifier {

    @JsonProperty("@context")
    private List<String> context;

    @JsonProperty("id")
    private String did;

    @JsonProperty("controller")
    private String controller; // Owner address

    @JsonProperty("verificationMethod")
    private List<VerificationMethod> verificationMethods;

    @JsonProperty("authentication")
    private List<String> authentication;

    @JsonProperty("service")
    private List<ServiceEndpoint> services;

    @JsonProperty("created")
    private long created;

    @JsonProperty("updated")
    private long updated;

    // Default constructor for Jackson
    public DecentralizedIdentifier() {
        this.context = Arrays.asList("https://www.w3.org/ns/did/v1");
        this.verificationMethods = new ArrayList<>();
        this.authentication = new ArrayList<>();
        this.services = new ArrayList<>();
    }

    public DecentralizedIdentifier(String deviceId, byte[] publicKey, String owner) {
        this();
        this.did = "did:iot:" + deviceId;
        this.controller = owner;
        this.created = System.currentTimeMillis();
        this.updated = this.created;

        // Add primary verification method
        VerificationMethod primaryKey = new VerificationMethod(
                did + "#key-1",
                "EcdsaSecp256k1VerificationKey2019",
                controller,
                Crypto.bytesToHex(publicKey));
        this.verificationMethods.add(primaryKey);
        this.authentication.add(primaryKey.getId());
    }

    /**
     * Verification method for cryptographic proof
     */
    public static class VerificationMethod {
        @JsonProperty("id")
        private String id;

        @JsonProperty("type")
        private String type;

        @JsonProperty("controller")
        private String controller;

        @JsonProperty("publicKeyHex")
        private String publicKeyHex;

        public VerificationMethod() {
        }

        public VerificationMethod(String id, String type, String controller, String publicKeyHex) {
            this.id = id;
            this.type = type;
            this.controller = controller;
            this.publicKeyHex = publicKeyHex;
        }

        public String getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public String getController() {
            return controller;
        }

        public String getPublicKeyHex() {
            return publicKeyHex;
        }
    }

    /**
     * Service endpoint for device interaction
     */
    public static class ServiceEndpoint {
        @JsonProperty("id")
        private String id;

        @JsonProperty("type")
        private String type;

        @JsonProperty("serviceEndpoint")
        private String serviceEndpoint;

        public ServiceEndpoint() {
        }

        public ServiceEndpoint(String id, String type, String endpoint) {
            this.id = id;
            this.type = type;
            this.serviceEndpoint = endpoint;
        }

        public String getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public String getServiceEndpoint() {
            return serviceEndpoint;
        }
    }

    // Getters
    public String getDid() {
        return did;
    }

    public String getController() {
        return controller;
    }

    public List<VerificationMethod> getVerificationMethods() {
        return verificationMethods;
    }

    public List<String> getAuthentication() {
        return authentication;
    }

    public List<ServiceEndpoint> getServices() {
        return services;
    }

    public long getCreated() {
        return created;
    }

    public long getUpdated() {
        return updated;
    }

    // Setters
    public void setController(String controller) {
        this.controller = controller;
        this.updated = System.currentTimeMillis();
    }

    public void addVerificationMethod(VerificationMethod method) {
        this.verificationMethods.add(method);
        this.updated = System.currentTimeMillis();
    }

    public void addService(ServiceEndpoint service) {
        this.services.add(service);
        this.updated = System.currentTimeMillis();
    }

    /**
     * Convert to W3C DID Document format
     */
    public Map<String, Object> toDIDDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("@context", context);
        doc.put("id", did);
        doc.put("controller", controller);
        doc.put("verificationMethod", verificationMethods);
        doc.put("authentication", authentication);
        if (!services.isEmpty()) {
            doc.put("service", services);
        }
        doc.put("created", new Date(created).toString());
        doc.put("updated", new Date(updated).toString());
        return doc;
    }

    /**
     * Extract device ID from DID
     */
    public String getDeviceId() {
        if (did != null && did.startsWith("did:iot:")) {
            return did.substring(8);
        }
        return null;
    }

    /**
     * Verify that a signature was created by this DID's controller
     */
    public boolean verifySignature(byte[] message, byte[] signature) {
        if (verificationMethods.isEmpty()) {
            return false;
        }

        // Try primary verification method
        VerificationMethod primary = verificationMethods.get(0);
        byte[] publicKey = Crypto.hexToBytes(primary.getPublicKeyHex());
        return Crypto.verify(message, signature, publicKey);
    }

    @Override
    public String toString() {
        return "DID{" + did + ", controller=" + controller + "}";
    }
}
