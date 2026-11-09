package com.rideledger;

import android.content.Context;

/** Owns the foreground LAN/hotspot/BLE coordinator. */
final class TeamTransportAdapter {
    private final Context context;
    private final LedgerStore store;
    private final PeerSync.Listener listener;
    private final boolean enrollmentPage;
    PeerSync nearby;
    private String route = "";

    TeamTransportAdapter(Context context, LedgerStore store, PeerSync.Listener listener) {
        this(context, store, listener, false);
    }

    TeamTransportAdapter(Context context, LedgerStore store, PeerSync.Listener listener, boolean enrollmentPage) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.listener = listener;
        this.enrollmentPage = enrollmentPage;
    }

    void select(LedgerStore.Trip trip) {
        String next = trip == null || trip.team == null || trip.people() < 2 || (!enrollmentPage && !store.teamReady(trip))
                ? "" : trip.id;
        if (route.equals(next)) return;
        stop();
        if (next.isEmpty()) return;
        route = next;
        nearby = new PeerSync(context, store, listener, true, trip);
        nearby.start();
    }

    String error() {
        return nearby == null ? "" : nearby.admissionError();
    }

    SyncEngine.Status syncState() {
        return nearby == null ? SyncEngine.Status.EMPTY : nearby.syncState();
    }

    void recordsChanged() { if (nearby != null) nearby.syncNow(); }
    void reconnect() { if (nearby != null) nearby.reconnect(); }

    void permissionsChanged() { if (nearby != null) nearby.refreshBluetooth(); }

    boolean receivingInitialHistory() { return nearby != null && nearby.enrollingNearby(); }
    boolean initialHistoryReceived() { return nearby != null && nearby.initialHistoryReceived(); }

    void stop() {
        if (nearby != null) nearby.stop();
        nearby = null;
        route = "";
    }
}
