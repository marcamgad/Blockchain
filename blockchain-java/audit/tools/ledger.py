"""HybridChain audit — Phase B coverage ledger and gate.

  py -I audit/tools/ledger.py build    # (re)build AUDIT_LEDGER.csv/.md from inventory + reviews
  py -I audit/tools/ledger.py gate     # print counts; exit 1 while anything is UNREVIEWED/invalid
  py -I audit/tools/ledger.py todo MOD # list UNREVIEWED rows whose class matches MOD (regex)

Rows: one per row of audit/inventory/methods.csv (M-xxxx) and tests.csv (T-xxxx).
Reviews: audit/reviews/*.csv with header  key,status,notes,findings,evidence
         (files applied in sorted order; a later file overrides an earlier one per key).

Validation (a row that fails validation counts as INVALID and fails the gate):
  REVIEWED-CLEAN   notes >= 25 chars AND name at least one checked property
                   (bounds, auth, determinism, overflow, locking, error-path, resource,
                   null, input, crypto, state, visibility, injection, atomicity, trivial)
  REVIEWED-FINDING findings must contain an HC-### id
  DEAD-CODE        inventory flags dead/test_only, OR notes justify it (>= 25 chars)
  NOT-VERIFIED     notes must say why (>= 15 chars)
"""
import csv, glob, os, re, sys
from collections import Counter

STATUSES = ["UNREVIEWED", "REVIEWED-CLEAN", "REVIEWED-FINDING", "DEAD-CODE", "NOT-VERIFIED"]
PROPS = re.compile(r"\b(bounds?|auth\w*|determinis\w*|overflow|lock\w*|error[- ]?path|resource\w*|null\w*|"
                   r"input|crypto\w*|state|visibility|inject\w*|atomic\w*|trivial|nondetermin\w*|"
                   r"concurren\w*|charset|locale|timing)\b", re.I)


def load_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def items():
    out = []
    for r in load_csv("audit/inventory/methods.csv"):
        key = f'{r["class"]}#{r["member"]}|{r["signature"]}|{r["kind"]}'
        flags = []
        if r["is_public_entry"]: flags.append("entry=" + r["is_public_entry"])
        if r["untrusted_reachable"] == "true": flags.append("UNTRUSTED")
        if r["dead"] == "true": flags.append("dead")
        if r["test_only"] == "true": flags.append("test-only")
        for k in ("touches_crypto", "touches_io", "touches_state", "sync_or_lock"):
            if r[k] == "true": flags.append(k.replace("touches_", ""))
        if r["nondeterminism"]: flags.append("nondet=" + r["nondeterminism"])
        out.append(dict(id=r["id"], key=key, item=f'{r["class"]}.{r["member"]}  [{r["kind"]}] {r["file"]}:{r["line"]}',
                        flags=" ".join(flags), dead=r["dead"] == "true" or r["test_only"] == "true", cls=r["class"]))
    for r in load_csv("audit/inventory/tests.csv"):
        key = f'TEST {r["class"]}#{r["method"]}'
        flags = [f'asserts={r["assertions"]}']
        if r["only_no_exception"] == "true": flags.append("ONLY-NO-EXCEPTION")
        if r["mocks_cut"] == "true": flags.append("MOCKS-CUT")
        if r["disabled"]: flags.append("DISABLED")
        if r["sleep_or_await"] == "true": flags.append("sleep/await")
        if r["dup_group"]: flags.append("dup=" + r["dup_group"])
        flags.append("wiring=" + r["wiring"])
        out.append(dict(id=r["id"], key=key, item=f'{r["class"]}.{r["method"]}  {r["file"]}:{r["line"]}',
                        flags=" ".join(flags), dead=False, cls=r["class"]))
    return out


def reviews():
    rv, origin = {}, {}
    for path in sorted(glob.glob("audit/reviews/*.csv")):
        for r in load_csv(path):
            k = (r.get("key") or "").strip()
            if not k:
                continue
            rv[k] = {c: (r.get(c) or "").strip() for c in ("status", "notes", "findings", "evidence")}
            origin[k] = os.path.basename(path)
    return rv, origin


def validate(row, dead_flag):
    s, n, f = row["status"], row["notes"], row["findings"]
    if s not in STATUSES:
        return f"unknown status '{s}'"
    if s == "REVIEWED-CLEAN" and (len(n) < 25 or not PROPS.search(n)):
        return "REVIEWED-CLEAN needs >=25-char notes naming the properties checked"
    if s == "REVIEWED-FINDING" and not re.search(r"HC-\d{3}", f):
        return "REVIEWED-FINDING needs an HC-### id in findings"
    if s == "DEAD-CODE" and not dead_flag and len(n) < 25:
        return "DEAD-CODE not flagged by inventory: justify in notes"
    if s == "NOT-VERIFIED" and len(n) < 15:
        return "NOT-VERIFIED needs a reason"
    return ""


def build():
    its = items()
    rv, origin = reviews()
    keys = {i["key"] for i in its}
    rows, invalid = [], []
    for i in its:
        r = rv.get(i["key"], {"status": "UNREVIEWED", "notes": "", "findings": "", "evidence": ""})
        err = validate(r, i["dead"]) if r["status"] != "UNREVIEWED" else ""
        if err:
            invalid.append((i["id"], i["key"], err))
        rows.append(dict(id=i["id"], item=i["item"], status=r["status"], notes=r["notes"], findings=r["findings"],
                         evidence=r["evidence"], flags=i["flags"], key=i["key"], reviewed_in=origin.get(i["key"], ""),
                         invalid=err))
    orphans = sorted(k for k in rv if k not in keys)
    with open("audit/AUDIT_LEDGER.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader(); w.writerows(rows)
    c = Counter(r["status"] for r in rows)
    with open("audit/AUDIT_LEDGER.md", "w", encoding="utf-8") as f:
        f.write("# HybridChain Audit Ledger\n\nGenerated by `audit/tools/ledger.py build` — do not edit by hand; "
                "edit `audit/reviews/*.csv` and rebuild.\n\n")
        f.write("| Status | Rows |\n|---|---:|\n")
        for s in STATUSES:
            f.write(f"| {s} | {c.get(s, 0)} |\n")
        f.write(f"| **INVALID** (fails validation) | {len(invalid)} |\n| **Total** | {len(rows)} |\n\n")
        if orphans:
            f.write(f"**Orphaned reviews** (key no longer in inventory): {len(orphans)}\n\n")
        f.write("| id | item | status | notes | findings | evidence | flags |\n|---|---|---|---|---|---|---|\n")
        esc = lambda s: (s or "").replace("|", "\\|")
        for r in rows:
            f.write(f'| {r["id"]} | {esc(r["item"])} | {r["status"]}{" ⚠" + esc(r["invalid"]) if r["invalid"] else ""} | '
                    f'{esc(r["notes"])} | {esc(r["findings"])} | {esc(r["evidence"])} | {esc(r["flags"])} |\n')
    return rows, invalid, orphans


def gate():
    rows, invalid, orphans = build()
    c = Counter(r["status"] for r in rows)
    m = [r for r in rows if r["id"].startswith("M-")]
    t = [r for r in rows if r["id"].startswith("T-")]
    print(f"ledger rows: {len(rows)}  (methods {len(m)}, tests {len(t)})")
    for s in STATUSES:
        print(f"  {s:17s} {c.get(s, 0):5d}   methods {sum(r['status'] == s for r in m):5d}   tests {sum(r['status'] == s for r in t):5d}")
    print(f"  {'INVALID':17s} {len(invalid):5d}")
    print(f"  orphaned reviews  {len(orphans):5d}")
    for i in invalid[:15]:
        print("   INVALID", i[0], i[2], "::", i[1][:110])
    unrev = c.get("UNREVIEWED", 0)
    if unrev or invalid:
        print(f"GATE: FAIL ({unrev} unreviewed, {len(invalid)} invalid)")
        return 1
    print("GATE: PASS")
    return 0


SHORT = {"CLEAN": "REVIEWED-CLEAN", "FINDING": "REVIEWED-FINDING", "DEAD": "DEAD-CODE", "NV": "NOT-VERIFIED"}


def ingest(tsv_path):
    """Resolve 'ID<TAB>STATUS<TAB>notes[<TAB>findings[<TAB>evidence]]' lines to stable keys.

    IDs (M-xxxx / T-xxxx) are only valid for the CURRENT inventory, so they are converted
    to keys immediately; the written audit/reviews/<name>.csv survives regeneration.
    """
    by_id = {i["id"]: i["key"] for i in items()}
    out, bad = [], []
    for n, line in enumerate(open(tsv_path, encoding="utf-8"), 1):
        line = line.rstrip(chr(10))
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split(chr(9))
        while len(parts) < 5:
            parts.append("")
        rid, st, notes, findings, evidence = (x.strip() for x in parts[:5])
        if rid not in by_id:
            bad.append(f"line {n}: unknown id {rid}"); continue
        out.append({"key": by_id[rid], "status": SHORT.get(st, st), "notes": notes,
                    "findings": findings, "evidence": evidence})
    name = os.path.splitext(os.path.basename(tsv_path))[0]
    dest = f"audit/reviews/{name}.csv"
    with open(dest, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["key", "status", "notes", "findings", "evidence"])
        w.writeheader(); w.writerows(out)
    print(f"ingested {len(out)} rows -> {dest}" + (f"; {len(bad)} errors" if bad else ""))
    for b in bad:
        print("  ", b)
    return 1 if bad else 0


def todo(pattern):
    rows, _, _ = build()
    rx = re.compile(pattern)
    for r in rows:
        if r["status"] == "UNREVIEWED" and rx.search(r["item"]):
            print(f'{r["id"]}\t{r["item"]}\t{r["flags"]}')


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "gate"
    if cmd == "build":
        rows, inv, orph = build(); print(f"built {len(rows)} rows, {len(inv)} invalid, {len(orph)} orphaned")
    elif cmd == "gate":
        sys.exit(gate())
    elif cmd == "ingest":
        sys.exit(ingest(sys.argv[2]))
    elif cmd == "todo":
        todo(sys.argv[2] if len(sys.argv) > 2 else ".")
    else:
        print(__doc__); sys.exit(2)
