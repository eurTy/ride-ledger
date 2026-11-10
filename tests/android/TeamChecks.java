package com.rideledger;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.time.LocalDate;
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

    static void run(Context base, JSONObject fixture, PeerProtocolChecks.Sink sink) {
        sink.check("open team: named seats, duplicate rejection, durable role and propagated roster", () -> {
            try (Device root = new Device(base); Device first = new Device(base); Device second = new Device(base);
                 Device extra = new Device(base)) {
                root.trip = new TeamCreator(root.store).create("北线骑行", "组织者甲", 3);
                String code = root.store.inviteCode(root.trip);
                require(code.startsWith(Team.OPEN_INVITE_PREFIX) && InviteQr.isTeamCode(code), "new invite is not a team QR payload");
                first.trip = new TeamJoiner(first.store).join(code, "星河");
                second.trip = second.store.joinTrip(code, "星河");
                require(first.trip.localPerson.isEmpty() && !first.store.teamReady(first.trip), "an unregistered name occupied a seat");
                require(exchange(first, root).complete(), "first named enrollment failed");
                require(exchange(second, root).clientError instanceof TeamProtocol.Admission, "duplicate name was admitted");
                second.trip = second.store.joinTrip(code, "山谷");
                require(exchange(second, root).complete(), "correcting an unregistered name failed");
                first.reopen(); second.reopen(); root.reopen();
                require(first.store.teamReady(first.trip) && first.trip.localPerson.equals("m2") &&
                        first.trip.nameOf("m2").equals("星河"), "name or seat was lost after reopening");
                require(exchange(first, second).complete(), "registered phones could not exchange without issuer");
                first.reopen();
                require(first.trip.nameOf("m3").equals("山谷"), "authenticated roster did not propagate");
                extra.trip = extra.store.joinTrip(code, "高原");
                require(exchange(extra, root).clientError instanceof TeamProtocol.Admission && root.store.teamSeatCount(root.trip.id) == 3,
                        "more than the expected number enrolled");
                record(first, "CNY", 130, "after open team");
                require(exchange(first, root).complete() && root.count() == 1, "new identity broke normal ledger sync");
            }
        });
        sink.check("team Windows: first enrollment, bidirectional TCP and repeat sync", () -> {
            try (Device phone = new Device(base)) {
                phone.trip = phone.store.joinTrip(fixture.getString("desktopTeamInvite"), "m2");
                int port = fixture.getInt("desktopTeamPort");
                String host = fixture.optString("testHost", "10.0.2.2");
                windowsExchange(phone, host, port);
                require(phone.store.teamReady(phone.trip) && phone.count() == 1, "Windows did not register Android and send its opening balance");
                record(phone, "USD", 271, "android offline expense");
                windowsExchange(phone, host, port);
                windowsExchange(phone, host, port);
                require(phone.count() == 2, "reconnect duplicated a Windows or Android record");
            }
        });
        sink.check("team: saved old QR ignores the relay and joins nearby", () -> {
            try (Device root = new Device(base); Device guest = new Device(base)) {
                root.trip = root.store.createOpenTeam("相册加入", "组织者甲", 2);
                String invite = root.store.inviteCode(root.trip);
                String prefix = Team.OPEN_INVITE_PREFIX;
                JSONObject payload = new JSONObject(new String(Team.decode(invite.substring(prefix.length())), StandardCharsets.UTF_8));
                require(!payload.has("syncUrl"), "a new invite carried a relay address");
                payload.put("syncUrl", "https://relay.example.invalid");
                String legacyInvite = prefix + Team.encode(payload.toString().getBytes(StandardCharsets.UTF_8));
                File file = File.createTempFile("team-qr-", ".png", base.getCacheDir());
                Bitmap qr = InviteQr.image(legacyInvite, 760);
                Bitmap screenshot = Bitmap.createBitmap(1280, 2560, Bitmap.Config.ARGB_8888);
                try {
                    Canvas canvas = new Canvas(screenshot);
                    canvas.drawColor(Color.rgb(236, 244, 239));
                    canvas.drawBitmap(qr, 260, 660, null);
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        require(screenshot.compress(Bitmap.CompressFormat.PNG, 100, out), "could not save QR fixture");
                    }
                    String selected = InviteQr.readImage(base.getContentResolver(), Uri.fromFile(file));
                    require(legacyInvite.equals(selected), "saved screenshot did not decode to the original invite");
                    guest.trip = new TeamJoiner(guest.store).join(selected, "队友乙");
                    require(!guest.store.teamReady(guest.trip),
                            "a legacy relay address bypassed enrollment");
                    require(exchange(guest, root).complete() && guest.store.teamReady(guest.trip),
                            "an old invite did not enroll over a nearby connection");
                } finally {
                    screenshot.recycle();
                    qr.recycle();
                    file.delete();
                }
            }
        });
        sink.check("team: rotated QR recovers a finder-pattern miss", () -> {
            java.util.Random random = new java.util.Random(907);
            byte[] data = null;
            for (int i = 0; i <= 39; i++) {
                data = new byte[300 + i];
                random.nextBytes(data);
            }
            String invite = "RLTEAM2." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data);
            require(invite.equals(rotatedCameraScan(invite)), "rotation fallback did not recover the QR text");
        });
        sink.check("team: offline QR decodes the invite and enrolls the selected member", () -> {
            try (Device root = new Device(base); Device guest = new Device(base)) {
                root.create();
                record(root, "CNY", 305, "before QR");
                String invite = root.store.inviteCode(root.trip);
                String scanned = rotatedCameraScan(invite);
                require(invite.equals(scanned), scanned == null ? "camera frame found no QR code" : "camera-frame decode altered the invitation");
                guest.trip = guest.store.joinTrip(scanned, "m4");
                require("阿满".equals(guest.trip.nameOf(guest.trip.localPerson)), "scanned roster could not select the fourth member");
                require(!guest.store.teamReady(guest.trip), "scanning bypassed enrollment");
                require(exchange(guest, root).complete() && guest.store.teamReady(guest.trip) && guest.count() == 1,
                        "scanned invite did not enroll and receive existing records");
                record(guest, "USD", 71, "after QR");
                require(exchange(root, guest).complete() && root.count() == 2,
                        "registered peer did not sync again with its saved credentials");
            }
        });
        sink.check("team: solo trip records the full amount and reopens with one member", () -> {
            try (Device solo = new Device(base)) {
                solo.trip = solo.store.createTeam("独行", Collections.singletonList("阿青"));
                record(solo, "CNY", 101, "solo expense");
                solo.reopen();
                require(solo.trip.people() == 1 && solo.store.teamReady(solo.trip), "solo trip or identity was lost");
                LedgerStore.Summary sum = solo.store.summary(solo.trip.id);
                require(sum.spent.get("CNY") == 101 && sum.teamNet.get("m1").get("CNY") == 0, "solo split created debt");
                try {
                    solo.store.inviteCode(solo.trip);
                    throw new AssertionError("solo trip issued an invitation without another seat");
                } catch (IllegalArgumentException expected) { }
            }
        });
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
                    b.link(c, PeerRoutePolicy.Channel.HOTSPOT, unused, true);
                    c.link(d, PeerRoutePolicy.Channel.HOTSPOT, new AtomicInteger(), false);
                });
                await(() -> b.count() == 2 && c.count() == 2 && d.count() == 2, 19000,
                        () -> "relay did not converge: counts " + b.count() + "/" + c.count() + "/" + d.count() +
                                ", dials " + wifi.get() + "/" + ble.get() + "/" + unused.get());
                require(wifi.get() > 0 && ble.get() > 0 && unused.get() > 0, "both LAN routes must fail before BLE");
                require(a.count() == 0 && e.count() == 0, "disconnected device changed");
                e.reopen();
                e.openCoordinator();
                AtomicInteger deadBle = new AtomicInteger(), hotspot = new AtomicInteger();
                onMain(() -> {
                    d.link(e, PeerRoutePolicy.Channel.BLUETOOTH, deadBle, true);
                    d.link(e, PeerRoutePolicy.Channel.HOTSPOT, hotspot, false);
                });
                await(() -> e.count() == 2, 12000, "late device did not receive historical records");
                require(deadBle.get() == 0 && hotspot.get() > 0, "reachable hotspot must take precedence over BLE");
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

        sink.check("team: three simultaneous Bluetooth peers all sync and a manual refresh never duplicates records", () -> {
            try (Device a = new Device(base); Device b = new Device(base);
                 Device c = new Device(base); Device d = new Device(base)) {
                a.trip = a.store.createTeam("三位蓝牙队友", MEMBERS.subList(0, 4));
                List<Device> peers = Arrays.asList(b, c, d);
                for (int i = 0; i < peers.size(); i++) {
                    Device peer = peers.get(i);
                    peer.enroll(a, i + 1);
                    record(peer, "CNY", 100 + i, "独立离线账目 " + i);
                    peer.openCoordinator();
                }
                a.openCoordinator();
                onMain(() -> {
                    for (int i = 0; i < peers.size(); i++)
                        a.link(peers.get(i), PeerRoutePolicy.Channel.BLUETOOTH, null, false);
                });
                await(() -> a.count() == 3 && peers.stream().allMatch(peer -> peer.count() == 3) &&
                                a.sync.syncState().links.size() == 3 && !a.sync.transferring(), 18000,
                        "the coordinator did not sync all three peers");
                int[] previous = peers.stream().mapToInt(peer -> peer.inboundDials.get()).toArray();
                onMain(a.sync::reconnect);
                await(() -> b.inboundDials.get() > previous[0] && c.inboundDials.get() > previous[1] &&
                                d.inboundDials.get() > previous[2] && !a.sync.transferring() &&
                                peers.stream().noneMatch(peer -> peer.sync.transferring()), 10000,
                        "manual refresh did not recheck each known peer");
                for (Device device : Arrays.asList(a, b, c, d)) {
                    require(device.count() == 3 && device.store.summary(device.trip.id).spent.get("CNY") == 303,
                            "reconnecting to already synchronized peers duplicated money");
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

        sink.check("team edits: independent fields merge, competing values survive until selected", () -> {
            try (Device a = new Device(base); Device b = new Device(base)) {
                a.trip = a.store.createTeam("并发编辑", MEMBERS.subList(0, 2));
                b.enroll(a, 1);
                LedgerStore.Event original = record(a, "CNY", 100, "原备注");
                require(exchange(a, b).complete(), "base record did not synchronize");
                LedgerActions aActions = new LedgerActions(a.store), bActions = new LedgerActions(b.store);
                aActions.editExpense(a.trip, visible(a), "m1", "CNY", "2", 0, null, "原备注");
                bActions.editExpense(b.trip, visible(b), "m1", "CNY", "1", 0, null, "队友备注");
                require(exchange(b, a).complete(), "independent edit exchange failed");
                require(visible(a).data.getLong("amount") == 200 && visible(a).data.getString("note").equals("队友备注") &&
                        !visible(a).conflicted(), "non-overlapping edits were not merged");
                aActions.editExpense(a.trip, visible(a), "m1", "CNY", "3", 0, null, "队友备注");
                bActions.editExpense(b.trip, visible(b), "m1", "CNY", "4", 0, null, "队友备注");
                require(exchange(a, b).complete(), "competing edits did not synchronize");
                LedgerStore.Event disputed = visible(b);
                require(disputed.financialOptions.size() == 2 && visible(a).financialOptions.size() == 2 &&
                        visible(a).data.getLong("amount") == disputed.data.getLong("amount"), "conflicting values did not converge");
                bActions.editExpense(b.trip, disputed, "m1", "CNY", "3", 0, null, "队友备注");
                require(exchange(b, a).complete(), "conflict resolution did not synchronize");
                Pair replay = exchange(a, b);
                LedgerStore.Event result = visible(a);
                require(replay.complete() && replay.client.sent == 0 && replay.server.sent == 0 &&
                        !result.conflicted() && !visible(b).conflicted() && result.at == original.at &&
                        result.id.equals(original.id) && result.data.getLong("amount") == 300 &&
                        result.data.getString("note").equals("队友备注"), "replay altered the resolved record or its time");
                aActions.delete(a.trip, Collections.singletonList(original.id));
                bActions.editExpense(b.trip, visible(b), "m1", "CNY", "5", 0, null, "离线编辑");
                require(exchange(a, b).complete() && a.store.summary(a.trip.id).visible.isEmpty() &&
                        b.store.summary(b.trip.id).visible.isEmpty(), "concurrent edit resurrected a deleted record");
            }
        });

        sink.check("team journal: over 10000 operations resume after interruption and replay zero records", () -> {
            try (Device a = new Device(base); Device b = new Device(base)) {
                a.trip = a.store.createTeam("长账本", MEMBERS.subList(0, 2));
                b.enroll(a, 1);
                int total = 10051;
                JSONArray operations = new JSONArray();
                for (int i = 0; i < total; i++) operations.put(new LedgerStore.Event(
                        java.util.UUID.randomUUID().toString(), a.trip.id, "topup", i,
                        new JSONObject().put("person", "m1").put("currency", "CNY").put("amount", 1)).json());
                require(a.store.mergeStrict(a.trip.id, operations) == total, "could not seed long journal");
                Pair broken = exchange(a, b, "drop-second-delta", "");
                int persisted = b.count();
                require(!broken.complete() && persisted > 0 && persisted < total, "long journal did not keep the first batch: " + broken);
                b.reopen();
                Pair resumed = exchange(a, b);
                require(resumed.complete() && resumed.client.sent == total - persisted && b.count() == total &&
                        b.store.summary(b.trip.id).teamCash.get("m1").get("CNY") == total,
                        "long journal did not resume the missing operations: " + resumed);
                Pair replay = exchange(b, a);
                require(replay.complete() && replay.client.sent == 0 && replay.server.sent == 0, "long journal replay was not empty");
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
            routes.syncFailure(first.stream().filter(endpoint -> "wifi".equals(endpoint.key)).findFirst().get());
            routes.success("other", 1);
            List<PeerRoutePolicy.Endpoint> alternateLan = routes.readyForRevision(1, one);
            require(only(alternateLan, "hotspot"), "hotspot should be attempted before BLE");
            routes.syncFailure(alternateLan.get(0));
            require(only(routes.readyForRevision(1, ""), "ble"), "both failed LAN routes should fall back to BLE");
            routes.success("ble", 1);
            require(routes.readyForRevision(1, "").isEmpty(), "backoff was bypassed");
            clock.addAndGet(2000);
            require(only(routes.readyForRevision(1, ""), "wifi"), "restored Wi-Fi did not regain priority");
            routes.success("wifi", 1);
            clock.addAndGet(60000);
            require(routes.readyForRevision(1, "").isEmpty() && routes.nextSyncRetry(1) == Long.MAX_VALUE,
                    "settled peers must not be polled");
            require(routes.readyForRevision(2, "").size() == 2, "new revision was not sent to every peer");
        });

        sink.check("team routes: three retries then idle until a new request", () -> {
            AtomicLong clock = new AtomicLong(100);
            PeerRoutePolicy routes = new PeerRoutePolicy(clock::get);
            String peer = java.util.UUID.randomUUID().toString();
            PeerRoutePolicy.Endpoint endpoint = new PeerRoutePolicy.Endpoint("wifi", PeerRoutePolicy.Channel.WIFI, peer, true, null);
            routes.offer(endpoint);
            for (long delay : new long[]{2000, 4000, 8000}) {
                require(only(routes.readyForRevision(1, ""), "wifi"), "attempt did not run");
                routes.syncFailure(endpoint);
                require(routes.readyForRevision(1, "").isEmpty() && routes.nextSyncRetry(1) == clock.get() + delay,
                        "unexpected retry deadline");
                clock.addAndGet(delay);
            }
            require(only(routes.readyForRevision(1, ""), "wifi"), "last retry did not run");
            routes.syncFailure(endpoint);
            clock.addAndGet(60000);
            require(routes.readyForRevision(1, "").isEmpty() && routes.nextSyncRetry(1) == Long.MAX_VALUE,
                    "retry budget did not stop the engine");
            routes.newSyncRequest();
            require(only(routes.readyForRevision(2, ""), "wifi"), "new work should reactivate the route");
        });

        sink.check("team sync engine: offline queue and authenticated checkpoint survive a reopen", () -> {
            try (Device a = new Device(base); Device b = new Device(base)) {
                a.trip = a.store.createTeam("补传测试", MEMBERS.subList(0, 2));
                b.enroll(a, 1);
                record(a, "USD", 600, "offline");
                a.reopen();
                a.openCoordinator();
                b.openCoordinator();
                await(() -> a.sync.status().equals("待同步 1 条"), 3000, "offline operation was not recovered");
                onMain(() -> a.link(b, PeerRoutePolicy.Channel.WIFI, new AtomicInteger(), false));
                await(() -> b.count() == 1 && a.sync.status().equals("已同步"), 10000, "durable receipt was not tracked");
                onMain(a.sync::stop);
                a.reopen();
                record(a, "USD", 700, "after confirmation");
                a.openCoordinator();
                await(() -> a.sync.status().equals("待同步 1 条"), 3000, "checkpoint did not survive coordinator and database reopen");
            }
        });

        sink.check("hotspot: fixed TCP gateway accepts organizer and participant hosts with bidirectional sync", () -> {
            try (Device organizer = new Device(base); Device participant = new Device(base)) {
                organizer.trip = organizer.store.createTeam("热点同行", MEMBERS.subList(0, 2));
                participant.enroll(organizer, 1);
                gatewayExchange(organizer, participant);
                gatewayExchange(participant, organizer);
            }
        });

        sink.check("hotspot: fixed gateway recovers after port occupation", () -> {
            try (Device a = new Device(base); Device b = new Device(base);
                 ServerSocket occupied = new ServerSocket(LanSync.TEAM_PORT)) {
                a.trip = a.store.createTeam("热点端口恢复", MEMBERS.subList(0, 2));
                b.enroll(a, 1);
                gatewayExchange(a, b, occupied);
            }
        });

        sink.check("sync log: seven local days, private file and error type without sensitive message", () -> {
            File files = new File(base.getCacheDir(), "nearby-log-test-" + java.util.UUID.randomUUID());
            File directory = new File(files, "nearby-sync");
            require(directory.mkdirs(), "log test directory could not be created");
            Context context = new ContextWrapper(base) {
                @Override public File getFilesDir() { return files; }
            };
            LocalDate today = LocalDate.now();
            File expired = new File(directory, today.minusDays(7) + ".log");
            File retained = new File(directory, today.minusDays(6) + ".log");
            File current = new File(directory, today + ".log");
            try {
                require(expired.createNewFile() && retained.createNewFile(), "log fixtures could not be created");
                SyncLog.write(context, SyncLog.Event.WIFI, new IOException("测试成员 512.00 CNY"));
                String logged = new String(Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8);
                require(!expired.exists() && retained.exists(), "daily retention did not preserve only the latest seven days");
                require(logged.contains("WIFI IOException") && !logged.contains("测试成员") && !logged.contains("512.00"),
                        "sync log leaked exception details");
            } finally {
                expired.delete();
                retained.delete();
                current.delete();
                directory.delete();
                files.delete();
            }
        });
    }

    private static void gatewayExchange(Device host, Device client) throws Exception {
        gatewayExchange(host, client, null);
    }

    private static LedgerStore.Event visible(Device device) { return device.store.summary(device.trip.id).visible.get(0); }

    private static void gatewayExchange(Device host, Device client, ServerSocket occupied) throws Exception {
        record(host, "CNY", 234, "host offline");
        record(client, "USD", 123, "client offline");
        int expected = host.count() + 1;
        PeerSync listening = new PeerSync(host.context, host.store, added -> { }, true, host.trip);
        onMain(listening::start);
        try {
            if (occupied != null) {
                SystemClock.sleep(650);
                occupied.close();
            }
            java.net.InetSocketAddress endpoint = LanSync.gatewayAddress(InetAddress.getByName("127.0.0.1"));
            String route;
            try (Socket socket = connectGateway(endpoint)) {
                socket.setSoTimeout(5000);
                route = TeamProtocol.probe(new PeerWire(socket.getInputStream(), socket.getOutputStream(), () -> { }), client.trip);
            }
            require(TeamProtocol.routeId(host.trip.team, host.id.deviceId).equals(route), "gateway probe did not identify the actual host");
            try (Socket socket = new Socket()) {
                socket.connect(endpoint, 3000);
                socket.setSoTimeout(5000);
                require(client.protocol().exchange(new PeerWire(socket.getInputStream(), socket.getOutputStream(), () -> { }), route).complete,
                        "fixed-port authenticated exchange failed");
            }
            require(host.count() == expected && client.count() == expected, "hotspot exchange was not bidirectional");
        } finally { onMain(listening::stop); }
    }

    private static Socket connectGateway(java.net.InetSocketAddress endpoint) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 6000;
        do {
            Socket socket = new Socket();
            try { socket.connect(endpoint, 1000); return socket; }
            catch (IOException unavailable) {
                socket.close();
                if (SystemClock.elapsedRealtime() >= deadline) throw unavailable;
                SystemClock.sleep(100);
            }
        } while (true);
    }

    private static void windowsExchange(Device phone, String host, int port) throws Exception {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(10000);
            TeamProtocol protocol = new TeamProtocol(phone.store, phone.trip, phone.id, QUIET, () -> {});
            PeerWire wire = new PeerWire(socket.getInputStream(), socket.getOutputStream(), () -> {});
            require(protocol.exchange(wire, TeamProtocol.routeId(phone.trip.team, phone.trip.team.issuer)).complete,
                    "Windows did not confirm durable team sync");
        }
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
        final AtomicInteger inboundDials = new AtomicInteger();
        LedgerStore store;
        LedgerStore.Trip trip;
        PeerSync sync;

        Device(Context base) throws Exception {
            context = new PeerProtocolChecks.Ctx(base);
            store = new LedgerStore(context);
            id = Team.Identity.load(context);
        }
        void create() throws Exception { trip = store.createTeam("五人骑行", MEMBERS); }
        void join(Device root, int seat) throws Exception { trip = store.joinTrip(root.store.inviteCode(root.trip), "m" + (seat + 1)); }
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
            sync.offer(endpoint(other, channel, dials, unreachable));
        }
        private PeerRoutePolicy.Endpoint endpoint(Device other, PeerRoutePolicy.Channel channel,
                                                  AtomicInteger dials, boolean unreachable) {
            String route = TeamProtocol.routeId(other.trip.team, other.id.deviceId);
            return new PeerRoutePolicy.Endpoint(channel.name().toLowerCase(java.util.Locale.ROOT) + ":" + route,
                    channel, route, true, () -> {
                        if (dials != null) dials.incrementAndGet();
                        if (unreachable) throw new IOException("simulated radio link unavailable");
                        try (ServerSocket listener = loopback()) {
                            Socket client = new Socket("127.0.0.1", listener.getLocalPort());
                            Socket server = listener.accept();
                            other.inboundDials.incrementAndGet();
                            other.sync.accept(other.connection(server, this, channel), channel, "");
                            return connection(client, other, channel);
                        }
                    });
        }
        private PeerConnection connection(Socket socket, Device other, PeerRoutePolicy.Channel channel) throws Exception {
            return new PeerConnection(socket.getInputStream(), socket.getOutputStream(), socket, null,
                    12345, (peer, callback) -> endpoint(other, channel, null, false));
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

    private static String rotatedCameraScan(String invite) throws Exception {
        Bitmap qr = InviteQr.image(invite, 520);
        try {
            Matrix rotation = new Matrix();
            rotation.postRotate(90);
            Bitmap rotated = Bitmap.createBitmap(qr, 0, 0, qr.getWidth(), qr.getHeight(), rotation, false);
            try {
                int width = rotated.getWidth(), height = rotated.getHeight();
                int[] pixels = new int[width * height];
                byte[] luminance = new byte[pixels.length];
                rotated.getPixels(pixels, 0, width, 0, 0, width, height);
                for (int i = 0; i < pixels.length; i++) luminance[i] = (byte) Color.red(pixels[i]);
                return InviteQr.readFrame(luminance, width, height);
            } finally { rotated.recycle(); }
        } finally { qr.recycle(); }
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
