# Design: KeyProvider abstraction (HSM, PKCS#11, remote signer)

**Status:** design only, not implemented. **Date:** 2026-10-21.

## Problem

- **The node key is plaintext.** It comes from the `NODE_PRIVATE_KEY` environment variable and lives as a `BigInteger` in memory for the whole process lifetime.
- **It is passed around everywhere.** `App`, `PeerNode` (4 signing sites), `PBFTConsensus` and `P2PMessage` receive it directly, so every class that signs holds the raw key.
- **The HSM is fake.** `security/HardwareSecurityModule` is a simulation.
- **Nothing stops a validator signing twice.** If an operator restarts a node from an old backup, it can sign a second, conflicting vote. Since O-5 that gets the validator jailed on-chain.

## Options

| Option | How it works | For | Against |
|---|---|---|---|
| **A. PKCS#11 directly** | A `Pkcs11KeyProvider` uses the JDK's SunPKCS11 provider against an HSM (YubiHSM 2, Thales Luna, AWS CloudHSM) or SoftHSM2 for tests. | Key never leaves the device. Standard interface. | secp256k1 support varies by device. HSMs return DER, possibly high-S signatures that must be normalised to low-S. ML-DSA support in HSMs is still limited. No protection against double signing. |
| **B. Remote signer** | A separate signer process (like Tendermint KMS or Web3Signer) holds the key, behind its own backend (file, PKCS#11 or cloud KMS). The node sends a typed request over mTLS: vote, block, transaction or handshake. | The signer can refuse to sign two different blocks at the same (phase, view, height), using a persisted high-water mark. One signer can serve HSM and cloud backends. | One more component to run. Adds latency to every vote. |
| **C. Cloud KMS** | AWS KMS and Google Cloud KMS support secp256k1 signing. | Managed, audited key custody. | Vendor lock-in, network dependency for every vote, and ML-DSA availability varies. |

## Recommendation

1. **Introduce `KeyProvider` first**, with no behaviour change:
   - `byte[] publicKey()`;
   - `byte[] signDigest(byte[] digest32, Purpose purpose, SignContext ctx)`;
   - the purposes: `VOTE`, `BLOCK`, `TRANSACTION`, `HANDSHAKE`, `CHECKPOINT`, `P2P_MESSAGE`.

   `EnvKeyProvider` wraps today's behaviour. Replace every `BigInteger privateKey` parameter in `PeerNode`, `PBFTConsensus`, `App` and `P2PMessage` with the provider.
2. **Production path: a remote signer (B)** with double-sign protection. It persists the highest (phase, view, height) it signed for and refuses any conflicting request. It uses PKCS#11 (A) as its key backend.
3. **Always normalise signatures**, from every provider, to low-S and the current raw format before use. Add a test that runs high-S HSM output through the normaliser.
4. **ML-DSA** stays in software (or in the remote signer's software backend) until the HSM vendor supports FIPS 204.

## Migration cost

About 2 weeks for the abstraction and `EnvKeyProvider`. About 2–3 more weeks for the remote signer with double-sign protection and a SoftHSM2-backed integration test.

No consensus change: signatures and encodings stay byte-identical. Operators gain a new deployment mode, and the environment-variable mode stays available for test networks.
