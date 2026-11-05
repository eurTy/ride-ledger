package com.rideledger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;

/** No Android dependencies: deterministic Wi-Fi preference, failover and bounded retry backoff. */
final class PeerRoutePolicy {
    enum Channel { WIFI, BLUETOOTH, HOTSPOT }
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
        long revision = -1;
        State(Endpoint endpoint) { this.endpoint = endpoint; }
    }
    private final LongSupplier clock;
    private final Map<String, State> states = new LinkedHashMap<>();
    PeerRoutePolicy(LongSupplier clock) { this.clock = clock; }
    synchronized boolean offer(Endpoint endpoint) {
        State old = states.get(endpoint.key);
        if (old != null) {
            if (endpoint.channel != old.endpoint.channel && !old.busy) {
                states.put(endpoint.key, new State(endpoint));
                return true;
            }
            if (endpoint.channel != old.endpoint.channel) return false;
            if (!PeerIdentity.valid(endpoint.peerId)) endpoint.peerId = old.endpoint.peerId;
            endpoint.modern |= old.endpoint.modern;
            old.endpoint = endpoint;
            return false;
        }
        // A new address for the same authenticated/advertised device replaces stale LAN coordinates.
        if (PeerIdentity.valid(endpoint.peerId)) states.entrySet().removeIf(entry -> !entry.getValue().busy &&
                entry.getValue().endpoint.channel == endpoint.channel && endpoint.peerId.equals(entry.getValue().endpoint.peerId));
        if (states.size() >= 16) return false;
        states.put(endpoint.key, new State(endpoint));
        return true;
    }
    synchronized void remove(String key) { states.remove(key); }
    synchronized void removeChannel(Channel channel) { states.entrySet().removeIf(value -> value.getValue().endpoint.channel == channel); }
    synchronized void remember(String key, String peerId, boolean modern) {
        if (!PeerIdentity.valid(peerId)) return;
        State state = states.get(key);
        if (state != null) { state.endpoint.peerId = peerId; state.endpoint.modern |= modern; }
    }
    synchronized List<Endpoint> ready(boolean urgent) {
        return ready(urgent, -1, "");
    }
    synchronized List<Endpoint> readyForRevision(long revision, String onlyPeer) {
        return ready(false, revision, onlyPeer);
    }
    private List<Endpoint> ready(boolean urgent, long revision, String onlyPeer) {
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
            if (last == null || value.endpoint.channel.ordinal() < last.endpoint.channel.ordinal()) lastSuccess.put(peer, value);
        }
        for (State value : states.values()) {
            String peer = group(value.endpoint);
            if (!onlyPeer.isEmpty() && !onlyPeer.equals(peer)) continue;
            if (value.busy || busyPeers.containsKey(peer) || now < value.retryAt) continue;
            State last = lastSuccess.get(peer);
            if (!urgent && last != null && (revision < 0 || last.endpoint.channel.ordinal() <= value.endpoint.channel.ordinal())) continue;
            State old = selected.get(peer);
            if (old == null || value.endpoint.channel.ordinal() < old.endpoint.channel.ordinal()) selected.put(peer, value);
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
            state.failures = 0;
            state.retryAt = 0;
            state.succeededAt = clock.getAsLong();
            state.revision = Math.max(state.revision, revision);
        }
    }
    synchronized void received(String peer, Channel channel, long revision) {
        for (State state : states.values()) {
            if (!group(state.endpoint).equals(peer) || state.endpoint.channel != channel) continue;
            state.succeededAt = clock.getAsLong();
            state.revision = Math.max(state.revision, revision);
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
    synchronized void clear() { states.clear(); }
    private String group(Endpoint endpoint) { return PeerIdentity.valid(endpoint.peerId) ? endpoint.peerId : endpoint.key; }
}
