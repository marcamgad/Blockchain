# Git history secret scan (O-8)

**Date:** 2026-10-21. **Scope:** every commit on every branch, excluding `node_modules` and `package-lock.json`.

## Method and its limits

gitleaks and trufflehog are not installed on the machine that ran this scan, and downloading them needed the owner's approval. A scripted scan was used instead (pattern rules: PEM private keys; `*PRIVATE_KEY*`, `*SECRET*`, `*SEED*`, `*AES_KEY*`, `*PASSWORD*` and `*TOKEN*` assigned 32+ hex characters; JWT and API secrets; AWS and GitHub tokens). It reports locations and value fingerprints only, never values.

**This is weaker than gitleaks** (fewer rules, no entropy analysis). Run gitleaks yourself before trusting the history (commands below).

## Findings: 74 distinct candidate values in 18 files

### No longer in the current tree (history only)

| File | Values | First seen in |
|---|---|---|
| `blockchain-java/validator_keys.txt` | 20 node private keys | `dad00f8` |
| `blockchain-java/.env.20nodes` | 20 node private keys + storage AES key | `0ca92e9` |
| `blockchain-java/.env.example` | 4 node private keys + storage AES key | `79aef48`, `aeb233c` |
| `blockchain-java/docker-compose.yml` | 3 node private keys | `aeb233c` |
| `blockchain-java/MULTI_PC_DEPLOYMENT.md` | 3 node private keys | `aeb233c` |
| `blockchain-java/test_results.txt` | 1 node private key + storage AES key | `eb4fcbf` |
| `.env.example` (root) | storage AES key (blanked in HC-075) | `44c7142` |

### Still in the current tree

These are handled under O-3 (`docs/KEY_ROTATION.md`):

| File | Values | Nature |
|---|---|---|
| `DOCKER_DEPLOYMENT.md`, `MTLS_SETUP.md` | 2 each | Example values in documentation |
| `vm-setup/docker-compose.yml`, `vm-setup/peer-node/…`, `vm-setup/seed-node/…` | 3, 1, 1 | Node private keys in compose files |
| `pom.xml`, `audit/tools/add_audit_profiles.py`, `ProductionSignatureRuleTest.java` | 4, 3, 1 | Test-profile keys passed as `-D` properties |
| `scripts/generate_keys.sh`, `scripts/generate-env-files.sh` | 1 each | Fixed key inside a key-generation script |

## What to do

1. **Treat every key above as compromised.** Never use them on a network that holds value. See `docs/KEY_ROTATION.md`.
2. **Run a full scanner yourself** (needs the tool installed):
   ```bash
   gitleaks detect --source D:/Blockchain --log-opts="--all" --redact --report-path gitleaks-report.json
   ```
3. **Optionally scrub history.** This rewrites every commit hash, needs a force-push and forces every clone to re-clone, so it is a human decision and was not done here. With `git filter-repo` installed:
   ```bash
   git filter-repo --invert-paths --path blockchain-java/validator_keys.txt --path blockchain-java/.env.20nodes --path blockchain-java/test_results.txt
   ```
   A scrub does **not** make the keys safe again. Anyone who cloned or forked earlier still has them, so rotation is required either way.

## Files untracked in this change (kept on disk)

`node_modules/` (3,125 files), `blockchain-java/data/`, `blockchain-java/test-data-1/`, `blockchain-java/test-data-2/`, `data/chain-db.bak/` (LevelDB stores), and `blockchain-java/Patcher.java`, `blockchain-java/patch.py`, `blockchain-java/tmp-failures.txt` (scratch files). New `.gitignore` rules stop them from coming back.
