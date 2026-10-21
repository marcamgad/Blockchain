# HC-079: binding the ML-DSA key to the account

**Status:** decided and implemented. **Date:** 2026-10-21.

## Problem

With `REQUIRE_QUANTUM_SIG=true`, every transaction carries an ECDSA signature and an ML-DSA signature. But the account address is derived from the ECDSA public key only (`"hb" + SHA-256(ecPub)[0..20]`), and the ML-DSA public key is whatever the transaction carries; it is never stored or checked against anything. Anyone who can forge the ECDSA signature (the quantum attacker ML-DSA is meant to stop) can generate a fresh ML-DSA key pair, sign with it, and spend. The ML-DSA signature therefore adds no protection.

**Requirement:** no one holding only the ECDSA key, or only the ML-DSA key, can spend from an account.

## Options

### (a) Address commits to both keys

`address = "hq" + SHA-256(domain "HYBRIDCHAIN-ADDR-HYBRID-v1", len‖ecPub, len‖mldsaPub)[0..20]`

- The binding exists from the moment the address exists. No window, no extra state.
- An attacker with only one key cannot produce a matching address with their own second key: that would need a SHA-256 second preimage on 160 bits.
- **Cost:** it is a new address format. Existing `hb` addresses are not covered and can never be, because they commit only to the ECDSA key. Funds must be moved to an `hq` address to get the protection.

### (b) Register the ML-DSA key in state on first use

- Keeps existing addresses.
- **Fails the requirement for accounts that have not spent yet.** Until an account's first transaction, the chain knows no ML-DSA key for it. An attacker holding only the ECDSA key (a quantum attacker who recovered it from a public key seen on-chain, for example) can make the first spend and register their own ML-DSA key. Every address that has received funds but never sent stays exposed for as long as it stays unused, and nothing forces the owner to register in time.
- Adds a per-account key to the state root, and a migration rule for accounts created before activation.

## Decision

**Option (a).** It is the only option that meets the requirement without a window, and it needs no new state. It is additive: existing `hb` accounts and their behaviour are unchanged.

What this means, stated plainly:

- **`hq` accounts are bound to both keys.** A transaction from an `hq` address must carry both public keys, the address must equal the hash of both, and both signatures must verify. This holds whatever `REQUIRE_QUANTUM_SIG` is set to.
- **`hb` accounts remain classical.** Their security is ECDSA-only. The extra ML-DSA signature that `REQUIRE_QUANTUM_SIG=true` requires on them is kept, so validity rules do not loosen, but it is documented as **not** providing post-quantum protection. Holders who want that protection must move funds to an `hq` address.
- Validator identities (node addresses, PBFT IDs, block rewards) stay `hb`. Validator reward accounts are therefore classical. Moving block rewards to a validator-chosen `hq` address is a follow-up (it changes the MINT rule from HC-016).
- Option (b) could later be added for legacy `hb` accounts as a partial measure, with the window documented above.

## Compatibility

| | Effect |
|---|---|
| Existing addresses | Unchanged (`hb`). |
| Existing state roots and stored data | Unchanged. No new state. |
| Validity | **Consensus change:** old nodes reject transactions from `hq` senders (their address check fails), and new nodes accept them. Every validator must run the new code before the first `hq` transaction. This is gated by `Config.HYBRID_ADDRESS_ACTIVATION_HEIGHT`: `hq` senders are rejected below that height. It is 0 for new networks. A running network sets it to an agreed future height. |
| Wire format | Unchanged. The existing `dilithiumPublicKey` and `dilithiumSignature` fields are used. |

## What this does not claim

It does not make HybridChain "post-quantum secure". Consensus votes, block signatures, P2P handshakes and `hb` accounts are still ECDSA-only.
