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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class LedgerStore extends SQLiteOpenHelper {
    // Leaves room for a full 10,000-entry travel to accumulate safely in minor units.
    static final long MAX_TEAM_AMOUNT = Long.MAX_VALUE / 10000;
    static final class Trip {
        final String id, name, personA, personB, secret, localPerson;
        final Team team;
        final String joinName;
        final Map<String, String> seatNames;

        Trip(String id, String name, String personA, String personB, String secret, String localPerson) {
            this(id, name, personA, personB, secret, localPerson, null);
        }

        Trip(String id, String name, String personA, String personB, String secret, String localPerson, Team team) {
            this(id, name, personA, personB, secret, localPerson, team, "", Collections.emptyMap());
        }

        Trip(String id, String name, String personA, String personB, String secret, String localPerson,
             Team team, String joinName, Map<String, String> seatNames) {
            this.id = id;
            this.name = name;
            this.personA = personA;
            this.personB = personB;
            this.secret = secret;
            this.localPerson = localPerson;
            this.team = team;
            this.joinName = joinName;
            this.seatNames = seatNames;
        }

        int people() { return team == null ? 2 : team.size(); }
        Trip withKey(String key) { return new Trip(id, name, personA, personB, key, localPerson, team, joinName, seatNames); }
        String member(int index) { return team == null ? (index == 0 ? "a" : "b") : team.member(index); }
        int indexOf(String person) { return team == null ? ("a".equals(person) ? 0 : "b".equals(person) ? 1 : -1) : team.index(person); }
        String nameOf(String person) {
            int index = indexOf(person);
            if (index < 0) return "".equals(person) && !joinName.isEmpty() ? joinName : "未知成员";
            return team == null ? (index == 0 ? personA : personB) : seatNames.getOrDefault(person, team.names.get(index));
        }
    }

    static final class Event {
        final String id, tripId, kind;
        final long at, localRow;
        final JSONObject data;
        final List<JSONObject> financialOptions;
        final List<String> noteOptions;

        Event(String id, String tripId, String kind, long at, JSONObject data) {
            this(id, tripId, kind, at, data, 0);
        }

        Event(String id, String tripId, String kind, long at, JSONObject data, long localRow) {
            this(id, tripId, kind, at, data, localRow, Collections.emptyList(), Collections.emptyList());
        }

        Event(String id, String tripId, String kind, long at, JSONObject data, long localRow,
              List<JSONObject> financialOptions, List<String> noteOptions) {
            this.id = id;
            this.tripId = tripId;
            this.kind = kind;
            this.at = at;
            this.data = data;
            this.localRow = localRow;
            this.financialOptions = financialOptions;
            this.noteOptions = noteOptions;
        }

        boolean conflicted() { return !financialOptions.isEmpty() || !noteOptions.isEmpty(); }

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
        boolean incomplete;
        final Map<String, Long> cashA = new HashMap<>();
        final Map<String, Long> cashB = new HashMap<>();
        final Map<String, Long> spent = new HashMap<>();
        // Positive means B owes A; negative means A owes B.
        final Map<String, Long> debtBToA = new HashMap<>();
        final Map<String, Map<String, Long>> teamCash = new HashMap<>();
        final Map<String, Map<String, Long>> teamNet = new HashMap<>();
        final List<Event> visible = new ArrayList<>();
    }

    private static final String TRIP_COLUMNS = "id,name,person_a,person_b,secret,local_person,team,join_name";
    private static LedgerStore applicationStore;
    private final Context context;

    static synchronized LedgerStore application(Context context) {
        if (applicationStore == null) applicationStore = new LedgerStore(context.getApplicationContext());
        return applicationStore;
    }

    LedgerStore(Context context) {
        super(context, "ride_ledger.db", null, 8);
        this.context = context;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips (id TEXT PRIMARY KEY, name TEXT NOT NULL, person_a TEXT NOT NULL, " +
                "person_b TEXT NOT NULL, secret TEXT NOT NULL, local_person TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "team TEXT NOT NULL DEFAULT '', join_name TEXT NOT NULL DEFAULT '')");
        createEvents(db);
        createEventIndexes(db);
        createJournalMetadata(db);
        createSeats(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE trips ADD COLUMN team TEXT NOT NULL DEFAULT ''");
            createSeats(db);
        }
        if (oldVersion < 4) {
            db.execSQL("DELETE FROM events WHERE trip_id IN (SELECT id FROM trips WHERE team = '')");
            db.execSQL("DELETE FROM trips WHERE team = ''");
        }
        if (oldVersion < 5) {
            boolean present = false;
            try (Cursor columns = db.rawQuery("PRAGMA table_info(trips)", null)) {
                while (columns.moveToNext()) present |= "join_name".equals(columns.getString(1));
            }
            if (!present) db.execSQL("ALTER TABLE trips ADD COLUMN join_name TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 6) {
            try (Cursor pragma = db.rawQuery("PRAGMA secure_delete=ON", null)) { pragma.moveToFirst(); }
            Map<String, String> wrapped = new HashMap<>();
            try (Cursor rows = db.rawQuery("SELECT id,secret FROM trips WHERE team<>''", null)) {
                while (rows.moveToNext()) {
                    String id = rows.getString(0);
                    wrapped.put(id, TripSecrets.wrap(id, rows.getString(1)));
                }
            }
            for (Map.Entry<String, String> entry : wrapped.entrySet()) {
                ContentValues values = new ContentValues();
                values.put("secret", entry.getValue());
                db.update("trips", values, "id=?", new String[]{entry.getKey()});
            }
        }
        if (oldVersion < 7) {
            boolean present = false;
            try (Cursor columns = db.rawQuery("PRAGMA table_info(events)", null)) {
                while (columns.moveToNext()) present |= "target_id".equals(columns.getString(1));
            }
            if (!present) db.execSQL("ALTER TABLE events ADD COLUMN target_id TEXT");
            try (Cursor rows = db.rawQuery("SELECT id,data FROM events WHERE kind IN ('edit','delete')", null)) {
                while (rows.moveToNext()) {
                    ContentValues values = new ContentValues();
                    try { values.put("target_id", new JSONObject(rows.getString(1)).getString("target")); }
                    catch (JSONException error) { throw new IllegalStateException("无法读取旧账目修订", error); }
                    db.update("events", values, "id=?", new String[]{rows.getString(0)});
                }
            }
            indexTargets(db);
        }
        if (oldVersion < 8) {
            boolean hasSequence = false;
            try (Cursor columns = db.rawQuery("PRAGMA table_info(events)", null)) {
                while (columns.moveToNext()) hasSequence |= "seq".equals(columns.getString(1));
            }
            if (!hasSequence) {
                db.execSQL("ALTER TABLE events RENAME TO events_before_journal");
                createEvents(db);
                db.execSQL("INSERT INTO events(seq,id,trip_id,kind,at,data,target_id) " +
                        "SELECT rowid,id,trip_id,kind,at,data,target_id FROM events_before_journal ORDER BY rowid");
                db.execSQL("DROP TABLE events_before_journal");
                createEventIndexes(db);
            }
            createJournalMetadata(db);
        }
    }

    private void createEvents(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, " +
                "trip_id TEXT NOT NULL, kind TEXT NOT NULL, at INTEGER NOT NULL, data TEXT NOT NULL, target_id TEXT, " +
                "source_epoch TEXT, source_seq INTEGER)");
    }

    private void createEventIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX events_trip ON events(trip_id, at, id)");
        db.execSQL("CREATE INDEX events_sequence ON events(trip_id, seq)");
        indexTargets(db);
    }

    private void createJournalMetadata(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS journal_epochs (trip_id TEXT PRIMARY KEY, epoch TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS journal_cursors (trip_id TEXT NOT NULL, peer TEXT NOT NULL, " +
                "epoch TEXT NOT NULL, seq INTEGER NOT NULL, event_id TEXT NOT NULL, digest TEXT NOT NULL, " +
                "PRIMARY KEY(trip_id, peer, epoch))");
    }

    private void indexTargets(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS events_target ON events(trip_id, target_id) WHERE target_id IS NOT NULL");
    }

    // Keep consumed seats even if this device removes the trip locally.
    private void createSeats(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE team_seats (trip_id TEXT NOT NULL, device_id TEXT NOT NULL, seat INTEGER NOT NULL, " +
                "grant_json TEXT NOT NULL, PRIMARY KEY(trip_id, device_id), UNIQUE(trip_id, seat))");
    }

    synchronized Trip createTeam(String rawName, List<String> members) throws Exception {
        return createTeam(rawName, members, 0);
    }

    synchronized Trip createOpenTeam(String rawName, String organizer, int capacity) throws Exception {
        return createTeam(rawName, Collections.singletonList(organizer), capacity);
    }

    private Trip createTeam(String rawName, List<String> members, int capacity) throws Exception {
        String name = rawName.trim();
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("请填写旅行名称（最多 80 字）");
        Team.Identity identity = Team.Identity.load(context);
        String id = UUID.randomUUID().toString();
        Team team = capacity == 0 ? new Team(id, identity.deviceId, identity.key, members) :
                Team.open(id, identity.deviceId, identity.key, members.get(0), capacity);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Trip trip = new Trip(id, name, "", "", Team.encode(key), team.member(0), team);
        JSONObject grant = team.grant(identity, identity.deviceId, identity.key, trip.localPerson, 0, team.names.get(0));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            saveTrip(trip);
            saveSeat(db, trip, grant);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return trip;
    }

    synchronized JSONObject seat(String tripId, String device) throws JSONException {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT grant_json FROM team_seats WHERE trip_id=? AND device_id=?",
                new String[]{tripId, device})) {
            return c.moveToFirst() ? new JSONObject(c.getString(0)) : null;
        }
    }

    synchronized boolean teamReady(Trip trip) {
        if (trip.team == null) return true;
        try {
            JSONObject grant = seat(trip.id, PeerIdentity.deviceId(context));
            return grant != null && trip.team.fingerprint.equals(grant.getString("team")) &&
                    (trip.localPerson.equals(grant.getString("member")) || (trip.team.openSeats &&
                            trip.localPerson.isEmpty() && trip.joinName.equals(grant.getString("name"))));
        } catch (JSONException e) { return false; }
    }

    synchronized int teamSeatCount(String tripId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM team_seats WHERE trip_id=?", new String[]{tripId})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Local acknowledgement point for UI. Journal positions are scoped to a single sender and epoch. */
    static final class SyncPoint {
        final long row;
        final String event;
        SyncPoint(long row, String event) { this.row = row; this.event = event; }
    }

    static final class JournalPosition {
        static final JournalPosition START = new JournalPosition(0, "", "");
        final long seq;
        final String id, digest;

        JournalPosition(long seq, String id, String digest) { this.seq = seq; this.id = id; this.digest = digest; }

        boolean matches(JournalPosition other) {
            return seq == other.seq && id.equals(other.id) && digest.equals(other.digest);
        }
    }

    static final class JournalWindow {
        final String epoch;
        final JournalPosition tip;

        JournalWindow(String epoch, JournalPosition tip) { this.epoch = epoch; this.tip = tip; }
    }

    synchronized JournalWindow journalWindow(Trip trip) throws Exception {
        Trip current = getTrip(trip.id);
        if (current == null || !current.secret.equals(trip.secret) || current.team == null ||
                !current.team.fingerprint.equals(trip.team.fingerprint) || !teamReady(current))
            throw new IllegalStateException("旅行已变更，请重新连接");
        SQLiteDatabase db = getWritableDatabase();
        String epoch = journalEpoch(db, trip.id);
        if (epoch.isEmpty()) {
            ContentValues values = new ContentValues();
            values.put("trip_id", trip.id);
            values.put("epoch", UUID.randomUUID().toString());
            db.insertWithOnConflict("journal_epochs", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            epoch = journalEpoch(db, trip.id);
            if (epoch.isEmpty()) throw new IllegalStateException("无法保存同步日志代次");
        }
        try (Cursor cursor = db.rawQuery("SELECT id,kind,at,data,seq FROM events WHERE trip_id=? ORDER BY seq DESC LIMIT 1",
                new String[]{trip.id})) {
            return new JournalWindow(epoch, cursor.moveToFirst()
                    ? position(trip.id, cursor) : JournalPosition.START);
        }
    }

    private static String journalEpoch(SQLiteDatabase db, String tripId) {
        try (Cursor cursor = db.rawQuery("SELECT epoch FROM journal_epochs WHERE trip_id=?", new String[]{tripId})) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }

    private static void requireEpoch(SQLiteDatabase db, String tripId, String epoch) {
        if (!epoch.equals(journalEpoch(db, tripId))) throw new IllegalStateException("同步日志已重建");
    }

    private static JournalPosition position(String tripId, Cursor cursor) throws Exception {
        Event event = new Event(cursor.getString(0), tripId, cursor.getString(1), cursor.getLong(2),
                new JSONObject(cursor.getString(3)), cursor.getLong(4));
        return new JournalPosition(event.localRow, event.id, PeerWire.hash(journalEvent(event)));
    }

    static JSONObject journalEvent(Event event) throws JSONException {
        normalizeEventData(event.kind, event.data);
        return event.json();
    }

    private static void normalizeEventData(String kind, JSONObject data) throws JSONException {
        LedgerRules.normalizeCurrencies(data);
        if ("edit".equals(kind) && data.optJSONObject("record") != null)
            LedgerRules.normalizeCurrencies(data.getJSONObject("record"));
    }

    synchronized boolean validJournalPosition(String tripId, String epoch, JournalPosition point) throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        requireEpoch(db, tripId, epoch);
        if (point.seq == 0) return point.matches(JournalPosition.START);
        try (Cursor cursor = db.rawQuery("SELECT id,kind,at,data,seq FROM events WHERE trip_id=? AND seq=?",
                new String[]{tripId, Long.toString(point.seq)})) {
            return cursor.moveToFirst() && point.matches(position(tripId, cursor));
        }
    }

    List<Event> journalPage(String tripId, JournalWindow window, long after, JournalWindow remote, int limit) {
        List<StoredEvent> rows = new ArrayList<>();
        synchronized (this) {
            SQLiteDatabase db = getReadableDatabase();
            requireEpoch(db, tripId, window.epoch);
            // A record first received from this peer in this epoch is already on that peer.
            try (Cursor cursor = db.rawQuery("SELECT id,kind,at,data,seq FROM events WHERE trip_id=? AND seq>? AND seq<=? " +
                    "AND (source_epoch IS NULL OR source_epoch<>? OR source_seq>?) ORDER BY seq LIMIT ?",
                    new String[]{tripId, Long.toString(after), Long.toString(window.tip.seq), remote.epoch,
                            Long.toString(remote.tip.seq), Integer.toString(limit)})) {
                while (cursor.moveToNext()) rows.add(new StoredEvent(cursor.getString(0), cursor.getString(1),
                        cursor.getLong(2), cursor.getString(3), cursor.getLong(4)));
            }
        }
        return decodeEvents(tripId, rows);
    }

    synchronized JournalPosition journalCursor(String tripId, String peer, String epoch) {
        return journalCursor(getReadableDatabase(), tripId, peer, epoch);
    }

    private static JournalPosition journalCursor(SQLiteDatabase db, String tripId, String peer, String epoch) {
        try (Cursor cursor = db.rawQuery("SELECT seq,event_id,digest FROM journal_cursors WHERE trip_id=? AND peer=? AND epoch=?",
                new String[]{tripId, peer, epoch})) {
            return cursor.moveToFirst() ? new JournalPosition(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
                    : JournalPosition.START;
        }
    }

    synchronized int commitJournal(Trip trip, String localEpoch, String peer, String remoteEpoch,
                                   JournalPosition expected, JournalPosition next,
                                   JSONArray events, List<Long> sourceSequences) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            requireEpoch(db, trip.id, localEpoch);
            if (!journalCursor(db, trip.id, peer, remoteEpoch).matches(expected))
                throw new IllegalStateException("同伴同步位点已更新，请重连");
            int inserted = mergeRows(db, trip, events, true, remoteEpoch, sourceSequences);
            if (next.seq != 0) {
                // A trailing row can have been omitted because it originally came from this device.
                try (Cursor cursor = db.rawQuery("SELECT id,kind,at,data,seq FROM events WHERE trip_id=? AND id=?",
                        new String[]{trip.id, next.id})) {
                    if (!cursor.moveToFirst() || !next.digest.equals(position(trip.id, cursor).digest))
                        throw new IllegalStateException("同步位点与本机流水不一致");
                }
            }
            if (!next.matches(expected)) {
                ContentValues values = new ContentValues();
                values.put("trip_id", trip.id);
                values.put("peer", peer);
                values.put("epoch", remoteEpoch);
                values.put("seq", next.seq);
                values.put("event_id", next.id);
                values.put("digest", next.digest);
                if (db.insertWithOnConflict("journal_cursors", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
                    throw new IllegalStateException("同步位点未保存");
            }
            db.setTransactionSuccessful();
            return inserted;
        } finally { db.endTransaction(); }
    }

    synchronized long confirmedRow(String tripId, long row, String event) {
        if (row == 0 || event == null) return 0;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM events WHERE rowid=? AND id=? AND trip_id=?",
                new String[]{Long.toString(row), event, tripId})) {
            return c.moveToFirst() ? row : 0;
        }
    }

    synchronized int countAfter(String tripId, long row) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events WHERE trip_id=? AND rowid>?",
                new String[]{tripId, Long.toString(row)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized List<String> teammateDevices(Trip trip, String self) {
        List<String> devices = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT device_id,grant_json FROM team_seats WHERE trip_id=? AND device_id<>?",
                new String[]{trip.id, self})) {
            while (c.moveToNext()) {
                try {
                    if (trip.team.fingerprint.equals(new JSONObject(c.getString(1)).getString("team")))
                        devices.add(c.getString(0));
                } catch (JSONException ignored) { }
            }
        }
        return devices;
    }

    synchronized JSONArray teamSeats(String tripId) throws JSONException {
        JSONArray seats = new JSONArray();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT grant_json FROM team_seats WHERE trip_id=? ORDER BY seat",
                new String[]{tripId})) {
            while (rows.moveToNext()) seats.put(new JSONObject(rows.getString(0)));
        }
        return seats;
    }

    synchronized JSONObject enroll(Trip trip, Team.Identity issuer, String device, String key, String member) throws Exception {
        return enroll(trip, issuer, device, key, member, "");
    }

    synchronized JSONObject enroll(Trip trip, Team.Identity issuer, String device, String key, String member, String requestedName) throws Exception {
        if (!trip.team.issuer.equals(issuer.deviceId) || !trip.team.issuerKey.equals(issuer.key))
            throw new IllegalStateException("首次登记需与建队设备连通");
        Trip current = getTrip(trip.id);
        if (current == null || current.team == null || !trip.team.fingerprint.equals(current.team.fingerprint))
            throw new IllegalStateException("小队已从本机移除");
        JSONObject existing = seat(trip.id, device);
        if (trip.team.openSeats) return enrollOpen(trip, issuer, device, key, requestedName, existing);
        if (existing != null) {
            trip.team.verifyGrant(existing, device, key, member);
            return existing;
        }
        int slot = trip.indexOf(member);
        if (teamSeatCount(trip.id) >= trip.people()) throw new TeamProtocol.Admission("小队设备名额已满");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM team_seats WHERE trip_id=? AND seat=?",
                new String[]{trip.id, Integer.toString(slot)})) {
            if (c.moveToFirst()) throw new TeamProtocol.Admission("这个名字已登记在另一台设备");
        }
        JSONObject grant = trip.team.grant(issuer, device, key, member, slot);
        saveSeat(getWritableDatabase(), trip, grant);
        return grant;
    }

    private JSONObject enrollOpen(Trip trip, Team.Identity issuer, String device, String key,
                                  String requestedName, JSONObject existing) throws Exception {
        String name = Team.displayName(requestedName);
        if (existing != null) {
            trip.team.verifyGrant(existing, device, key, existing.getString("member"));
            if (!name.equals(existing.getString("name"))) throw new TeamProtocol.Admission("本机已登记为「" + existing.getString("name") + "」");
            return existing;
        }
        boolean[] occupied = new boolean[trip.people()];
        JSONArray seats = teamSeats(trip.id);
        for (int i = 0; i < seats.length(); i++) {
            JSONObject seat = seats.getJSONObject(i);
            if (name.equalsIgnoreCase(seat.getString("name"))) throw new TeamProtocol.Admission("这个名字已登记在另一台设备");
            occupied[seat.getInt("seat")] = true;
        }
        for (int slot = 1; slot < occupied.length; slot++) {
            if (occupied[slot]) continue;
            JSONObject grant = trip.team.grant(issuer, device, key, trip.member(slot), slot, name);
            saveSeat(getWritableDatabase(), trip, grant);
            return grant;
        }
        throw new TeamProtocol.Admission("小队设备名额已满");
    }

    synchronized void acceptSeat(Trip trip, JSONObject grant, String device, String key, String member) throws Exception {
        Trip current = getTrip(trip.id);
        if (current == null || current.team == null || !trip.team.fingerprint.equals(current.team.fingerprint))
            throw new IllegalStateException("小队已从本机移除");
        trip.team.verifyGrant(grant, device, key, member);
        boolean local = device.equals(PeerIdentity.deviceId(context));
        boolean assigning = local && current.team.openSeats && current.localPerson.isEmpty();
        if (assigning && !current.joinName.equals(grant.getString("name")))
            throw new TeamProtocol.Admission("登记名称与本机输入不一致");
        if (local && !assigning && !current.localPerson.equals(member))
            throw new IllegalArgumentException("本机登记姓名已更改");
        JSONObject existing = seat(trip.id, device);
        if (existing != null) {
            trip.team.verifyGrant(existing, device, key, member);
            if (existing.getInt("seat") != grant.getInt("seat")) throw new IllegalArgumentException("设备席位冲突");
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        if (!assigning) {
            saveSeat(db, trip, grant);
            return;
        }
        db.beginTransaction();
        try {
            saveSeat(db, trip, grant);
            ContentValues values = new ContentValues();
            values.put("local_person", member);
            db.update("trips", values, "id=?", new String[]{trip.id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized boolean acceptRoster(Trip trip, JSONArray roster) throws Exception {
        if (!trip.team.openSeats || roster == null) return false;
        if (roster.length() > trip.people()) throw new IllegalArgumentException("小队人数超出预期");
        boolean changed = false;
        for (int i = 0; i < roster.length(); i++) {
            JSONObject grant = roster.getJSONObject(i);
            String device = grant.getString("device");
            if (device.equals(PeerIdentity.deviceId(context))) continue;
            boolean known = seat(trip.id, device) != null;
            acceptSeat(trip, grant, device, grant.getString("key"), grant.getString("member"));
            changed |= !known;
        }
        return changed;
    }

    private void saveSeat(SQLiteDatabase db, Trip trip, JSONObject grant) throws JSONException {
        ContentValues values = new ContentValues();
        values.put("trip_id", trip.id);
        values.put("device_id", grant.getString("device"));
        values.put("seat", grant.getInt("seat"));
        values.put("grant_json", grant.toString());
        db.insertOrThrow("team_seats", null, values);
    }

    synchronized Trip createTrip(String name, String personA, String personB) {
        if (personA.trim().equalsIgnoreCase(personB.trim()))
            throw new IllegalArgumentException("两人的名字请不同，避免记账混淆");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Trip trip = new Trip(UUID.randomUUID().toString(), name.trim(), personA.trim(), personB.trim(),
                Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), "a");
        saveTrip(trip);
        return trip;
    }

    synchronized Trip joinTrip(String invite, String localPerson) throws JSONException {
        Trip candidate = previewInvite(invite);
        if (candidate.team != null && candidate.team.openSeats) return joinOpenTeam(candidate, localPerson);
        if (candidate.indexOf(localPerson) < 0)
            throw new IllegalArgumentException("请选择这台设备的使用者");
        Trip trip = new Trip(candidate.id, candidate.name, candidate.personA, candidate.personB,
                candidate.secret, localPerson, candidate.team);
        if (trip.team != null) {
            String localDevice = PeerIdentity.deviceId(context);
            if (trip.team.member(0).equals(localPerson) && !trip.team.issuer.equals(localDevice))
                throw new IllegalArgumentException("请选择自己的名字，第一位是建队者");
            JSONObject previous = seat(trip.id, localDevice);
            if (previous != null && !trip.localPerson.equals(previous.getString("member")))
                throw new IllegalArgumentException("这台设备已登记为 " + trip.nameOf(previous.getString("member")));
            if (previous != null && !trip.team.fingerprint.equals(previous.getString("team")))
                throw new IllegalArgumentException("邀请码与本机的登记凭证不一致");
        }
        if (getTrip(trip.id) != null)
            throw new IllegalArgumentException(trip.team == null ? "这个旅行已加入" : "这个小队已在本机");
        saveTrip(trip);
        return trip;
    }

    private Trip joinOpenTeam(Trip candidate, String requestedName) throws JSONException {
        String name = Team.displayName(requestedName);
        String device = PeerIdentity.deviceId(context);
        if (candidate.team.issuer.equals(device)) throw new IllegalArgumentException("本机是这趟旅行的组织者");
        if (name.equalsIgnoreCase(candidate.team.names.get(0))) throw new IllegalArgumentException("请使用与组织者不同的名字");
        Trip existing = getTrip(candidate.id);
        if (existing != null) {
            if (existing.team == null || !existing.team.fingerprint.equals(candidate.team.fingerprint) ||
                    !existing.secret.equals(candidate.secret)) throw new IllegalArgumentException("邀请与已有小队不一致");
            if (teamReady(existing)) {
                if (!existing.joinName.equals(name)) throw new IllegalArgumentException("本机已登记为「" + existing.joinName + "」");
                return existing;
            }
            ContentValues values = new ContentValues();
            values.put("join_name", name);
            getWritableDatabase().update("trips", values, "id=?", new String[]{candidate.id});
            return getTrip(candidate.id);
        }
        JSONObject previous = seat(candidate.id, device);
        String member = "";
        if (previous != null) {
            if (!candidate.team.fingerprint.equals(previous.getString("team")) || !name.equals(previous.getString("name")))
                throw new IllegalArgumentException("本机已有这个小队的登记凭证，请使用原姓名");
            member = previous.getString("member");
        }
        saveTrip(new Trip(candidate.id, candidate.name, "", "", candidate.secret, member,
                candidate.team, name, Collections.emptyMap()));
        return getTrip(candidate.id);
    }

    synchronized Trip previewInvite(String invite) throws JSONException {
        String clean = invite.trim();
        String prefix = Team.invitePrefix(clean);
        if (!prefix.isEmpty()) {
            if (android.os.Build.VERSION.SDK_INT < 29) throw new IllegalArgumentException("多人小队需要 Android 10 或更高版本");
            if (clean.length() > 4096) throw new IllegalArgumentException("小队邀请码过长");
            JSONObject value = new JSONObject(new String(Team.decode(clean.substring(prefix.length())), StandardCharsets.UTF_8));
            Team team = Team.parse(value.getJSONObject("team"));
            if (team.openSeats != prefix.equals(Team.OPEN_INVITE_PREFIX)) throw new IllegalArgumentException("小队邀请码版本无效");
            if (team.size() < 2) throw new IllegalArgumentException("单人旅行无需邀请码");
            String name = value.getString("name").trim(), secret = value.getString("secret");
            if (name.isEmpty() || name.length() > 80 || Team.decode(secret).length != 32)
                throw new IllegalArgumentException("小队邀请码无效");
            return new Trip(team.tripId, name, "", "", secret, team.member(1), team);
        }
        byte[] bytes = Base64.decode(clean, Base64.URL_SAFE | Base64.NO_WRAP);
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        for (String field : new String[]{"id", "name", "personA", "personB", "secret"}) {
            if (!(json.opt(field) instanceof String)) throw new IllegalArgumentException("邀请码字段无效");
        }
        String inviterRole = json.optString("inviterRole", "a");
        if (!"a".equals(inviterRole) && !"b".equals(inviterRole))
            throw new IllegalArgumentException("邀请码身份无效");
        Trip trip = new Trip(json.getString("id"), json.getString("name"), json.getString("personA"),
                json.getString("personB"), json.getString("secret"),
                "a".equals(inviterRole) ? "b" : "a");
        if (!trip.id.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}") ||
                trip.name.trim().isEmpty() ||
                trip.personA.trim().isEmpty() || trip.personB.trim().isEmpty() ||
                trip.personA.trim().equalsIgnoreCase(trip.personB.trim()) ||
                Base64.decode(trip.secret, Base64.URL_SAFE | Base64.NO_WRAP).length != 32)
            throw new IllegalArgumentException("邀请码无效");
        return trip;
    }

    synchronized String inviteCode(Trip trip) throws JSONException {
        if (trip.team != null) {
            if (trip.people() < 2) throw new IllegalArgumentException("单人旅行无需邀请码");
            JSONObject value = new JSONObject().put("team", trip.team.json()).put("name", trip.name).put("secret", trip.secret);
            String prefix = trip.team.openSeats ? Team.OPEN_INVITE_PREFIX : Team.INVITE_PREFIX;
            return prefix + Team.encode(value.toString().getBytes(StandardCharsets.UTF_8));
        }
        JSONObject json = new JSONObject().put("id", trip.id).put("name", trip.name)
                .put("personA", trip.personA).put("personB", trip.personB)
                .put("secret", trip.secret).put("inviterRole", trip.localPerson);
        return Base64.encodeToString(json.toString().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private void saveTrip(Trip trip) {
        ContentValues values = new ContentValues();
        values.put("id", trip.id);
        values.put("name", trip.name);
        values.put("person_a", trip.personA);
        values.put("person_b", trip.personB);
        values.put("secret", trip.team == null ? trip.secret : TripSecrets.wrap(trip.id, trip.secret));
        values.put("local_person", trip.localPerson);
        values.put("join_name", trip.joinName);
        values.put("created_at", System.currentTimeMillis());
        try { if (trip.team != null) values.put("team", trip.team.json().toString()); }
        catch (JSONException e) { throw new IllegalArgumentException("无法保存小队资料", e); }
        getWritableDatabase().insertOrThrow("trips", null, values);
    }

    synchronized List<Trip> trips() {
        List<Trip> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + TRIP_COLUMNS + " FROM trips ORDER BY created_at DESC", null)) {
            while (c.moveToNext()) result.add(readTrip(c));
        }
        return result;
    }

    synchronized Trip getTrip(String id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + TRIP_COLUMNS + " FROM trips WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? readTrip(c) : null;
        }
    }

    synchronized boolean deleteTrip(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("events", "trip_id=?", new String[]{id});
            db.delete("journal_cursors", "trip_id=?", new String[]{id});
            db.delete("journal_epochs", "trip_id=?", new String[]{id});
            int removed = db.delete("trips", "id=?", new String[]{id});
            db.setTransactionSuccessful();
            return removed > 0;
        } finally { db.endTransaction(); }
    }

    private Trip readTrip(Cursor c) {
        try {
            Team team = c.getString(6).isEmpty() ? null : Team.parse(new JSONObject(c.getString(6)));
            Map<String, String> names = new HashMap<>();
            if (team != null) {
                JSONArray seats = teamSeats(team.tripId);
                for (int i = 0; i < seats.length(); i++) {
                    JSONObject seat = seats.getJSONObject(i);
                    String member = seat.getString("member");
                    int index = team.index(member);
                    if (index >= 0) names.put(member, team.openSeats ? seat.getString("name") : team.names.get(index));
                }
            }
            String id = c.getString(0);
            String secret = team == null ? c.getString(4) : TripSecrets.unwrap(id, c.getString(4));
            return new Trip(id, c.getString(1), c.getString(2), c.getString(3), secret, c.getString(5),
                    team, c.getString(7), names);
        } catch (JSONException e) { throw new IllegalStateException("本机小队资料损坏", e); }
    }

    synchronized Event add(String tripId, String kind, JSONObject data) {
        Trip trip = getTrip(tripId);
        if (trip == null) throw new IllegalArgumentException("旅行不存在");
        return appendLocal(getWritableDatabase(), trip, kind, data);
    }

    private Event appendLocal(SQLiteDatabase db, Trip trip, String kind, JSONObject data) {
        if (!teamReady(trip)) throw new IllegalArgumentException("请完成首次设备登记");
        Event event = new Event(UUID.randomUUID().toString(), trip.id, kind, System.currentTimeMillis(), data);
        try {
            if (!writeEvent(db, trip, event, WriteMode.LOCAL, null, 0))
                throw new IllegalStateException("流水未保存，请重试");
            return event;
        } catch (JSONException error) { throw new IllegalArgumentException(error.getMessage(), error); }
    }

    synchronized void edit(Event original, JSONObject data) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Trip trip = getTrip(original.tripId);
            if (trip == null || !teamReady(trip)) throw new IllegalStateException("这趟旅行暂时无法编辑");
            List<StoredEvent> rows = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("SELECT id,kind,at,data,rowid FROM events WHERE trip_id=? AND id=? " +
                    "UNION ALL SELECT id,kind,at,data,rowid FROM events WHERE trip_id=? AND target_id=?",
                    new String[]{original.tripId, original.id, original.tripId, original.id})) {
                while (cursor.moveToNext()) rows.add(new StoredEvent(cursor.getString(0), cursor.getString(1),
                        cursor.getLong(2), cursor.getString(3), cursor.getLong(4)));
            }
            List<Event> events = decodeEvents(original.tripId, rows);
            LedgerRevisions revisions = new LedgerRevisions(events);
            Event base = null;
            for (Event event : events) if (event.id.equals(original.id)) { base = event; break; }
            LedgerRevisions.Resolution resolution = base == null ? null : revisions.resolve(base);
            if (resolution == null) throw new IllegalStateException("这笔记录已删除");
            Event current = resolution.current();
            if (current.localRow != original.localRow)
                throw new IllegalStateException("这笔记录已更新，请关闭后重新打开");
            int parts = 0;
            if (!current.financialOptions.isEmpty() || !PeerWire.canonical(LedgerRevisions.financial(current.data))
                    .equals(PeerWire.canonical(LedgerRevisions.financial(data)))) parts |= LedgerRevisions.FINANCIAL;
            if ("expense".equals(base.kind) && (!current.noteOptions.isEmpty() ||
                    !current.data.optString("note").equals(data.optString("note")))) parts |= LedgerRevisions.NOTE;
            if (parts != 0) {
                JSONObject payload = new JSONObject().put("target", base.id).put("kind", base.kind)
                        .put("revision", Math.addExact(resolution.revision, 1)).put("record", data)
                        .put("parts", parts).put("parents", resolution.parents(parts));
                appendLocal(db, trip, "edit", payload);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private enum WriteMode { LOCAL, STRICT, IMPORT }

    private boolean writeEvent(SQLiteDatabase db, Trip trip, Event event, WriteMode mode,
                               String sourceEpoch, long sourceSequence) throws JSONException {
        if (!trip.id.equals(event.tripId)) {
            if (mode != WriteMode.IMPORT) throw new JSONException("流水旅行不一致");
            return false;
        }
        normalizeEventData(event.kind, event.data);
        if (((mode != WriteMode.LOCAL || trip.team != null) && !LedgerRules.validEvent(trip, event)) ||
                (mode == WriteMode.STRICT && !PeerIdentity.valid(event.id))) {
            if (mode != WriteMode.IMPORT) throw new JSONException("流水格式无效");
            return false;
        }
        if (mode == WriteMode.STRICT && alreadyStored(db, event)) return false;
        ContentValues values = new ContentValues();
        values.put("id", event.id);
        values.put("trip_id", event.tripId);
        values.put("kind", event.kind);
        values.put("at", event.at);
        values.put("data", event.data.toString());
        if ("edit".equals(event.kind) || "delete".equals(event.kind))
            values.put("target_id", event.data.optString("target"));
        if (sourceEpoch != null) {
            values.put("source_epoch", sourceEpoch);
            values.put("source_seq", sourceSequence);
        }
        boolean inserted = db.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
        if (!inserted && mode == WriteMode.STRICT) throw new JSONException("流水批次未完整保存");
        return inserted;
    }

    private static boolean alreadyStored(SQLiteDatabase db, Event event) throws JSONException {
        try (Cursor existing = db.rawQuery("SELECT trip_id,kind,at,data FROM events WHERE id=?", new String[]{event.id})) {
            if (!existing.moveToFirst()) return false;
            JSONObject data = new JSONObject(existing.getString(3));
            normalizeEventData(existing.getString(1), data);
            if (!event.tripId.equals(existing.getString(0)) || !event.kind.equals(existing.getString(1)) ||
                    event.at != existing.getLong(2) || !PeerWire.canonical(event.data).equals(PeerWire.canonical(data)))
                throw new JSONException("发现同 ID 的冲突流水，已保留原账目");
            return true;
        } catch (JSONException error) { throw error; }
        catch (Exception error) { throw new JSONException("流水校验失败"); }
    }

    synchronized int merge(String tripId, JSONArray incoming) throws JSONException {
        return mergeInternal(tripId, incoming, false);
    }

    synchronized int mergeStrict(String tripId, JSONArray incoming) throws Exception {
        return mergeInternal(tripId, incoming, true);
    }

    private int mergeInternal(String tripId, JSONArray incoming, boolean strict) throws JSONException {
        Trip trip = getTrip(tripId);
        if (trip == null) return 0;
        if (!teamReady(trip)) throw new JSONException("请先完成小队设备登记");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int inserted = mergeRows(db, trip, incoming, strict, null, null);
            db.setTransactionSuccessful();
            return inserted;
        } finally { db.endTransaction(); }
    }

    private int mergeRows(SQLiteDatabase db, Trip trip, JSONArray incoming, boolean strict,
                          String sourceEpoch, List<Long> sourceSequences) throws JSONException {
        int inserted = 0;
        WriteMode mode = strict ? WriteMode.STRICT : WriteMode.IMPORT;
        for (int i = 0; i < incoming.length(); i++) {
            Event event = Event.from(incoming.getJSONObject(i));
            if (writeEvent(db, trip, event, mode, sourceEpoch, sourceSequences == null ? 0 : sourceSequences.get(i))) inserted++;
        }
        return inserted;
    }

    JSONArray eventsJson(String tripId) throws JSONException {
        JSONArray result = new JSONArray();
        for (Event event : events(tripId)) result.put(event.json());
        return result;
    }

    List<Event> events(String tripId) {
        return decodeEvents(tripId, readEvents(tripId, false));
    }

    List<Event> visibleExchanges(String tripId) {
        return LedgerRevisions.visibleRecords(exchangesAndDeletes(tripId));
    }

    List<Event> exchangesAndDeletes(String tripId) {
        return decodeEvents(tripId, readEvents(tripId, true));
    }

    /** Copy SQLite rows while holding the store lock; JSON decoding and hashing run outside it. */
    private synchronized List<StoredEvent> readEvents(String tripId, boolean exchangeOnly) {
        List<StoredEvent> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,kind,at,data,rowid FROM events WHERE trip_id=?" +
                        (exchangeOnly ? " AND kind IN ('exchange','delete','edit')" : "") + " ORDER BY at,id",
                new String[]{tripId})) {
            while (c.moveToNext()) result.add(new StoredEvent(c.getString(0), c.getString(1), c.getLong(2),
                    c.getString(3), c.getLong(4)));
        }
        return result;
    }

    private static final class StoredEvent {
        final String id, kind, data;
        final long at, row;
        StoredEvent(String id, String kind, long at, String data, long row) {
            this.id = id; this.kind = kind; this.at = at; this.data = data; this.row = row;
        }
    }

    private static List<Event> decodeEvents(String tripId, List<StoredEvent> rows) {
        List<Event> events = new ArrayList<>(rows.size());
        for (StoredEvent row : rows) {
            try { events.add(new Event(row.id, tripId, row.kind, row.at, new JSONObject(row.data), row.row)); }
            catch (JSONException error) {
                // A damaged row must not silently vanish from totals or be acknowledged to a peer.
                throw new IllegalStateException("本机流水无法读取，请保留账本后重试", error);
            }
        }
        return events;
    }

    Map<String, Long> recentCurrencyCodes(String tripId, Set<String> excluded, int limit) {
        Map<String, Long> codes = new LinkedHashMap<>();
        List<Event> visible = LedgerRevisions.visibleRecords(events(tripId));
        for (int i = visible.size() - 1; i >= 0 && codes.size() < limit; i--) {
            Event event = visible.get(i);
            if ("exchange".equals(event.kind)) {
                addCurrencyCode(codes, excluded, event.data.optString("toCurrency"), event.at);
                if (codes.size() < limit) addCurrencyCode(codes, excluded, event.data.optString("fromCurrency"), event.at);
            } else addCurrencyCode(codes, excluded, event.data.optString("currency"), event.at);
        }
        return codes;
    }

    private static void addCurrencyCode(Map<String, Long> codes, Set<String> excluded, String raw, long at) {
        String code;
        try { code = Money.code(raw); }
        catch (IllegalArgumentException ignored) { return; }
        if (!excluded.contains(code)) codes.putIfAbsent(code, at);
    }

    static final class Snapshot {
        final Trip trip;
        final List<Event> events;
        final SyncPoint syncPoint;

        Snapshot(Trip trip, List<Event> events) {
            this.trip = trip;
            this.events = Collections.unmodifiableList(events);
            long row = 0;
            String id = "";
            for (Event event : events) if (event.localRow > row) { row = event.localRow; id = event.id; }
            syncPoint = new SyncPoint(row, id);
        }

        Summary totals() { return LedgerTotals.calculate(trip != null && trip.team != null, events); }
    }

    Snapshot snapshot(String tripId) {
        Trip trip;
        List<StoredEvent> rows;
        synchronized (this) {
            trip = getTrip(tripId);
            rows = readEvents(tripId, false);
        }
        return new Snapshot(trip, decodeEvents(tripId, rows));
    }

    Summary summary(String tripId) {
        return snapshot(tripId).totals();
    }
}
