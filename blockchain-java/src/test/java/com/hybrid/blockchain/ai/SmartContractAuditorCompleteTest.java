package com.hybrid.blockchain.ai;

import com.hybrid.blockchain.*;
import com.hybrid.blockchain.testutil.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the AI-powered Smart Contract Auditor.
 * Verifies vulnerability detection for reentrancy, loops, and malformed bytecode.
 */
@Tag("ai")
public class SmartContractAuditorCompleteTest {

    private SmartContractAuditor auditor;

    @BeforeEach
    void setUp() {
        auditor = new SmartContractAuditor();
    }

    @Test
    @DisplayName("SC1.1 — Empty bytecode")
    void testEmptyBytecode() {
        SmartContractAuditor.AuditResult res = auditor.audit(new byte[0]);
        assertThat(res.isRejected()).isTrue();
        assertThat(res.getFindings()).anyMatch(f -> f.contains("Empty bytecode"));
    }

    @Test
    @DisplayName("SC1.2-1.3 — Terminating opcodes")
    void testTerminatingOpcode() {
        byte[] good = { (byte)OpCode.STOP.getByte() };
        assertThat(auditor.audit(good).isRejected()).isFalse();
        
        byte[] bad = { (byte)OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,1 };
        assertThat(auditor.audit(bad).isRejected()).isTrue();
        assertThat(auditor.audit(bad).getFindings()).anyMatch(f -> f.contains("terminating opcode"));
    }

    @Test
    @DisplayName("SC1.4 — Unbounded backward loop")
    void testBackwardLoop() {
        byte[] loop = { 
            (byte)OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,0, // PUSH 0
            (byte)OpCode.JUMP.getByte()                 // JUMP to 0
        };
        SmartContractAuditor.AuditResult res = auditor.audit(loop);
        assertThat(res.isRejected()).isTrue();
        assertThat(res.getFindings()).anyMatch(f -> f.contains("backward loop"));
    }

    @Test
    @DisplayName("SC1.5 — Reentrancy detection")
    void testReentrancyDetection() {
        // Pattern: CALL followed by SSTORE
        byte[] code = {
            (byte)OpCode.CALL.getByte(),
            (byte)OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,0,
            (byte)OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,1,
            (byte)OpCode.SSTORE.getByte(),
            (byte)OpCode.STOP.getByte()
        };
        SmartContractAuditor.AuditResult res = auditor.audit(code);
        assertThat(res.getFindings()).anyMatch(f -> f.contains("Reentrancy"));
    }

    @Test
    @DisplayName("SC1.12 — On-chain deployment rejection")
    void testDeploymentRejection() throws Exception {
        try (TestBlockchain tb = new TestBlockchain()) {
            Blockchain chain = tb.getBlockchain();
            TestKeyPair alice = new TestKeyPair(1);
            chain.getAccountState().credit(alice.getAddress(), 1000L);
            
            // Loop code is critical/high severity
            byte[] loop = { (byte)OpCode.PUSH.getByte(), 0,0,0,0,0,0,0,0, (byte)OpCode.JUMP.getByte() };
            Transaction tx = TestTransactionFactory.createContractCreation(alice, loop, 1, 1);
            
            assertThatThrownBy(() -> chain.addTransaction(tx))
                    .hasMessageContaining("Contract rejected by AI Audit");
        }
    }
}
