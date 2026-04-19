package com.hybrid.blockchain.ai;

import com.hybrid.blockchain.OpCode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Static analyzer for Smart Contract bytecode.
 *
 * <p><b>Algorithm:</b> Single-pass static opcode analysis.
 * <p><b>Time Complexity:</b> O(N) where N is bytecode length.
 * <p><b>IoT Rationale:</b> Prevents deployment of clearly malicious or buggy 
 * contracts (reentrancy, infinite loops) before they consume node resources.</p>
 *
 * <p>Checks for known vulnerabilities like reentrancy and infinite loops.</p>
 */
public class SmartContractAuditor {

    public enum Severity {
        NONE, LOW, MEDIUM, HIGH, CRITICAL
    }

    public static class AuditResult {
        private Severity maxSeverity = Severity.NONE;
        private final List<String> findings = new ArrayList<>();

        public void addFinding(Severity severity, String message) {
            findings.add("[" + severity.name() + "] " + message);
            if (severity.ordinal() > maxSeverity.ordinal()) {
                maxSeverity = severity;
            }
        }

        public Severity getMaxSeverity() {
            return maxSeverity;
        }

        public List<String> getFindings() {
            return findings;
        }
        
        public boolean isRejected() {
            return maxSeverity == Severity.HIGH || maxSeverity == Severity.CRITICAL;
        }
    }

    /**
     * Statically analyzes bytecode. O(N) complexity where N is bytecode length.
     * @param bytecode The smart contract bytecode
     * @return AuditResult containing findings and maximum severity
     */
    public static AuditResult audit(byte[] bytecode) {
        AuditResult result = new AuditResult();
        if (bytecode == null || bytecode.length == 0) {
            result.addFinding(Severity.HIGH, "Empty bytecode");
            return result;
        }

        boolean hasStopReturnRevert = false;
        boolean hasCall = false;
        boolean hasMStore = false;
        
        int pc = 0;
        long lastPushedValue = -1;

        while (pc < bytecode.length) {
            byte b = bytecode[pc];
            OpCode op = OpCode.fromByte(b);
            int currentPc = pc;
            pc++; // advance program counter for opcode
            
            if (op == null) {
                // Unknown opcode -> Critical risk
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
                    if (pc + 8 <= bytecode.length) {
                        ByteBuffer buffer = ByteBuffer.wrap(bytecode, pc, 8).order(ByteOrder.BIG_ENDIAN);
                        lastPushedValue = buffer.getLong();
                        pc += 8;
                    } else {
                        result.addFinding(Severity.CRITICAL, "Malformed PUSH at PC " + currentPc);
                        pc = bytecode.length; // Abort
                    }
                    break;
                case JUMP:
                case JUMPI:
                    // If bounding backward without condition or with condition, could be an infinite loop.
                    // Statically we approximate: if JUMP targets a PC <= currentPc, and it was a direct PUSH beforehand.
                    if (lastPushedValue >= 0 && lastPushedValue <= currentPc) {
                        result.addFinding(Severity.HIGH, "Potential unbounded backward loop (JUMP to " + lastPushedValue + ") at PC " + currentPc);
                    }
                    break;
                case MSTORE:
                    hasMStore = true;
                    // For the sake of the static analysis, any MSTORE could be an unbounded array risk if in a loop.
                    break;
                case CALL:
                    hasCall = true;
                    // External calls are a Reentrancy footprint.
                    result.addFinding(Severity.MEDIUM, "Reentrancy footprint: External CALL opcode detected at PC " + currentPc);
                    break;
                default:
                    break;
            }
            
            // Reset last pushed value if opcode is not PUSH, so JUMP only looks at immediately preceding PUSH
            if (op != OpCode.PUSH) {
                lastPushedValue = -1;
            }
        }

        if (!hasStopReturnRevert) {
            result.addFinding(Severity.HIGH, "Missing terminating opcode (STOP/RETURN/REVERT).");
        }

        if (hasMStore) {
            // Unbounded MSTORE arrays
            result.addFinding(Severity.MEDIUM, "Unbounded MSTORE risk detected. Explicit bounds checks recommended.");
        }

        return result;
    }
}
