package com.rideledger;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

/** Runs the production screen in the separate test package, with a fictitious local trip. */
final class PeerUiChecks {
    static void run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        if (!"com.rideledger.synctest".equals(context.getPackageName())) throw new AssertionError("UI test package is not isolated");
        context.getSharedPreferences("ui", 0).edit().remove("last_trip").commit();
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView();
                require(countText(root, "新建小队旅行") == 1, "single creation entry missing");
                require(text(root, "新建旅行") == null && text(root, "加入小队旅行") != null, "team join entry was missing");
            });
            Field peers = MainActivity.class.getDeclaredField("teamPeers");
            peers.setAccessible(true);
            require(peers.get(activity) == null, "empty screen started a sync coordinator");
        } finally { instrumentation.runOnMainSync(activity::finish); instrumentation.waitForIdleSync(); }
    }

    private static int countText(View view, String expected) {
        int count = view instanceof TextView && expected.contentEquals(((TextView) view).getText()) ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += countText(group.getChildAt(i), expected);
        }
        return count;
    }

    static void runTeam(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        require("com.rideledger.synctest".equals(context.getPackageName()), "UI test package is not isolated");
        PeerProtocolChecks.Ctx other = new PeerProtocolChecks.Ctx(context);
        try (LedgerStore store = new LedgerStore(context); LedgerStore companion = new LedgerStore(other)) {
            LedgerStore.Trip trip = store.createTeam("小队界面检查", Arrays.asList("骑手甲", "骑手乙"));
            LedgerStore.Trip peer = companion.joinTrip(store.inviteCode(trip, ""), "m2");
            context.getSharedPreferences("ui", 0).edit().putString("last_trip", trip.id).putBoolean("team_ble_prompted", true).commit();
            Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                Field teamPeers = MainActivity.class.getDeclaredField("teamPeers");
                teamPeers.setAccessible(true);
                PeerSync sync = (PeerSync) teamPeers.get(activity);
                require(sync != null, "group coordinator not running on open");
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    require(text(root, "快速记账") != null && text(root, "全员平摊  ⌄") != null, "group quick entry missing");
                    amount(root).setText("1.01");
                    require(text(root, "保存这笔支出").performClick(), "group save action missing");
                    amount(activity.getWindow().getDecorView()).setText("7.77");
                });
                require(store.events(trip.id).size() == 1 && store.summary(trip.id).spent.get("CNY") == 101L,
                        "group could not record offline");
                teamExchange(sync, companion, peer, other);
                companion.add(peer.id, "expense", new JSONObject().put("payer", peer.localPerson).put("currency", "USD")
                        .put("amount", 500).put("mode", "shared").put("shares", peer.team.shares(500, peer.localPerson, false)));
                teamExchange(sync, companion, peer, other);
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    require("7.77".contentEquals(amount(root).getText()), "incoming record cleared quick entry draft");
                    require(text(root, "复盘").performClick(), "review navigation missing");
                    require(text(activity.getWindow().getDecorView(), "2 笔") != null, "synced record was not visible");
                });
                companion.add(peer.id, "opening", new JSONObject().put("person", peer.localPerson)
                        .put("currency", "USD").put("amount", 1000));
                teamExchange(sync, companion, peer, other);
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    require(text(root, "3 笔") != null, "review did not refresh after durable sync");
                    require(text(root, "旅行").performClick(), "travel navigation missing");
                    root = activity.getWindow().getDecorView();
                    require(countText(root, "新建小队旅行") == 1 && text(root, "邀请同伴") != null, "first setup entries missing");
                    require(text(root, "蓝牙连接") == null && text(root, "热点地址直连") == null &&
                            text(root, "重新连接并补传") == null, "manual sync controls appeared in group trip");
                });
            } finally { instrumentation.runOnMainSync(activity::finish); instrumentation.waitForIdleSync(); }
        } finally { other.cleanup(); }
    }

    private static void teamExchange(PeerSync sync, LedgerStore peerStore, LedgerStore.Trip peer, Context context) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             Socket client = new Socket("127.0.0.1", server.getLocalPort());
             Socket accepted = server.accept()) {
            client.setSoTimeout(8000);
            sync.accept(new PeerConnection(accepted.getInputStream(), accepted.getOutputStream(), accepted, null),
                    PeerRoutePolicy.Channel.WIFI, "");
            TeamProtocol protocol = new TeamProtocol(peerStore, peer, Team.Identity.load(context), new PeerProtocol.Listener() {
                @Override public void authenticated(String id) { }
                @Override public void stored(int count) { }
            }, () -> { });
            require(protocol.exchange(new PeerWire(client.getInputStream(), client.getOutputStream(), () -> { }), "").complete,
                    "group screen did not accept automatic exchange");
        }
    }

    private static TextView text(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView result = text(((ViewGroup) view).getChildAt(i), expected); if (result != null) return result;
        }
        return null;
    }
    private static EditText amount(View view) {
        if (view instanceof EditText && "0.00".contentEquals(((EditText) view).getHint())) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            EditText result = amount(((ViewGroup) view).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
