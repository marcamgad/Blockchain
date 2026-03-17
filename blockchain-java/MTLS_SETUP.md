# mTLS Setup Guide for HybridChain

## Overview

HybridChain uses mutual TLS (mTLS) for secure peer-to-peer communication. This document describes how the mTLS infrastructure works and how to bootstrap a new deployment.

## Architecture

The mTLS system uses a **shared Certificate Authority (CA)** whose keys are derived deterministically from the `STORAGE_AES_KEY`. This ensures that:

1. **All nodes derive the same CA** from the same seed, even on different machines
2. **All nodes trust a single root CA**, enabling seamless peer communication
3. **Each node gets a unique certificate** signed by the shared CA
4. **No certificate distribution is needed** — certs are generated on-the-fly from deterministic keys

## Components

### CertificateAuthority Class

Located in `src/main/java/com/hybrid/blockchain/security/CertificateAuthority.java`

**Responsibilities:**
- Generates a deterministic CA KeyPair from `STORAGE_AES_KEY` using SHA256-based KDF
- Issues self-signed CA certificate (root of trust)
- Issues node certificates signed by the CA for each peer

**Key Properties:**
- **EC Curve**: secp256r1 (approved, constant across runs)
- **CA Validity**: 1 year
- **Node Cert Validity**: 1 year
- **Signature Algorithm**: SHA256WithECDSA

### SSLUtils Class

Located in `src/main/java/com/hybrid/blockchain/security/SSLUtils.java`

**New Method: `createSSLContextWithCA()`**
- Takes a `CertificateAuthority`, node `KeyPair`, and `nodeId`
- Issues/retrieves node certificate from CA
- Creates Java KeyStore with node's private key + certificate chain (node cert + CA cert)
- Creates Java TrustStore with only the CA certificate (trust root)
- Returns configured `SSLContext` for mTLS connections

## Bootstrap Process

### Step 1: Ensure STORAGE_AES_KEY is Set

```bash
export STORAGE_AES_KEY=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff
export NODE_PRIVATE_KEY=a24327eaed4fe735576f1ec2a4c433094d2e88a515ddaf22e2c98a592f0b81d8
export VALIDATOR_PUBKEYS=0398e0f5fccf41f104eb724ba1f59c6f68043dad84786ddadc38614d635f25282b
```

**Important**: All nodes in the network **must use the same `STORAGE_AES_KEY`** for the CA to be identical. This is the only secret that must be shared at bootstrap.

### Step 2: Node Startup

When a node starts (see `App.java`):

1. Initialize `CertificateAuthority` with `Config.STORAGE_AES_KEY`:
   ```java
   CertificateAuthority ca = new CertificateAuthority(Config.STORAGE_AES_KEY);
   ```

2. Generate a temporary EC KeyPair for the node's TLS identity:
   ```java
   KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC", "BC");
   keyGen.initialize(new ECGenParameterSpec("secp256r1"));
   KeyPair nodeKeyPair = keyGen.generateKeyPair();
   ```

3. Issue a certificate for this node from the CA:
   ```java
   X509Certificate nodeCert = ca.issueNodeCertificate(nodeId, nodeKeyPair.getPublic(), nodeKeyPair.getPrivate());
   ```

4. Create SSLContext with the node certificate:
   ```java
   SSLContext sslContext = SSLUtils.createSSLContextWithCA(ca, nodeKeyPair, nodeId);
   ```

5. Pass to `PeerNode`:
   ```java
   PeerNode peerNode = new PeerNode(P2P_PORT, blockchain, pbft, privKey, nodeKeyPair, ca);
   ```

### Step 3: Peer Connection

When connecting to a peer:

1. Each peer presents its node certificate signed by the shared CA
2. Each peer's SSLContext has the CA certificate as the trust root
3. Java's SSL/TLS layer validates the peer's certificate is signed by the CA
4. If valid, the connection is established; if not, the connection is rejected

## Troubleshooting

### Issue: "PKIX path validation failed"

**Cause**: Nodes are using different `STORAGE_AES_KEY` values, resulting in different CAs.

**Solution**: Ensure all nodes in the network set the same `STORAGE_AES_KEY` before startup.

```bash
# All nodes must use this same key
export STORAGE_AES_KEY=<shared-key>
```

### Issue: "certificate CN does not match hostname"

**Cause**: Hostname mismatch (typically ignored in mTLS with Subject Alternative Names, but may occur in strict validation).

**Solution**: This is typically not an issue in HybridChain since we validate the CA chain, not the CN. If you need strict hostname validation, add Subject Alternative Names to the node certificate in `CertificateAuthority.issueNodeCertificate()`.

### Issue: "Could not find trusted certificate"

**Cause**: A node's certificate is not being accepted by peers.

**Solution**: 
1. Verify the node certificate was issued by the same CA as the peer's trust roots
2. Check that `CertificateAuthority` is initialized with the correct `STORAGE_AES_KEY`
3. Verify the certificate chain includes both the node cert and CA cert in the KeyStore

## Production Deployment

### Single Deployment

For a single deployment (all nodes in one organization):

1. **Generate `STORAGE_AES_KEY`** on a secure, air-gapped machine:
   ```bash
   openssl rand -hex 32 > storage_aes_key.txt
   ```

2. **Securely distribute** the key to all nodes (via secure config management, HSM, or sealed secret)

3. **Set environment variable** on all nodes:
   ```bash
   export STORAGE_AES_KEY=$(cat storage_aes_key.txt)
   ```

4. **Start all nodes** — each will generate its own node certificate from the shared CA

### Multi-Organization Deployment (Future)

For multi-organization deployments, additional changes would be needed:

1. Create separate CAs per organization (e.g., `ORG1_STORAGE_AES_KEY`, `ORG2_STORAGE_AES_KEY`)
2. Cross-sign CA certificates or create a parent CA
3. Bootstrap validators with peer CA certificates to establish inter-organization trust

*(This is a known limitation; see `PRODUCTION_CHECKLIST.md` for details.)*

## Security Considerations

1. **STORAGE_AES_KEY is sensitive**: This key must be protected at rest and in transit, equivalent to a CA private key
2. **No key escrow**: Unlike traditional PKI, there's no way to recover a lost STORAGE_AES_KEY; the entire network must re-bootstrap with a new key
3. **Certificate rotation**: Currently not implemented; nodes must restart to issue new certificates (should be added in future)
4. **Peer authentication**: Certificates prove node identity only at the network level; application-level authentication (signing transactions) is separate

## Implementation Details

### Deterministic Key Derivation

The CA key is derived using SHA256:

```
CA_Private_Key = SHA256(STORAGE_AES_KEY) mod (curve_order)
```

This ensures:
- Same key is derived on every node every time
- No need to store or transmit the CA private key
- Key derivation is cryptographically sound (using a KDF)

### Instance Checking in PBFTConsensus

When receiving consensus messages, PeerNode uses `instanceof PBFTConsensus` to check if PBFT is active. If running PoA consensus instead, the network will use the legacy self-signed certificates. This is intentional to support multiple consensus types.

## References

- [Java SSLContext Documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/net/ssl/SSLContext.html)
- [BouncyCastle Certificate Generation](https://www.bouncycastle.org/)
- [TLS 1.3 RFC](https://tools.ietf.org/html/rfc8446)
