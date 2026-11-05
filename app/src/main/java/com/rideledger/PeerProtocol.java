package com.rideledger;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable, symmetric synchronization over any ordered byte stream. No IP or Bluetooth API here. */
final class PeerProtocol {
    interface Listener {
        void authenticated(String remoteId);
        void stored(int added);
    }
    static final class Result {
        String remoteId = "";
        int added, sent;
        boolean complete, authenticated, durable, modern;
        long bytesRead, bytesWritten;
    }
    private static final int VERSION = 2, MAX_EVENTS = 10000, BATCH_BYTES = 48000;
    private final LedgerStore store;
    private final String deviceId;
    private final Listener listener;
    private final String remoteDevice;
    private final String sessionKey;
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private final Map<String, Travel> common = new LinkedHashMap<>();
    private final Result result = new Result();
    private String session;
    private PeerWire wire;
    private boolean manifestFailure;

    private static final class Snapshot {
        final LedgerStore.Trip trip;
        final JSONObject hashes = new JSONObject();
        final Map<String, JSONObject> events = new LinkedHashMap<>();
        String root;
        Snapshot(LedgerStore.Trip trip) { this.trip = trip; }
    }
    private static final class Travel {
        final Snapshot local;
        final String remoteRoot;
        final int remoteCount;
        JSONObject remoteHashes;
        final Set<String> missing = new HashSet<>();
        final JSONArray sentHashes = new JSONArray(), receivedHashes = new JSONArray();
        boolean failed;
        Travel(Snapshot local, String root, int count) { this.local = local; remoteRoot = root; remoteCount = count; }
    }

    PeerProtocol(LedgerStore store, String deviceId, Listener listener) throws Exception {
        this(store, deviceId, listener, null, "", "");
    }

    PeerProtocol(LedgerStore store, String deviceId, Listener listener, LedgerStore.Trip teamTrip,
                 String remoteDevice, String sessionKey) throws Exception {
        this.store = store; this.deviceId = deviceId; this.listener = listener;
        this.remoteDevice = remoteDevice;
        this.sessionKey = sessionKey;
        if (!PeerIdentity.valid(deviceId)) throw new IllegalArgumentException("设备身份无效");
        synchronized (store) {
            for (LedgerStore.Trip trip : store.trips()) {
                if (teamTrip == null ? trip.team != null : trip.team == null || !trip.id.equals(teamTrip.id) ||
                        !trip.team.fingerprint.equals(teamTrip.team.fingerprint) || !trip.secret.equals(teamTrip.secret)) continue;
                Snapshot snapshot = new Snapshot(trip);
                for (LedgerStore.Event entry : store.events(trip.id)) {
                    JSONObject event = entry.json(), data = event.getJSONObject("data");
                    for (String field : new String[]{"currency", "fromCurrency", "toCurrency"})
                        if ("RMB".equalsIgnoreCase(data.optString(field))) data.put(field, "CNY");
                    snapshot.events.put(entry.id, event); snapshot.hashes.put(entry.id, PeerWire.hash(event));
                }
                if (snapshot.events.size() > MAX_EVENTS) throw new IllegalArgumentException("单趟旅行记录过多");
                snapshot.root = PeerWire.hash(snapshot.hashes); snapshots.put(trip.id, snapshot);
            }
        }
    }

    Result progress() { return result; }
    Result exchange(PeerWire wire, boolean modern) throws Exception {
        this.wire = wire;
        if (!modern && sessionKey.isEmpty()) return legacyInitiator();
        session = UUID.randomUUID().toString();
        wire.write(hello(false));
        JSONObject remote = wire.read(); readHello(remote, "hello");
        wire.write(index()); readIndex(readControl("index"));
        sendDeltas(); wire.write(frame("end"));
        JSONObject end = receiveDeltas();
        boolean acknowledged = verifyReceipts(end.optJSONArray("receipts"));
        wire.write(frame("confirm").put("receipts", receipts()));
        finish(acknowledged); return result;
    }

    Result accept(PeerWire wire) throws Exception {
        this.wire = wire;
        JSONObject first = wire.read();
        if (first.getInt("version") == 1 && sessionKey.isEmpty()) return legacyReceiver(first);
        session = first.getString("session");
        if (!PeerIdentity.valid(session)) throw new IllegalArgumentException("同步会话无效");
        readHello(first, "hello"); wire.write(hello(true));
        readIndex(readControl("index")); wire.write(index());
        receiveDeltas();
        sendDeltas(); wire.write(frame("end").put("receipts", receipts()));
        boolean acknowledged = verifyReceipts(readControl("confirm").optJSONArray("receipts"));
        finish(acknowledged); return result;
    }

    private JSONObject frame(String kind) throws Exception {
        return new JSONObject().put("version", VERSION).put("kind", kind).put("session", session).put("deviceId", deviceId);
    }
    private JSONObject payload(String kind) throws Exception {
        return new JSONObject().put("type", kind).put("session", session).put("sender", deviceId);
    }
    private LedgerStore.Trip wireTrip(LedgerStore.Trip trip) {
        return sessionKey.isEmpty() ? trip : trip.withKey(sessionKey);
    }
    private JSONObject hello(boolean onlyCommon) throws Exception {
        JSONArray values = new JSONArray();
        for (Snapshot value : snapshots.values()) {
            if (onlyCommon && !common.containsKey(value.trip.id)) continue;
            values.put(SyncCodec.sealPeer(wireTrip(value.trip), payload("manifest").put("root", value.root).put("count", value.events.size())));
        }
        return frame("hello").put("bundles", values);
    }
    private void readHello(JSONObject frame, String kind) throws Exception {
        if (frame.getInt("version") != VERSION || !kind.equals(frame.getString("kind")) || !session.equals(frame.getString("session")))
            throw new IllegalArgumentException("同步协议不匹配");
        result.remoteId = frame.getString("deviceId"); result.modern = true;
        if (!PeerIdentity.valid(result.remoteId) || deviceId.equals(result.remoteId) ||
                (!remoteDevice.isEmpty() && !remoteDevice.equals(result.remoteId)))
            throw new IllegalArgumentException("同伴设备身份无效");
        JSONArray bundles = frame.getJSONArray("bundles");
        if (bundles.length() > 100) throw new IllegalArgumentException("旅行数量过多");
        for (int i = 0; i < bundles.length(); i++) {
            JSONObject bundle = bundles.getJSONObject(i); Snapshot own = snapshots.get(bundle.optString("tripId"));
            if (own == null) continue;
            try {
                JSONObject value = open(own, bundle, "manifest");
                String root = value.getString("root"); int count = value.getInt("count");
                if (!root.matches("[a-f0-9]{64}") || count < 0 || count > MAX_EVENTS || common.containsKey(own.trip.id))
                    throw new IllegalArgumentException("摘要无效");
                common.put(own.trip.id, new Travel(own, root, count));
            } catch (Exception ignored) { manifestFailure = true; }
        }
        if (common.isEmpty()) throw new IllegalArgumentException("请先让两台手机加入同一旅行");
        result.authenticated = true; listener.authenticated(result.remoteId);
    }
    private JSONObject open(Snapshot own, JSONObject bundle, String type) throws Exception {
        JSONObject value = SyncCodec.openPeer(wireTrip(own.trip), bundle);
        if (!type.equals(value.getString("type")) || !session.equals(value.getString("session")) ||
                !result.remoteId.equals(value.getString("sender"))) throw new IllegalArgumentException("同步消息身份不一致");
        return value;
    }
    private JSONObject readControl(String kind) throws Exception {
        JSONObject value = wire.read(); verifyControl(value, kind); return value;
    }
    private void verifyControl(JSONObject value, String kind) throws Exception {
        if (value.getInt("version") != VERSION || !kind.equals(value.getString("kind")) ||
                !session.equals(value.getString("session")) || !result.remoteId.equals(value.getString("deviceId")))
            throw new IllegalArgumentException("同步消息顺序无效");
    }

    private JSONObject index() throws Exception {
        JSONArray values = new JSONArray();
        for (Travel value : common.values()) {
            if (!value.local.root.equals(value.remoteRoot))
                values.put(SyncCodec.sealPeer(wireTrip(value.local.trip), payload("index").put("entries", value.local.hashes)));
        }
        return frame("index").put("bundles", values);
    }
    private void readIndex(JSONObject frame) throws Exception {
        Set<String> seen = new HashSet<>(); JSONArray bundles = frame.getJSONArray("bundles");
        if (bundles.length() > common.size()) throw new IllegalArgumentException("索引数量无效");
        for (int i = 0; i < bundles.length(); i++) {
            JSONObject bundle = bundles.getJSONObject(i); Travel value = common.get(bundle.optString("tripId"));
            if (value == null || !seen.add(value.local.trip.id)) throw new IllegalArgumentException("索引旅行不一致");
            try {
                JSONObject entries = open(value.local, bundle, "index").getJSONObject("entries");
                if (entries.length() != value.remoteCount || !PeerWire.hash(entries).equals(value.remoteRoot))
                    throw new IllegalArgumentException("同伴索引与摘要不一致");
                Iterator<String> ids = entries.keys();
                while (ids.hasNext()) {
                    String id = ids.next();
                    if (!PeerIdentity.valid(id) || !entries.getString(id).matches("[a-f0-9]{64}"))
                        throw new IllegalArgumentException("流水索引无效");
                }
                value.remoteHashes = entries;
            } catch (Exception error) { value.failed = true; }
        }
        for (Travel value : common.values()) {
            if (value.local.root.equals(value.remoteRoot)) value.remoteHashes = value.local.hashes;
            if (value.remoteHashes == null) { value.failed = true; continue; }
            Iterator<String> ids = value.remoteHashes.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                if (!value.local.hashes.has(id)) value.missing.add(id);
                else if (!value.local.hashes.getString(id).equals(value.remoteHashes.getString(id))) value.failed = true;
            }
        }
    }
    private void sendDeltas() throws Exception {
        for (Travel value : common.values()) {
            if (value.failed) continue;
            JSONArray batch = new JSONArray(); int bytes = 0;
            for (Map.Entry<String, JSONObject> event : value.local.events.entrySet()) {
                if (value.remoteHashes.has(event.getKey())) continue;
                int length = event.getValue().toString().getBytes(StandardCharsets.UTF_8).length;
                if (batch.length() > 0 && bytes + length > BATCH_BYTES) { sendBatch(value, batch); batch = new JSONArray(); bytes = 0; }
                batch.put(event.getValue()); bytes += length;
            }
            if (batch.length() > 0) sendBatch(value, batch);
        }
    }
    private void sendBatch(Travel value, JSONArray batch) throws Exception {
        JSONObject bundle = SyncCodec.sealPeer(wireTrip(value.local.trip), payload("events").put("events", batch));
        wire.write(frame("delta").put("bundle", bundle));
        value.sentHashes.put(PeerWire.hash(bundle)); result.sent += batch.length();
    }
    private JSONObject receiveDeltas() throws Exception {
        for (int frames = 0; frames < 20000; frames++) {
            JSONObject next = wire.read(); String kind = next.getString("kind");
            verifyControl(next, kind);
            if (wire.bytesRead > 32_000_000) throw new IllegalArgumentException("同步数据总量过大");
            if ("end".equals(kind)) return next;
            if (!"delta".equals(kind)) throw new IllegalArgumentException("同步消息类型无效");
            JSONObject bundle = next.getJSONObject("bundle"); Travel value = common.get(bundle.optString("tripId"));
            if (value == null) throw new IllegalArgumentException("同步旅行未授权");
            if (value.failed) continue;
            try {
                JSONArray events = open(value.local, bundle, "events").getJSONArray("events");
                if (events.length() < 1 || events.length() > MAX_EVENTS) throw new IllegalArgumentException("流水数量无效");
                for (int i = 0; i < events.length(); i++) {
                    JSONObject event = events.getJSONObject(i); String id = event.getString("id");
                    if (!value.remoteHashes.has(id) || !PeerWire.hash(event).equals(value.remoteHashes.getString(id)))
                        throw new IllegalArgumentException("流水内容与摘要不一致");
                }
                int added;
                synchronized (store) {
                    LedgerStore.Trip current = store.getTrip(value.local.trip.id);
                    if (current == null || !current.secret.equals(value.local.trip.secret) || (value.local.trip.team != null &&
                            (current.team == null || !value.local.trip.team.fingerprint.equals(current.team.fingerprint))))
                        throw new IllegalArgumentException("旅行已从本机移除");
                    added = store.mergeStrict(current.id, events);
                }
                for (int i = 0; i < events.length(); i++) value.missing.remove(events.getJSONObject(i).getString("id"));
                value.receivedHashes.put(PeerWire.hash(bundle)); result.added += added;
                if (added > 0) listener.stored(added);
            } catch (Exception error) { value.failed = true; }
        }
        throw new IllegalArgumentException("同步消息数量过多");
    }
    private JSONArray receipts() throws Exception {
        JSONArray result = new JSONArray();
        if (manifestFailure) return result;
        for (Travel value : common.values()) {
            if (value.failed || !value.missing.isEmpty() || store.getTrip(value.local.trip.id) == null) continue;
            result.put(new JSONObject().put("tripId", value.local.trip.id).put("token",
                    SyncCodec.receipt(wireTrip(value.local.trip), session, this.result.remoteId, deviceId, value.receivedHashes)));
        }
        return result;
    }
    private boolean verifyReceipts(JSONArray receipts) throws Exception {
        if (receipts == null) return false;
        Map<String, String> tokens = new LinkedHashMap<>();
        for (int i = 0; i < receipts.length(); i++) {
            JSONObject value = receipts.getJSONObject(i); tokens.put(value.getString("tripId"), value.getString("token"));
        }
        boolean complete = true;
        for (Travel value : common.values()) {
            String expected = SyncCodec.receipt(wireTrip(value.local.trip), session, deviceId, result.remoteId, value.sentHashes);
            String actual = tokens.get(value.local.trip.id);
            if (actual == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII))) complete = false;
        }
        return complete;
    }
    private void finish(boolean acknowledged) {
        result.complete = acknowledged && !common.isEmpty() && !manifestFailure; result.durable = true;
        for (Travel value : common.values()) if (value.failed || !value.missing.isEmpty()) result.complete = false;
        result.bytesRead = wire.bytesRead; result.bytesWritten = wire.bytesWritten;
    }

    // The published desktop/web client still uses version 1. Keep that path interoperable.
    private JSONObject legacySnapshot() throws Exception {
        JSONArray bundles = new JSONArray();
        for (Snapshot snapshot : snapshots.values()) bundles.put(SyncCodec.bundle(store, snapshot.trip));
        return new JSONObject().put("version", 1).put("bundles", bundles).put("peerProtocol", VERSION).put("deviceId", deviceId);
    }
    private JSONArray mergeLegacy(JSONObject message) throws Exception {
        if (message.getInt("version") != 1) throw new IllegalArgumentException("同步协议不一致");
        JSONArray accepted = new JSONArray(), bundles = message.getJSONArray("bundles");
        if (bundles.length() > 100) throw new IllegalArgumentException("旅行数量过多");
        boolean failure = false;
        for (int i = 0; i < bundles.length(); i++) {
            try {
                JSONObject bundle = bundles.getJSONObject(i);
                synchronized (store) {
                    LedgerStore.Trip trip = store.getTrip(bundle.getString("tripId"));
                    if (trip == null || trip.team != null) continue;
                    int added = store.mergeStrict(trip.id, SyncCodec.legacyEvents(trip, bundle));
                    result.added += added; if (added > 0) listener.stored(added);
                    accepted.put(bundle);
                }
            } catch (Exception error) { failure = true; }
        }
        result.complete = !failure && accepted.length() > 0;
        result.authenticated = accepted.length() > 0;
        return accepted;
    }
    private JSONArray legacyReceipts(JSONArray values, String sender, String receiver) throws Exception {
        JSONArray receipts = new JSONArray();
        for (int i = 0; i < values.length(); i++) {
            JSONObject bundle = values.getJSONObject(i); LedgerStore.Trip trip = store.getTrip(bundle.getString("tripId"));
            if (trip != null && trip.team == null) receipts.put(new JSONObject().put("tripId", trip.id).put("token",
                    SyncCodec.receipt(trip, session, sender, receiver, new JSONArray().put(PeerWire.hash(bundle)))));
        }
        return receipts;
    }
    private boolean verifyLegacyReceipts(JSONArray expectedBundles, JSONArray receipts, String remote, JSONArray sharedBundles) throws Exception {
        if (receipts == null) return false;
        JSONArray expected = legacyReceipts(expectedBundles, deviceId, remote);
        Set<String> required = new HashSet<>();
        for (int i = 0; i < sharedBundles.length(); i++) required.add(sharedBundles.getJSONObject(i).getString("tripId"));
        Map<String, String> tokens = new LinkedHashMap<>();
        for (int i = 0; i < receipts.length(); i++) tokens.put(receipts.getJSONObject(i).getString("tripId"), receipts.getJSONObject(i).getString("token"));
        int shared = 0, needed = 0;
        for (int i = 0; i < expected.length(); i++) {
            JSONObject value = expected.getJSONObject(i); String actual = tokens.get(value.getString("tripId"));
            if (!required.contains(value.getString("tripId"))) continue;
            needed++;
            if (actual != null && MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), value.getString("token").getBytes(StandardCharsets.US_ASCII))) shared++;
        }
        return needed > 0 && shared == needed;
    }
    private Result legacyInitiator() throws Exception {
        session = UUID.randomUUID().toString(); JSONObject sent = legacySnapshot().put("session", session);
        wire.write(sent); JSONObject response = wire.read(); JSONArray received = mergeLegacy(response);
        if (response.optInt("peerProtocol") == VERSION && PeerIdentity.valid(response.optString("deviceId"))) {
            result.remoteId = response.getString("deviceId"); result.modern = true; result.durable = true;
            if (!session.equals(response.optString("session"))) throw new IllegalArgumentException("同步会话不匹配");
            boolean ack = verifyLegacyReceipts(sent.getJSONArray("bundles"), response.optJSONArray("receipts"), result.remoteId, received);
            result.complete &= ack; result.authenticated = ack;
            if (ack) listener.authenticated(result.remoteId);
            wire.write(new JSONObject().put("version", 1).put("kind", "ack").put("session", session)
                    .put("receipts", legacyReceipts(received, result.remoteId, deviceId)));
        }
        result.bytesRead = wire.bytesRead; result.bytesWritten = wire.bytesWritten; return result;
    }
    private Result legacyReceiver(JSONObject request) throws Exception {
        JSONArray received = mergeLegacy(request); JSONObject response = legacySnapshot();
        boolean modern = request.optInt("peerProtocol") == VERSION && PeerIdentity.valid(request.optString("deviceId")) &&
                PeerIdentity.valid(request.optString("session"));
        if (modern) {
            session = request.getString("session"); result.remoteId = request.getString("deviceId"); result.modern = true; result.durable = true;
            response.put("session", session).put("receipts", legacyReceipts(received, result.remoteId, deviceId));
        }
        wire.write(response);
        if (modern) {
            JSONObject confirmation = wire.read();
            if (!"ack".equals(confirmation.optString("kind")) || !session.equals(confirmation.optString("session")))
                throw new IllegalArgumentException("等待同伴保存确认");
            boolean ack = verifyLegacyReceipts(response.getJSONArray("bundles"), confirmation.optJSONArray("receipts"), result.remoteId, received);
            result.complete &= ack; result.authenticated = ack;
            if (ack) listener.authenticated(result.remoteId);
        }
        result.bytesRead = wire.bytesRead; result.bytesWritten = wire.bytesWritten; return result;
    }
}
