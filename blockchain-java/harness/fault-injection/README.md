# Fault-injection harness (O-2)

Runs 4 or 7 validators as Docker containers on **one machine**, injects faults, and checks three properties through the REST API after every scenario.

**Status: written but NOT EXECUTED.** It could not be run when it was written: the Docker daemon was not running on the authoring machine, and pulling the base images needs network access. Only the parts that run without Docker are tested: the checker's `--self-test` (8 checks) and `HarnessHelpersTest` (evidence building and the `/consensus` jail field). Nothing here is evidence of a multi-machine test. Even a successful run is a **single-host simulated network**, and its results must be labelled that way (the runner writes that label into every result file).

## What it checks

| Property | How |
|---|---|
| **Safety** | For every height held by two or more reachable nodes: identical block hash **and** state root. |
| **Liveness** | After a fault heals, the minimum height across nodes grows by the expected number of blocks within a timeout. |
| **Jail agreement** | Every node reports the same `jailedValidators` set from `GET /api/v1/consensus`. |

## Scenarios

| Scenario | Fault | Expected |
|---|---|---|
| `baseline` | none | progress |
| `leader-crash` | `docker kill` of the current leader, later restarted | survivors progress (NEW_VIEW), the restarted node rejoins |
| `partition` | 2/2 split for 4 validators, 3/4 for 7 (pumba netem, 100% loss between halves) | no conflicting blocks during the split; progress after healing |
| `delay-loss` | 300 ms ± 100 ms delay and 10% loss on every node | safety; slower progress |
| `restart-resync` | one validator stopped for 3 blocks, then restarted | it catches up with identical blocks |
| `equivocation` | conflicting PREPAREs signed offline with one validator's key and submitted as evidence over CoAP | every node jails that validator |
| `clock-skew` | one validator runs 30 s ahead (libfaketime) | safety; progress (its proposals exceed the 15 s drift limit) |

The equivocation scenario does **not** reproduce live double-voting on the wire. It checks the evidence path and jail agreement.

## Prerequisites

Docker Desktop (Linux containers) running; the images `eclipse-temurin:17-jre-jammy`, `gaiaadm/pumba` and its default helper image (`ghcr.io/alexei-led/pumba-alpine-nettools`) (pulled on first run); Python 3; Maven with a populated local repository; Git Bash on Windows.

## Run

```bash
cd blockchain-java
harness/fault-injection/run.sh 4
harness/fault-injection/run.sh 7 leader-crash partition
```

`gen.sh` creates throw-away random keys in `harness/fault-injection/.env.N` (mode 600; ignored by git). Blocks come every 60 s (`Config.TARGET_BLOCK_TIME_MS`, a protocol constant), so a full run takes about an hour. Stop with the `docker compose … down -v` line the runner prints.
