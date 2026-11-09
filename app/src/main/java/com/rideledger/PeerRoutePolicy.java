package com.rideledger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;

/** No Android dependencies: deterministic Wi-Fi preference, failover and bounded retry backoff. */
final class PeerRoutePolicy {
    static final long ENROLLMENT_STAGE_MILLIS = 1500;
    private static final long REDISCOVERY_QUIET = 30000;
    enum Channel { WIFI, BLUETOOTH, HOTSPOT }
    private enum Phase { LEGACY, ENROLLMENT, LEDGER }
    static final class Endpoint {
        final String key;
        final Channel channel;
        final Callable<PeerConnection> dial;
        String peerId;
        boolean modern;
        Endpoint(String key, Channel channel, String peerId, boolean modern, Callable<PeerConnection> dial) {
            this.key = key; this.channel = channel; this.peerId = peerId == null ? "" : peerId;
            this.modern = modern; this.dial = dial;
        }
    }
    private static final class State {
        Endpoint endpoint; int failures; long retryAt, succeededAt; boolean busy;
        Endpoint attempt;
        long revision = -1;
        long lastSeen;
        State(Endpoint endpoint, long now) { this.endpoint = endpoint; lastSeen = now; }
    }
    private final LongSupplier clock;
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Integer> syncFailures = new HashMap<>();
    PeerRoutePolicy(LongSupplier clock) { this.clock = clock; }
    synchronized boolean offer(Endpoint endpoint) {
        long now = clock.getAsLong();
        State old = states.get(endpoint.key);
        if (old != null) {
            // DHCP can reuse the same IP and port for a different team device. An old
            // connection must never confirm work for the newly discovered identity.
            if (PeerIdentity.valid(endpoint.peerId) && PeerIdentity.valid(old.endpoint.peerId) &&
                    !endpoint.peerId.equals(old.endpoint.peerId)) {
                states.put(endpoint.key, new State(endpoint, now));
                syncFailures.remove(group(endpoint));
                return true;
            }
            if (endpoint.channel != old.endpoint.channel && !old.busy) {
                states.put(endpoint.key, new State(endpoint, now));
                syncFailures.remove(group(endpoint));
                return true;
            }
            if (endpoint.channel != old.endpoint.channel) return false;
            if (!PeerIdentity.valid(endpoint.peerId)) endpoint.peerId = old.endpoint.peerId;
            endpoint.modern |= old.endpoint.modern;
            old.endpoint = endpoint;
            boolean rediscovered = !old.busy && now - old.lastSeen >= REDISCOVERY_QUIET &&
                    syncFailures.getOrDefault(group(endpoint), 0) >= 4;
            old.lastSeen = now;
            if (rediscovered) {
                syncFailures.remove(group(endpoint));
                old.retryAt = 0;
            }
            return rediscovered;
        }
        // A new address for the same authenticated/advertised device replaces stale LAN coordinates.
        if (PeerIdentity.valid(endpoint.peerId)) states.entrySet().removeIf(entry -> !entry.getValue().busy &&
                entry.getValue().endpoint.channel == endpoint.channel && endpoint.peerId.equals(entry.getValue().endpoint.peerId));
        if (states.size() >= 16) return false;
        states.put(endpoint.key, new State(endpoint, now));
        // A newly advertised address can be a recovered link with a different port.
        syncFailures.remove(group(endpoint));
        return true;
    }
    synchronized void remove(String key) {
        State removed = states.remove(key);
        if (removed != null) syncFailures.remove(group(removed.endpoint));
    }
    synchronized void removeChannel(Channel channel) {
        states.entrySet().removeIf(value -> {
            if (value.getValue().endpoint.channel != channel) return false;
            syncFailures.remove(group(value.getValue().endpoint));
            return true;
        });
    }
    synchronized void remember(String key, String peerId, boolean modern) {
        if (!PeerIdentity.valid(peerId)) return;
        State state = states.get(key);
        if (state != null) { state.endpoint.peerId = peerId; state.endpoint.modern |= modern; }
    }
    synchronized List<Endpoint> ready(boolean urgent) {
        return ready(urgent, -1, "", 2, Phase.LEGACY);
    }
    synchronized List<Endpoint> readyForRevision(long revision, String onlyPeer) {
        List<Endpoint> result = new ArrayList<>();
        for (State state : syncCandidates(revision, onlyPeer).values()) {
            if (clock.getAsLong() < state.retryAt) continue;
            state.busy = true;
            state.attempt = state.endpoint;
            result.add(state.endpoint);
        }
        return result;
    }
    synchronized long nextSyncRetry(long revision) {
        long next = Long.MAX_VALUE;
        for (State state : syncCandidates(revision, "").values()) next = Math.min(next, state.retryAt);
        return next;
    }
    synchronized void syncFailure(Endpoint endpoint) {
        State state = states.get(endpoint.key);
        if (state == null || state.attempt != endpoint) return;
        state.busy = false;
        state.attempt = null;
        String peer = group(endpoint);
        int failures = syncFailures.getOrDefault(peer, 0) + 1;
        syncFailures.put(peer, failures);
        state.retryAt = clock.getAsLong() + (2000L << Math.min(2, failures - 1));
    }
    synchronized void syncSuccess(Endpoint endpoint, long revision) {
        State state = states.get(endpoint.key);
        if (state != null && state.attempt == endpoint) {
            syncFailures.remove(group(endpoint));
            success(endpoint.key, revision);
        }
    }
    synchronized void newSyncRequest() {
        syncFailures.clear();
        resetBackoff();
    }
    synchronized int syncing() {
        int count = 0;
        for (State state : states.values()) if (state.busy) count++;
        return count;
    }
    synchronized Set<String> onlinePeers() {
        Set<String> peers = new HashSet<>();
        long now = clock.getAsLong();
        for (State state : states.values()) {
            String peer = group(state.endpoint);
            if (syncFailures.getOrDefault(peer, 0) < 4 && (state.busy || state.retryAt <= now)) peers.add(peer);
        }
        return peers;
    }
    private Map<String, State> syncCandidates(long revision, String onlyPeer) {
        Map<String, State> candidates = new LinkedHashMap<>();
        Map<String, Integer> completed = new HashMap<>();
        Map<String, Boolean> busy = new HashMap<>();
        long now = clock.getAsLong();
        for (State state : states.values()) {
            String peer = group(state.endpoint);
            if (state.busy) busy.put(peer, true);
            if (state.revision >= revision) completed.merge(peer, rank(state.endpoint.channel, Phase.LEDGER), Math::min);
        }
        for (State state : states.values()) {
            String peer = group(state.endpoint);
            int rank = rank(state.endpoint.channel, Phase.LEDGER);
            if ((!onlyPeer.isEmpty() && !onlyPeer.equals(peer)) || busy.containsKey(peer) ||
                    syncFailures.getOrDefault(peer, 0) >= 4 || completed.getOrDefault(peer, 2) <= rank) continue;
            State previous = candidates.get(peer);
            if (previous == null || prefer(state, previous, now)) candidates.put(peer, state);
        }
        return candidates;
    }
    private static boolean prefer(State candidate, State previous, long now) {
        boolean available = candidate.retryAt <= now, previousAvailable = previous.retryAt <= now;
        if (available != previousAvailable) return available;
        if (!available && candidate.retryAt != previous.retryAt) return candidate.retryAt < previous.retryAt;
        return rank(candidate.endpoint.channel, Phase.LEDGER) < rank(previous.endpoint.channel, Phase.LEDGER);
    }
    synchronized List<Endpoint> readyForEnrollment(long revision, String issuer, int maximumRank) {
        return ready(false, revision, issuer, maximumRank, Phase.ENROLLMENT);
    }
    synchronized long nextEnrollmentWake(String issuer, long discoveryStarted) {
        if (busy(issuer)) return Long.MAX_VALUE;
        long now = clock.getAsLong(), next = Long.MAX_VALUE;
        for (State state : states.values()) {
            if (!issuer.equals(group(state.endpoint))) continue;
            long stage = discoveryStarted + ENROLLMENT_STAGE_MILLIS * rank(state.endpoint.channel, Phase.ENROLLMENT);
            long wake = Math.max(stage, state.retryAt);
            if (wake > now) next = Math.min(next, wake);
        }
        return next;
    }
    synchronized boolean busy(String peer) {
        return states.values().stream().anyMatch(state -> state.busy && peer.equals(group(state.endpoint)));
    }
    private static int rank(Channel channel, Phase phase) {
        if (phase == Phase.LEDGER) return channel == Channel.BLUETOOTH ? 1 : 0;
        if (phase == Phase.LEGACY || channel == Channel.WIFI) return channel.ordinal();
        return channel == Channel.HOTSPOT ? 1 : 2;
    }
    private List<Endpoint> ready(boolean urgent, long revision, String onlyPeer, int maximumRank, Phase phase) {
        long now = clock.getAsLong(); Map<String, State> selected = new HashMap<>();
        Map<String, Boolean> busyPeers = new HashMap<>(); Map<String, State> lastSuccess = new HashMap<>();
        // A group peer may only be discoverable in one direction. Poll its manifest so that
        // changes relayed to it still reach the device that can initiate the connection.
        long idlePoll = revision < 0 ? 30000 : 8000;
        for (State value : states.values()) {
            String peer = group(value.endpoint);
            if (value.busy) busyPeers.put(peer, true);
            if (value.succeededAt <= 0 || value.revision < revision || now - value.succeededAt >= idlePoll) continue;
            State last = lastSuccess.get(peer);
            if (last == null || rank(value.endpoint.channel, phase) < rank(last.endpoint.channel, phase)) lastSuccess.put(peer, value);
        }
        for (State value : states.values()) {
            String peer = group(value.endpoint);
            if (!onlyPeer.isEmpty() && !onlyPeer.equals(peer)) continue;
            if (rank(value.endpoint.channel, phase) > maximumRank) continue;
            if (value.busy || busyPeers.containsKey(peer) || now < value.retryAt) continue;
            State last = lastSuccess.get(peer);
            if (!urgent && last != null && (revision < 0 || rank(last.endpoint.channel, phase) <= rank(value.endpoint.channel, phase))) continue;
            State old = selected.get(peer);
            if (old == null || rank(value.endpoint.channel, phase) < rank(old.endpoint.channel, phase)) selected.put(peer, value);
        }
        List<Endpoint> result = new ArrayList<>();
        for (State value : selected.values()) { value.busy = true; result.add(value.endpoint); }
        return result;
    }
    synchronized void success(String key) {
        success(key, -1);
    }
    synchronized void success(String key, long revision) {
        State state = states.get(key);
        if (state != null) {
            state.busy = false;
            state.attempt = null;
            state.failures = 0;
            state.retryAt = 0;
            state.succeededAt = clock.getAsLong();
            state.revision = Math.max(state.revision, revision);
        }
    }
    synchronized void received(String peer, Channel channel, long revision) {
        syncFailures.remove(peer);
        for (State state : states.values()) {
            if (!group(state.endpoint).equals(peer) || state.endpoint.channel != channel) continue;
            state.succeededAt = clock.getAsLong();
            state.revision = Math.max(state.revision, revision);
            state.retryAt = 0;
        }
    }
    synchronized void failure(String key) {
        State state = states.get(key);
        if (state != null) {
            state.busy = false; state.failures = Math.min(5, state.failures + 1);
            state.retryAt = clock.getAsLong() + Math.min(60000, 5000L << (state.failures - 1));
        }
    }
    synchronized void resetBackoff() { for (State state : states.values()) state.retryAt = 0; }
    synchronized void clear() { states.clear(); syncFailures.clear(); }
    private String group(Endpoint endpoint) { return PeerIdentity.valid(endpoint.peerId) ? endpoint.peerId : endpoint.key; }
}
