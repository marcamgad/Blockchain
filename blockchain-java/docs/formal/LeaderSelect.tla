---------------------------- MODULE LeaderSelect ----------------------------
(***************************************************************************)
(* Minimal model of PBFTConsensus.selectLeader for checking Leader          *)
(* Agreement (Definition 4.1 of docs/formal/pbft_leader_model.md).          *)
(*                                                                         *)
(* Two instantiations are checked (see LeaderSelect_Broken.cfg /            *)
(* LeaderSelect_Fixed.cfg):                                                 *)
(*                                                                         *)
(*   Broken = TRUE   reputation may be updated by a LOCAL action enabled    *)
(*                   independently at a single node  (models                *)
(*                   triggerViewChange() firing on one node's local timer)  *)
(*                                                                         *)
(*   Broken = FALSE  reputation is updated only by a CONSENSUS action that  *)
(*                   applies to every correct node atomically (models a     *)
(*                   penalty applied at a 2f+1 boundary)                    *)
(*                                                                         *)
(* Expected result: TLC reports an invariant violation for Broken and no    *)
(* violation for Fixed within the bounded space.  A bounded no-violation    *)
(* result is EVIDENCE, not a proof of unbounded correctness.                *)
(*                                                                         *)
(* Modelling notes:                                                        *)
(*  - Reputation is a SCALED INTEGER (InitRep = 100 models 1.00,            *)
(*    Penalty = 10 models REP_MISSED_SLOT = -0.10).  Integers deliberately  *)
(*    sidestep the IEEE-754 non-associativity issue of Remark 7.1, which is *)
(*    a separate defect and out of scope for this model.                    *)
(*  - The implementation's LCG fraction phi(v) in [0,1) is replaced by a    *)
(*    universally-quantified rational kk/Denom.  Theorem 1 only needs the   *)
(*    EXISTENCE of a view whose phi falls in the divergence interval, and   *)
(*    quantifying over all kk covers the fraction space at resolution       *)
(*    1/Denom.  This is a faithful over-approximation: any real phi lies in *)
(*    some 1/Denom bucket.                                                  *)
(***************************************************************************)
EXTENDS Integers, Sequences, FiniteSets

CONSTANTS
    Nodes,      \* set of CORRECT nodes computing a leader, e.g. {"p1","p2"}
    NumVals,    \* number of validators (4 or 7)
    InitRep,    \* initial reputation, scaled integer (100 = 1.00)
    Penalty,    \* penalty magnitude, scaled integer (10 = 0.10)
    Denom,      \* resolution of the view-derived fraction phi
    MaxSteps,   \* bound on number of penalty events (state-space bound)
    Broken      \* TRUE = local per-node penalty; FALSE = consensus-ordered

VARIABLES
    rep,        \* rep[n][i] : reputation node n assigns to validator i
    steps       \* number of penalty events applied so far

vars == <<rep, steps>>

Indices == 1..NumVals

(* Cumulative weight of validators 1..k as seen by node n.                  *)
(* Mirrors the running `cumulative` accumulator of selectLeader step 6.     *)
RECURSIVE SumTo(_, _)
SumTo(n, k) == IF k = 0 THEN 0 ELSE SumTo(n, k - 1) + rep[n][k]

Total(n) == SumTo(n, NumVals)

(* First index whose cumulative weight reaches the target kk/Denom * Total. *)
(* Cross-multiplied to stay in integer arithmetic:                          *)
(*     cumulative >= phi * Total   <=>   cumulative * Denom >= kk * Total    *)
Leader(n, kk) ==
    CHOOSE i \in Indices :
        /\ SumTo(n, i) * Denom >= kk * Total(n)
        /\ \A j \in Indices :
              j < i => ~(SumTo(n, j) * Denom >= kk * Total(n))

--------------------------------------------------------------------------

\* Bounded on purpose: TLC cannot enumerate the infinite set Int.  Reputation only
\* ever decreases from InitRep, and the guard rep > Penalty keeps it strictly positive.
TypeOK ==
    /\ rep \in [Nodes -> [Indices -> 0..InitRep]]
    /\ steps \in 0..MaxSteps

Init ==
    /\ rep = [n \in Nodes |-> [i \in Indices |-> InitRep]]
    /\ steps = 0

(* BROKEN: a single node applies the penalty on its own (local timeout).    *)
LocalPenalty(n, i) ==
    /\ rep[n][i] > Penalty
    /\ rep' = [rep EXCEPT ![n][i] = @ - Penalty]
    /\ steps' = steps + 1

(* FIXED: the penalty is consensus-ordered, so every correct node applies   *)
(* it atomically as part of the same committed event.                       *)
CommitPenalty(i) ==
    /\ \A n \in Nodes : rep[n][i] > Penalty
    /\ rep' = [n \in Nodes |-> [rep[n] EXCEPT ![i] = @ - Penalty]]
    /\ steps' = steps + 1

Next ==
    /\ steps < MaxSteps
    /\ IF Broken
         THEN \E n \in Nodes, i \in Indices : LocalPenalty(n, i)
         ELSE \E i \in Indices : CommitPenalty(i)

Spec == Init /\ [][Next]_vars

--------------------------------------------------------------------------

(* Definition 4.1: all correct nodes compute the same leader for the same  *)
(* view.  Quantified over every fraction bucket kk.                        *)
LeaderAgreement ==
    \A kk \in 0..(Denom - 1) :
        \A n1, n2 \in Nodes : Leader(n1, kk) = Leader(n2, kk)

(* State-space bound for TLC. *)
StepConstraint == steps <= MaxSteps

=============================================================================
