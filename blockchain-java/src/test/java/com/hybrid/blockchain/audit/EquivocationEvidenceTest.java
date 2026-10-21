package com.hybrid.blockchain.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.Block;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.TestKeyPair;
import com.hybrid.blockchain.Transaction;
import com.hybrid.blockchain.consensus.PBFTConsensus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [O-5] Equivocation is proven on-chain and jails the validator on every node. */
class EquivocationEvidenceTest {

    private static String sig(PBFTConsensus.Phase p, long view, long seq, String hash, TestKeyPair k) {
        PBFTConsensus.PBFTMessage m = new PBFTConsensus.PBFTMessage(p, view, seq, hash, k.getAddress());
        m.sign(k.getPrivateKey());
        return HexUtils.encode(m.signature);
    }

    private static PBFTConsensus.EquivocationEvidence evidence(TestKeyPair v, String hashB, long viewB) {
        PBFTConsensus.EquivocationEvidence ev = new PBFTConsensus.EquivocationEvidence();
        ev.phase = "COMMIT";
        ev.view = 0;
        ev.seq = 7;
        ev.validatorId = v.getAddress();
        ev.hashA = "aa".repeat(32);
        ev.signatureA = sig(PBFTConsensus.Phase.COMMIT, 0, 7, ev.hashA, v);
        ev.hashB = hashB;
        ev.signatureB = sig(PBFTConsensus.Phase.COMMIT, viewB, 7, hashB, v);
        return ev;
    }

    private static Transaction tx(TestKeyPair reporter, PBFTConsensus.EquivocationEvidence ev, long fee, long nonce) throws Exception {
        return new Transaction.Builder().type(Transaction.Type.EQUIVOCATION_EVIDENCE).from(reporter.getAddress())
                .data(new ObjectMapper().writeValueAsBytes(ev)).fee(fee).nonce(nonce)
                .sign(reporter.getPrivateKey(), reporter.getPublicKey());
    }

    @Test
    @DisplayName("valid evidence in a block jails the validator in the state root and in consensus")
    void evidenceJailsValidator() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair reporter = new TestKeyPair(8801);
            f.fund(reporter.getAddress());
            TestKeyPair cheater = LedgerPoCFixture.VALIDATORS[2];
            String rootBefore = f.chain.getState().calculateStateRoot();
            long fee = Math.max(1, f.chain.getCurrentBaseFee());
            Transaction t = tx(reporter, evidence(cheater, "bb".repeat(32), 0), fee, 1);
            f.chain.validateTransaction(t);

            Block b = f.build(List.of(t), 0);
            f.commitAndApply(b);

            assertThat(f.chain.getState().isJailed(cheater.getAddress())).isTrue();
            assertThat(f.consensus.getJailedValidators()).contains(cheater.getAddress());
            assertThat(f.chain.getState().calculateStateRoot()).isNotEqualTo(rootBefore);
            // a jailed validator is never elected and its votes are refused
            for (long view = 0; view < 40; view++) {
                assertThat(f.consensus.selectLeader(view)).isNotEqualTo(cheater.getAddress());
            }
            assertThatThrownBy(() -> f.consensus.addPrepareVote(9, "cc".repeat(32), cheater.getAddress(),
                    HexUtils.decode(sig(PBFTConsensus.Phase.PREPARE, f.consensus.getViewNumber(), 9, "cc".repeat(32), cheater))))
                    .hasMessageContaining("jailed");
            // the same validator cannot be jailed twice
            assertThatThrownBy(() -> f.chain.validateTransaction(tx(reporter, evidence(cheater, "dd".repeat(32), 0), fee, 2)))
                    .hasMessageContaining("already jailed");
        }
    }

    @Test
    @DisplayName("not evidence: same hash twice, different views, a forged signature, a non-validator")
    void invalidEvidenceRejected() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            TestKeyPair reporter = new TestKeyPair(8802);
            f.fund(reporter.getAddress());
            long fee = Math.max(1, f.chain.getCurrentBaseFee());
            TestKeyPair v = LedgerPoCFixture.VALIDATORS[1];

            PBFTConsensus.EquivocationEvidence same = evidence(v, "aa".repeat(32), 0);
            PBFTConsensus.EquivocationEvidence otherView = evidence(v, "bb".repeat(32), 1);   // re-vote in a new view is legal
            PBFTConsensus.EquivocationEvidence forged = evidence(v, "bb".repeat(32), 0);
            forged.signatureB = sig(PBFTConsensus.Phase.COMMIT, 0, 7, "bb".repeat(32), LedgerPoCFixture.VALIDATORS[3]);
            PBFTConsensus.EquivocationEvidence outsider = evidence(new TestKeyPair(8899), "bb".repeat(32), 0);

            for (PBFTConsensus.EquivocationEvidence ev : List.of(same, otherView, forged, outsider)) {
                assertThatThrownBy(() -> f.chain.validateTransaction(tx(reporter, ev, fee, 1)))
                        .hasMessageContaining("Invalid equivocation evidence");
            }
            assertThat(f.chain.getState().getJailedValidators()).isEmpty();
        }
    }

    @Test
    @DisplayName("HC-078: a locally observed slash no longer changes which commit certificates are valid")
    void localSlashDoesNotAffectCertificates() throws Exception {
        try (LedgerPoCFixture f = new LedgerPoCFixture()) {
            assertThat(f.consensus.getSlashedValidators()).isEmpty();
            assertThat(f.consensus.getJailedValidators()).isEmpty();
            TestKeyPair v = LedgerPoCFixture.VALIDATORS[1];
            long view = f.consensus.getViewNumber();
            String a = "aa".repeat(32), b = "bb".repeat(32);
            f.consensus.addCommitVote(5, a, v.getAddress(), HexUtils.decode(sig(PBFTConsensus.Phase.COMMIT, view, 5, a, v)));
            f.consensus.addCommitVote(5, b, v.getAddress(), HexUtils.decode(sig(PBFTConsensus.Phase.COMMIT, view, 5, b, v)));
            assertThat(f.consensus.getSlashedValidators()).contains(v.getAddress());   // local observation only

            Block blk = f.build(List.of(), 0);
            List<Block.CommitSignature> cert = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                TestKeyPair k = LedgerPoCFixture.VALIDATORS[i];
                cert.add(new Block.CommitSignature(k.getAddress(), view, sig(PBFTConsensus.Phase.COMMIT, view, blk.getIndex(), blk.getHash(), k)));
            }
            blk.setCommitCertificate(cert);   // includes the locally slashed validator 1
            assertThat(f.consensus.verifyCommitCertificate(blk)).isTrue();
        }
    }
}
