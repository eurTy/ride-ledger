package com.rideledger;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Aggregates the part of the journal confirmed by the trip's peers. */
final class TripStatistics {
    final LedgerStore.Trip trip;
    final LedgerReport report;
    final boolean pending, canVerifyTeam;

    private TripStatistics(LedgerStore.Trip trip, LedgerReport report, boolean pending, boolean canVerifyTeam) {
        this.trip = trip;
        this.report = report;
        this.pending = pending;
        this.canVerifyTeam = canVerifyTeam;
    }

    static TripStatistics load(Context context, LedgerStore store, String tripId) {
        LedgerStore.Snapshot snapshot = store.snapshot(tripId);
        LedgerStore.Trip trip = snapshot.trip;
        if (trip == null) throw new IllegalArgumentException("旅行不存在");

        boolean solo = trip.people() == 1;
        boolean canVerifyTeam = solo || (trip.team != null && store.teamReady(trip));
        long confirmedThrough = solo ? Long.MAX_VALUE : canVerifyTeam ? SyncEngine.confirmedThrough(context, store, trip) : 0;
        boolean pending = snapshot.syncPoint.row > confirmedThrough;
        if (pending) {
            // Project revisions after applying the boundary, so pending edits and deletions cannot change the totals.
            List<LedgerStore.Event> confirmed = new ArrayList<>();
            for (LedgerStore.Event event : snapshot.events) {
                if (event.localRow <= confirmedThrough) confirmed.add(event);
            }
            snapshot = new LedgerStore.Snapshot(trip, confirmed);
        }
        return new TripStatistics(trip, LedgerReport.from(snapshot), pending, canVerifyTeam);
    }
}
