package com.rideledger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class LedgerArchitectureChecks {
    static void run(LedgerStore store, PeerProtocolChecks.Sink sink) {
        sink.check("peer presence: verified routes change channel, disappear on loss and expire without polling", () -> {
            PeerPresence presence = new PeerPresence();
            Object wifi = new Object(), bluetooth = new Object(), hotspot = new Object();
            presence.authenticated(wifi, "m2", PeerRoutePolicy.Channel.WIFI, "wifi");
            require(presence.links(100).get(0).active, "authenticated socket did not appear");
            presence.finished(wifi, "wifi", 200);
            require(!presence.links(200).get(0).active, "completed socket was shown as held open");
            presence.authenticated(bluetooth, "m2", PeerRoutePolicy.Channel.BLUETOOTH, "ble");
            presence.finished(bluetooth, "ble", 300);
            require(presence.links(300).get(0).channel == PeerRoutePolicy.Channel.BLUETOOTH,
                    "a new BLE exchange did not replace the older Wi-Fi indication");
            presence.authenticated(hotspot, "m3", PeerRoutePolicy.Channel.HOTSPOT, "hotspot");
            require(presence.links(350).size() == 2, "second device was not represented separately");
            presence.lost(PeerRoutePolicy.Channel.HOTSPOT);
            presence.finished(hotspot, "hotspot", 400);
            require(presence.links(400).size() == 1, "lost radio reappeared when its old session closed");
            presence.lost("wifi");
            require(presence.links(300 + PeerPresence.RECENT_MILLIS).isEmpty(), "stale teammate did not expire");
        });

        sink.check("money: exact currency precision and malformed amount rejection", () -> {
            require(Money.parse("101", "JPY") == 101, "JPY fraction handling changed");
            require(Money.parse("1.234", "KWD") == 1234, "KWD lost precision");
            require(Money.parseShare("", "CNY") == 0, "empty share must be zero");
            for (String invalid : new String[]{"1e2147483647", "NaN", "-1", "0.001", "99999999999999999999999999"}) {
                boolean rejected = false;
                try { Money.parse(invalid, "CNY"); }
                catch (IllegalArgumentException | ArithmeticException expected) { rejected = true; }
                require(rejected, "invalid amount was accepted");
            }
        });

        sink.check("ledger: uneven splits, foreign cash and repayments conserve exact amounts", () -> {
            LedgerStore.Trip trip = store.createTeam("五人多币种", Arrays.asList("甲", "乙", "丙", "丁", "戊"));
            LedgerActions actions = new LedgerActions(store);
            actions.expense(trip, "m1", "JPY", "101", false, "尾差");
            actions.cash(trip, "m2", "CNY", "100");
            actions.exchange(trip, "m2", "CNY", "USD", "50", "7");
            LedgerActions.CustomSplit split = LedgerActions.customSplit(trip, "USD", new String[]{"36", "23", "", "", ""});
            actions.expense(trip, "m2", split, "餐费");
            actions.transfer(trip, "m1", "m2", "USD", "36");
            LedgerStore.Summary totals = store.summary(trip.id);
            require(totals.spent.get("JPY") == 101 && totals.spent.get("USD") == 5900,
                    "totals combined currencies or lost a rounding unit");
            require(totals.teamCash.get("m2").get("CNY") == 5000 && totals.teamCash.get("m2").get("USD") == -1600,
                    "cash from exchange, purchase and repayment did not reconcile");
            require(totals.teamNet.get("m1").get("JPY") == 80 && totals.teamNet.get("m2").get("JPY") == -20,
                    "equal split remainder was allocated incorrectly");
            for (Map<String, Long> net : totals.teamNet.values())
                require(net.getOrDefault("USD", 0L) == 0, "exact repayment did not settle USD");
            LedgerStore.Event exchange = store.exchangesAndDeletes(trip.id).get(0);
            actions.delete(trip, java.util.Collections.singletonList(exchange.id));
            actions.exchange(trip, "m2", "KWD", "JPY", "1.234", "101");
            require(Arrays.equals(actions.exchangeDefaults(trip.id), new String[]{"KWD", "JPY"}),
                    "exchange defaults ignored newest visible exchange");
            String lastExchange = store.exchangesAndDeletes(trip.id).stream()
                    .filter(event -> "exchange".equals(event.kind) && !event.id.equals(exchange.id)).findFirst().get().id;
            actions.delete(trip, java.util.Collections.singletonList(lastExchange));
            require(Arrays.equals(actions.exchangeDefaults(trip.id), new String[]{"CNY", "USD"}),
                    "a deleted exchange was used as the default");
        });

        sink.check("journal: a large snapshot has a stable row checkpoint independent of the device clock", () -> {
            LedgerStore.Trip trip = store.createOpenTeam("大账本", "甲", 1);
            JSONArray entries = new JSONArray();
            for (int i = 0; i < 1500; i++) entries.put(new LedgerStore.Event(UUID.randomUUID().toString(),
                    trip.id, "topup", 10000 - i, new JSONObject().put("person", "m1")
                    .put("currency", "CNY").put("amount", 1)).json());
            require(store.mergeStrict(trip.id, entries) == 1500, "bulk ledger did not commit");
            LedgerStore.Snapshot snapshot = store.snapshot(trip.id);
            LedgerStore.Event extra = store.add(trip.id, "topup", new JSONObject().put("person", "m1")
                    .put("currency", "CNY").put("amount", 200));
            require(snapshot.events.size() == 1500 && snapshot.totals().teamCash.get("m1").get("CNY") == 1500,
                    "later write changed the frozen snapshot");
            require(snapshot.syncPoint.event.equals(entries.getJSONObject(1499).getString("id")) &&
                    store.countAfter(trip.id, snapshot.syncPoint.row) == 1 &&
                    store.snapshot(trip.id).syncPoint.event.equals(extra.id),
                    "checkpoint followed displayed time instead of committed row order");
        });

        sink.check("routes: reused address and rediscovery never inherit stale confirmations", () -> {
            AtomicLong clock = new AtomicLong(100);
            PeerRoutePolicy policy = new PeerRoutePolicy(clock::get);
            PeerRoutePolicy.Endpoint old = endpoint(UUID.randomUUID().toString());
            PeerRoutePolicy.Endpoint next = endpoint(UUID.randomUUID().toString());
            policy.offer(old);
            require(policy.readyForRevision(1, "").get(0) == old, "old peer did not dial");
            require(policy.offer(next), "different device did not replace stale address");
            policy.syncSuccess(old, 1);
            require(policy.readyForRevision(1, "").get(0) == next, "old session confirmed the wrong device");
            for (int i = 0; i < 4; i++) {
                policy.syncFailure(next);
                if (i < 3) {
                    clock.addAndGet(2000L << i);
                    require(policy.readyForRevision(1, "").get(0) == next, "route failed to retry");
                }
            }
            require(!policy.offer(next) && policy.readyForRevision(1, "").isEmpty(),
                    "repeat discovery bypassed the retry budget");
            clock.addAndGet(30000);
            require(policy.offer(next), "a returned peer could not wake the exhausted route");
            List<PeerRoutePolicy.Endpoint> ready = policy.readyForRevision(1, "");
            require(ready.size() == 1 && ready.get(0) == next, "rediscovery did not resume synchronization");
        });
    }

    private static PeerRoutePolicy.Endpoint endpoint(String id) {
        return new PeerRoutePolicy.Endpoint("wifi:reused", PeerRoutePolicy.Channel.WIFI, id, true, null);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
