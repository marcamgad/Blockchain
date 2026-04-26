package com.hybrid.blockchain.ai;

import com.hybrid.blockchain.OpCode;
import com.hybrid.blockchain.ai.SmartContractAuditor.AuditResult;
import com.hybrid.blockchain.ai.SmartContractAuditor.Severity;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public class SmartContractAuditorTest {

    @Test
    public void testEmptyBytecode() {
        AuditResult result = SmartContractAuditor.audit(new byte[0]);
        assertTrue(result.isRejected());
        assertEquals(Severity.HIGH, result.getMaxSeverity());
    }

    @Test
    public void testCleanContract() {
        // PUSH 8 bytes, STOP
        byte[] code = new byte[10];
        code[0] = OpCode.PUSH.getByte();
        // 8 bytes of zeros
        code[9] = OpCode.STOP.getByte();
        
        AuditResult result = SmartContractAuditor.audit(code);
        assertFalse(result.isRejected(), "Clean contract should not be rejected");
        assertEquals(Severity.NONE, result.getMaxSeverity());
    }

    @Test
    public void testMissingStopReturn() {
        byte[] code = new byte[2];
        code[0] = OpCode.ADD.getByte();
        code[1] = OpCode.SUB.getByte();
        
        AuditResult result = SmartContractAuditor.audit(code);
        assertTrue(result.isRejected());
        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("Missing terminating opcode")));
    }

    @Test
    public void testReentrancyFootprint() {
        byte[] code = new byte[4];
        code[0] = OpCode.CALL.getByte();
        code[1] = OpCode.SSTORE.getByte();
        code[2] = OpCode.STOP.getByte();
        
        AuditResult result = SmartContractAuditor.audit(code);
        assertFalse(result.isRejected());
        assertEquals(Severity.MEDIUM, result.getMaxSeverity());
        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("Reentrancy")));
    }

    @Test
    public void testInfiniteLoop() {
        byte[] code = new byte[11];
        code[0] = OpCode.PUSH.getByte();
        ByteBuffer.wrap(code, 1, 8).putLong(0L); // Push 0 as jump target (i.e., back to start)
        code[9] = OpCode.JUMP.getByte();
        code[10] = OpCode.STOP.getByte();
        
        AuditResult result = SmartContractAuditor.audit(code);
        assertTrue(result.isRejected());
        assertEquals(Severity.HIGH, result.getMaxSeverity());
        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("loop")));
    }
}
