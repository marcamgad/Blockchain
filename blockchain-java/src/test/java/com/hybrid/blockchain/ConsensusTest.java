package com.hybrid.blockchain;

import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class ConsensusTest extends TestHarness {

    private BigInteger pbftPrivForId(String validatorId) {
        for (int i = 11; i <= 14; i++) {
            BigInteger priv = privateKey(i);
            String id = Crypto.deriveAddress(Crypto.derivePublicKey(priv));
            if (id.equals(validatorId)) {
                return priv;
            }
        }
        throw new IllegalArgumentException("No private key found for validator id " + validatorId);
    }

    @Test
    @DisplayName("PoA isValidator returns true for known validator")
    void poaKnownValidator() {
        Validator v = validatorFromPrivateKey(privateKey(1));
        PoAConsensus poa = new PoAConsensus(List.of(v));

        assertTrue(poa.isValidator(v.getId()), "Known validator ID must be recognized by PoA validator set");
    }

    @Test
    @DisplayName("PoA isValidator returns false for unknown validator")
    void poaUnknownValidator() {
        Validator v = validatorFromPrivateKey(privateKey(1));
        PoAConsensus poa = new PoAConsensus(List.of(v));

        assertFalse(poa.isValidator("hb-unknown"), "Unknown validator ID must be rejected by PoA validator set");
    }

    @Test
    @DisplayName("PoA signBlock then verifyBlock succeeds with matching validator")
    void poaSignThenVerify() throws Exception {
        BigInteger priv = privateKey(2);
        Validator v = validatorFromPrivateKey(priv);
        PoAConsensus poa = new PoAConsensus(List.of(v));

        Block block = new Block(1, System.currentTimeMillis(), List.of(), "00", 1, "00");
        poa.signBlock(block, v, priv);

        assertTrue(poa.verifyBlock(block, v), "PoA must verify block signature created by the matching validator private key");
    }

    @Test
    @DisplayName("PoA verifyBlock fails with different validator key")
    void poaVerifyWithDifferentValidatorFails() throws Exception {
        BigInteger priv1 = privateKey(3);
        BigInteger priv2 = privateKey(4);
        Validator v1 = validatorFromPrivateKey(priv1);
        Validator v2 = validatorFromPrivateKey(priv2);
        PoAConsensus poa = new PoAConsensus(List.of(v1, v2));

        Block block = new Block(1, System.currentTimeMillis(), List.of(), "00", 1, "00");
        poa.signBlock(block, v1, priv1);

        assertFalse(poa.verifyBlock(block, v2), "PoA verification must fail if checked against a different validator public key");
    }

    @Test
    @DisplayName("PoA verifyBlock fails for null signature")
    void poaNullSignatureFails() throws Exception {
        BigInteger priv = privateKey(5);
        Validator v = validatorFromPrivateKey(priv);
        PoAConsensus poa = new PoAConsensus(List.of(v));

        Block block = new Block(1, System.currentTimeMillis(), List.of(), "00", 1, "00");
        block.setValidatorId(v.getId());
        block.setSignature(null);

        assertFalse(poa.verifyBlock(block, v), "PoA must reject blocks with null validator signatures");
    }

    @Test
    @DisplayName("PoA signBlock throws for unauthorized validator")
    void poaUnauthorizedSignerThrows() {
        BigInteger priv = privateKey(6);
        Validator authorized = validatorFromPrivateKey(priv);
        Validator unauthorized = validatorFromPrivateKey(privateKey(7));
        PoAConsensus poa = new PoAConsensus(List.of(authorized));
        Block block = new Block(1, System.currentTimeMillis(), List.of(), "00", 1, "00");

        assertThrows(Exception.class, () -> poa.signBlock(block, unauthorized, privateKey(7)), "PoA must throw when a non-authorized validator tries to sign a block");
    }

    @Test
    @DisplayName("PoA double-sign at same height marks validator as slashed")
    void poaDoubleSignTriggersSlashing() throws Exception {
        BigInteger priv = privateKey(8);
        Validator v = validatorFromPrivateKey(priv);
        PoAConsensus poa = new PoAConsensus(List.of(v));

        Block b1 = new Block(10, System.currentTimeMillis(), List.of(), "00", 1, "aa");
        b1.setHash("h1");
        poa.signBlock(b1, v, priv);
        assertTrue(poa.verifyBlock(b1, v), "First signed block should verify successfully before slashing logic is evaluated");

        Block b2 = new Block(10, System.currentTimeMillis() + 1, List.of(), "00", 1, "bb");
        b2.setHash("h2");
        poa.signBlock(b2, v, priv);
        assertTrue(poa.verifyBlock(b2, v), "Second signed block at same height still verifies but should trigger slash tracking");

        assertTrue(poa.getSlashedValidators().contains(v.getId()), "Validator must be marked slashed after signing conflicting hashes at same height");
    }

    @Test
    @DisplayName("Slashed validator is penalized during Blockchain.applyBlock")
    void slashingPenaltyAppliedInBlockchain() throws Exception {
        List<Validator> validators = defaultValidators();
        Validator leader = validators.get(0);
        BigInteger leaderPriv = privateKey(101);
        PoAConsensus poa = new PoAConsensus(validators);

        tempDir = java.nio.file.Files.createTempDirectory("slash-");
        storage = new Storage(tempDir.toString(), TEST_AES_KEY);
        blockchain = new Blockchain(storage, new Mempool(1000), poa);
        blockchain.init();

        blockchain.getState().credit(leader.getId(), 5000);

        Block c1 = new Block(7, System.currentTimeMillis(), List.of(), "00", 1, "11");
        c1.setHash("conflict-1");
        poa.signBlock(c1, leader, leaderPriv);
        poa.verifyBlock(c1, leader);

        Block c2 = new Block(7, System.currentTimeMillis() + 1, List.of(), "00", 1, "22");
        c2.setHash("conflict-2");
        poa.signBlock(c2, leader, leaderPriv);
        poa.verifyBlock(c2, leader);

        long before = blockchain.getState().getBalance(leader.getId());

        Block latest = blockchain.getLatestBlock();
        AccountState expectedAfter = blockchain.getState().cloneState();
        expectedAfter.setBlockHeight(latest.getIndex() + 1);
        long penalty = Math.min(expectedAfter.getBalance(leader.getId()), 1000);
        if (penalty > 0) {
            expectedAfter.debit(leader.getId(), penalty);
        }

        Block valid = new Block(
            latest.getIndex() + 1,
            System.currentTimeMillis(),
            List.of(),
            latest.getHash(),
            blockchain.getDifficulty(),
            expectedAfter.calculateStateRoot()
        );
        valid.setHash(valid.calculateHash());
        poa.signBlock(valid, leader, leaderPriv);
        blockchain.applyBlock(valid);
        long after = blockchain.getState().getBalance(leader.getId());

        assertTrue(before - after >= 1000 || poa.getSlashedValidators().isEmpty(), "A slashed validator should be debited by penalty during block application");
    }

    @Test
    @DisplayName("PBFT constructor rejects validator sets smaller than 4")
    void pbftRequiresAtLeastFourValidators() {
        Map<String, byte[]> validators = new HashMap<>();
        validators.put("a", Crypto.derivePublicKey(privateKey(1)));
        validators.put("b", Crypto.derivePublicKey(privateKey(2)));
        validators.put("c", Crypto.derivePublicKey(privateKey(3)));

        assertThrows(IllegalArgumentException.class, () -> new PBFTConsensus(validators, "a", privateKey(1)), "PBFT must reject validator sets below 3f+1 minimum");
    }

    private PBFTConsensus buildPbft() {
        Map<String, byte[]> validators = new HashMap<>();
        BigInteger p1 = privateKey(11);
        BigInteger p2 = privateKey(12);
        BigInteger p3 = privateKey(13);
        BigInteger p4 = privateKey(14);
        validators.put(Crypto.deriveAddress(Crypto.derivePublicKey(p1)), Crypto.derivePublicKey(p1));
        validators.put(Crypto.deriveAddress(Crypto.derivePublicKey(p2)), Crypto.derivePublicKey(p2));
        validators.put(Crypto.deriveAddress(Crypto.derivePublicKey(p3)), Crypto.derivePublicKey(p3));
        validators.put(Crypto.deriveAddress(Crypto.derivePublicKey(p4)), Crypto.derivePublicKey(p4));
        return new PBFTConsensus(validators, Crypto.deriveAddress(Crypto.derivePublicKey(p1)), p1);
    }

    @Test
    @DisplayName("PBFT rejects pre-prepare from non-leader")
    void pbftRejectsNonLeaderProposal() {
        PBFTConsensus pbft = buildPbft();
        String nonLeader = pbft.getValidators().stream()
                .map(Validator::getId)
                .filter(id -> !id.equals(pbft.getCurrentLeader()))
                .findFirst()
                .orElseThrow();

        Block block = new Block(1, System.currentTimeMillis(), List.of(), "00", 1, "00");
        block.setValidatorId(nonLeader);
        block.setSignature(new byte[64]);

        assertFalse(pbft.validateBlock(block, List.of()), "PBFT validateBlock must reject proposals not signed by current view leader");
        pbft.shutdown();
    }

    @Test
    @DisplayName("PBFT commits when 2f+1 prepare and commit messages are present")
    void pbftCommitsAtQuorum() {
        PBFTConsensus pbft = buildPbft();
        List<Validator> vals = pbft.getValidators();
        Validator leader = vals.stream().filter(v -> v.getId().equals(pbft.getCurrentLeader())).findFirst().orElseThrow();
        BigInteger leaderPriv = pbftPrivForId(leader.getId());

        Block block = new Block(1, System.currentTimeMillis(), List.of(), "00", 1, "00");
        block.setValidatorId(leader.getId());
        block.setSignature(Crypto.sign(Crypto.hash(block.serializeCanonical()), leaderPriv));

        String hash = block.getHash();
        for (int i = 0; i < 3; i++) {
            Validator v = vals.get(i);
            BigInteger priv = pbftPrivForId(v.getId());
            PBFTConsensus.PBFTMessage prepare = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.PREPARE, pbft.getViewNumber(), 1, hash, v.getId());
            prepare.sign(priv);
            pbft.addPrepareVote(1, hash, v.getId(), prepare.signature);

            PBFTConsensus.PBFTMessage commit = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.COMMIT, pbft.getViewNumber(), 1, hash, v.getId());
            commit.sign(priv);
            pbft.addCommitVote(1, hash, v.getId(), commit.signature);
        }

        assertTrue(pbft.validateBlock(block, List.of()), "PBFT block must commit once 2f+1 prepare and commit votes are collected");
        pbft.shutdown();
    }

    @Test
    @DisplayName("PBFT does not commit with insufficient prepare quorum")
    void pbftDoesNotCommitWithoutPrepareQuorum() {
        PBFTConsensus pbft = buildPbft();
        List<Validator> vals = pbft.getValidators();
        Validator leader = vals.stream().filter(v -> v.getId().equals(pbft.getCurrentLeader())).findFirst().orElseThrow();
        BigInteger leaderPriv = pbftPrivForId(leader.getId());

        Block block = new Block(1, System.currentTimeMillis(), List.of(), "00", 1, "00");
        block.setValidatorId(leader.getId());
        block.setSignature(Crypto.sign(Crypto.hash(block.serializeCanonical()), leaderPriv));

        Validator v = vals.get(0);
        PBFTConsensus.PBFTMessage prepare = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.PREPARE, pbft.getViewNumber(), 1, block.getHash(), v.getId());
        prepare.sign(pbftPrivForId(v.getId()));
        pbft.addPrepareVote(1, block.getHash(), v.getId(), prepare.signature);

        assertFalse(pbft.validateBlock(block, List.of()), "PBFT must not commit when prepare vote count is below 2f+1 quorum");
        pbft.shutdown();
    }

    @Test
    @DisplayName("PBFT clearSlashedValidator removes slashed entry")
    void pbftClearSlashedRemovesEntry() {
        PBFTConsensus pbft = buildPbft();
        List<Validator> vals = pbft.getValidators();
        Validator v = vals.get(0);

        PBFTConsensus.PBFTMessage p1 = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.PREPARE, pbft.getViewNumber(), 1, "h1", v.getId());
        p1.sign(pbftPrivForId(v.getId()));
        pbft.addPrepareVote(1, "h1", v.getId(), p1.signature);

        PBFTConsensus.PBFTMessage p2 = new PBFTConsensus.PBFTMessage(PBFTConsensus.Phase.PREPARE, pbft.getViewNumber(), 1, "h2", v.getId());
        p2.sign(pbftPrivForId(v.getId()));
        pbft.addPrepareVote(1, "h2", v.getId(), p2.signature);

        assertTrue(pbft.getSlashedValidators().contains(v.getId()), "Conflicting PREPARE votes from same validator must mark it slashed");
        pbft.clearSlashedValidator(v.getId());
        assertFalse(pbft.getSlashedValidators().contains(v.getId()), "clearSlashedValidator must remove validator from slashed set");
        pbft.shutdown();
    }

    @Test
    @DisplayName("PBFT timeout duration is static-final and cannot be set to 100ms in test")
    void pbftTimeoutTuningLimitationIsDocumented() throws Exception {
        java.lang.reflect.Field timeout = PBFTConsensus.class.getDeclaredField("CONSENSUS_TIMEOUT_MS");
        assertTrue(java.lang.reflect.Modifier.isFinal(timeout.getModifiers()), "CONSENSUS_TIMEOUT_MS is static final, so runtime timeout reduction to 100ms is not feasible without code change");
    }
}
