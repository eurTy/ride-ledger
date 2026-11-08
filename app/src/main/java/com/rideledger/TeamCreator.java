package com.rideledger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class TeamCreator {
    private final LedgerStore store;

    TeamCreator(LedgerStore store) { this.store = store; }

    LedgerStore.Trip create(String title, String organizer, int expected) throws Exception {
        if (expected < 1 || expected > Team.MAX_DEVICES) throw new IllegalArgumentException("预期人数须为 1 至 5 人");
        return store.createOpenTeam(title, organizer, expected);
    }

    List<String> roster(LedgerStore.Trip trip) throws Exception {
        JSONArray seats = store.teamSeats(trip.id);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < seats.length(); i++) {
            JSONObject seat = seats.getJSONObject(i);
            result.add(trip.team.openSeats ? seat.getString("name") : trip.nameOf(seat.getString("member")));
        }
        return result;
    }

    String invite(LedgerStore.Trip trip) throws Exception { return store.inviteCode(trip); }
}
