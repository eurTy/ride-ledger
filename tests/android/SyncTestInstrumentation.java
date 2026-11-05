package com.rideledger;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Runs production SQLite, invitation, AES-GCM, HTTP and TCP code in an isolated package. */
public final class SyncTestInstrumentation extends Instrumentation {
    private Bundle arguments;
    private final List<TestContext> contexts = new ArrayList<>();
    private final List<LedgerStore> stores = new ArrayList<>();
    private final JSONArray passed = new JSONArray(), failed = new JSONArray();
    private interface Check { void run() throws Exception; }

    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (arguments.containsKey("desktopPeerInvite")) runDesktopPeer(result);
            else if (arguments.containsKey("desktopInvite")) runDesktopConnection(result);
            else {
                JSONObject fixture = new JSONObject(new String(Base64.decode(arguments.getString("fixture"),
                        Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8));
                runChecks(fixture, result);
            }
        } catch (Throwable error) {
            failed.put("test harness: " + error.toString());
        } finally {
            for (LedgerStore store : stores) store.close();
            for (TestContext context : contexts) context.cleanup();
        }
        try { result.putString("report", new JSONObject().put("passed", passed).put("failed", failed).toString()); }
        catch (Exception error) { result.putString("report", "{\"failed\":[\"report generation failed\"]}"); }
        finish(failed.length() == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void check(String name, Check action) {
        try { action.run(); passed.put(name); }
        catch (Throwable error) { failed.put(name + ": " + error.toString()); }
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private TestContext context() {
        TestContext context = new TestContext(getTargetContext(), "sync_" + UUID.randomUUID().toString());
        contexts.add(context);
        return context;
    }
    private LedgerStore store(TestContext context) {
        LedgerStore store = new LedgerStore(context); stores.add(store); return store;
    }

    private void runDesktopConnection(Bundle result) throws Exception {
        TestContext context = context();
        LedgerStore phone = store(context);
        LedgerStore.Trip travel = phone.joinTrip(arguments.getString("desktopInvite"), "b");
        int expected = Integer.parseInt(arguments.getString("expectedEvents"));
        check("desktop public invitation automatically configures Android connection", () -> {
            require(travel.syncUrl.startsWith("https://") && travel.localPerson.equals("b"), "connection or role missing");
            SyncResult sync = cloud(context, phone, "");
            require(!sync.failed && phone.events(travel.id).size() == expected,
                    "Android could not receive desktop records using only the invitation");
        });
        check("Android offline entry uploads to desktop through public HTTPS", () -> {
            phone.add(travel.id, "expense", new JSONObject().put("payer", "b").put("currency", "CNY")
                    .put("amount", 700).put("shareA", 5000).put("mode", "shared")
                    .put("note", "Android offline entry via public invitation"));
            require(phone.events(travel.id).size() == expected + 1, "offline entry was not stored");
            SyncResult sync = cloud(context, phone, "");
            require(!sync.failed, "Android entry could not upload to desktop");
        });
        check("Android connection and ledger survive reopening without manual server configuration", () -> {
            phone.close();
            LedgerStore reopened = store(context);
            require(reopened.getTrip(travel.id).syncUrl.equals(travel.syncUrl), "connection was lost");
            require(reopened.events(travel.id).size() == expected + 1, "ledger was lost");
            SyncResult sync = cloud(context, reopened, "");
            require(!sync.failed && reopened.events(travel.id).size() == expected + 1, "repeated sync duplicated records");
            result.putString("phoneBundle", SyncCodec.bundle(reopened, reopened.getTrip(travel.id)).toString());
        });
    }

    private void runDesktopPeer(Bundle result) throws Exception {
        TestContext context = context();
        LedgerStore phone = store(context);
        LedgerStore.Trip travel = phone.joinTrip(arguments.getString("desktopPeerInvite"), "b");
        String address = arguments.getString("desktopPeerAddress");
        int expected = Integer.parseInt(arguments.getString("expectedEvents"));
        phone.add(travel.id, "expense", new JSONObject().put("payer", "b").put("currency", "CNY")
                .put("amount", 900).put("shareA", 5000).put("mode", "shared")
                .put("note", "Android peer / 保存确认 \\\" 🚴\n第二行"));
        check("desktop peer refuses save confirmation when the renderer cannot persist records", () -> {
            PeerProtocol.Result exchange = peerExchange(context, phone, address, true);
            require(!exchange.complete, "desktop acknowledged a failed local save");
            require(phone.events(travel.id).size() == 1, "failed transfer changed the offline record");
        });
        check("Android 10 and desktop v2 transfer both ways with authenticated durable receipts", () -> {
            PeerProtocol.Result exchange = peerExchange(context, phone, address, true);
            require(exchange.complete && exchange.durable && exchange.authenticated && exchange.modern,
                    "v2 confirmation failed");
            require(exchange.added == expected && exchange.sent == 1 && phone.events(travel.id).size() == expected + 1,
                    "v2 did not exchange missing records in both directions");
        });
        check("manual v1 negotiation authenticates the newest desktop and preserves identities", () -> {
            phone.add(travel.id, "expense", new JSONObject().put("payer", "b").put("currency", "USD")
                    .put("amount", 375).put("shareA", 5000).put("mode", "shared").put("note", "Android peer manual negotiation"));
            PeerProtocol.Result exchange = peerExchange(context, phone, address, false);
            require(exchange.complete && exchange.modern && exchange.durable, "manual negotiation did not confirm storage");
            require(phone.events(travel.id).size() == expected + 2 && phone.getTrip(travel.id).localPerson.equals("b"),
                    "manual negotiation changed records or identity");
        });
        check("reopening and replay over v2 keep one copy and send no already saved records", () -> {
            phone.close();
            LedgerStore reopened = store(context);
            PeerProtocol.Result exchange = null;
            for (int attempt = 0; attempt < 6; attempt++) {
                exchange = peerExchange(context, reopened, address, true);
                require(reopened.events(travel.id).size() == expected + 2, "replay lost or duplicated records");
                if (exchange.complete && exchange.sent == 0) break;
                Thread.sleep(200);
            }
            require(exchange != null && exchange.complete && exchange.sent == 0, "desktop did not converge to a matching root");
            result.putString("phoneBundle", SyncCodec.bundle(reopened, reopened.getTrip(travel.id)).toString());
        });
    }

    private PeerProtocol.Result peerExchange(TestContext context, LedgerStore phone, String address, boolean modern) throws Exception {
        int separator = address.lastIndexOf(':');
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress(address.substring(0, separator), Integer.parseInt(address.substring(separator + 1))), 3500);
            socket.setSoTimeout(15000);
            PeerProtocol protocol = new PeerProtocol(phone, PeerIdentity.deviceId(context), new PeerProtocol.Listener() {
                public void authenticated(String id) { }
                public void stored(int added) { }
            });
            return protocol.exchange(new PeerWire(socket.getInputStream(), socket.getOutputStream(), () -> {}), modern);
        }
    }

    private void runChecks(JSONObject fixture, Bundle result) throws Exception {
        String invite = fixture.getString("inviteA");
        JSONObject meta = fixture.getJSONObject("trip");
        String tripId = meta.getString("id");
        TestContext firstContext = context();
        LedgerStore first = store(firstContext);
        check("web invitation A defaults to B", () -> {
            LedgerStore.Trip preview = first.previewInvite(invite);
            require(preview.id.equals(tripId) && preview.localPerson.equals("b"), "wrong default identity");
            require(preview.personA.equals(meta.getString("personA")) &&
                    preview.personB.equals(meta.getString("personB")), "Unicode names changed");
        });
        check("web invitation B defaults to A", () -> require(
                first.previewInvite(fixture.getString("inviteB")).localPerson.equals("a"), "wrong reverse role"));
        check("legacy invitation", () -> require(
                first.previewInvite(fixture.getString("legacyInvite")).localPerson.equals("b"), "wrong legacy role"));
        check("line-wrapped clipboard invitation", () -> require(
                first.previewInvite(fixture.getString("wrappedInvite")).id.equals(tripId), "wrapped invite failed"));
        JSONArray invalid = fixture.getJSONArray("invalidInvites");
        for (int index = 0; index < invalid.length(); index++) {
            JSONObject item = invalid.getJSONObject(index);
            check("reject invitation " + item.getString("name"), () -> {
                boolean rejected = false;
                try { first.previewInvite(item.getString("code")); } catch (Exception expected) { rejected = true; }
                require(rejected, "malformed invitation accepted");
            });
        }
        LedgerStore.Trip joined = first.joinTrip(invite, "b");
        check("duplicate join does not switch saved identity", () -> {
            boolean rejected = false;
            try { first.joinTrip(invite, "a"); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected && first.getTrip(tripId).localPerson.equals("b"), "duplicate changed identity");
        });
        result.putString("androidInvite", first.inviteCode(joined));
        JSONObject webBundle = fixture.getJSONObject("webBundle");
        int expectedCount = fixture.getJSONArray("events").length();
        check("web AES-GCM bundle and duplicate replay", () -> {
            require(SyncCodec.mergeBundle(first, webBundle) == expectedCount, "missing web records");
            require(SyncCodec.mergeBundle(first, webBundle) == 0, "replay duplicated records");
            verifySummary(first.summary(tripId), fixture.getJSONObject("expectedSummary"));
        });
        result.putString("androidBundle", SyncCodec.bundle(first, joined).toString());
        check("invitation carries per-travel connection and updates retain records and identity", () -> {
            String connected = new JSONObject(meta.toString()).put("inviterRole", "b")
                    .put("syncUrl", "https://relay.example.test/").toString();
            String code = Base64.encodeToString(connected.getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            LedgerStore.Trip updated = first.joinTrip(code, "a");
            require(updated.localPerson.equals("b") && updated.syncUrl.equals("https://relay.example.test"),
                    "updating connection changed the identity");
            require(first.events(tripId).size() == expectedCount, "updating connection changed records");
            CloudSync cloud = new CloudSync(firstContext, first, (message, count) -> { });
            try { require(cloud.effectiveEndpoint(updated).equals(updated.syncUrl), "invite connection was not selected"); }
            finally { cloud.close(); }
            result.putString("connectedInvite", first.inviteCode(updated));
        });
        check("version one database upgrade removes old dual trips and their records", () -> {
            TestContext oldContext = context();
            SQLiteDatabase old = oldContext.openOrCreateDatabase("ride_ledger.db", Context.MODE_PRIVATE, null);
            old.execSQL("CREATE TABLE trips (id TEXT PRIMARY KEY,name TEXT NOT NULL,person_a TEXT NOT NULL," +
                    "person_b TEXT NOT NULL,secret TEXT NOT NULL,local_person TEXT NOT NULL,created_at INTEGER NOT NULL)");
            old.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY,trip_id TEXT NOT NULL,kind TEXT NOT NULL," +
                    "at INTEGER NOT NULL,data TEXT NOT NULL)");
            old.execSQL("INSERT INTO trips VALUES (?,?,?,?,?,?,?)", new Object[]{tripId, meta.getString("name"),
                    meta.getString("personA"), meta.getString("personB"), meta.getString("secret"), "a", 1});
            JSONObject entry = fixture.getJSONArray("events").getJSONObject(0);
            old.execSQL("INSERT INTO events VALUES (?,?,?,?,?)", new Object[]{entry.getString("id"), tripId,
                    entry.getString("kind"), entry.getLong("at"), entry.getJSONObject("data").toString()});
            old.setVersion(1); old.close();
            LedgerStore upgraded = store(oldContext);
            require(upgraded.getTrip(tripId) == null && upgraded.events(tripId).isEmpty(),
                    "old dual trip or events survived upgrade");
        });
        check("incorrect key cannot import records", () -> {
            LedgerStore other = store(context());
            other.joinTrip(fixture.getString("wrongKeyInvite"), "a");
            boolean rejected = false;
            try { SyncCodec.mergeBundle(other, webBundle); } catch (Exception expected) { rejected = true; }
            require(rejected && other.events(tripId).isEmpty(), "wrong key imported records");
        });

        TestContext aContext = context(), bContext = context();
        LedgerStore a = store(aContext), b = store(bContext);
        LedgerStore.Trip aTrip = a.joinTrip(invite, "a");
        b.joinTrip(invite, "b");
        JSONArray all = fixture.getJSONArray("events"), left = new JSONArray(), right = new JSONArray();
        for (int i = 0; i < all.length(); i++) (i % 2 == 0 ? left : right).put(all.getJSONObject(i));
        a.merge(tripId, left); b.merge(tripId, right);
        String endpoint = fixture.getString("endpoint");
        check("Android devices synchronize through separate HTTP paths", () -> {
            cloud(aContext, a, endpoint + "/normal");
            cloud(bContext, b, endpoint + "/normal");
            cloud(aContext, a, endpoint + "/normal");
            require(a.events(tripId).size() == expectedCount && b.events(tripId).size() == expectedCount,
                    "native peers failed to converge");
            verifySummary(a.summary(tripId), fixture.getJSONObject("expectedSummary"));
            verifySummary(b.summary(tripId), fixture.getJSONObject("expectedSummary"));
            require(a.getTrip(tripId).localPerson.equals("a") && b.getTrip(tripId).localPerson.equals("b"),
                    "sync changed device identities");
        });
        check("lost HTTP acknowledgement retries without duplicating new records", () -> {
            a.add(tripId, "expense", new JSONObject().put("payer", "a").put("currency", "CNY")
                    .put("amount", 100).put("shareA", 5000).put("mode", "shared").put("note", "native lost ack"));
            require(cloud(aContext, a, endpoint + "/lost-ack").failed, "lost response reported success");
            cloud(bContext, b, endpoint + "/normal");
            cloud(aContext, a, endpoint + "/normal");
            require(a.events(tripId).size() == expectedCount + 1 && b.events(tripId).size() == expectedCount + 1,
                    "retry lost or duplicated the new record");
        });
        check("503, refused connection and invalid response preserve offline records", () -> {
            a.add(tripId, "expense", new JSONObject().put("payer", "a").put("currency", "USD")
                    .put("amount", 200).put("shareA", 5000).put("mode", "shared").put("note", "native offline"));
            for (String path : new String[]{"/503", "/truncated"})
                require(cloud(aContext, a, endpoint + path).failed, "failed request reported success");
            require(cloud(aContext, a, "http://127.0.0.1:1").failed, "refused connection reported success");
            require(a.events(tripId).size() == expectedCount + 2, "offline record lost");
            cloud(aContext, a, endpoint + "/normal"); cloud(bContext, b, endpoint + "/normal");
            require(b.events(tripId).size() == expectedCount + 2, "offline record did not upload later");
        });
        check("corrupt cloud snapshot does not block healthy devices or hide partial progress", () -> {
            TestContext cContext = context(); LedgerStore c = store(cContext);
            c.joinTrip(invite, "b");
            SyncResult sync = cloud(cContext, c, endpoint + "/corrupt");
            require(sync.failed, "corrupt snapshot reported complete success");
            require(c.events(tripId).size() == expectedCount + 2 && sync.added == expectedCount + 2,
                    "corrupt snapshot blocked valid records or progress reporting");
        });
        check("per-travel connection takes precedence over an unrelated global endpoint", () -> {
            TestContext connectedContext = context(); LedgerStore connected = store(connectedContext);
            connected.joinTrip(invite, "b");
            connected.setTripSyncUrl(tripId, endpoint + "/normal");
            SyncResult sync = cloud(connectedContext, connected, "http://127.0.0.1:1");
            require(!sync.failed && connected.events(tripId).size() == expectedCount + 2,
                    "global endpoint overrode the travel's connection");
        });
        result.putString("androidCloudBundle", SyncCodec.bundle(a, aTrip).toString());

        check("fragmented LAN TCP frames and duplicate replay", () -> {
            LedgerStore local = store(context()); local.joinTrip(invite, "a");
            SyncResult sync = lan(local, fixture.getInt("lanPort"));
            require(!sync.failed && local.events(tripId).size() == expectedCount, "fragmented frame did not merge");
            require(lan(local, fixture.getInt("lanPort")).added == 0, "TCP replay duplicated records");
            verifySummary(local.summary(tripId), fixture.getJSONObject("expectedSummary"));
        });
        check("truncated LAN frame retains local data and retries", () -> {
            LedgerStore local = store(context()); local.joinTrip(invite, "a"); local.merge(tripId, left);
            require(lan(local, fixture.getInt("lanDropPort")).failed, "truncated frame reported success");
            require(local.events(tripId).size() == left.length(), "partial frame changed records");
            lan(local, fixture.getInt("lanPort"));
            require(local.events(tripId).size() == expectedCount, "TCP retry did not converge");
        });
        check("corrupt LAN bundle does not block healthy records or hide partial progress", () -> {
            LedgerStore local = store(context()); local.joinTrip(invite, "a");
            SyncResult sync = lan(local, fixture.getInt("lanCorruptPort"));
            require(sync.failed && local.events(tripId).size() == expectedCount && sync.added == expectedCount,
                    "corrupt LAN bundle blocked healthy records or partial progress");
        });
        PeerProtocolChecks.run(getTargetContext(), fixture, (name, action) -> check(name, action::run));
        check("UI: one team creation entry and no sync coordinator on an empty ledger", () -> PeerUiChecks.run(this));
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            check("mixed version three database upgrade keeps team records and enrollment", () -> {
                TestContext mixed = context();
                LedgerStore before = store(mixed);
                LedgerStore.Trip oldTrip = before.createTrip("Old", "A", "B");
                before.add(oldTrip.id, "opening", new JSONObject().put("person", "a").put("currency", "CNY").put("amount", 123));
                LedgerStore.Trip team = before.createTeam("Team", java.util.Arrays.asList("A", "B"));
                before.add(team.id, "opening", new JSONObject().put("person", "m1").put("currency", "CNY").put("amount", 456));
                before.getWritableDatabase().setVersion(3);
                before.close();
                LedgerStore after = store(mixed);
                require(after.getTrip(oldTrip.id) == null && after.events(oldTrip.id).isEmpty(), "old trip was not removed");
                LedgerStore.Trip retained = after.getTrip(team.id);
                require(retained != null && after.teamReady(retained) &&
                        after.summary(team.id).teamCash.get("m1").get("CNY") == 456L, "team cash or enrollment was lost");
            });
            TeamChecks.run(getTargetContext(), fixture, (name, action) -> check(name, action::run));
            check("two-person team UI: quick entry, review and sync use the same team flow", () -> PeerUiChecks.runTeam(this));
        }
    }

    private static void verifySummary(LedgerStore.Summary sum, JSONObject expected) throws Exception {
        Map<String, Long>[] maps = new Map[]{sum.cashA, sum.cashB, sum.spent, sum.debtBToA};
        String[] names = {"cashA", "cashB", "spent", "debt"};
        for (int i = 0; i < names.length; i++) {
            JSONObject values = expected.getJSONObject(names[i]);
            require(maps[i].size() == values.length(), names[i] + " currency count differs");
            for (String code : maps[i].keySet())
                require(maps[i].get(code) == values.getLong(code), names[i] + " amount differs for " + code);
        }
    }
    private static final class SyncResult {
        final boolean failed; final int added;
        SyncResult(boolean failed, int added) { this.failed = failed; this.added = added; }
    }
    private SyncResult cloud(TestContext context, LedgerStore store, String endpoint) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> status = new AtomicReference<>("");
        AtomicInteger added = new AtomicInteger();
        CloudSync sync = new CloudSync(context, store, (message, count) -> {
            if (message.startsWith("云同步完成") || message.startsWith("云同步待重试")) {
                status.set(message); added.set(count); completed.countDown();
            }
        });
        try {
            sync.setEndpoint(endpoint); sync.start();
            require(completed.await(20, TimeUnit.SECONDS), "native cloud request did not finish");
            return new SyncResult(status.get().startsWith("云同步待重试"), added.get());
        } finally { sync.close(); }
    }
    private SyncResult lan(LedgerStore store, int port) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> status = new AtomicReference<>("");
        AtomicInteger added = new AtomicInteger();
        LanSync sync = new LanSync(getTargetContext(), store, (message, count) -> {
            status.set(message); added.set(count); completed.countDown();
        });
        // Exercise real framing and merge code without broadcasting test trips over the user's Wi-Fi.
        Field running = LanSync.class.getDeclaredField("running"); running.setAccessible(true); running.set(sync, true);
        Method exchange = LanSync.class.getDeclaredMethod("exchange", InetSocketAddress.class);
        exchange.setAccessible(true); exchange.invoke(sync, new InetSocketAddress("10.0.2.2", port));
        require(completed.await(15, TimeUnit.SECONDS), "native TCP request did not finish");
        running.set(sync, false);
        return new SyncResult(!status.get().startsWith("局域网同步完成"), added.get());
    }

    private static final class TestContext extends ContextWrapper {
        final String prefix;
        TestContext(Context base, String prefix) { super(base); this.prefix = prefix + "_"; }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences(prefix + name, mode);
        }
        @Override public File getDatabasePath(String name) { return super.getDatabasePath(prefix + name); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
            return super.openOrCreateDatabase(prefix + name, mode, factory);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
                                                            DatabaseErrorHandler handler) {
            return super.openOrCreateDatabase(prefix + name, mode, factory, handler);
        }
        void cleanup() {
            super.deleteDatabase(prefix + "ride_ledger.db");
            super.getSharedPreferences(prefix + "cloud", MODE_PRIVATE).edit().clear().commit();
        }
    }
}
