#!/usr/bin/env python3
"""[O-2] Safety / liveness / jail-agreement checker for a HybridChain harness network.

Uses only the REST API and the Python standard library.

  check.py --nodes http://localhost:18081,http://localhost:18082,... --phase NAME \
           [--expect-progress K --timeout SECONDS] [--expect-jailed ADDRESS] [--out results.jsonl]
           [--expect-skew NODE --skew-seconds S] [--expect-synced NODE --max-lag K]
  check.py --self-test        # checks the checker itself on synthetic data (no network)

Safety:   for every height present on two or more reachable nodes, block hash AND state root agree.
Liveness: the minimum height across reachable nodes grows by >= K within the timeout.
Jail:     every reachable node reports the same jailedValidators set (and it contains ADDRESS).
Skew:     the node's own clock (/api/v1/health "time") is off the host clock by about S seconds,
          so a clock-skew phase proves the skew reached the JVM.
Synced:   the node's height is within K blocks of the highest reachable node.
Exit status 0 only if every requested check passed.
"""
import argparse, json, sys, time, urllib.request

def http_get(url, timeout=5):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))

class Net:
    """Network access; replaced by a fake in --self-test."""
    def height(self, node): return int(http_get(node + "/api/v1/chain/height")["height"])
    def block(self, node, h):
        b = http_get(node + f"/api/v1/blocks/height/{h}")
        return b.get("hash"), b.get("stateRoot")
    def jailed(self, node): return sorted(http_get(node + "/api/v1/consensus").get("jailedValidators", []))
    def time(self, node): return int(http_get(node + "/api/v1/health")["time"])

def reachable_heights(net, nodes):
    out = {}
    for n in nodes:
        try:
            out[n] = net.height(n)
        except Exception:
            out[n] = None
    return out

def check_safety(net, nodes):
    heights = {n: h for n, h in reachable_heights(net, nodes).items() if h is not None}
    if len(heights) < 2:
        return {"ok": False, "reason": "fewer than two reachable nodes", "heights": heights}
    violations = []
    for h in range(0, max(heights.values()) + 1):
        seen = {}
        for n, top in heights.items():
            if top >= h:
                try:
                    seen[n] = net.block(n, h)
                except Exception:
                    pass
        if len(set(seen.values())) > 1:
            violations.append({"height": h, "blocks": {n: {"hash": v[0], "stateRoot": v[1]} for n, v in seen.items()}})
    return {"ok": not violations, "heights": heights, "violations": violations[:5]}

def check_liveness(net, nodes, k, timeout, sleep=5, clock=time.monotonic, wait=time.sleep):
    start = [h for h in reachable_heights(net, nodes).values() if h is not None]
    if not start:
        return {"ok": False, "reason": "no reachable node"}
    base, deadline = min(start), clock() + timeout
    while clock() < deadline:
        now = [h for h in reachable_heights(net, nodes).values() if h is not None]
        if now and min(now) >= base + k:
            return {"ok": True, "from": base, "to": min(now)}
        wait(sleep)
    now = [h for h in reachable_heights(net, nodes).values() if h is not None]
    return {"ok": False, "from": base, "to": min(now) if now else None, "reason": f"min height did not grow by {k} in {timeout}s"}

def check_jail(net, nodes, expect=None):
    sets = {}
    for n in nodes:
        try:
            sets[n] = tuple(net.jailed(n))
        except Exception:
            pass
    agree = len(set(sets.values())) <= 1 and len(sets) >= 2
    contains = expect is None or all(expect in s for s in sets.values())
    return {"ok": agree and contains, "sets": {n: list(s) for n, s in sets.items()}, "expected": expect}

def check_skew(net, node, seconds, tolerance=10, host_ms=lambda: int(time.time() * 1000)):
    try:
        before = host_ms(); t = net.time(node); after = host_ms()
    except Exception as e:
        return {"ok": False, "reason": f"node time unavailable: {e}"}
    offset = (t - (before + after) / 2) / 1000.0
    return {"ok": abs(offset - seconds) <= tolerance, "node": node, "offset_s": round(offset, 1), "expected_s": seconds}

def check_synced(net, nodes, node, max_lag):
    hs = {n: h for n, h in reachable_heights(net, nodes).items() if h is not None}
    if node not in hs:
        return {"ok": False, "reason": "node unreachable", "node": node}
    lag = max(hs.values()) - hs[node]
    return {"ok": lag <= max_lag, "node": node, "lag": lag, "max_lag": max_lag}

def self_test():
    class Fake(Net):
        def __init__(self, chains, jails=None):
            self.chains, self.jails, self.t = chains, jails or {}, 0
        def height(self, n):
            if self.chains[n] is None: raise IOError("down")
            return len(self.chains[n]) - 1
        def block(self, n, h): return self.chains[n][h]
        def jailed(self, n): return sorted(self.jails.get(n, []))
    g, a, b = ("g", "r0"), ("a", "r1"), ("b", "r1x")
    same = Fake({"n1": [g, a], "n2": [g, a], "n3": [g]})
    assert check_safety(same, ["n1", "n2", "n3"])["ok"], "agreeing prefixes must pass"
    fork = Fake({"n1": [g, a], "n2": [g, b]})
    r = check_safety(fork, ["n1", "n2"]);  assert not r["ok"] and r["violations"][0]["height"] == 1, "fork must be caught"
    root = Fake({"n1": [g, ("a", "r1")], "n2": [g, ("a", "DIFF")]})
    assert not check_safety(root, ["n1", "n2"])["ok"], "same hash, different state root must be caught"
    down = Fake({"n1": [g, a], "n2": None, "n3": [g, a]})
    assert check_safety(down, ["n1", "n2", "n3"])["ok"], "unreachable node is skipped"
    t = {"v": 0.0}
    grow = Fake({"n1": [g], "n2": [g]})
    def wait(s):
        t["v"] += s
        for c in grow.chains.values(): c.append(a)
    assert check_liveness(grow, ["n1", "n2"], 2, 60, clock=lambda: t["v"], wait=wait)["ok"], "growth must pass"
    t["v"] = 0.0
    stuck = Fake({"n1": [g], "n2": [g]})
    assert not check_liveness(stuck, ["n1", "n2"], 1, 30, clock=lambda: t["v"], wait=lambda s: t.__setitem__("v", t["v"] + s))["ok"], "stall must fail"
    assert check_jail(Fake({"n1": [g], "n2": [g]}, {"n1": ["x"], "n2": ["x"]}), ["n1", "n2"], "x")["ok"]
    assert not check_jail(Fake({"n1": [g], "n2": [g]}, {"n1": ["x"], "n2": []}), ["n1", "n2"])["ok"], "disagreement must fail"
    # [clock-skew] the skewed node must really be skewed, and must keep up with the others
    sk = Fake({"n1": [g, a, a], "n2": [g, a, a], "n3": [g, a]})
    sk.time = lambda n: 1_000_000 + (30_000 if n == "n3" else 0)
    assert check_skew(sk, "n3", 30, host_ms=lambda: 1_000_000)["ok"], "a +30 s node must be detected as skewed"
    assert not check_skew(sk, "n1", 30, host_ms=lambda: 1_000_000)["ok"], "an unskewed node must fail the skew check"
    assert check_synced(sk, ["n1", "n2", "n3"], "n3", 1)["ok"], "lag 1 is within max lag 1"
    assert not check_synced(sk, ["n1", "n2", "n3"], "n3", 0)["ok"], "lag 1 exceeds max lag 0"
    print("SELF-TEST PASSED (12 checks)")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--nodes"); ap.add_argument("--phase", default="check")
    ap.add_argument("--expect-progress", type=int, default=0); ap.add_argument("--timeout", type=int, default=300)
    ap.add_argument("--expect-jailed"); ap.add_argument("--out"); ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--expect-skew"); ap.add_argument("--skew-seconds", type=float, default=0)
    ap.add_argument("--expect-synced"); ap.add_argument("--max-lag", type=int, default=1)
    a = ap.parse_args()
    if a.self_test:
        self_test(); return 0
    nodes, net = [n.strip() for n in a.nodes.split(",") if n.strip()], Net()
    result = {"phase": a.phase, "time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "safety": check_safety(net, nodes)}
    if a.expect_progress:
        result["liveness"] = check_liveness(net, nodes, a.expect_progress, a.timeout)
        result["safety_after"] = check_safety(net, nodes)
    if a.expect_skew:
        result["skew"] = check_skew(net, a.expect_skew, a.skew_seconds)
    if a.expect_synced:
        result["synced"] = check_synced(net, nodes, a.expect_synced, a.max_lag)
    if a.expect_jailed is not None:
        result["jail"] = check_jail(net, nodes, a.expect_jailed or None)
    ok = all(v.get("ok", True) for k, v in result.items() if isinstance(v, dict))
    result["ok"] = ok
    line = json.dumps(result)
    print(("PASS " if ok else "FAIL ") + a.phase + " " + line[:400])
    if a.out:
        with open(a.out, "a") as f: f.write(line + "\n")
    return 0 if ok else 1

if __name__ == "__main__":
    sys.exit(main())
