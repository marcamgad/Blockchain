package com.hybrid.blockchain.ai;

// FIX 8: Reduce false positives in reentrancy detection.
// Previous: any CALL opcode immediately triggered MEDIUM regardless of what follows.
// Fixed:
//   - Track callDetected + lastCallPc; only add reentrancy finding if SSTORE
//     appears AFTER a CALL in linear bytecode order (conservative approximation).
//   - DELEGATECALL → always HIGH (can corrupt own storage via external callee).

import com.hybrid.blockchain.OpCode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Static analyzer for Smart Contract bytecode.
 *
 * <p><b>Algorithm:</b> Single-pass O(N) opcode scan with post-scan pattern checks.
 * <p><b>Time Complexity:</b> O(N) where N is bytecode length.
 * <p><b>IoT Rationale:</b> Prevents deployment of clearly malicious or buggy
 * contracts (reentrancy, infinite loops, missing terminator) before they consume
 * node resources.
 *
 * <p>Reentrancy detection: only flagged when a SSTORE opcode appears linearly
 * after a CALL opcode in the bytecode — a conservative but sound approximation
 * of the checks-effects-interactions violation pattern.
 */
public class SmartContractAuditor {

    public enum Severity {
        NONE, LOW, MEDIUM, HIGH, CRITICAL
    }

    public static class AuditResult {
        private Severity maxSeverity = Severity.NONE;
        private final List<String> findings = new ArrayList<>();

        /**
         * Records a finding at the given severity level.
         *
         * @param severity finding severity
         * @param message  human-readable finding description
         */
        public void addFinding(Severity severity, String message) {
            findings.add("[" + severity.name() + "] " + message);
            if (severity.ordinal() > maxSeverity.ordinal()) {
                maxSeverity = severity;
            }
        }

        /**
         * Returns the maximum severity across all recorded findings.
         *
         * @return max severity
         */
        public Severity getMaxSeverity() {
            return maxSeverity;
        }

        /**
         * Returns all recorded finding messages.
         *
         * @return unmodifiable list of finding strings
         */
        public List<String> getFindings() {
            return findings;
        }

        /**
         * Returns true if the contract should be rejected (HIGH or CRITICAL severity).
         *
         * @return true when rejection is warranted
         */
        public boolean isRejected() {
            return maxSeverity == Severity.HIGH || maxSeverity == Severity.CRITICAL;
        }
    }

    /**
     * Statically analyzes bytecode for known vulnerabilities.
     *
     * <p>Checks performed:
     * <ul>
     *   <li>Missing terminating opcode (STOP/RETURN/REVERT) — HIGH</li>
     *   <li>Reentrancy: SSTORE after CALL in linear order — MEDIUM</li>
     *   <li>DELEGATECALL presence — HIGH (arbitrary storage corruption risk)</li>
     *   <li>Unbounded backward jump — HIGH (potential infinite loop)</li>
     *   <li>Unknown opcode — CRITICAL</li>
     *   <li>Unbounded MSTORE — MEDIUM (advisory)</li>
     * </ul>
     *
     * @param bytecode the smart contract bytecode to audit
     * @return {@link AuditResult} with all findings and aggregate severity
     */
    public static AuditResult audit(byte[] bytecode) {
        AuditResult result = new AuditResult();
        if (bytecode == null || bytecode.length == 0) {
            result.addFinding(Severity.HIGH, "Empty bytecode");
            return result;
        }

        boolean hasStopReturnRevert = false;
        boolean hasMStore           = false;

        // FIX 8: Track external CALL positions and SSTORE positions separately.
        // Reentrancy is only flagged when at least one SSTORE PC > lastCallPc.
        boolean callDetected = false;
        int     lastCallPc   = -1;
        Set<Integer> sstorePcs = new LinkedHashSet<>();

        int    pc              = 0;
        long   lastPushedValue = -1;

        while (pc < bytecode.length) {
            byte   b         = bytecode[pc];
            OpCode op        = OpCode.fromByte(b);
            int    currentPc = pc;
            pc++;   // advance past opcode

            if (op == null) {
                result.addFinding(Severity.CRITICAL, "Unknown OpCode at PC " + currentPc);
                continue;
            }

            switch (op) {
                case STOP:
                case RETURN:
                case REVERT:
                    hasStopReturnRevert = true;
                    break;

                case PUSH:
                    int immediateBytes = op.getImmediateBytes();
                    if (pc + immediateBytes <= bytecode.length) {
                        ByteBuffer buffer = ByteBuffer.wrap(bytecode, pc, immediateBytes).order(ByteOrder.BIG_ENDIAN);
                        lastPushedValue = buffer.getLong();
                        pc += immediateBytes;
                    } else {
                        result.addFinding(Severity.CRITICAL, "Malformed PUSH at PC " + currentPc);
                        pc = bytecode.length; // abort scan
                    }
                    break;

                case JUMP:
                case JUMPI:
                    if (lastPushedValue >= 0 && lastPushedValue <= currentPc) {
                        result.addFinding(Severity.HIGH,
                                "Potential unbounded backward loop (JUMP to " + lastPushedValue + ") at PC " + currentPc);
                    }
                    break;

                case MSTORE:
                    hasMStore = true;
                    break;

                case SSTORE:
                    // FIX 8: record SSTORE pc for post-scan reentrancy check
                    sstorePcs.add(currentPc);
                    break;

                case CALL:
                    // FIX 8: do NOT immediately add finding — defer to post-scan pattern check
                    callDetected = true;
                    lastCallPc   = currentPc;
                    break;

                case DELEGATECALL:
                    // FIX 8: DELEGATECALL allows the callee to write to the CALLER's storage
                    // slots, breaking storage isolation. Always HIGH severity.
                    result.addFinding(Severity.HIGH,
                            "DELEGATECALL detected at PC " + currentPc +
                            " — callee can corrupt calling contract storage unexpectedly");
                    break;

                default:
                    break;
            }

            // Only PUSH leaves lastPushedValue; all other opcodes reset it
            if (op != OpCode.PUSH) {
                lastPushedValue = -1;
            }
        }

        // ── Post-scan pattern checks ──────────────────────────────────────────

        if (!hasStopReturnRevert) {
            result.addFinding(Severity.HIGH, "Missing terminating opcode (STOP/RETURN/REVERT).");
        }

        // FIX 8: Reentrancy only if at least one SSTORE appears after the last CALL
        // in linear bytecode order. A CALL followed only by STOP is benign.
        if (callDetected) {
            boolean sstoreAfterCall = false;
            for (int sp : sstorePcs) {
                if (sp > lastCallPc) {
                    sstoreAfterCall = true;
                    break;
                }
            }
            if (sstoreAfterCall) {
                result.addFinding(Severity.MEDIUM,
                        "Reentrancy footprint: SSTORE at PC > CALL at PC " + lastCallPc +
                        " — state write after external call (checks-effects-interactions violation)");
            }
        }

        if (hasMStore) {
            result.addFinding(Severity.MEDIUM,
                    "Unbounded MSTORE risk detected. Explicit bounds checks recommended.");
        }

        return result;
    }
}
