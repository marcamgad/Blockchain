# Design: replacing the shared NETWORK_CA_SEED trust model

**Status:** design only, not implemented. **Date:** 2026-10-21.

## Problem

Every node derives the network CA's **private key** from `NETWORK_CA_SEED`, which all nodes share (HC-003 separated it from the disk key, but it is still shared). The consequences:

- **One compromised node compromises admission.** Anyone who extracts the seed from any node can issue certificates for any node identity. The handshake then has to stop that impostor: it checks node-key ownership and validator-set membership (HC-011, HC-042).
- **Rotation is all-at-once.** Replacing the seed means replacing it on every node at the same time.
- **No revocation.** One node's certificate cannot be withdrawn without changing the seed for everyone.
- **A test-only escape hatch exists.** `SSLUtils` still contains a trust-all manager, enabled only by an explicit system property for legacy tests. It should be refused under the production profile.

## Options

| Option | How it works | For | Against |
|---|---|---|---|
| **A. Offline root CA plus per-node certificates** | The network operator keeps the CA key offline (or in an HSM). Each node generates its own TLS key, sends a CSR, and receives a short-lived certificate (days). Nodes pin only the CA certificate. | Standard PKI. Short lifetimes bound the damage of a stolen certificate. Per-node revocation by not renewing. | Needs an issuing process and renewal automation. The operator is a central point of trust. |
| **B. No CA: trust the validator keys** | Each node uses a self-signed TLS certificate. Trust comes from the handshake: the peer signs a challenge **bound to this TLS session** (TLS exporter value) with its node key, and the key must be in `VALIDATOR_PUBKEYS`, or later in on-chain state. | No shared secret at all. Identity is the consensus identity. Rotation becomes a validator-set change. | TLS does not authenticate the peer by itself; the handshake must use channel binding, or a relay attack is possible. Non-validator peers need their own allow-list. |
| **C. Consortium of intermediate CAs** | A consortium root signs one intermediate CA per organisation, with name constraints. Each organisation issues its own nodes' certificates. | Fits a multi-organisation permissioned network. Each organisation controls its own nodes. | More PKI to operate. Root ceremony required. |

## Recommendation

1. **B for validators.** Validator identity is already the node key list, and the handshake already proves key ownership. Add channel binding: sign `TLS-Exporter("EXPORTER-hybridchain-handshake", 32)` in the handshake, with a new domain tag. Then accept self-signed TLS certificates and remove `NETWORK_CA_SEED` from validator deployments.
2. **A or C for non-validator peers** (gateways, observers, light clients), if they must join the P2P network. C fits a consortium; A fits a single operator.
3. **Refuse the test trust-all property under the production profile** (small, independent change).

## Migration cost

About 2–3 weeks:
- channel-bound handshake;
- `SSLUtils` trust manager for self-signed certificates plus handshake authentication;
- P2P protocol version bump (old and new nodes cannot talk);
- tests:
  - a relayed handshake is rejected;
  - a non-validator key is rejected;
  - a validator outside the set is rejected.

Removing the seed affects deployments only, not chain data. Operators drop `NETWORK_CA_SEED` after every node has upgraded.
