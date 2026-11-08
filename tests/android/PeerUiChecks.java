package com.rideledger;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
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
        context.getSharedPreferences("ui", 0).edit().remove("last_trip")
                .putBoolean(MainActivity.STARTUP_PERMISSIONS_REQUESTED, true).commit();
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView();
                require(countText(root, "新建小队旅行") == 1, "single creation entry missing");
                require(text(root, "新建旅行") == null && text(root, "加入小队旅行") != null, "team join entry was missing");
            });
            require(transport(activity).nearby == null, "empty screen started a sync coordinator");
            JobInfo job = context.getSystemService(JobScheduler.class).getPendingJob(SyncBackground.PERIODIC);
            require(job != null && job.isPeriodic() && job.isPersisted() &&
                    job.getIntervalMillis() == 15 * 60 * 1000L && job.getRequiredNetwork() == null,
                    "background fallback was not scheduled as a persisted near-field job");
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
            LedgerStore.Trip peer = companion.joinTrip(store.inviteCode(trip), "m2");
            context.getSharedPreferences("ui", 0).edit().putString("last_trip", trip.id)
                    .putBoolean(MainActivity.STARTUP_PERMISSIONS_REQUESTED, true).commit();
            Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                PeerSync sync = transport(activity).nearby;
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
                    View review = activity.getWindow().getDecorView();
                    require(text(review, "2 笔") != null, "synced record was not visible");
                    java.util.Set<android.view.ViewParent> sections = new java.util.HashSet<>();
                    for (String title : new String[]{"总支出", "现金余额", "小队结算", "现金与结算", "旅行记录"}) {
                        TextView label = text(review, title);
                        require(label != null && sections.add(label.getParent()) && ((View) label.getParent()).getBackground() != null,
                                "review sections are not visually separated");
                    }
                    for (String filter : new String[]{"全部", "共同支出", "换汇记录"}) {
                        TextView chip = text(review, filter);
                        require(chip != null && ((android.widget.LinearLayout.LayoutParams) chip.getLayoutParams()).weight == 1 &&
                                !(chip.getParent().getParent() instanceof android.widget.HorizontalScrollView),
                                "record filter still requires horizontal scrolling");
                    }
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

    private static TeamTransportAdapter transport(Activity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("teamTransport");
        field.setAccessible(true);
        return (TeamTransportAdapter) field.get(activity);
    }

    static void runTeamSetup(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        Activity page = instrumentation.startActivitySync(new Intent(context, TeamSetupActivity.class)
                .putExtra(TeamSetupActivity.MODE, TeamSetupActivity.CREATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                View root = page.getWindow().getDecorView();
                require(text(root, "打开应用权限设置") == null, "the setup page still shows the permission shortcut");
                require(text(root, "生成邀请").performClick(), "create button missing");
                require(text(root, "请填写旅行名称（最多 80 字）") != null, "validation did not appear inline");
                input(root, "旅行名称").setText("内联组队检查");
                input(root, "本人名称").setText("组织者甲");
                require(text(root, "小队人数") != null && input(root, "HTTPS 中转地址（选填）") == null,
                        "the create page kept the old controls");
                java.util.ArrayList<View> sliders = new java.util.ArrayList<>();
                root.findViewsWithText(sliders, "小队人数，1 人，包括你", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                require(sliders.size() == 1 && sliders.get(0) instanceof SeekBar, "team size slider is missing");
                SeekBar count = (SeekBar) sliders.get(0);
                require(count.getMin() == 1 && count.getMax() == 5, "team size slider bounds are wrong");
                count.setProgress(2);
                require(text(root, "2 人") != null, "slider did not update the displayed count");
                require(text(root, "生成邀请").performClick(), "could not create an open team");
                root = page.getWindow().getDecorView();
                require(text(root, "已加入 1 / 2 人") != null && text(root, "组织者甲 · 已加入") != null,
                        "organizer membership count is not shown inline");
                require(text(root, "令牌") != null && !hasVisibleCode(root), "token should be behind its button");
                require(text(root, "小队人数") == null && text(root, "保存中转地址") == null,
                        "the invite page retained the count picker or relay action");
            });
        } finally { instrumentation.runOnMainSync(page::finish); instrumentation.waitForIdleSync(); }
        long end = SystemClock.elapsedRealtime() + 2500;
        while (!page.isDestroyed() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50);
        require(setupTransport(page).nearby == null, "organizer kept listening after leaving setup");
    }

    static void runTeamJoin(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        PeerProtocolChecks.Ctx organizer = new PeerProtocolChecks.Ctx(context);
        try (LedgerStore remote = new LedgerStore(organizer); LedgerStore local = new LedgerStore(context)) {
            LedgerStore.Trip created = remote.createOpenTeam("同行测试", "组织者乙", 2);
            String code = remote.inviteCode(created);
            Activity page = instrumentation.startActivitySync(new Intent(context, TeamSetupActivity.class)
                    .putExtra(TeamSetupActivity.MODE, TeamSetupActivity.JOIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                instrumentation.runOnMainSync(() -> {
                    View root = page.getWindow().getDecorView();
                    require(text(root, "打开应用权限设置") == null, "the join page still shows the permission shortcut");
                    require(text(root, "扫码加入").getCurrentTextColor() == Color.WHITE &&
                            text(root, "从相册选二维码").getCurrentTextColor() != Color.WHITE,
                            "scan is not the primary action");
                    require(text(root, "读取令牌").getVisibility() == View.GONE, "backup token should start collapsed");
                    text(root, "令牌（备用）  ▾").performClick();
                    java.util.ArrayList<View> fields = new java.util.ArrayList<>();
                    root.findViewsWithText(fields, "备用令牌", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                    EditText token = (EditText) fields.stream().filter(view -> view instanceof EditText).findFirst().get();
                    require(token.getHint() == null && token.isShown(), "expanded token field should be visible and have no hint");
                    token.setText(code.substring(0, 3) + " \n" + code.substring(3));
                    text(root, "读取令牌").performClick();
                    require(text(root, "旅行名称 · 同行测试") != null && text(root, "组织者 · 组织者乙") != null,
                            "join details not shown inline");
                    input(root, "本人名称").setText("组织者乙");
                    require(text(root, "请使用与组织者不同的名字") != null, "known name collision was not shown before submit");
                    input(root, "本人名称").setText("自由命名");
                    text(root, "确认并加入").performClick();
                    require(text(page.getWindow().getDecorView(), "正在加入小队...") != null, "connecting state missing");
                });
                Field start = TeamSetupActivity.class.getDeclaredField("startedAt");
                start.setAccessible(true);
                instrumentation.runOnMainSync(() -> {
                    try { start.setLong(page, SystemClock.elapsedRealtime() - TeamJoiner.WAIT_MILLIS - 1); }
                    catch (IllegalAccessException error) { throw new AssertionError(error); }
                });
                SystemClock.sleep(450);
                instrumentation.runOnMainSync(() -> {
                    View root = page.getWindow().getDecorView();
                    require(text(root, "无法连接到组织者，请确认对方已打开小队页面，再重试") != null,
                            "join timeout did not give an inline explanation");
                    try { require(setupTransport(page).nearby == null, "join timeout kept radios running"); }
                    catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                    require(text(root, "重试连接").performClick(), "join retry was not available");
                });
                Team.Identity guest = Team.Identity.load(context);
                JSONObject grant = remote.enroll(created, Team.Identity.load(organizer), guest.deviceId, guest.key, "", "自由命名");
                local.acceptSeat(local.getTrip(created.id), grant, guest.deviceId, guest.key, grant.getString("member"));
                SystemClock.sleep(450);
                instrumentation.runOnMainSync(() -> require(!page.isFinishing() &&
                        text(page.getWindow().getDecorView(), "已成功加入，可返回记账") != null, "success not visible before return"));
                long end = SystemClock.elapsedRealtime() + 4000;
                while (!page.isFinishing() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
                require(page.isFinishing(), "join page did not return after success");
            } finally { instrumentation.runOnMainSync(page::finish); instrumentation.waitForIdleSync(); }
        } finally { organizer.cleanup(); }
    }

    private static TeamTransportAdapter setupTransport(Activity activity) throws ReflectiveOperationException {
        Field field = TeamSetupActivity.class.getDeclaredField("transport");
        field.setAccessible(true);
        return (TeamTransportAdapter) field.get(activity);
    }

    private static EditText input(View view, String hint) {
        if (view instanceof EditText && hint.contentEquals(((EditText) view).getHint())) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            EditText child = input(((ViewGroup) view).getChildAt(i), hint);
            if (child != null) return child;
        }
        return null;
    }

    private static boolean hasVisibleCode(View view) {
        if (view instanceof TextView && InviteQr.isTeamCode(((TextView) view).getText().toString())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasVisibleCode(((ViewGroup) view).getChildAt(i))) return true;
        return false;
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
