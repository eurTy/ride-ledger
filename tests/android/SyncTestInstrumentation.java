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
            JSONObject fixture = new JSONObject(new String(Base64.decode(arguments.getString("fixture"),
                    Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8));
            runChecks(fixture, result);
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
