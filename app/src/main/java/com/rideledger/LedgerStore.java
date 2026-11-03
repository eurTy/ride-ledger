package com.rideledger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class LedgerStore extends SQLiteOpenHelper {
    static final class Trip {
        final String id, name, personA, personB, secret, localPerson;

        Trip(String id, String name, String personA, String personB, String secret, String localPerson) {
            this.id = id;
            this.name = name;
            this.personA = personA;
            this.personB = personB;
            this.secret = secret;
            this.localPerson = localPerson;
        }

        String nameOf(String person) { return "a".equals(person) ? personA : personB; }
    }

    static final class Event {
        final String id, tripId, kind;
        final long at;
        final JSONObject data;

        Event(String id, String tripId, String kind, long at, JSONObject data) {
            this.id = id;
            this.tripId = tripId;
            this.kind = kind;
            this.at = at;
            this.data = data;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("tripId", tripId).put("kind", kind)
                    .put("at", at).put("data", data);
        }

        static Event from(JSONObject json) throws JSONException {
            return new Event(json.getString("id"), json.getString("tripId"),
                    json.getString("kind"), json.getLong("at"), json.getJSONObject("data"));
        }
    }

    static final class Summary {
        final Map<String, Long> cashA = new HashMap<>();
        final Map<String, Long> cashB = new HashMap<>();
        final Map<String, Long> spent = new HashMap<>();
        // Positive means B owes A; negative means A owes B.
        final Map<String, Long> debtBToA = new HashMap<>();
        final List<Event> visible = new ArrayList<>();
    }

    LedgerStore(Context context) { super(context, "ride_ledger.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips (id TEXT PRIMARY KEY, name TEXT NOT NULL, person_a TEXT NOT NULL, " +
                "person_b TEXT NOT NULL, secret TEXT NOT NULL, local_person TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, trip_id TEXT NOT NULL, kind TEXT NOT NULL, " +
                "at INTEGER NOT NULL, data TEXT NOT NULL)");
        db.execSQL("CREATE INDEX events_trip ON events(trip_id, at, id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("尚未定义数据库升级路径");
    }

    synchronized Trip createTrip(String name, String personA, String personB) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Trip trip = new Trip(UUID.randomUUID().toString(), name.trim(), personA.trim(), personB.trim(),
                Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), "a");
        saveTrip(trip);
        return trip;
    }

    synchronized Trip joinTrip(String invite) throws JSONException {
        String clean = invite.trim();
        byte[] bytes = Base64.decode(clean, Base64.URL_SAFE | Base64.NO_WRAP);
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        Trip trip = new Trip(json.getString("id"), json.getString("name"), json.getString("personA"),
                json.getString("personB"), json.getString("secret"), "b");
        if (getTrip(trip.id) != null) throw new IllegalArgumentException("这个旅行已加入");
        if (Base64.decode(trip.secret, Base64.URL_SAFE | Base64.NO_WRAP).length != 32)
            throw new IllegalArgumentException("邀请码无效");
        saveTrip(trip);
        return trip;
    }

    synchronized String inviteCode(Trip trip) throws JSONException {
        JSONObject json = new JSONObject().put("id", trip.id).put("name", trip.name)
                .put("personA", trip.personA).put("personB", trip.personB).put("secret", trip.secret);
        return Base64.encodeToString(json.toString().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private void saveTrip(Trip trip) {
        ContentValues values = new ContentValues();
        values.put("id", trip.id);
        values.put("name", trip.name);
        values.put("person_a", trip.personA);
        values.put("person_b", trip.personB);
        values.put("secret", trip.secret);
        values.put("local_person", trip.localPerson);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("trips", null, values);
    }

    synchronized List<Trip> trips() {
        List<Trip> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,person_a,person_b,secret,local_person FROM trips ORDER BY created_at DESC", null)) {
            while (c.moveToNext()) result.add(readTrip(c));
        }
        return result;
    }

    synchronized Trip getTrip(String id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,person_a,person_b,secret,local_person FROM trips WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? readTrip(c) : null;
        }
    }

    private Trip readTrip(Cursor c) {
        return new Trip(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                c.getString(4), c.getString(5));
    }

    synchronized Event add(String tripId, String kind, JSONObject data) {
        Event event = new Event(UUID.randomUUID().toString(), tripId, kind, System.currentTimeMillis(), data);
        insertEvent(getWritableDatabase(), event);
        return event;
    }

    private long insertEvent(SQLiteDatabase db, Event event) {
        ContentValues values = new ContentValues();
        values.put("id", event.id);
        values.put("trip_id", event.tripId);
        values.put("kind", event.kind);
        values.put("at", event.at);
        values.put("data", event.data.toString());
        return db.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    synchronized int merge(String tripId, JSONArray incoming) throws JSONException {
        if (getTrip(tripId) == null) return 0;
        SQLiteDatabase db = getWritableDatabase();
        int inserted = 0;
        db.beginTransaction();
        try {
            for (int i = 0; i < incoming.length(); i++) {
                Event event = Event.from(incoming.getJSONObject(i));
                if (!tripId.equals(event.tripId)) continue;
                normalizeCurrency(event.data, "currency");
                normalizeCurrency(event.data, "fromCurrency");
                normalizeCurrency(event.data, "toCurrency");
                if (!validEvent(event)) continue;
                if (insertEvent(db, event) != -1) inserted++;
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return inserted;
    }

    private void normalizeCurrency(JSONObject data, String field) throws JSONException {
        if ("RMB".equalsIgnoreCase(data.optString(field))) data.put(field, "CNY");
    }

    private boolean validEvent(Event event) {
        try {
            JSONObject d = event.data;
            switch (event.kind) {
                case "opening": case "topup":
                    return person(d.getString("person")) && currency(d.getString("currency")) && d.getLong("amount") > 0;
                case "expense":
                    int share = d.getInt("shareA");
                    return person(d.getString("payer")) && currency(d.getString("currency"))
                            && d.getLong("amount") > 0 && share >= 0 && share <= 10000;
                case "transfer":
                    return person(d.getString("from")) && currency(d.getString("currency")) && d.getLong("amount") > 0;
                case "exchange":
                    return person(d.getString("person")) && currency(d.getString("fromCurrency"))
                            && currency(d.getString("toCurrency")) && d.getLong("fromAmount") > 0
                            && d.getLong("toAmount") > 0;
                case "delete":
                    return !d.getString("target").isEmpty();
                default: return false;
            }
        } catch (Exception ignored) { return false; }
    }

    private boolean person(String value) { return "a".equals(value) || "b".equals(value); }
    private boolean currency(String value) {
        try { return Money.code(value).equals(value); } catch (Exception ignored) { return false; }
    }

    synchronized JSONArray eventsJson(String tripId) throws JSONException {
        JSONArray result = new JSONArray();
        for (Event event : events(tripId)) result.put(event.json());
        return result;
    }

    synchronized List<Event> events(String tripId) {
        List<Event> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,kind,at,data FROM events WHERE trip_id=? ORDER BY at,id", new String[]{tripId})) {
            while (c.moveToNext()) {
                try { result.add(new Event(c.getString(0), tripId, c.getString(1), c.getLong(2),
                        new JSONObject(c.getString(3)))); }
                catch (JSONException ignored) { }
            }
        }
        return result;
    }

    synchronized Summary summary(String tripId) {
        Summary summary = new Summary();
        List<Event> all = events(tripId);
        Set<String> deleted = new HashSet<>();
        for (Event event : all) if ("delete".equals(event.kind)) deleted.add(event.data.optString("target"));
        for (Event event : all) {
            if ("delete".equals(event.kind) || deleted.contains(event.id)) continue;
            JSONObject d = event.data;
            try {
                switch (event.kind) {
                    case "opening": case "topup":
                        cash(summary, d.getString("person"), d.getString("currency"), d.getLong("amount"));
                        break;
                    case "expense": {
                        String payer = d.getString("payer"), currency = d.getString("currency");
                        long amount = d.getLong("amount");
                        long aShare = Money.shareA(amount, d.getInt("shareA"));
                        cash(summary, payer, currency, -amount);
                        add(summary.spent, currency, amount);
                        add(summary.debtBToA, currency, "a".equals(payer) ? amount - aShare : -aShare);
                        break;
                    }
                    case "transfer": {
                        String from = d.getString("from"), currency = d.getString("currency");
                        long amount = d.getLong("amount");
                        cash(summary, from, currency, -amount);
                        cash(summary, "a".equals(from) ? "b" : "a", currency, amount);
                        add(summary.debtBToA, currency, "b".equals(from) ? -amount : amount);
                        break;
                    }
                    case "exchange": {
                        String person = d.getString("person");
                        cash(summary, person, d.getString("fromCurrency"), -d.getLong("fromAmount"));
                        cash(summary, person, d.getString("toCurrency"), d.getLong("toAmount"));
                        break;
                    }
                }
                summary.visible.add(event);
            } catch (Exception ignored) { }
        }
        Collections.reverse(summary.visible);
        return summary;
    }

    private void cash(Summary summary, String person, String currency, long delta) {
        add("a".equals(person) ? summary.cashA : summary.cashB, currency, delta);
    }

    private void add(Map<String, Long> map, String currency, long delta) {
        map.put(currency, Math.addExact(map.getOrDefault(currency, 0L), delta));
    }
}
