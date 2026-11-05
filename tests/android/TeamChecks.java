package com.rideledger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Real SQLite and encrypted streams, with a simulated proximity graph for the radio routes. */
final class TeamChecks {
    private static final List<String> MEMBERS = Arrays.asList("阿青", "小周", "大白", "阿满", "小赵");
    private static final PeerProtocol.Listener QUIET = new PeerProtocol.Listener() {
        @Override public void authenticated(String id) { }
        @Override public void stored(int added) { }
    };

    static void run(Context base, PeerProtocolChecks.Sink sink) {
        sink.check("team: one signed seat per member, limit five, and lost enrollment response retries", () -> {
            try (Device root = new Device(base); Device b = new Device(base); Device c = new Device(base);
                 Device d = new Device(base); Device e = new Device(base); Device extra = new Device(base)) {
                root.create();
                b.join(root, 1);
                Pair interrupted = exchange(b, root, "", "drop-second-frame");
                require(!interrupted.complete() && root.store.teamSeatCount(root.trip.id) == 2 &&
                        !b.store.teamReady(b.trip), "enrollment response loss did not preserve exactly one seat");
                b.reopen();
                require(exchange(b, root).complete() && b.store.teamReady(b.trip), "enrollment did not resume");
                extra.join(root, 1);
                Pair duplicateName = exchange(extra, root);
                require(duplicateName.clientError instanceof TeamProtocol.Admission &&
                        duplicateName.clientError.getMessage().contains("另一台设备"), "duplicate name was not blocked");
                c.enroll(root, 2);
                d.enroll(root, 3);
                e.enroll(root, 4);
                root.reopen();
                record(root, "CNY", 501, "private expense");
                Pair overflow = exchange(extra, root);
                require(overflow.clientError instanceof TeamProtocol.Admission &&
                        overflow.clientError.getMessage().contains("名额已满"), "sixth device was not blocked");
                require(root.store.teamSeatCount(root.trip.id) == 5 && !extra.store.teamReady(extra.trip) &&
                        extra.store.events(extra.trip.id).isEmpty(), "unregistered device accessed data");
                JSONObject grant = b.store.seat(b.trip.id, b.id.deviceId);
                KeyPairGenerator key = KeyPairGenerator.getInstance("EC");
                key.initialize(new ECGenParameterSpec("secp256r1"));
                try {
                    b.trip.team.verifyGrant(grant, b.id.deviceId,
                            Team.encode(key.generateKeyPair().getPublic().getEncoded()), b.trip.localPerson);
                    throw new AssertionError("copied seat grant worked with a different device key");
                } catch (IllegalArgumentException expected) { }
            }
        });

        sink.check("team: five devices converge by forwarding with the creator offline and a late peer", () -> {
            try (Device a = new Device(base); Device b = new Device(base); Device c = new Device(base);
                 Device d = new Device(base); Device e = new Device(base)) {
                a.create();
                b.enroll(a, 1);
                c.enroll(a, 2);
                d.enroll(a, 3);
                e.enroll(a, 4);
                b.openCoordinator();
                c.openCoordinator();
                d.openCoordinator();
                AtomicInteger wifi = new AtomicInteger(), ble = new AtomicInteger(), unused = new AtomicInteger();
                record(b, "CNY", 101, "hotel");
                record(d, "USD", 502, "water");
                onMain(() -> {
                    b.link(c, PeerRoutePolicy.Channel.WIFI, wifi, true);
                    b.link(c, PeerRoutePolicy.Channel.BLUETOOTH, ble, false);
                    b.link(c, PeerRoutePolicy.Channel.HOTSPOT, unused, false);
                    c.link(d, PeerRoutePolicy.Channel.HOTSPOT, new AtomicInteger(), false);
                });
                await(() -> b.count() == 2 && c.count() == 2 && d.count() == 2, 19000,
                        () -> "relay did not converge: counts " + b.count() + "/" + c.count() + "/" + d.count() +
                                ", dials " + wifi.get() + "/" + ble.get() + "/" + unused.get());
                require(wifi.get() > 0 && ble.get() > 0 && unused.get() == 0, "hotel link did not prefer Wi-Fi then BLE");
                require(a.count() == 0 && e.count() == 0, "disconnected device changed");
                e.reopen();
                e.openCoordinator();
                AtomicInteger deadBle = new AtomicInteger(), hotspot = new AtomicInteger();
                onMain(() -> {
                    d.link(e, PeerRoutePolicy.Channel.BLUETOOTH, deadBle, true);
                    d.link(e, PeerRoutePolicy.Channel.HOTSPOT, hotspot, false);
                });
                await(() -> e.count() == 2, 12000, "late device did not receive historical records");
                require(deadBle.get() > 0 && hotspot.get() > 0, "outdoor fallback did not use hotspot after BLE failed");
                a.openCoordinator();
                onMain(() -> e.link(a, PeerRoutePolicy.Channel.WIFI, new AtomicInteger(), false));
                await(() -> a.count() == 2, 12000, "returning creator did not catch up as a normal peer");
                for (Device device : Arrays.asList(a, b, c, d, e)) {
                    LedgerStore.Summary summary = device.store.summary(device.trip.id);
                    require(device.count() == 2 && summary.spent.get("CNY") == 101 && summary.spent.get("USD") == 502,
                            "ledger diverged or currencies mixed");
                    require(summary.teamNet.get("m2").get("CNY") == 81 && summary.teamNet.get("m1").get("CNY") == -21,
                            "minor-unit split did not add up");
                }
            }
        });

        sink.check("team: interrupted batches resume missing records, replays and tombstones stay idempotent", () -> {
            try (Device a = new Device(base); Device b = new Device(base)) {
                a.create();
                b.enroll(a, 1);
                String note = new String(new char[2400]).replace('\0', '骑');
                LedgerStore.Event removed = record(a, "CNY", 500, "later removed");
                for (int i = 0; i < 24; i++) record(a, "CNY", 101, note + i);
                record(b, "USD", 102, "offline expense");
                require(!exchange(a, b, "drop-second-delta", "").complete(), "stream fault did not occur");
                int persisted = b.count();
                require(persisted > 1 && persisted < 26, "first batch was not durably committed");
                b.reopen();
                Pair resumed = exchange(a, b);
                require(resumed.complete() && resumed.client.sent == 26 - persisted && a.count() == 26 && b.count() == 26,
                        "restart did not send only the missing records");
                a.store.add(a.trip.id, "delete", new JSONObject().put("target", removed.id));
                require(exchange(a, b).complete(), "tombstone did not sync");
                Pair replay = exchange(b, a);
                require(replay.complete() && replay.client.sent == 0 && replay.server.sent == 0 &&
                        b.store.summary(b.trip.id).visible.size() == 25, "replay duplicated or restored deleted money");
            }
        });

        sink.check("team routes: revisions fan out per peer and recovered Wi-Fi takes priority", () -> {
            AtomicLong clock = new AtomicLong(100);
            PeerRoutePolicy routes = new PeerRoutePolicy(clock::get);
            String one = java.util.UUID.randomUUID().toString(), two = java.util.UUID.randomUUID().toString();
            routes.offer(new PeerRoutePolicy.Endpoint("wifi", PeerRoutePolicy.Channel.WIFI, one, true, null));
            routes.offer(new PeerRoutePolicy.Endpoint("ble", PeerRoutePolicy.Channel.BLUETOOTH, one, true, null));
            routes.offer(new PeerRoutePolicy.Endpoint("hotspot", PeerRoutePolicy.Channel.HOTSPOT, one, true, null));
            routes.offer(new PeerRoutePolicy.Endpoint("other", PeerRoutePolicy.Channel.BLUETOOTH, two, true, null));
            List<PeerRoutePolicy.Endpoint> first = routes.readyForRevision(1, "");
            require(first.size() == 2 && first.stream().anyMatch(endpoint -> "wifi".equals(endpoint.key)), "initial preference wrong");
            routes.failure("wifi");
            routes.success("other", 1);
            require(only(routes.readyForRevision(1, one), "ble"), "failed Wi-Fi did not switch to BLE for enrollment");
            routes.failure("ble");
            require(only(routes.readyForRevision(1, ""), "hotspot"), "failed BLE did not switch to hotspot");
            routes.success("hotspot", 1);
            require(routes.readyForRevision(1, "").isEmpty(), "already committed peers retried immediately");
            require(routes.readyForRevision(2, "").size() == 2, "new revision was not sent to every peer");
            routes.success("hotspot", 2);
            routes.success("other", 2);
            clock.addAndGet(5001);
            require(only(routes.readyForRevision(2, ""), "wifi"), "restored Wi-Fi did not regain priority");
        });
    }

    private static boolean only(List<PeerRoutePolicy.Endpoint> ready, String key) {
        return ready.size() == 1 && key.equals(ready.get(0).key);
    }

    private static LedgerStore.Event record(Device device, String currency, long amount, String note) throws Exception {
        String payer = device.trip.localPerson;
        return device.store.add(device.trip.id, "expense", new JSONObject().put("payer", payer).put("currency", currency)
                .put("amount", amount).put("mode", "shared").put("note", note)
                .put("shares", device.trip.team.shares(amount, payer, false)));
    }

    private static final class Pair {
        PeerProtocol.Result client, server;
        Exception clientError, serverError;
        boolean complete() { return client != null && server != null && client.complete && server.complete; }
        @Override public String toString() { return "client=" + clientError + ", server=" + serverError; }
    }

    private static Pair exchange(Device from, Device to) throws Exception { return exchange(from, to, "", ""); }
    private static Pair exchange(Device from, Device to, String clientFault, String serverFault) throws Exception {
        Pair result = new Pair();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = loopback()) {
            Future<?> remote = pool.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(7000);
                    result.server = to.protocol().accept(wire(socket, serverFault));
                } catch (Exception error) { result.serverError = error; }
            });
            try (Socket socket = new Socket("127.0.0.1", listener.getLocalPort())) {
                socket.setSoTimeout(7000);
                result.client = from.protocol().exchange(wire(socket, clientFault),
                        TeamProtocol.routeId(to.trip.team, to.id.deviceId));
            } catch (Exception error) { result.clientError = error; }
            remote.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        return result;
    }

    private static PeerWire wire(Socket socket, String fault) throws Exception {
        return new PeerWire(PeerProtocolChecks.fragment(socket.getInputStream()),
                new PeerProtocolChecks.FaultOutput(socket, fault), () -> { });
    }

    private static ServerSocket loopback() throws Exception {
        ServerSocket socket = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        socket.setSoTimeout(5000);
        return socket;
    }

    private static final class Device implements AutoCloseable {
        final PeerProtocolChecks.Ctx context;
        final Team.Identity id;
        LedgerStore store;
        LedgerStore.Trip trip;
        PeerSync sync;

        Device(Context base) throws Exception {
            context = new PeerProtocolChecks.Ctx(base);
            store = new LedgerStore(context);
            id = Team.Identity.load(context);
        }
        void create() throws Exception { trip = store.createTeam("五人骑行", MEMBERS); }
        void join(Device root, int seat) throws Exception { trip = store.joinTrip(root.store.inviteCode(root.trip, ""), "m" + (seat + 1)); }
        void enroll(Device root, int seat) throws Exception {
            join(root, seat);
            Pair pair = exchange(this, root);
            require(pair.complete() && store.teamReady(trip), "first enrollment failed: " + pair);
        }
        void reopen() { store.close(); store = new LedgerStore(context); trip = store.getTrip(trip.id); }
        int count() { return store.events(trip.id).size(); }
        TeamProtocol protocol() { return new TeamProtocol(store, trip, id, QUIET, () -> { }); }
        void openCoordinator() throws Exception {
            sync = new PeerSync(context, store, ignored -> { }, false, trip);
            onMain(sync::start);
        }
        void link(Device other, PeerRoutePolicy.Channel channel, AtomicInteger dials, boolean unreachable) {
            String route = TeamProtocol.routeId(other.trip.team, other.id.deviceId);
            sync.offer(new PeerRoutePolicy.Endpoint(channel.name().toLowerCase(java.util.Locale.ROOT) + ":" + route,
                    channel, route, true, () -> {
                dials.incrementAndGet();
                if (unreachable) throw new IOException("simulated radio link unavailable");
                try (ServerSocket listener = loopback()) {
                    Socket client = new Socket("127.0.0.1", listener.getLocalPort());
                    Socket server = listener.accept();
                    other.sync.accept(connection(server), channel, "");
                    return connection(client);
                }
            }));
        }
        private PeerConnection connection(Socket socket) throws Exception {
            return new PeerConnection(socket.getInputStream(), socket.getOutputStream(), socket, null);
        }
        @Override public void close() throws Exception {
            if (sync != null) onMain(sync::stop);
            store.close();
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
            keys.load(null);
            keys.deleteEntry("ride-ledger-team-" + id.deviceId);
            context.cleanup();
        }
    }

    private static void onMain(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        new Handler(Looper.getMainLooper()).post(task);
        task.get(5, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier done, long timeout, String message) {
        await(done, timeout, () -> message);
    }

    private static void await(BooleanSupplier done, long timeout, java.util.function.Supplier<String> message) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            if (done.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError(message.get());
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
