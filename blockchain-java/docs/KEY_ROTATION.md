# Key rotation runbook

**Why:** keys derived from seeds published in this repository (`hybridchain-20node-seed`, `hybridchain-key-seed`), keys written in old `.env` files, compose files, docs and `validator_keys.txt`, and the fixed storage AES keys in old scripts are all **public**. Anyone can recompute or read them. See `docs/security/HISTORY_SECRET_SCAN.md`.

**What the code now does:** outside `DEBUG`, a node refuses to start if `NODE_PRIVATE_KEY`, `STORAGE_AES_KEY` or `NETWORK_CA_SEED` is a known-public value (`security/CompromisedKeys`: published-seed derivations, the old DEBUG fallbacks, and fingerprints of every value found in git history). The check can only catch keys it knows about; a key you leaked somewhere else is not detected.

## The only supported key sources

| Source | Use |
|---|---|
| `scripts/generate_keys.sh [OUT_ENV]` | All keys for a network: 20 node keys, `VALIDATOR_PUBKEYS`, a random `STORAGE_AES_KEY` and a random `NETWORK_CA_SEED`. Uses `SecureRandom` and the OS random generator. Writes a mode-600 file and refuses to overwrite. |
| `KeygenTool <n>` (no seed) | Single keys, random. |
| `--test-seed` / `KeygenTool <n> <seed>` | **Test only.** Reproducible keys that anyone who knows the seed can recompute. |

## The consensus signing record (HC-095)

Each validator keeps `consensus-signing-record.json` in its `STORAGE_PATH`: the votes it has signed
(phase, view, height → block hash) and its current view. The node writes it to disk **before**
signing a vote and loads it at start-up, so a restarted validator can't sign a vote that conflicts
with one it signed earlier. Such a vote would be valid equivocation evidence and get it jailed.

- **Never delete it** on a live validator, and never restore an older copy. Either can let the node sign a conflicting vote.
- **Never run two instances** with the same key, or with copies of the same record.
- When **moving** a validator to a new machine: stop it, copy the data directory including the record, then start it on the new machine. Never run both at once.
- A **corrupt** record stops the node at start-up on purpose. Restore the record from the stopped node. Delete it only if you're sure this validator hasn't signed anything in the network's current view (for example, the network has moved on by several views since it stopped).
- On **key rotation** (below), the new key starts with no record. That's safe, because the old key's votes can't conflict with the new key's.

## Rotation procedure (a permissioned network without a key-change transaction)

HybridChain has no on-chain validator key rotation yet, so a validator key change means a coordinated change of the validator set.

1. **Stop accepting value.** Announce a maintenance window. Treat any balance controlled by an old key as at risk.
2. **Generate new material** on a trusted machine:
   ```bash
   cd blockchain-java && scripts/generate_keys.sh ../secrets/network-2026-10.env
   ```
   Keep the file offline. Give each operator only their own `NODE_*_PRIVATE_KEY`.
3. **Distribute the public part.** `VALIDATOR_PUBKEYS` and `NETWORK_CA_SEED` are the same on every node. `NETWORK_CA_SEED` is secret: whoever holds it can mint node certificates.
4. **Choose a storage key per node** (`STORAGE_AES_KEY`). Data encrypted under an old key cannot be read with the new one. Either start from an empty `STORAGE_PATH` and resync from peers, or keep the old key only for a one-off export.
5. **Move funds.** For every account controlled by a compromised key, send its balance to a new address while the old key still works. Prefer hybrid `hq` addresses (HC-079). Validator reward accounts are `hb` addresses of the validator key, so sweep them too.
6. **Restart all validators together** with the new `VALIDATOR_PUBKEYS` and their new keys. Old and new validator sets cannot coexist, because quorum is computed from the configured set.
7. **Verify:**
   - each node logs its new node ID, and no node logs "publicly known";
   - `GET /api/v1/consensus` shows the new validators;
   - `GET /api/v1/chain/status` advances on every node with the same state root.
8. **Destroy old material:** old `.env` files, compose overrides and backups that contain old keys.

## History

Removing old keys from git history does **not** make them safe: clones and forks keep them. If you also want them out of history, see the human-only commands in `docs/security/HISTORY_SECRET_SCAN.md`.

## Not covered

- **HSM / remote signing.** Keys still sit in environment variables. Design: `docs/design/KEY_PROVIDER.md`.
- **Per-node CA certificates without a shared seed.** Design: `docs/design/CA_TRUST_MODEL.md`.
