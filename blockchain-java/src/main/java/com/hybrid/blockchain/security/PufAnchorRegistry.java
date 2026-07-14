package com.hybrid.blockchain.security;

import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.Storage;

/**
 * PUFchain-style on-chain anchoring of device identity.
 *
 * <p>At provisioning, a hash of the device's PUF response ({@link PUFIdentityProvider#computeAnchor})
 * is written to the ledger keyed by device id. On every subsequent authentication the device's
 * freshly-read PUF response is re-hashed and compared against the anchored value. Because the
 * anchor lives on the tamper-evident ledger rather than only in local memory, a cloned device
 * (different silicon fingerprint) or an attacker who swaps the local key store cannot pass
 * verification — the identity is bound to the chain, not to the node.
 *
 * <p>Only the one-way anchor is stored; the raw PUF response never leaves the device.
 */
public final class PufAnchorRegistry {

    private static final String KEY_PREFIX = "puf:anchor:";

    private PufAnchorRegistry() {}

    /**
     * Anchors a device's PUF identity at provisioning time.
     *
     * <p>Idempotent for the same device+response. If the device is already anchored with a
     * <b>different</b> response, this refuses to overwrite (re-provisioning a device to a new
     * fingerprint is a privileged, out-of-band operation — silently overwriting would erase
     * the tamper-evidence this whole mechanism provides).
     *
     * @return the stored anchor (hex)
     * @throws IllegalStateException if a conflicting anchor already exists
     */
    public static String anchorIdentity(String deviceId, byte[] pufResponse, Storage storage) {
        String anchor = HexUtils.encode(PUFIdentityProvider.computeAnchor(pufResponse));
        String existing = storage != null ? storage.get(key(deviceId), String.class) : null;
        if (existing != null) {
            if (!existing.equals(anchor)) {
                throw new IllegalStateException(
                        "Device " + deviceId + " already anchored to a different PUF identity");
            }
            return existing; // idempotent re-anchor with same fingerprint
        }
        if (storage != null) {
            storage.put(key(deviceId), anchor);
        }
        return anchor;
    }

    /**
     * Verifies a device's freshly-read PUF response against its on-chain anchor.
     *
     * @return true iff an anchor exists for the device and it matches this response
     */
    public static boolean verifyIdentity(String deviceId, byte[] pufResponse, Storage storage) {
        if (storage == null) return false;
        String existing = storage.get(key(deviceId), String.class);
        if (existing == null) return false;
        String anchor = HexUtils.encode(PUFIdentityProvider.computeAnchor(pufResponse));
        return constantTimeEquals(existing, anchor);
    }

    public static boolean isAnchored(String deviceId, Storage storage) {
        return storage != null && storage.get(key(deviceId), String.class) != null;
    }

    private static String key(String deviceId) {
        return KEY_PREFIX + deviceId;
    }

    /** Length-independent constant-time compare to avoid leaking match progress via timing. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
        return r == 0;
    }
}
