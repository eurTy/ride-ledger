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
    // Leaves room for a full 10,000-entry travel to accumulate safely in minor units.
    private static final long MAX_TEAM_AMOUNT = Long.MAX_VALUE / 10000;
    static final class Trip {
        final String id, name, personA, personB, secret, localPerson, syncUrl;
        final Team team;

        Trip(String id, String name, String personA, String personB, String secret, String localPerson) {
            this(id, name, personA, personB, secret, localPerson, "");
        }

        Trip(String id, String name, String personA, String personB, String secret, String localPerson, String syncUrl) {
            this(id, name, personA, personB, secret, localPerson, syncUrl, null);
        }

        Trip(String id, String name, String personA, String personB, String secret, String localPerson, String syncUrl, Team team) {
            this.id = id;
            this.name = name;
            this.personA = personA;
            this.personB = personB;
            this.secret = secret;
            this.localPerson = localPerson;
            this.syncUrl = syncUrl;
            this.team = team;
        }

        int people() { return team == null ? 2 : team.size(); }
        Trip withKey(String key) { return new Trip(id, name, personA, personB, key, localPerson, syncUrl, team); }
        String member(int index) { return team == null ? (index == 0 ? "a" : "b") : team.member(index); }
        int indexOf(String person) { return team == null ? ("a".equals(person) ? 0 : "b".equals(person) ? 1 : -1) : team.index(person); }
        String nameOf(String person) {
            int index = indexOf(person);
            if (index < 0) return "未知成员";
            return team == null ? (index == 0 ? personA : personB) : team.names.get(index);
        }
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
        final Map<String, Map<String, Long>> teamCash = new HashMap<>();
        final Map<String, Map<String, Long>> teamNet = new HashMap<>();
        final List<Event> visible = new ArrayList<>();
    }

    private static final String TRIP_COLUMNS = "id,name,person_a,person_b,secret,local_person,sync_url,team";
    private final Context context;

    LedgerStore(Context context) {
        super(context, "ride_ledger.db", null, 4);
        this.context = context;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips (id TEXT PRIMARY KEY, name TEXT NOT NULL, person_a TEXT NOT NULL, " +
                "person_b TEXT NOT NULL, secret TEXT NOT NULL, local_person TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "sync_url TEXT NOT NULL DEFAULT '', team TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, trip_id TEXT NOT NULL, kind TEXT NOT NULL, " +
                "at INTEGER NOT NULL, data TEXT NOT NULL)");
        db.execSQL("CREATE INDEX events_trip ON events(trip_id, at, id)");
        createSeats(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE trips ADD COLUMN sync_url TEXT NOT NULL DEFAULT ''");
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE trips ADD COLUMN team TEXT NOT NULL DEFAULT ''");
            createSeats(db);
        }
        if (oldVersion < 4) {
            db.execSQL("DELETE FROM events WHERE trip_id IN (SELECT id FROM trips WHERE team = '')");
            db.execSQL("DELETE FROM trips WHERE team = ''");
        }
    }

    // Keep consumed seats even if this device removes the trip locally.
    private void createSeats(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE team_seats (trip_id TEXT NOT NULL, device_id TEXT NOT NULL, seat INTEGER NOT NULL, " +
                "grant_json TEXT NOT NULL, PRIMARY KEY(trip_id, device_id), UNIQUE(trip_id, seat))");
    }

    synchronized Trip createTeam(String rawName, List<String> members) throws Exception {
        String name = rawName.trim();
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("请填写旅行名称（最多 80 字）");
        Team.Identity identity = Team.Identity.load(context);
        String id = UUID.randomUUID().toString();
        Team team = new Team(id, identity.deviceId, identity.key, members);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Trip trip = new Trip(id, name, "", "", Team.encode(key), team.member(0), "", team);
        JSONObject grant = team.grant(identity, identity.deviceId, identity.key, trip.localPerson, 0);
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
            return grant != null && trip.localPerson.equals(grant.getString("member")) &&
                    trip.team.fingerprint.equals(grant.getString("team"));
        } catch (JSONException e) { return false; }
    }

    synchronized int teamSeatCount(String tripId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM team_seats WHERE trip_id=?", new String[]{tripId})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized JSONObject enroll(Trip trip, Team.Identity issuer, String device, String key, String member) throws Exception {
        if (!trip.team.issuer.equals(issuer.deviceId) || !trip.team.issuerKey.equals(issuer.key))
            throw new IllegalStateException("首次登记需与建队设备连通");
        Trip current = getTrip(trip.id);
        if (current == null || current.team == null || !trip.team.fingerprint.equals(current.team.fingerprint))
            throw new IllegalStateException("小队已从本机移除");
        JSONObject existing = seat(trip.id, device);
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

    synchronized void acceptSeat(Trip trip, JSONObject grant, String device, String key, String member) throws Exception {
        Trip current = getTrip(trip.id);
        if (current == null || current.team == null || !trip.team.fingerprint.equals(current.team.fingerprint))
            throw new IllegalStateException("小队已从本机移除");
        trip.team.verifyGrant(grant, device, key, member);
        if (device.equals(PeerIdentity.deviceId(context)) && !current.localPerson.equals(member))
            throw new IllegalArgumentException("本机登记姓名已更改");
        JSONObject existing = seat(trip.id, device);
        if (existing != null) {
            trip.team.verifyGrant(existing, device, key, member);
            if (existing.getInt("seat") != grant.getInt("seat")) throw new IllegalArgumentException("设备席位冲突");
            return;
        }
        saveSeat(getWritableDatabase(), trip, grant);
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
        if (candidate.indexOf(localPerson) < 0)
            throw new IllegalArgumentException("请选择这台设备的使用者");
        Trip trip = new Trip(candidate.id, candidate.name, candidate.personA, candidate.personB,
                candidate.secret, localPerson, candidate.syncUrl, candidate.team);
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
        Trip existing = getTrip(trip.id);
        if (existing != null) {
            if (existing.team != null && trip.team != null && !teamReady(existing) && !trip.syncUrl.isEmpty() &&
                    !trip.syncUrl.equals(existing.syncUrl) && trip.secret.equals(existing.secret) &&
                    trip.team.fingerprint.equals(existing.team.fingerprint) && trip.localPerson.equals(existing.localPerson)) {
                setTripSyncUrl(trip.id, trip.syncUrl);
                return getTrip(trip.id);
            }
            if (candidate.team != null || existing.team != null) throw new IllegalArgumentException("这个小队已在本机");
            if (candidate.syncUrl.isEmpty()) throw new IllegalArgumentException("这个旅行已加入");
            if (!java.util.Arrays.equals(Base64.decode(existing.secret, Base64.URL_SAFE | Base64.NO_WRAP),
                    Base64.decode(candidate.secret, Base64.URL_SAFE | Base64.NO_WRAP)))
                throw new IllegalArgumentException("邀请码与已有旅行的密钥不一致");
            setTripSyncUrl(trip.id, candidate.syncUrl);
            return getTrip(trip.id);
        }
        saveTrip(trip);
        return trip;
    }

    synchronized Trip previewInvite(String invite) throws JSONException {
        String clean = invite.trim();
        if (clean.startsWith(Team.INVITE_PREFIX)) {
            if (android.os.Build.VERSION.SDK_INT < 29) throw new IllegalArgumentException("多人小队需要 Android 10 或更高版本");
            if (clean.length() > 4096) throw new IllegalArgumentException("小队邀请码过长");
            JSONObject value = new JSONObject(new String(Team.decode(clean.substring(Team.INVITE_PREFIX.length())), StandardCharsets.UTF_8));
            Team team = Team.parse(value.getJSONObject("team"));
            if (team.size() < 2) throw new IllegalArgumentException("单人旅行无需邀请码");
            String name = value.getString("name").trim(), secret = value.getString("secret");
            if (name.isEmpty() || name.length() > 80 || Team.decode(secret).length != 32)
                throw new IllegalArgumentException("小队邀请码无效");
            String syncUrl = teamEndpoint(value.has("syncUrl") ? value.getString("syncUrl") : "");
            return new Trip(team.tripId, name, "", "", secret, team.member(1), syncUrl, team);
        }
        byte[] bytes = Base64.decode(clean, Base64.URL_SAFE | Base64.NO_WRAP);
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        for (String field : new String[]{"id", "name", "personA", "personB", "secret"}) {
            if (!(json.opt(field) instanceof String)) throw new IllegalArgumentException("邀请码字段无效");
        }
        String inviterRole = json.optString("inviterRole", "a");
        if (!"a".equals(inviterRole) && !"b".equals(inviterRole))
            throw new IllegalArgumentException("邀请码身份无效");
        String syncUrl = "";
        if (json.has("syncUrl")) {
            if (!(json.opt("syncUrl") instanceof String)) throw new IllegalArgumentException("邀请码连接地址无效");
            syncUrl = SyncEndpoint.normalize(json.getString("syncUrl"), false);
        }
        Trip trip = new Trip(json.getString("id"), json.getString("name"), json.getString("personA"),
                json.getString("personB"), json.getString("secret"),
                "a".equals(inviterRole) ? "b" : "a", syncUrl);
        if (!trip.id.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}") ||
                trip.name.trim().isEmpty() ||
                trip.personA.trim().isEmpty() || trip.personB.trim().isEmpty() ||
                trip.personA.trim().equalsIgnoreCase(trip.personB.trim()) ||
                Base64.decode(trip.secret, Base64.URL_SAFE | Base64.NO_WRAP).length != 32)
            throw new IllegalArgumentException("邀请码无效");
        return trip;
    }

    synchronized String inviteCode(Trip trip) throws JSONException {
        return inviteCode(trip, trip.syncUrl);
    }

    synchronized String inviteCode(Trip trip, String syncUrl) throws JSONException {
        if (trip.team != null) {
            if (trip.people() < 2) throw new IllegalArgumentException("单人旅行无需邀请码");
            JSONObject value = new JSONObject().put("team", trip.team.json()).put("name", trip.name).put("secret", trip.secret);
            String endpoint = teamEndpoint(syncUrl);
            if (!endpoint.isEmpty()) value.put("syncUrl", endpoint);
            return Team.INVITE_PREFIX + Team.encode(value.toString().getBytes(StandardCharsets.UTF_8));
        }
        JSONObject json = new JSONObject().put("id", trip.id).put("name", trip.name)
                .put("personA", trip.personA).put("personB", trip.personB)
                .put("secret", trip.secret).put("inviterRole", trip.localPerson);
        if (syncUrl != null && syncUrl.startsWith("https://"))
            json.put("syncUrl", SyncEndpoint.normalize(syncUrl, false));
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
        values.put("sync_url", trip.syncUrl);
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
            int removed = db.delete("trips", "id=?", new String[]{id});
            db.setTransactionSuccessful();
            return removed > 0;
        } finally { db.endTransaction(); }
    }

    synchronized void setTripSyncUrl(String id, String raw) {
        Trip trip = getTrip(id);
        if (trip == null) throw new IllegalArgumentException("旅行不存在");
        String syncUrl = trip.team == null ? SyncEndpoint.normalize(raw, true) : teamEndpoint(raw);
        ContentValues values = new ContentValues();
        values.put("sync_url", syncUrl);
        if (getWritableDatabase().update("trips", values, "id=?", new String[]{id}) == 0)
            throw new IllegalArgumentException("旅行不存在");
    }

    private static String teamEndpoint(String raw) {
        String endpoint = SyncEndpoint.normalize(raw, false);
        if (endpoint.length() > 250) throw new IllegalArgumentException("HTTPS 中转地址过长");
        return endpoint;
    }

    private Trip readTrip(Cursor c) {
        try {
            Team team = c.getString(7).isEmpty() ? null : Team.parse(new JSONObject(c.getString(7)));
            return new Trip(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6), team);
        } catch (JSONException e) { throw new IllegalStateException("本机小队资料损坏", e); }
    }

    synchronized Event add(String tripId, String kind, JSONObject data) {
        Event event = new Event(UUID.randomUUID().toString(), tripId, kind, System.currentTimeMillis(), data);
        Trip trip = getTrip(tripId);
        if (trip == null) throw new IllegalArgumentException("旅行不存在");
        if (trip.team != null && (!teamReady(trip) || !validEvent(trip, event)))
            throw new IllegalArgumentException("请完成首次设备登记并检查流水内容");
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
        int inserted = 0;
        db.beginTransaction();
        try {
            for (int i = 0; i < incoming.length(); i++) {
                Event event = Event.from(incoming.getJSONObject(i));
                if (!tripId.equals(event.tripId)) {
                    if (strict) throw new JSONException("流水旅行不一致");
                    continue;
                }
                normalizeCurrency(event.data, "currency");
                normalizeCurrency(event.data, "fromCurrency");
                normalizeCurrency(event.data, "toCurrency");
                if (!validEvent(trip, event) || (strict && !PeerIdentity.valid(event.id))) {
                    if (strict) throw new JSONException("流水格式无效");
                    continue;
                }
                if (strict) {
                    try (Cursor existing = db.rawQuery("SELECT trip_id,kind,at,data FROM events WHERE id=?", new String[]{event.id})) {
                        if (existing.moveToFirst()) {
                            JSONObject data = new JSONObject(existing.getString(3));
                            normalizeCurrency(data, "currency"); normalizeCurrency(data, "fromCurrency"); normalizeCurrency(data, "toCurrency");
                            if (!tripId.equals(existing.getString(0)) || !event.kind.equals(existing.getString(1)) ||
                                    event.at != existing.getLong(2) || !PeerWire.canonical(event.data).equals(PeerWire.canonical(data)))
                                throw new JSONException("发现同 ID 的冲突流水，已保留原账目");
                        }
                    } catch (JSONException e) { throw e; }
                    catch (Exception e) { throw new JSONException("流水校验失败"); }
                }
                if (insertEvent(db, event) != -1) inserted++;
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return inserted;
    }

    private void normalizeCurrency(JSONObject data, String field) throws JSONException {
        if ("RMB".equalsIgnoreCase(data.optString(field))) data.put(field, "CNY");
    }

    private boolean validEvent(Trip trip, Event event) {
        try {
            JSONObject d = event.data;
            switch (event.kind) {
                case "opening": case "topup":
                    return trip.indexOf(d.getString("person")) >= 0 && currency(d.getString("currency")) && validAmount(trip, d, "amount");
                case "expense":
                    if (trip.team != null) return validTeamExpense(trip, d);
                    int share = d.getInt("shareA");
                    return person(d.getString("payer")) && currency(d.getString("currency"))
                            && d.getLong("amount") > 0 && share >= 0 && share <= 10000;
                case "transfer":
                    if (trip.team != null) return trip.indexOf(d.getString("from")) >= 0 && trip.indexOf(d.getString("to")) >= 0 &&
                            !d.getString("from").equals(d.getString("to")) && currency(d.getString("currency")) && validAmount(trip, d, "amount");
                    return person(d.getString("from")) && currency(d.getString("currency")) && d.getLong("amount") > 0;
                case "exchange":
                    return trip.indexOf(d.getString("person")) >= 0 && currency(d.getString("fromCurrency"))
                            && currency(d.getString("toCurrency")) && validAmount(trip, d, "fromAmount")
                            && validAmount(trip, d, "toAmount")
                            && (trip.team == null || !d.getString("fromCurrency").equals(d.getString("toCurrency")));
                case "delete":
                    return trip.team == null ? !d.getString("target").isEmpty() : PeerIdentity.valid(d.getString("target"));
                default: return false;
            }
        } catch (Exception ignored) { return false; }
    }

    private boolean validTeamExpense(Trip trip, JSONObject data) throws JSONException {
        long amount = data.getLong("amount");
        String payer = data.getString("payer");
        JSONObject shares = data.getJSONObject("shares");
        if (!validAmount(trip, data, "amount") || trip.indexOf(payer) < 0 || !currency(data.getString("currency")) ||
                shares.length() == 0 || shares.length() > trip.people()) return false;
        long total = 0;
        java.util.Iterator<String> keys = shares.keys();
        while (keys.hasNext()) {
            String member = keys.next();
            long share = exactLong(shares, member);
            if (trip.indexOf(member) < 0 || share < 0 || share > amount - total) return false;
            total += share;
        }
        return total == amount && (!"personal".equals(data.optString("mode")) ||
                shares.length() == 1 && shares.optLong(payer, -1) == amount);
    }

    private boolean validAmount(Trip trip, JSONObject data, String field) throws JSONException {
        if (trip.team == null) return data.getLong(field) > 0;
        long amount = exactLong(data, field);
        return amount > 0 && amount <= MAX_TEAM_AMOUNT;
    }

    private long exactLong(JSONObject data, String field) throws JSONException {
        Object value = data.get(field);
        if (value instanceof Integer || value instanceof Long) return ((Number) value).longValue();
        throw new JSONException("金额应为整数最小货币单位");
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
        Trip trip = getTrip(tripId);
        List<Event> all = events(tripId);
        Set<String> deleted = new HashSet<>();
        for (Event event : all) if ("delete".equals(event.kind)) deleted.add(event.data.optString("target"));
        for (Event event : all) {
            if ("delete".equals(event.kind) || deleted.contains(event.id)) continue;
            JSONObject d = event.data;
            try {
                if (trip != null && trip.team != null) {
                    summarizeTeam(summary, event);
                    summary.visible.add(event);
                    continue;
                }
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

    private void summarizeTeam(Summary summary, Event event) throws JSONException {
        JSONObject d = event.data;
        if ("exchange".equals(event.kind)) {
            Map<String, Long> cash = summary.teamCash.computeIfAbsent(d.getString("person"), id -> new HashMap<>());
            add(cash, d.getString("fromCurrency"), -d.getLong("fromAmount"));
            add(cash, d.getString("toCurrency"), d.getLong("toAmount"));
            return;
        }
        String currency = d.getString("currency");
        long amount = d.getLong("amount");
        switch (event.kind) {
            case "opening": case "topup":
                add(summary.teamCash.computeIfAbsent(d.getString("person"), id -> new HashMap<>()), currency, amount);
                break;
            case "expense": {
                String payer = d.getString("payer");
                add(summary.teamCash.computeIfAbsent(payer, id -> new HashMap<>()), currency, -amount);
                add(summary.spent, currency, amount);
                add(summary.teamNet.computeIfAbsent(payer, id -> new HashMap<>()), currency, amount);
                JSONObject shares = d.getJSONObject("shares");
                java.util.Iterator<String> keys = shares.keys();
                while (keys.hasNext()) {
                    String member = keys.next();
                    add(summary.teamNet.computeIfAbsent(member, id -> new HashMap<>()), currency, -shares.getLong(member));
                }
                break;
            }
            case "transfer": {
                String from = d.getString("from"), to = d.getString("to");
                add(summary.teamCash.computeIfAbsent(from, id -> new HashMap<>()), currency, -amount);
                add(summary.teamCash.computeIfAbsent(to, id -> new HashMap<>()), currency, amount);
                add(summary.teamNet.computeIfAbsent(from, id -> new HashMap<>()), currency, amount);
                add(summary.teamNet.computeIfAbsent(to, id -> new HashMap<>()), currency, -amount);
                break;
            }
        }
    }

    private void cash(Summary summary, String person, String currency, long delta) {
        add("a".equals(person) ? summary.cashA : summary.cashB, currency, delta);
    }

    private void add(Map<String, Long> map, String currency, long delta) {
        map.put(currency, Math.addExact(map.getOrDefault(currency, 0L), delta));
    }
}
