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

        check("fragmented LAN TCP frames and duplicate replay", () -> {
            LedgerStore local = store(context()); local.joinTrip(invite, "a");
            SyncResult sync = lan(local, fixture, "lanPort");
            require(!sync.failed && local.events(tripId).size() == expectedCount, "fragmented frame did not merge");
            require(lan(local, fixture, "lanPort").added == 0, "TCP replay duplicated records");
            verifySummary(local.summary(tripId), fixture.getJSONObject("expectedSummary"));
        });
        check("truncated LAN frame retains local data and retries", () -> {
            JSONArray left = new JSONArray();
            JSONArray all = fixture.getJSONArray("events");
            for (int i = 0; i < all.length(); i += 2) left.put(all.getJSONObject(i));
            LedgerStore local = store(context()); local.joinTrip(invite, "a"); local.merge(tripId, left);
            require(lan(local, fixture, "lanDropPort").failed, "truncated frame reported success");
            require(local.events(tripId).size() == left.length(), "partial frame changed records");
            lan(local, fixture, "lanPort");
            require(local.events(tripId).size() == expectedCount, "TCP retry did not converge");
        });
        check("corrupt LAN bundle does not block healthy records or hide partial progress", () -> {
            LedgerStore local = store(context()); local.joinTrip(invite, "a");
            SyncResult sync = lan(local, fixture, "lanCorruptPort");
            require(sync.failed && local.events(tripId).size() == expectedCount && sync.added == expectedCount,
                    "corrupt LAN bundle blocked healthy records or partial progress");
        });
        PeerProtocolChecks.run(getTargetContext(), fixture, (name, action) -> check(name, action::run));
        check("ledger totals reject an overflowing event without applying a partial debit", () -> {
            LedgerStore.Event cashA = new LedgerStore.Event("a", "trip", "opening", 1,
                    new JSONObject().put("person", "a").put("currency", "CNY").put("amount", 10L));
            LedgerStore.Event cashB = new LedgerStore.Event("b", "trip", "opening", 2,
                    new JSONObject().put("person", "b").put("currency", "CNY").put("amount", Long.MAX_VALUE));
            LedgerStore.Event transfer = new LedgerStore.Event("c", "trip", "transfer", 3,
                    new JSONObject().put("from", "a").put("currency", "CNY").put("amount", 1L));
            LedgerStore.Summary totals = LedgerTotals.calculate(false, java.util.Arrays.asList(cashA, cashB, transfer));
            require(totals.incomplete && totals.cashA.get("CNY") == 10L &&
                    totals.cashB.get("CNY") == Long.MAX_VALUE && totals.visible.contains(transfer),
                    "overflowing event partly changed the balances or disappeared");
        });
        check("UI: one team creation entry and no sync coordinator on an empty ledger", () -> PeerUiChecks.run(this));
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            LedgerArchitectureChecks.run(store(context()), (name, action) -> check(name, action::run));
            check("mixed version three database upgrade keeps team records and enrollment", () -> {
                TestContext mixed = context();
                LedgerStore before = store(mixed);
                LedgerStore.Trip oldTrip = before.createTrip("Old", "A", "B");
                before.add(oldTrip.id, "opening", new JSONObject().put("person", "a").put("currency", "CNY").put("amount", 123));
                LedgerStore.Trip team = before.createTeam("Team", java.util.Arrays.asList("A", "B"));
                before.add(team.id, "opening", new JSONObject().put("person", "m1").put("currency", "CNY").put("amount", 456));
                before.getWritableDatabase().execSQL("UPDATE trips SET secret=? WHERE id=?", new Object[]{team.secret, team.id});
                before.getWritableDatabase().setVersion(3);
                before.close();
                LedgerStore after = store(mixed);
                require(after.getTrip(oldTrip.id) == null && after.events(oldTrip.id).isEmpty(), "old trip was not removed");
                LedgerStore.Trip retained = after.getTrip(team.id);
                require(retained != null && retained.secret.equals(team.secret) && after.teamReady(retained) &&
                        after.summary(team.id).teamCash.get("m1").get("CNY") == 456L, "team cash or enrollment was lost");
                try (android.database.Cursor row = after.getReadableDatabase().rawQuery(
                        "SELECT secret FROM trips WHERE id=?", new String[]{team.id})) {
                    require(row.moveToFirst() && row.getString(0).startsWith("keystore:v1:") &&
                            !row.getString(0).contains(team.secret), "legacy team secret remained in cleartext");
                }
            });
            check("version six journal upgrade indexes existing edits and keeps original record time", () -> {
                TestContext migrated = context();
                LedgerStore before = store(migrated);
                LedgerStore.Trip trip = before.createTeam("旧修订升级", java.util.Arrays.asList("甲", "乙"));
                LedgerActions actions = new LedgerActions(before);
                actions.cash(trip, "m1", "CNY", "1");
                LedgerStore.Event original = before.events(trip.id).get(0);
                before.add(trip.id, "edit", new JSONObject().put("target", original.id)
                        .put("kind", original.kind).put("revision", 1).put("record", new JSONObject()
                                .put("person", "m1").put("currency", "CNY").put("amount", 200)));
                before.getWritableDatabase().execSQL("UPDATE events SET target_id=NULL");
                before.getWritableDatabase().setVersion(6);
                before.close();
                LedgerStore after = store(migrated);
                LedgerStore.Event revised = after.summary(trip.id).visible.get(0);
                new LedgerActions(after).cash(trip, "m1", "CNY", "3", revised);
                LedgerStore.Event latestEdit = after.summary(trip.id).visible.get(0);
                require(latestEdit.at == original.at && latestEdit.id.equals(original.id) && latestEdit.data.getLong("amount") == 300,
                        "upgraded legacy revision could not be edited");
            });
            check("background trip rotation survives removed and reordered trips", () -> {
                LedgerStore.Trip rotationA = new LedgerStore.Trip("a", "", "", "", "", "");
                LedgerStore.Trip rotationB = new LedgerStore.Trip("b", "", "", "", "", "");
                LedgerStore.Trip rotationC = new LedgerStore.Trip("c", "", "", "", "", "");
                java.util.List<LedgerStore.Trip> trips = java.util.Arrays.asList(rotationC, rotationA, rotationB);
                require(SyncJobService.nextTrip(trips, "") == rotationA, "initial position incorrect");
                require(SyncJobService.nextTrip(trips, rotationA.id) == rotationB, "first attempt starved next trip");
                require(SyncJobService.nextTrip(java.util.Arrays.asList(rotationC, rotationA), rotationB.id) == rotationC,
                        "removed cursor skipped next trip");
                require(SyncJobService.nextTrip(trips, rotationC.id) == rotationA, "queue did not wrap");
            });
            TeamChecks.run(getTargetContext(), fixture, (name, action) -> check(name, action::run));
            check("review groups currencies by payment direction", () -> {
                LedgerStore.Trip trip = store(context()).createTeam("结算检查",
                        java.util.Arrays.asList("甲", "乙", "丙"));
                LedgerStore.Summary summary = new LedgerStore.Summary();
                for (int i = 0; i < trip.people(); i++) {
                    Map<String, Long> balance = new java.util.HashMap<>();
                    balance.put("TJS", i == 0 ? 61200L : -30600L);
                    balance.put("USD", i == 0 ? 5732L : -2866L);
                    summary.teamNet.put(trip.member(i), balance);
                }
                // A payment in the opposite direction belongs to a separate group.
                summary.teamNet.get(trip.member(0)).put("EUR", -200L);
                summary.teamNet.get(trip.member(1)).put("EUR", 200L);
                List<LedgerReview.Debt> debts = new LedgerReview(trip, summary).settlement;
                require(debts.size() == 3 && debts.get(0).payer.equals("甲") && debts.get(0).recipient.equals("乙")
                        && debts.get(0).amounts.equals(java.util.Collections.singletonList("2.00 EUR")),
                        "opposite directions were combined");
                for (int i = 1; i < 3; i++) {
                    LedgerReview.Debt debt = debts.get(i);
                    require(debt.payer.equals(trip.nameOf(trip.member(i))) && debt.recipient.equals("甲")
                            && debt.amounts.equals(java.util.Arrays.asList("306.00 TJS", "28.66 USD")),
                            "currencies or members were lost during grouping");
                }
            });
            check("two-person team UI: quick entry, review and sync use the same team flow", () -> PeerUiChecks.runTeam(this));
            check("custom split UI: payer, individual shares and settlement agree", () -> PeerUiChecks.runCustomSplit(this));
            check("record deletion: full-list checkboxes, confirmation, offline propagation and replay", () -> PeerUiChecks.runRecordDeletion(this));
            check("record editing: bottom sheet, original time and offline propagation", () -> PeerUiChecks.runRecordEdit(this));
            check("review badge: verified channels, Wi-Fi failure falls back to Bluetooth on tap, and recent contact expires", () -> PeerUiChecks.runRosterRefresh(this));
            check("open team UI: organizer roster and validation are inline", () -> PeerUiChecks.runTeamSetup(this));
            check("open team UI: join details, connecting and delayed success are inline", () -> PeerUiChecks.runTeamJoin(this));
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
    private SyncResult lan(LedgerStore store, JSONObject fixture, String portKey) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> status = new AtomicReference<>("");
        AtomicInteger added = new AtomicInteger();
        LanSync sync = new LanSync(getTargetContext(), store, (message, count) -> {
            status.set(message); added.set(count); completed.countDown();
        });
        // Exercise real framing and merge code without broadcasting test trips over the user's Wi-Fi.
        Field running = LanSync.class.getDeclaredField("running"); running.setAccessible(true); running.set(sync, true);
        Method exchange = LanSync.class.getDeclaredMethod("exchange", InetSocketAddress.class);
        exchange.setAccessible(true);
        exchange.invoke(sync, new InetSocketAddress(fixture.optString("testHost", "10.0.2.2"), fixture.getInt(portKey)));
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
