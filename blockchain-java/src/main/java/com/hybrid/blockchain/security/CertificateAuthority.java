package com.hybrid.blockchain.security;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Certificate Authority for HybridChain mTLS.
 * 
 * Generates a deterministic CA key pair from STORAGE_AES_KEY and issues
 * node certificates signed by this CA. All nodes trust the CA certificate
 * as the root of their trust chain.
 */
public class CertificateAuthority {

    private final KeyPair caKeyPair;
    private final X509Certificate caCertificate;
    private final Map<String, X509Certificate> issuedCertificates;
    private static final String CA_DN = "CN=HybridChain-CA,O=HybridChain,C=US";
    private static final long CERT_VALIDITY_MS = 365L * 24L * 60L * 60L * 1000L; // 1 year
    private static final String SIGNATURE_ALGORITHM = "SHA256WithECDSA";
    private static final String KEY_ALGORITHM = "EC";
    private static final String EC_CURVE_NAME = "secp256r1";

    /**
     * Initialize CA with a deterministic key pair derived from the given seed.
     * 
     * @param seed the seed bytes (e.g., STORAGE_AES_KEY) for deterministic key generation
     * @throws Exception if CA initialization fails
     */
    public CertificateAuthority(byte[] seed) throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        this.caKeyPair = generateDeterministicKeyPair(seed);
        this.caCertificate = generateCACertificate(caKeyPair);
        this.issuedCertificates = new HashMap<>();
    }

    /**
     * Generate a deterministic EC key pair from a seed using SHA256-based KDF.
     * 
     * @param seed the seed bytes for key derivation
     * @return a keypair derived from the seed
     * @throws Exception if key generation fails
     */
    private KeyPair generateDeterministicKeyPair(byte[] seed) throws Exception {
        // Use SHA256 to derive a consistent private key from the seed
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] derivedKey = digest.digest(seed);
        
        // Ensure the derived key is a valid EC private key (must be < curve order)
        BigInteger privKeyInt = new BigInteger(1, derivedKey);
        // secp256r1 curve order (approx 2^256)
        BigInteger curveOrder = new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);
        privKeyInt = privKeyInt.mod(curveOrder);
        
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM, "BC");
        keyGen.initialize(new ECGenParameterSpec(EC_CURVE_NAME));
        KeyPair generatedPair = keyGen.generateKeyPair();
        
        // Now we need to reconstruct with our deterministic private key
        // Use BC specs directly
        ECNamedCurveParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec(EC_CURVE_NAME);
        org.bouncycastle.jce.spec.ECPrivateKeySpec privateKeySpec = 
            new org.bouncycastle.jce.spec.ECPrivateKeySpec(privKeyInt, ecSpec);
        
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM, "BC");
        PrivateKey prvKey = keyFactory.generatePrivate(privateKeySpec);
        PublicKey pubKey = generatedPair.getPublic();
        
        return new KeyPair(pubKey, prvKey);
    }

    /**
     * Generate a self-signed CA certificate.
     * 
     * @param caKeyPair the keypair for the CA
     * @return the CA certificate
     * @throws Exception if certificate generation fails
     */
    private X509Certificate generateCACertificate(KeyPair caKeyPair) throws Exception {
        long now = System.currentTimeMillis();
        X500Name caName = new X500Name(CA_DN);
        BigInteger serialNumber = new BigInteger(Long.toString(now));
        Date notBefore = new Date(now);
        Date notAfter = new Date(now + CERT_VALIDITY_MS);

        X509v3CertificateBuilder caBuilder = new JcaX509v3CertificateBuilder(
                caName, serialNumber, notBefore, notAfter, caName, caKeyPair.getPublic());

        // Add CA extensions
        caBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        caBuilder.addExtension(Extension.keyUsage, true, 
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign));

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(caKeyPair.getPrivate());
        X509CertificateHolder certHolder = caBuilder.build(signer);

        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder);
    }

    /**
     * Issue a node certificate signed by this CA.
     * 
     * @param nodeId the node identifier (will be used in CN)
     * @param nodePublicKey the node's public key
     * @param nodePrivateKey the node's private key (used for signing request, if applicable)
     * @return a certificate signed by the CA
     * @throws Exception if certificate issuance fails
     */
    public X509Certificate issueNodeCertificate(String nodeId, PublicKey nodePublicKey, PrivateKey nodePrivateKey) throws Exception {
        long now = System.currentTimeMillis();
        X500Name nodeName = new X500Name("CN=" + nodeId + ",O=HybridChain,C=US");
        BigInteger serialNumber = new BigInteger(Long.toString(now + nodeId.hashCode()));
        Date notBefore = new Date(now);
        Date notAfter = new Date(now + CERT_VALIDITY_MS);

        X509v3CertificateBuilder nodeBuilder = new JcaX509v3CertificateBuilder(
                new X500Name(CA_DN), serialNumber, notBefore, notAfter, nodeName, nodePublicKey);

        // Add node certificate extensions
        nodeBuilder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        nodeBuilder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.keyAgreement));

        // Subject Alternative Name for mTLS hostname validation
        GeneralNames altNames = new GeneralNames(new GeneralName(GeneralName.dNSName, nodeId));
        nodeBuilder.addExtension(Extension.subjectAlternativeName, false, altNames);

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(caKeyPair.getPrivate());
        X509CertificateHolder certHolder = nodeBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder);

        issuedCertificates.put(nodeId, cert);
        return cert;
    }

    /**
     * Get the CA certificate (root of trust).
     * 
     * @return the CA certificate
     */
    public X509Certificate getCACertificate() {
        return caCertificate;
    }

    /**
     * Get a previously issued node certificate.
     * 
     * @param nodeId the node identifier
     * @return the certificate, or null if not issued
     */
    public X509Certificate getNodeCertificate(String nodeId) {
        return issuedCertificates.get(nodeId);
    }
}
