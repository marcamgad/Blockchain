#!/usr/bin/env bash
# HybridChain audit — Phase C automated evidence. Run from blockchain-java/.
# Each step logs to audit/out/<step>.log and copies machine-readable output to
# audit/evidence/. A step that fails is recorded as NOT-RUN (never as "clean").
set -u
cd "$(dirname "$0")/../.."
mkdir -p audit/out audit/evidence
STATUS=audit/evidence/phase_c_status.txt
: > "$STATUS"
note() { echo "$(date -u +%FT%TZ) $*" | tee -a "$STATUS"; }

step() {   # step <name> <command...>
  local name=$1; shift
  note "START $name"
  if "$@" > "audit/out/$name.log" 2>&1; then note "OK    $name"; else note "FAIL  $name (exit $?) — see audit/out/$name.log"; fi
}

# 1. JaCoCo with the repository's DEFAULT test config (baseline)
step jacoco-default mvn -o test
[ -f target/site/jacoco/jacoco.csv ] && cp target/site/jacoco/jacoco.csv audit/evidence/jacoco-default.csv
cp -r target/surefire-reports audit/out/surefire-default 2>/dev/null

# 2. JaCoCo with PRODUCTION consensus defaults (no MIN_PEERS / REQUIRE_QUANTUM_SIG overrides)
step jacoco-prod mvn -o test -P audit-prod
[ -f target/site/jacoco/jacoco.csv ] && cp target/site/jacoco/jacoco.csv audit/evidence/jacoco-prod.csv
cp -r target/surefire-reports-prod audit/out/surefire-prod 2>/dev/null

# 3. SpotBugs + FindSecBugs (online: plugin download from Maven Central)
step spotbugs mvn -P audit-static compile com.github.spotbugs:spotbugs-maven-plugin:4.8.6.6:spotbugs
[ -f target/spotbugsXml.xml ] && cp target/spotbugsXml.xml audit/evidence/spotbugs.xml

# 4. CycloneDX SBOM
step sbom mvn -P audit-sbom org.cyclonedx:cyclonedx-maven-plugin:2.7.11:makeAggregateBom
for f in target/bom.json target/bom.xml; do [ -f "$f" ] && cp "$f" audit/evidence/; done

# 5. Error Prone (recompile main sources, then restore a normal compile)
rm -rf target/classes
step errorprone mvn -P audit-errorprone compile
grep -E "warning: \[|error: \[" audit/out/errorprone.log > audit/evidence/errorprone.txt 2>/dev/null
rm -rf target/classes
step recompile mvn -o compile

note "PHASE C (1-5) DONE"
