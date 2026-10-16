# Ledger module — working notes (batch 2)

Running notes while reading; every candidate here must end as a finding (with PoC) or a
recorded correction. Line numbers refer to the working tree at batch 2 start (commit d3479f4).

## Candidates

- C-L1 `Blockchain.init:191` genesis = `new Block(0, System.currentTimeMillis(), ...)`.
  Block hash commits to timestamp => independently started nodes get different genesis
  hashes; `applyBlockInternal`/`validateBlock` require `prevHash == local tip hash` =>
  block 1 from any other node is rejected. Multi-node tests may pass only when both chains
  are created in the same millisecond. NEEDS PoC (sleep between inits).
- C-L2 `Blockchain.init:163-183` fast-sync: on state-root mismatch, `state`/`utxo` already
  replaced and `chain.add(cpBlock)` already done, then falls through to genesis creation =>
  chain = [cpBlock, genesis(index 0)] with unverified state. Atomicity bug. NEEDS PoC.
- C-L3 `Blockchain.init:80-105` snapshot path never restores `totalMinted` (only tip-hash
  path at :119). Check Tokenomics.getTotalMinted source: if it uses blockchain.totalMinted,
  a node restarted via the snapshot path computes a different MINT schedule => consensus
  split. Also check whether `lastSnapshotHeight` is ever written.
- C-L4 `Blockchain.init:131-133` checkpoint quorum `2f+1` (same root cause as HC-002;
  report as interaction, not new finding unless distinct).
