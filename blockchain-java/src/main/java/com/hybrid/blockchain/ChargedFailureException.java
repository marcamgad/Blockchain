package com.hybrid.blockchain;

/**
 * A transaction that was validly included and executed, but whose execution failed after its
 * fee and nonce were already charged (e.g. a contract running out of gas). Its state changes are
 * discarded, it gets a FAILED receipt, and its fee is paid to the proposer.
 *
 * <p>[HC-017] This is distinct from a transaction that cannot be applied at all (insufficient
 * balance, unknown token, malformed data): those make the whole block invalid, because their fee
 * was never debited and crediting it would create money.
 */
public class ChargedFailureException extends Exception {
    public ChargedFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
