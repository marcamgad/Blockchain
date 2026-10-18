"""HybridChain audit — Phase A.5 dependency inventory.

Usage:  py -I audit/tools/deps.py            (run from blockchain-java/)
Reads:  audit/out/deps-runtime.txt (mvn dependency:list -DincludeScope=runtime)
        target/blockchain-java-1.0-SNAPSHOT-jar-with-dependencies.jar
        ~/.m2/repository (jars + poms, for class lists and licences)
Writes: audit/inventory/deps.csv, audit/inventory/dup_classes.csv,
        audit/inventory/fatjar_composition.csv, audit/evidence/osv_raw.json

CVE source: OSV.dev (https://api.osv.dev), which aggregates GitHub Security Advisories
and NVD for the Maven ecosystem. Network errors are recorded as NOT-VERIFIED, never as
"no CVEs".
"""
import csv, hashlib, io, json, os, re, sys, urllib.request, zipfile, xml.etree.ElementTree as ET
from collections import defaultdict

M2 = os.path.join(os.path.expanduser("~"), ".m2", "repository")
FAT = "target/blockchain-java-1.0-SNAPSHOT-jar-with-dependencies.jar"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def parse_list(path):
    deps = []
    for line in open(path, encoding="utf-8"):
        line = re.sub(r"\[[0-9;]*m", "", line).strip()      # Maven colour codes
        if not line or line.startswith("The following") or ":" not in line:
            continue
        line = re.sub(r"\s+--.*$", "", line)            # strip " -- module x" suffixes
        parts = line.split(":")
        if len(parts) == 5:
            g, a, t, v, s = parts; c = ""
        elif len(parts) == 6:
            g, a, t, c, v, s = parts
        else:
            continue
        deps.append(dict(group=g, artifact=a, type=t, classifier=c, version=v, scope=s))
    return deps


def art_path(d, ext):
    base = os.path.join(M2, *d["group"].split("."), d["artifact"], d["version"])
    name = f"{d['artifact']}-{d['version']}" + (f"-{d['classifier']}" if d["classifier"] else "") + "." + ext
    return os.path.join(base, name)


def pom_licences(group, artifact, version, depth=0):
    p = os.path.join(M2, *group.split("."), artifact, version, f"{artifact}-{version}.pom")
    if not os.path.exists(p) or depth > 6:
        return "", "pom-not-cached" if not os.path.exists(p) else "parent-chain-too-deep"
    try:
        root = ET.parse(p).getroot()
    except ET.ParseError:
        return "", "pom-unparseable"
    ns = NS if root.tag.startswith("{") else {"m": ""}
    q = (lambda x: f"m:{x}") if root.tag.startswith("{") else (lambda x: x)
    lic = [l.findtext(q("name"), default="", namespaces=ns).strip() for l in root.findall(f"{q('licenses')}/{q('license')}", ns)]
    lic = [x for x in lic if x]
    if lic:
        return "; ".join(lic), f"{artifact}-{version}.pom" if depth == 0 else f"inherited from {artifact}-{version}"
    par = root.find(q("parent"), ns)
    if par is not None:
        return pom_licences(par.findtext(q("groupId"), namespaces=ns), par.findtext(q("artifactId"), namespaces=ns),
                            par.findtext(q("version"), namespaces=ns), depth + 1)
    return "", "no <licenses> in pom chain"


def osv(deps):
    queries = [{"package": {"ecosystem": "Maven", "name": f"{d['group']}:{d['artifact']}"}, "version": d["version"]} for d in deps]
    out = {}
    try:
        req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps({"queries": queries}).encode(),
                                     headers={"Content-Type": "application/json"})
        res = json.load(urllib.request.urlopen(req, timeout=60))
    except Exception as e:  # noqa: BLE001 — record, never swallow as "clean"
        return None, f"NOT-VERIFIED: OSV unreachable ({e.__class__.__name__}: {e})"
    details = {}
    for d, r in zip(deps, res.get("results", [])):
        ids = [v["id"] for v in r.get("vulns", [])]
        out[(d["group"], d["artifact"])] = ids
        for vid in ids:
            details.setdefault(vid, None)
    for vid in list(details):
        try:
            details[vid] = json.load(urllib.request.urlopen(f"https://api.osv.dev/v1/vulns/{vid}", timeout=60))
        except Exception as e:  # noqa: BLE001
            details[vid] = {"id": vid, "summary": f"detail fetch failed: {e}"}
    return (out, details), "OSV.dev querybatch"


def severity(v):
    for s in v.get("severity", []) or []:
        if s.get("type", "").startswith("CVSS"):
            return s.get("score", "")
    ds = v.get("database_specific", {}) or {}
    return ds.get("severity", "")


def main():
    deps = parse_list("audit/out/deps-runtime.txt")
    fat = zipfile.ZipFile(FAT)
    fat_entries = {i.filename: i for i in fat.infolist() if not i.is_dir()}

    owner_of = defaultdict(list)     # class entry -> [dep keys]
    jar_hash = {}
    rows = []
    for d in deps:
        key = f"{d['group']}:{d['artifact']}:{d['version']}"
        jp = art_path(d, d["type"] if d["type"] != "bundle" else "jar")
        classes, size = [], 0
        if os.path.exists(jp):
            size = os.path.getsize(jp)
            with zipfile.ZipFile(jp) as z:
                for i in z.infolist():
                    if i.filename.endswith(".class") and not i.filename.startswith("META-INF/versions/"):
                        classes.append(i.filename)
                        owner_of[i.filename].append(key)
                        jar_hash[(key, i.filename)] = i.CRC
        d.update(key=key, jar=jp if os.path.exists(jp) else "NOT CACHED", size=size, nclasses=len(classes))
        lic, src = pom_licences(d["group"], d["artifact"], d["version"])
        d.update(licence=lic or "UNKNOWN", licence_source=src)
        rows.append(d)

    res, src = osv(rows)
    vulns_by_dep, details = (res if res else ({}, {}))
    with open("audit/evidence/osv_raw.json", "w", encoding="utf-8") as f:
        json.dump({"source": src, "by_dep": {f"{g}:{a}": ids for (g, a), ids in vulns_by_dep.items()},
                   "details": details}, f, indent=1)

    # duplicates: same class in >1 dep; CRC differs => behaviour depends on assembly order
    dups = {c: ks for c, ks in owner_of.items() if len(ks) > 1}
    with open("audit/inventory/dup_classes.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f); w.writerow(["class", "provided_by", "bytes_identical", "in_fat_jar"])
        for c, ks in sorted(dups.items()):
            same = len({jar_hash[(k, c)] for k in ks}) == 1
            w.writerow([c, " | ".join(ks), same, c in fat_entries])

    # fat-jar composition: compressed bytes attributed to the dep that provides each entry
    comp = defaultdict(int); unattributed = 0
    for name, info in fat_entries.items():
        ks = owner_of.get(name)
        if ks:
            comp[ks[0] if len(ks) == 1 else "AMBIGUOUS(" + ks[0] + "...)"] += info.compress_size
        elif name.startswith("com/hybrid/"):
            comp["<project classes>"] += info.compress_size
        else:
            unattributed += info.compress_size
    comp["<resources/metadata/unattributed>"] = unattributed
    with open("audit/inventory/fatjar_composition.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f); w.writerow(["provider", "compressed_bytes", "pct"])
        tot = sum(comp.values())
        for k, v in sorted(comp.items(), key=lambda kv: -kv[1]):
            w.writerow([k, v, f"{100 * v / tot:.2f}"])

    with open("audit/inventory/deps.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["group", "artifact", "version", "scope", "classifier", "jar_bytes", "classes", "licence",
                    "licence_source", "dup_classes_with_others", "osv_ids", "max_severity", "osv_summaries", "cve_source"])
        for d in rows:
            ids = vulns_by_dep.get((d["group"], d["artifact"]), [])
            sev = [severity(details.get(i) or {}) for i in ids]
            summ = [((details.get(i) or {}).get("summary") or "")[:80] for i in ids]
            ndup = sum(1 for c, ks in dups.items() if d["key"] in ks)
            w.writerow([d["group"], d["artifact"], d["version"], d["scope"], d["classifier"], d["size"], d["nclasses"],
                        d["licence"], d["licence_source"], ndup, " ".join(ids), " ".join(s for s in sev if s),
                        " || ".join(s for s in summ if s), src])
    vulnerable = sum(1 for d in rows if vulns_by_dep.get((d["group"], d["artifact"])))
    print(f"deps={len(rows)} vulnerable_deps={vulnerable} osv_ids={len(details)} dup_class_entries={len(dups)} "
          f"fat_entries={len(fat_entries)} cve_source={src}")


if __name__ == "__main__":
    main()
