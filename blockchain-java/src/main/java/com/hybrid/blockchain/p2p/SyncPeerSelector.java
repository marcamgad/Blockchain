package com.hybrid.blockchain.p2p;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [HC-096] Chooses the peer to request missing blocks from.
 *
 * <p>Catch-up used to ask the single peer with the highest self-reported height, and a claimed
 * height could only go up, so one Byzantine validator advertising a huge height and delivering
 * nothing kept every lagging node from catching up. Now:</p>
 * <ul>
 *   <li>the peer is chosen at random among those claiming to be ahead;</li>
 *   <li>a peer that was asked and delivered nothing by the next round, or whose blocks were
 *       rejected, is skipped for {@link #BACKOFF_MS} and its claimed height is forgotten.</li>
 * </ul>
 * <p>Heights are hints only; every synced block is still fully verified (HC-046, HC-068).</p>
 */
public final class SyncPeerSelector {

    public static final long BACKOFF_MS = 5 * 60_000L;

    private final Random random;
    private final Map<String, Long> backoffUntil = new ConcurrentHashMap<>();
    /** peer → the height we asked it for in the last round */
    private final Map<String, Long> pending = new ConcurrentHashMap<>();
    private final Map<String, PeerManager.PeerInfo> lastChosen = new ConcurrentHashMap<>();

    public SyncPeerSelector(Random random) {
        this.random = random;
    }

    /** Call at the start of each sync round: a peer asked last round without progress is backed off. */
    public void beforeRound(long localHeight, long now) {
        for (Map.Entry<String, Long> e : pending.entrySet()) {
            if (localHeight < e.getValue()) {
                backOff(e.getKey(), now);
            }
        }
        pending.clear();
    }

    public PeerManager.PeerInfo choose(Collection<PeerManager.PeerInfo> peers, long localHeight, long now) {
        List<PeerManager.PeerInfo> ahead = new ArrayList<>();
        for (PeerManager.PeerInfo p : peers) {
            Long until = backoffUntil.get(p.getId());
            if (until != null && now < until) continue;
            if (p.getBlockHeight() > localHeight) ahead.add(p);
        }
        if (ahead.isEmpty()) return null;
        PeerManager.PeerInfo chosen = ahead.get(random.nextInt(ahead.size()));
        lastChosen.put(chosen.getId(), chosen);
        return chosen;
    }

    public void onRequested(String peerId, long fromHeight) {
        pending.put(peerId, fromHeight);
    }

    /** A block from this peer failed verification. */
    public void onRejected(String peerId, long now) {
        pending.remove(peerId);
        backOff(peerId, now);
    }

    private void backOff(String peerId, long now) {
        backoffUntil.put(peerId, now + BACKOFF_MS);
        PeerManager.PeerInfo p = lastChosen.remove(peerId);
        if (p != null) p.setBlockHeight(0);   // forget the unproven claim
    }
}
