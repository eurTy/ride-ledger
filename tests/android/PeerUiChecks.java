package com.rideledger;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

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
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> text(activity.getWindow().getDecorView(), "复盘").performClick());
                waitForText(instrumentation, activity, "还没有记录");
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    TextView noRecords = text(root, "还没有记录");
                    TextView settled = text(root, "目前已结清");
                    require(text(root, "本机已保存\n暂无同伴核对时间") != null, "offline review misrepresented its peer state");
                    require(noRecords != null && settled != null &&
                            noRecords.getTextSize() == settled.getTextSize() &&
                            noRecords.getCurrentTextColor() == settled.getCurrentTextColor(),
                            "review empty states differ in text size or color");
                    text(root, "记账").performClick();
                });
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    require(text(root, "快速记账") != null && paymentControl(root) != null, "group quick entry missing");
                    View save = text(root, "保存这笔支出");
                    View cash = text(root, "现金管理");
                    require(fullyVisible(labeledInput(root, "支付金额")) && fullyVisible(labeledInput(root, "货币")) &&
                            fullyVisible(labeledInput(root, "备注（可选）")) && fullyVisible(save) &&
                            containingScroll(save).getScrollY() == 0, "quick entry requires scrolling on the first screen");
                    require(fullyVisible((View) save.getParent()) && fullyVisible(cash) && cash.getParent() == save.getParent() &&
                            fullyVisible(text(root, "现金入账")) && fullyVisible(text(root, "成员还款")) &&
                            fullyVisible(text(root, "换汇")), "cash actions did not fit in the quick-entry card");
                    requireNext(labeledInput(root, "支付金额"), labeledInput(root, "货币"));
                    requireNext(labeledInput(root, "货币"), labeledInput(root, "备注（可选）"));
                    amount(root).setText("1.01");
                    require(save.performClick(), "group save action missing");
                    save.performClick();
                    require(text(activity.getWindow().getDecorView(), "复盘").performClick(), "review navigation missing");
                });
                waitForRecordColor(instrumentation, activity, "1.01 CNY", 0xffe9ebed);
                require(store.events(trip.id).size() == 1 && store.summary(trip.id).spent.get("CNY") == 101L,
                        "group could not record offline");
                awaitBadge(instrumentation, activity, "无连接。点击重新连接", 3000);
                teamExchange(sync, companion, peer, other);
                try {
                    waitForRecordColor(instrumentation, activity, "1.01 CNY", 0xfff4f8f5);
                } catch (AssertionError error) {
                    throw new AssertionError(error.getMessage() + ", engine=" + sync.syncState().confirmedThrough
                            + ", disk=" + SyncEngine.confirmedThrough(context, store, store.getTrip(trip.id))
                            + ", roster=" + store.teamSeatCount(trip.id)
                            + ", received=" + companion.events(peer.id).size()
                            + ", checkpoint entries=" + context.getSharedPreferences("peer_checkpoints", 0).getAll().size());
                }
                instrumentation.runOnMainSync(() -> {
                    text(activity.getWindow().getDecorView(), "记账").performClick();
                    amount(activity.getWindow().getDecorView()).setText("7.77");
                });
                long older = store.events(trip.id).get(0).at - 60000;
                LedgerStore.Event late = new LedgerStore.Event(java.util.UUID.randomUUID().toString(), peer.id,
                        "expense", older, new JSONObject().put("payer", peer.localPerson).put("currency", "USD")
                        .put("amount", 500).put("mode", "shared").put("shares", peer.team.shares(500, peer.localPerson, false)));
                companion.mergeStrict(peer.id, new JSONArray().put(late.json()));
                teamExchange(sync, companion, peer, other);
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    require("7.77".contentEquals(amount(root).getText()), "incoming record cleared quick entry draft");
                    require(text(root, "复盘").performClick(), "review navigation missing");
                });
                waitForText(instrumentation, activity, "2 笔");
                instrumentation.runOnMainSync(() -> {
                    View review = activity.getWindow().getDecorView();
                    require(text(review, "2 笔") != null, "synced record was not visible");
                    require(containsText(review, "上次与同伴核对") && text(review, "已同步") == null,
                            "review is missing the previous check time or still shows an unconditional sync claim");
                    View newer = recordContaining(review, "1.01 CNY");
                    View backdated = recordContaining(review, "5.00 USD");
                    require(newer != null && backdated != null && newer.getParent() == backdated.getParent() &&
                            ((ViewGroup) newer.getParent()).indexOfChild(newer) < ((ViewGroup) backdated.getParent()).indexOfChild(backdated),
                            "an older record arriving during sync changed the chronological order");
                    java.util.Set<android.view.ViewParent> sections = new java.util.HashSet<>();
                    for (String title : new String[]{"总支出", "现金余额", "小队结算", "旅行记录"}) {
                        TextView label = text(review, title);
                        android.view.ViewParent card = label == null ? null : label.getParent();
                        while (card instanceof View && ((View) card).getBackground() == null) card = card.getParent();
                        require(card instanceof View && sections.add(card), "review sections are not visually separated");
                        if ("小队结算".equals(title)) require(text((View) card, "汇率结算") != null,
                                "currency conversion is missing from the settlement card");
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
                companion.add(peer.id, "topup", new JSONObject().put("person", peer.localPerson)
                        .put("currency", "USD").put("amount", 250));
                teamExchange(sync, companion, peer, other);
                waitForText(instrumentation, activity, "4 笔");
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    require(text(root, "现金管理") == null &&
                            text(root, "成员还款") == null && text(root, "换汇") == null,
                            "cash actions still appeared in review");
                    View history = (View) text(root, "旅行记录").getParent();
                    require(text(history, "现金入账").performClick(), "cash history filter was missing");
                    require(text(history, "2 / 4 笔") != null && recordContaining(history, "现金入账 +10.00 USD") != null &&
                            recordContaining(history, "现金入账 +2.50 USD") != null,
                            "opening and top-up were not both included in cash history");
                    text(history, "全部").performClick();
                });
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    checkCurrencyForms(instrumentation, activity, store, trip);
                    instrumentation.runOnMainSync(() -> text(activity.getWindow().getDecorView(), "复盘").performClick());
                }
                waitForText(instrumentation, activity, "5 笔");
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    require(recordContaining(root, "现金入账 +2.50 CNY") != null,
                            "cash recorded from quick entry did not appear in review");
                });
                checkRecordExpansion(instrumentation, activity, store, trip, sync, companion, peer, other);
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    require(text(root, "旅行").performClick(), "travel navigation missing");
                    root = activity.getWindow().getDecorView();
                    require(countText(root, "新建小队旅行") == 1 && text(root, "邀请同伴") != null, "first setup entries missing");
                    require(text(root, "蓝牙连接") == null && text(root, "热点地址直连") == null &&
                            text(root, "重新连接并补传") == null, "manual sync controls appeared in group trip");
                    text(root, "复盘").performClick();
                    text(activity.getWindow().getDecorView(), "旅行").performClick();
                });
                waitForText(instrumentation, activity, "新建小队旅行");
                instrumentation.runOnMainSync(() -> require(text(activity.getWindow().getDecorView(), "总支出") == null,
                        "a stale review load replaced the trips page"));
            } finally { instrumentation.runOnMainSync(activity::finish); instrumentation.waitForIdleSync(); }
        } finally { other.cleanup(); }
    }

    static void runFirstSyncCardColors(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        PeerProtocolChecks.Ctx other = new PeerProtocolChecks.Ctx(context);
        try (LedgerStore store = new LedgerStore(context); LedgerStore companion = new LedgerStore(other)) {
            LedgerStore.Trip trip = store.createOpenTeam("卡片确认检查", "甲", 2);
            LedgerStore.Trip pending = companion.joinTrip(store.inviteCode(trip), "乙");
            Team.Identity identity = Team.Identity.load(other);
            JSONObject grant = store.enroll(trip, Team.Identity.load(context), identity.deviceId, identity.key, "", "乙");
            companion.acceptSeat(pending, grant, identity.deviceId, identity.key, grant.getString("member"));
            LedgerStore.Trip peer = companion.getTrip(trip.id);
            LedgerActions localActions = new LedgerActions(store), peerActions = new LedgerActions(companion);
            localActions.expense(trip, trip.localPerson, "CNY", "11.23", false, "首轮本机");
            peerActions.expense(peer, peer.localPerson, "USD", "24.56", false, "首轮同伴");
            context.getSharedPreferences("ui", 0).edit().putString("last_trip", trip.id)
                    .putBoolean(MainActivity.STARTUP_PERMISSIONS_REQUESTED, true).commit();
            Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                LedgerStyle style = new LedgerStyle(context);
                PeerSync sync = transport(activity).nearby;
                require(sync != null, "group coordinator not running on open");
                instrumentation.runOnMainSync(() -> text(activity.getWindow().getDecorView(), "复盘").performClick());
                waitForRecordColor(instrumentation, activity, "11.23 CNY", style.pendingRecord);
                instrumentation.runOnMainSync(() -> require(text(activity.getWindow().getDecorView(), "查看全部").performClick(),
                        "full records could not open before sync"));
                waitForText(instrumentation, activity, "全部旅行记录");
                waitForRecordColor(instrumentation, activity, "11.23 CNY", style.pendingRecord);

                // One real session with records in both directions while the full list is open.
                teamExchange(sync, companion, peer, other);
                waitForRecordColor(instrumentation, activity, "11.23 CNY", style.confirmedRecord);
                waitForRecordColor(instrumentation, activity, "24.56 USD", style.confirmedRecord);
                require(store.events(trip.id).size() == 2 && companion.events(peer.id).size() == 2,
                        "the first exchange did not produce exactly two records on each device");
                instrumentation.runOnMainSync(() -> require(text(activity.getWindow().getDecorView(), "退出").performClick(),
                        "the open record list was replaced during sync"));
                waitForRecordColor(instrumentation, activity, "24.56 USD", style.confirmedRecord);

                // Insert after the next session freezes both journals, before it receives a new peer row.
                peerActions.expense(peer, peer.localPerson, "USD", "31.57", false, "下一笔同伴");
                teamExchange(sync, companion, peer, other, PeerRoutePolicy.Channel.WIFI, () -> {
                    try { localActions.expense(trip, trip.localPerson, "CNY", "43.89", false, "会话中的本机新账"); }
                    catch (Exception error) { throw new AssertionError(error); }
                });
                waitForText(instrumentation, activity, "4 笔");
                waitForRecordColor(instrumentation, activity, "43.89 CNY", style.pendingRecord);
                require(companion.events(peer.id).size() == 3,
                        "a simultaneous local entry unexpectedly entered the frozen outbound journal");
                require(SyncEngine.confirmedThrough(context, store, trip) < store.snapshot(trip.id).syncPoint.row,
                        "advancing an inbound checkpoint skipped pending local work");
            } finally { instrumentation.runOnMainSync(activity::finish); instrumentation.waitForIdleSync(); }
        } finally { other.cleanup(); }
    }

    static void runQuickDrafts(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        try (LedgerStore store = new LedgerStore(context)) {
            LedgerStore.Trip first = store.createTeam("草稿甲旅行", Arrays.asList("甲"));
            LedgerStore.Trip second = store.createTeam("草稿乙旅行", Arrays.asList("乙"));
            context.getSharedPreferences("ui", 0).edit().putString("last_trip", first.id)
                    .putBoolean(MainActivity.STARTUP_PERMISSIONS_REQUESTED, true).commit();
            Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                String firstTrip = first.name, secondTrip = second.name;
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    amount(root).setText("31.");
                    labeledInput(root, "备注（可选）").setText("甲的草稿");
                    labeledInput(root, "货币").setText("U");
                    clickDraftPages(activity, "复盘", "记账", "旅行", "记账");
                    requireDraft(activity, "31.", "甲的草稿");
                    require("U".contentEquals(labeledInput(activity.getWindow().getDecorView(), "货币").getText()), "partial currency was lost");
                    clickDraftPages(activity, "旅行", secondTrip);
                    requireDraft(activity, "", "");

                    root = activity.getWindow().getDecorView();
                    amount(root).setText("0");
                    labeledInput(root, "备注（可选）").setText("乙的草稿");
                    clickDraftPages(activity, "保存这笔支出", "复盘", "记账");
                    requireDraft(activity, "0", "乙的草稿");
                    require(!text(activity.getWindow().getDecorView(), "保存这笔支出").isEnabled(), "a returning form allowed a second in-flight save");
                });
                waitForQuickSaveButton(instrumentation, activity);
                require(store.events(first.id).isEmpty() && store.events(second.id).isEmpty(), "draft or failed save became a ledger entry");
                instrumentation.runOnMainSync(() -> {
                    requireDraft(activity, "0", "乙的草稿");
                    amount(activity.getWindow().getDecorView()).setText("22.00");
                    clickDraftPages(activity, "保存这笔支出", "旅行", firstTrip);
                    requireDraft(activity, "31.", "甲的草稿");
                    clickDraftPages(activity, "旅行", secondTrip);
                    require(!text(activity.getWindow().getDecorView(), "保存这笔支出").isEnabled(), "saving form was reopened as a second entry");
                });
                waitForQuickSaveButton(instrumentation, activity);
                instrumentation.runOnMainSync(() -> {
                    requireDraft(activity, "", "");
                    clickDraftPages(activity, "旅行", firstTrip);
                    requireDraft(activity, "31.", "甲的草稿");
                });
                require(store.events(first.id).isEmpty() && store.events(second.id).size() == 1, "submission did not clear just the saved trip");
            } finally {
                instrumentation.runOnMainSync(activity::finish);
                instrumentation.waitForIdleSync();
            }
        }
    }

    private static void clickDraftPages(Activity activity, String... labels) {
        for (String label : labels) {
            View button = text(activity.getWindow().getDecorView(), label);
            while (button != null && !button.isClickable() && button.getParent() instanceof View)
                button = (View) button.getParent();
            require(button != null && button.performClick(), "missing form action " + label);
        }
    }

    private static void requireDraft(Activity activity, String amountText, String noteText) {
        View root = activity.getWindow().getDecorView();
        require(amountText.contentEquals(amount(root).getText()) &&
                noteText.contentEquals(labeledInput(root, "备注（可选）").getText()), "unexpected amount or note after restoring draft");
    }

    private static void waitForQuickSaveButton(Instrumentation instrumentation, Activity activity) {
        java.util.concurrent.atomic.AtomicBoolean enabled = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            instrumentation.runOnMainSync(() -> enabled.set(text(activity.getWindow().getDecorView(), "保存这笔支出").isEnabled()));
            if (enabled.get()) return;
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("the quick-entry submission did not finish");
    }

    static void runCustomSplit(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        try (LedgerStore store = new LedgerStore(context)) {
            LedgerStore.Trip trip = store.createTeam("分摊界面检查", Arrays.asList("甲", "乙"));
            context.getSharedPreferences("ui", 0).edit().putString("last_trip", trip.id)
                    .putBoolean(MainActivity.STARTUP_PERMISSIONS_REQUESTED, true).commit();
            Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    require(paymentControl(root).performClick(), "payment settings did not open");
                });
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View dialog = windowWithText("完成");
                    require(dialog != null, "payment settings are missing");
                    Spinner payer = paymentPayer(dialog);
                    require(payer.getCount() == 2, "payer dropdown did not include all members");
                    payer.setSelection(1);
                    View equal = description(dialog, "分摊规则，全员平摊");
                    View personal = description(dialog, "分摊规则，仅付款人");
                    View custom = description(dialog, "分摊规则，自定义分摊");
                    require(fullyVisible(equal) && fullyVisible(personal) && fullyVisible(custom) && equal.isSelected()
                            && !personal.isSelected() && !custom.isSelected(), "direct split choices are missing or inconsistent");
                    require(personal.performClick() && personal.isSelected() && !equal.isSelected(),
                            "personal split did not switch directly");
                    require(custom.performClick(), "custom split fields did not open");
                });
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View dialog = windowWithText("完成");
                    require(dialog != null && labeledInput(dialog, "甲（我）") != null, "custom split inputs are missing");
                    text(dialog, "取消").performClick();
                    View root = activity.getWindow().getDecorView();
                    require(paymentControl(root).getContentDescription().toString().contains("甲（我），全员平摊") &&
                            amount(root).isEnabled() && amount(root).length() == 0,
                            "cancelling payment settings changed the payer, rule or amount");
                    paymentControl(root).performClick();
                });
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View dialog = windowWithText("完成");
                    require(text(dialog, "实际付款人") == null, "payer was repeated inside the split dialog");
                    paymentPayer(dialog).setSelection(1);
                    description(dialog, "分摊规则，自定义分摊").performClick();
                    labeledInput(dialog, "甲（我）").setText("36");
                    labeledInput(dialog, "乙").setText("23");
                    require(text(dialog, "合计 59.00 CNY") != null, "incorrect live total");
                    text(dialog, "完成").performClick();
                    View root = activity.getWindow().getDecorView();
                    require("59.00".contentEquals(amount(root).getText()) && !amount(root).isEnabled(),
                            "split did not populate the quick-entry amount");
                    require(paymentControl(root).getContentDescription().toString().contains("乙，自定义分摊"),
                            "payment summary did not update the payer and rule");
                    text(root, "复盘").performClick();
                });
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    text(activity.getWindow().getDecorView(), "记账").performClick();
                    View root = activity.getWindow().getDecorView();
                    require("59.00".contentEquals(amount(root).getText()) && !amount(root).isEnabled() &&
                            paymentControl(root).getContentDescription().toString().contains("乙，自定义分摊"),
                            "custom split draft was lost after switching tabs");
                    text(root, "保存这笔支出").performClick();
                    text(activity.getWindow().getDecorView(), "复盘").performClick();
                });
                waitForText(instrumentation, activity, "1 笔");
                java.util.concurrent.atomic.AtomicBoolean hasDetails = new java.util.concurrent.atomic.AtomicBoolean();
                long deadline = SystemClock.elapsedRealtime() + 5000;
                do {
                    instrumentation.runOnMainSync(() -> {
                        View root = activity.getWindow().getDecorView();
                        hasDetails.set(recordContaining(root, "分摊：甲 36.00 · 乙 23.00") != null &&
                                text(root, "甲 → 乙") != null);
                    });
                    if (!hasDetails.get()) SystemClock.sleep(40);
                } while (!hasDetails.get() && SystemClock.elapsedRealtime() < deadline);
                require(hasDetails.get(), "review lost the detail or reversed the repayment");
                LedgerStore.Summary summary = store.summary(trip.id);
                require(summary.spent.get("CNY") == 5900L && summary.teamNet.get("m1").get("CNY") == -3600L
                        && summary.teamCash.get("m2").get("CNY") == -5900L,
                        "custom split changed the total, payer cash or repayment amount");
            } finally { instrumentation.runOnMainSync(activity::finish); instrumentation.waitForIdleSync(); }
        }
    }

    static void runNoteHistory(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("ui", 0);
        NoteHistory history = new NoteHistory(prefs);
        String isolated = java.util.UUID.randomUUID().toString();
        for (int i = 1; i <= 6; i++) history.remember(isolated, "备注" + i);
        history.remember(isolated, " 备注3 ");
        history.remember(isolated, "   ");
        require(history.options(isolated).equals(Arrays.asList("备注3", "备注6", "备注5", "备注4", "备注2")),
                "recent note limit, order, or deduplication failed");
        android.content.SharedPreferences.Editor clear = prefs.edit();
        history.clear(clear, isolated);
        clear.apply();
        require(history.options(isolated).isEmpty(), "trip note history was not cleared");

        try (LedgerStore store = new LedgerStore(context)) {
            LedgerStore.Trip trip = store.createTeam("备注历史检查", Arrays.asList("甲"));
            require(history.options(trip.id).isEmpty(), "another trip inherited note history");
            prefs.edit().putString("last_trip", trip.id).putBoolean(MainActivity.STARTUP_PERMISSIONS_REQUESTED, true).commit();
            Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity entry = instrumentation.startActivitySync(launch);
            String note = "骑行午餐";
            try {
                instrumentation.runOnMainSync(() -> {
                    View root = entry.getWindow().getDecorView();
                    labeledInput(root, "备注（可选）").setText(note);
                    require(history.options(trip.id).isEmpty(), "unsaved input entered note history");
                    amount(root).setText("18");
                    require(text(root, "保存这笔支出").performClick(), "quick save was missing");
                });
                long deadline = SystemClock.elapsedRealtime() + 5000;
                while (history.options(trip.id).isEmpty() && SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(40);
                require(history.options(trip.id).equals(Arrays.asList(note)) &&
                        note.equals(store.events(trip.id).get(0).data.getString("note")), "saved note was not remembered");
            } finally {
                instrumentation.runOnMainSync(entry::finish);
                instrumentation.waitForIdleSync();
            }

            Activity reopened = instrumentation.startActivitySync(launch);
            try {
                instrumentation.runOnMainSync(() -> {
                    EditText field = labeledInput(reopened.getWindow().getDecorView(), "备注（可选）");
                    require(field.getText().length() == 0, "previous note was automatically applied");
                    field.requestFocus();
                    field.performClick();
                });
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View popup = windowWithText(note);
                    require(popup != null, "reopened app did not show saved note");
                    View row = text(popup, note);
                    while (!(row.getParent() instanceof android.widget.ListView)) row = (View) row.getParent();
                    android.widget.ListView list = (android.widget.ListView) row.getParent();
                    int position = list.getPositionForView(row);
                    require(list.performItemClick(row, position, list.getItemIdAtPosition(position)), "note was not selectable");
                    EditText field = labeledInput(reopened.getWindow().getDecorView(), "备注（可选）");
                    require(note.contentEquals(field.getText()) && field.getSelectionEnd() == note.length(),
                            "selecting a note did not fill the editable field");
                    field.append("补充");
                    require((note + "补充").contentEquals(field.getText()), "selected note was no longer editable");
                    View categories = description(reopened.getWindow().getDecorView(), "选择备注分类");
                    require(categories != null && categories.performClick(), "category choices did not open");
                });
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View popup = windowWithText("住宿");
                    require(popup != null && text(popup, "不分类") == null, "note choices allocated an extra unclassified row");
                    View row = popup == null ? null : text(popup, "住宿");
                    while (row != null && row.getParent() instanceof View &&
                            !(row.getParent() instanceof android.widget.ListView)) row = (View) row.getParent();
                    require(row != null && row.getParent() instanceof android.widget.ListView, "category choice missing");
                    android.widget.ListView list = (android.widget.ListView) row.getParent();
                    int position = list.getPositionForView(row);
                    require(list.performItemClick(row, position, list.getItemIdAtPosition(position)), "category selection failed");
                    View root = reopened.getWindow().getDecorView();
                    require(description(root, "备注分类，住宿") != null &&
                            (note + "补充").contentEquals(labeledInput(root, "备注（可选）").getText()),
                            "category replaced the editable note or was not selected");
                });
                require(store.events(trip.id).size() == 1 && history.options(trip.id).equals(Arrays.asList(note)),
                        "using a suggestion changed the ledger or history before save");
            } finally {
                instrumentation.runOnMainSync(reopened::finish);
                instrumentation.waitForIdleSync();
            }
        }
    }

    static void runTripStatistics(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("ui", 0);
        PeerProtocolChecks.Ctx other = new PeerProtocolChecks.Ctx(context);
        try (LedgerStore store = new LedgerStore(context); LedgerStore companion = new LedgerStore(other)) {
            LedgerStore.Trip trip = store.createTeam("统计界面检查", Arrays.asList("甲", "乙"));
            LedgerStore.Trip peer = companion.joinTrip(store.inviteCode(trip), "m2");
            LedgerActions actions = new LedgerActions(store);
            actions.expense(trip, trip.localPerson, "CNY", "36.50", false, "【餐食】 午餐");
            actions.expense(trip, trip.localPerson, "JPY", "200", true, "【交通】 车票");
            LedgerStore.Event meal = store.events(trip.id).stream()
                    .filter(event -> "CNY".equals(event.data.optString("currency"))).findFirst().get();
            LedgerStore.Event fare = store.events(trip.id).stream()
                    .filter(event -> "JPY".equals(event.data.optString("currency"))).findFirst().get();
            prefs.edit().putString("last_trip", trip.id).putBoolean(MainActivity.STARTUP_PERMISSIONS_REQUESTED, true)
                    .putLong("archived_at/" + trip.id, System.currentTimeMillis()).commit();
            Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                waitForText(instrumentation, activity, "保存这笔支出");
                TripStatistics unsynced = TripStatistics.load(context, store, trip.id);
                require(unsynced.pending && unsynced.report.records.isEmpty(), "stats counted unsynced local expenses");
                instrumentation.runOnMainSync(() -> {
                    clickDraftPages(activity, "旅行");
                    openTripReport(activity, trip.name);
                });
                clickWindow(instrumentation, "暂无已同步账目", "返回");

                PeerSync sync = transport(activity).nearby;
                teamExchange(sync, companion, peer, other);
                waitForTripConfirmation(context, store, trip.id);
                actions.editExpense(trip, meal, trip.localPerson, "CNY", "40", 0, null, "【住宿】 房费");
                actions.delete(trip, java.util.Collections.singletonList(fare.id));
                actions.expense(trip, trip.localPerson, "USD", "5", true, "未同步的支出");
                TripStatistics pending = TripStatistics.load(context, store, trip.id);
                require(pending.pending && pending.report.records.size() == 2 && pending.report.records.stream()
                                .anyMatch(row -> "36.50 CNY".equals(row.amount) && "【餐食】 午餐".equals(row.note)) &&
                                pending.report.records.stream().anyMatch(row -> "200 JPY".equals(row.amount)),
                        "pending edits or deletions changed the confirmed report before sync");
                instrumentation.runOnMainSync(() -> openTripReport(activity, trip.name));
                clickWindow(instrumentation, "消费分类", "返回");

                teamExchange(sync, companion, peer, other);
                waitForTripConfirmation(context, store, trip.id);
                TripStatistics confirmed = TripStatistics.load(context, store, trip.id);
                require(!confirmed.pending && confirmed.report.records.size() == 2 && confirmed.report.records.stream()
                                .anyMatch(row -> "40.00 CNY".equals(row.amount) && "【住宿】 房费".equals(row.note)) &&
                                confirmed.report.records.stream().anyMatch(row -> "5.00 USD".equals(row.amount)) &&
                                confirmed.report.records.stream().noneMatch(row -> fare.id.equals(row.id)),
                        "stats did not incorporate the confirmed edit, deletion and new expense");
                instrumentation.runOnMainSync(() -> clickDraftPages(activity, "记账"));
                waitForText(instrumentation, activity, "保存这笔支出");
            } finally {
                instrumentation.runOnMainSync(activity::finish);
                instrumentation.waitForIdleSync();
            }
        } finally { other.cleanup(); }
    }

    private static void openTripReport(Activity activity, String tripName) {
        View card = text(activity.getWindow().getDecorView(), tripName);
        while (card != null && text(card, "查看报告") == null && card.getParent() instanceof View)
            card = (View) card.getParent();
        View action = card == null ? null : text(card, "查看报告");
        require(action != null && action.performClick(), "trip card did not directly open its statistics");
    }

    private static void waitForTripConfirmation(Context context, LedgerStore store, String tripId) {
        long deadline = SystemClock.elapsedRealtime() + 5000L;
        do {
            LedgerStore.Snapshot snapshot = store.snapshot(tripId);
            if (SyncEngine.confirmedThrough(context, store, snapshot.trip) >= snapshot.syncPoint.row) return;
            SystemClock.sleep(60);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("peer acknowledgement did not reach the last local operation");
    }

    private static void clickWindow(Instrumentation instrumentation, String anchor, String action) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        java.util.concurrent.atomic.AtomicBoolean clicked = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            instrumentation.runOnMainSync(() -> {
                View window = windowWithText(anchor);
                View target = window == null ? null : text(window, action);
                if (target != null) clicked.set(target.performClick());
            });
            if (clicked.get()) {
                instrumentation.waitForIdleSync();
                return;
            }
            SystemClock.sleep(60);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("missing " + action + " in " + anchor);
    }

    static void runLedgerBackup(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        try (LedgerStore store = new LedgerStore(context)) {
            LedgerStore.Trip trip = store.createTeam("备份检查", Arrays.asList("甲", "乙"));
            LedgerActions actions = new LedgerActions(store);
            actions.expense(trip, "m1", "CNY", "12", false, "原备注");
            LedgerStore.Event original = store.summary(trip.id).visible.get(0);
            actions.editExpense(trip, original, "m1", "CNY", "15", 0, null, "修订\n备注");
            actions.delete(trip, java.util.Collections.singletonList(original.id));
            actions.cash(trip, "m1", "USD", "25");
            List<LedgerStore.Event> operations = store.events(trip.id);
            context.getSharedPreferences("ui", 0).edit().putString("last_trip", trip.id)
                    .putBoolean(MainActivity.STARTUP_PERMISSIONS_REQUESTED, true).commit();
            Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.io.File file = java.io.File.createTempFile("ride-backup-", ".rlbackup.txt", context.getCacheDir());
            android.content.IntentFilter createDocument = new android.content.IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
            createDocument.addCategory(Intent.CATEGORY_OPENABLE);
            createDocument.addDataType("text/plain");
            Instrumentation.ActivityMonitor picker = instrumentation.addMonitor(createDocument, new Instrumentation.ActivityResult(
                    Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(file))), true);
            try {
                instrumentation.runOnMainSync(() -> text(activity.getWindow().getDecorView(), "复盘").performClick());
                waitForText(instrumentation, activity, "1 笔");
                instrumentation.runOnMainSync(() -> text(activity.getWindow().getDecorView(), "查看全部").performClick());
                waitForText(instrumentation, activity, "全部旅行记录");
                instrumentation.runOnMainSync(() -> require(text(activity.getWindow().getDecorView(), "日志导出").performClick(),
                        "existing export action unavailable"));
                long deadline = SystemClock.elapsedRealtime() + 5000;
                String contents;
                do {
                    contents = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                    if (contents.contains("\"sha256\"") && contents.endsWith("\n")) break;
                    SystemClock.sleep(40);
                } while (SystemClock.elapsedRealtime() < deadline);
                String[] lines = contents.split("\n");
                require(picker.getHits() == 1 && lines.length == operations.size() + 2 &&
                        LedgerBackup.isBackupName("trip.RLBACKUP") && !LedgerBackup.isBackupName("trip.txt"),
                        "backup did not use the existing picker or omitted operations");
                JSONObject header = new JSONObject(lines[0]);
                require(header.getInt("version") == 1 && header.getString("tripId").equals(trip.id) &&
                        header.getInt("operations") == 4 && header.getJSONObject("members").getString("m1").equals("甲") &&
                        !contents.contains(trip.secret), "backup metadata is missing or contains credentials");
                for (int i = 0; i < operations.size(); i++)
                    require(PeerWire.canonical(new JSONObject(lines[i + 1])).equals(PeerWire.canonical(operations.get(i).json())),
                            "backup changed an operation, its timestamp, or its payload");
                String payload = contents.substring(0, contents.length() - lines[lines.length - 1].length() - 1);
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte value : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                require(new JSONObject(lines[lines.length - 1]).getString("sha256").contentEquals(hex),
                        "backup checksum does not match the exported bytes");
            } finally {
                instrumentation.removeMonitor(picker);
                file.delete();
                instrumentation.runOnMainSync(activity::finish);
                instrumentation.waitForIdleSync();
            }
        }
    }

    static void runRecordEdit(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        PeerProtocolChecks.Ctx other = new PeerProtocolChecks.Ctx(context);
        try (LedgerStore local = new LedgerStore(context); LedgerStore remote = new LedgerStore(other)) {
            LedgerStore.Trip trip = local.createTeam("编辑记录测试", Arrays.asList("甲", "乙"));
            LedgerStore.Trip peer = remote.joinTrip(local.inviteCode(trip), "m2");
            LedgerActions entries = new LedgerActions(local);
            entries.expense(trip, "m1", "CNY", "36", false, "骑行午饭");
            LedgerStore.Event original = local.events(trip.id).get(0);
            context.getSharedPreferences("ui", 0).edit().putString("last_trip", trip.id)
                    .putBoolean(MainActivity.STARTUP_PERMISSIONS_REQUESTED, true).commit();
            Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                PeerSync sync = transport(activity).nearby;
                teamExchange(sync, remote, peer, other);
                instrumentation.runOnMainSync(() -> text(activity.getWindow().getDecorView(), "复盘").performClick());
                waitForText(instrumentation, activity, "1 笔");
                instrumentation.runOnMainSync(() -> require(
                        recordContaining(activity.getWindow().getDecorView(), "骑行午饭").performClick(), "record was not editable"));
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View editor = windowWithText("编辑记录");
                    require(editor != null, "edit sheet did not open");
                    EditText amount = labeledInput(editor, "支付金额");
                    require("36.00".contentEquals(amount.getText()), "editor did not load original amount");
                    amount.setText("59");
                    text(editor, "保存修改").performClick();
                });
                waitForRecordColor(instrumentation, activity, "59.00 CNY", 0xffe9ebed);
                LedgerStore.Event updated = local.summary(trip.id).visible.get(0);
                require(updated.id.equals(original.id) && updated.at == original.at, "edit changed stable identity or time");
                require(remote.summary(peer.id).spent.get("CNY") == 3600, "offline edit reached the remote ledger prematurely");
                teamExchange(sync, remote, peer, other);
                teamExchange(sync, remote, peer, other);
                require(remote.summary(peer.id).visible.size() == 1 && remote.summary(peer.id).spent.get("CNY") == 5900 &&
                        remote.events(peer.id).size() == 2, "edit did not converge or replay created another record");
            } finally {
                instrumentation.runOnMainSync(activity::finish);
                instrumentation.waitForIdleSync();
            }
        } finally { other.cleanup(); }
    }

    static void runRecordDeletion(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        PeerProtocolChecks.Ctx other = new PeerProtocolChecks.Ctx(context);
        try (LedgerStore local = new LedgerStore(context); LedgerStore remote = new LedgerStore(other)) {
            LedgerStore.Trip trip = local.createTeam("批量删除测试", Arrays.asList("甲", "乙"));
            LedgerStore.Trip peer = remote.joinTrip(local.inviteCode(trip), "m2");
            LedgerActions entries = new LedgerActions(local);
            entries.expense(trip, trip.localPerson, "CNY", "1", false, "离线删除甲");
            entries.expense(trip, trip.localPerson, "CNY", "2", false, "离线删除乙");
            entries.expense(trip, trip.localPerson, "CNY", "3", false, "保留这笔");
            context.getSharedPreferences("ui", 0).edit().putString("last_trip", trip.id)
                    .putBoolean(MainActivity.STARTUP_PERMISSIONS_REQUESTED, true).commit();
            Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                PeerSync sync = transport(activity).nearby;
                teamExchange(sync, remote, peer, other);
                require(remote.summary(peer.id).visible.size() == 3, "fixture did not sync initial records");
                instrumentation.runOnMainSync(() -> text(activity.getWindow().getDecorView(), "复盘").performClick());
                waitForText(instrumentation, activity, "3 笔");
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    require(description(root, "选择要删除的记录") == null && !recordContaining(root, "离线删除甲").isLongClickable(),
                            "recent records exposed a delete action");
                    text(root, "查看全部").performClick();
                });
                waitForText(instrumentation, activity, "全部旅行记录");
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    require(!recordCheckBox(root, "离线删除甲").isShown(), "checkbox appeared before the trash action");
                    description(root, "选择要删除的记录").performClick();
                });
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    require(!text(root, "删除").isEnabled(), "delete enabled with an empty selection");
                    recordCheckBox(root, "离线删除甲").performClick();
                    recordCheckBox(root, "离线删除乙").performClick();
                    require(text(root, "已选 2 笔") != null && text(root, "删除").performClick(),
                            "checkbox selection did not enable bulk deletion");
                });
                instrumentation.waitForIdleSync();
                require(local.summary(trip.id).visible.size() == 3, "records were deleted before confirmation");
                instrumentation.runOnMainSync(() -> {
                    View confirmation = windowWithText("确认删除");
                    require(confirmation != null, "delete confirmation missing");
                    text(confirmation, "取消").performClick();
                });
                instrumentation.waitForIdleSync();
                require(local.summary(trip.id).visible.size() == 3, "cancelled deletion changed the ledger");
                instrumentation.runOnMainSync(() -> text(activity.getWindow().getDecorView(), "删除").performClick());
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> text(windowWithText("确认删除"), "确认删除").performClick());
                waitForText(instrumentation, activity, "1 笔");
                instrumentation.runOnMainSync(() -> require(text(activity.getWindow().getDecorView(), "全部旅行记录") != null,
                        "confirming deletion closed the full list"));

                require(local.summary(trip.id).visible.size() == 1 && local.summary(trip.id).spent.get("CNY") == 300L,
                        "offline deletion did not update the local review");
                require(remote.summary(peer.id).visible.size() == 3, "remote ledger changed without a connection");
                teamExchange(sync, remote, peer, other);
                require(remote.summary(peer.id).visible.size() == 1 && remote.summary(peer.id).spent.get("CNY") == 300L,
                        "reconnection did not apply both deletions on the other device");
                teamExchange(sync, remote, peer, other);
                require(local.events(trip.id).size() == 5 && remote.events(peer.id).size() == 5 &&
                        remote.summary(peer.id).visible.size() == 1, "replay duplicated or restored deleted records");
            } finally {
                instrumentation.runOnMainSync(activity::finish);
                instrumentation.waitForIdleSync();
            }
        } finally { other.cleanup(); }
    }

    private static android.widget.CheckBox recordCheckBox(View root, String note) {
        if (root instanceof android.widget.CheckBox && root.getContentDescription() != null &&
                root.getContentDescription().toString().contains(note)) return (android.widget.CheckBox) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.CheckBox match = recordCheckBox(group.getChildAt(i), note);
                if (match != null) return match;
            }
        }
        return null;
    }

    static void runRosterRefresh(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        PeerProtocolChecks.Ctx other = new PeerProtocolChecks.Ctx(context);
        try (LedgerStore store = new LedgerStore(context); LedgerStore companion = new LedgerStore(other)) {
            LedgerStore.Trip trip = store.createOpenTeam("名单刷新", "甲", 2);
            String teammate = "同行乙的超长昵称";
            LedgerStore.Trip peer = companion.joinTrip(store.inviteCode(trip), teammate);
            context.getSharedPreferences("ui", 0).edit().putString("last_trip", trip.id)
                    .putBoolean(MainActivity.STARTUP_PERMISSIONS_REQUESTED, true).commit();
            Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    amount(root).setText("36.23");
                    labeledInput(root, "备注（可选）").setText("路上的午饭");
                    paymentControl(root).performClick();
                });
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    View dialog = windowWithText("完成");
                    paymentPayer(dialog).setSelection(1);
                });
                teamExchange(transport(activity).nearby, companion, peer, other);
                long rosterDeadline = SystemClock.elapsedRealtime() + 5000;
                java.util.concurrent.atomic.AtomicBoolean rosterUpdated = new java.util.concurrent.atomic.AtomicBoolean();
                do {
                    instrumentation.runOnMainSync(() -> {
                        View dialog = windowWithText("完成");
                        Spinner payer = paymentPayer(dialog);
                        rosterUpdated.set(payer.getSelectedItemPosition() == 1 &&
                                teammate.contentEquals((String) payer.getSelectedItem()));
                    });
                    if (!rosterUpdated.get()) SystemClock.sleep(40);
                } while (!rosterUpdated.get() && SystemClock.elapsedRealtime() < rosterDeadline);
                instrumentation.runOnMainSync(() -> {
                    require(rosterUpdated.get(), "roster refresh reset the open payer dropdown or left its old name");
                    text(windowWithText("完成"), "完成").performClick();
                    View root = activity.getWindow().getDecorView();
                    require("36.23".contentEquals(amount(root).getText()) &&
                            "路上的午饭".contentEquals(labeledInput(root, "备注（可选）").getText()) &&
                            paymentControl(root).getContentDescription().toString().contains(teammate + "，全员平摊"),
                            "roster refresh reset the draft or selected member");
                    text(root, "复盘").performClick();
                });
                waitForText(instrumentation, activity, "旅行记录");
                String badgeDescription = teammate + "刚通过Wi-Fi确认连通。点击重新连接";
                awaitBadge(instrumentation, activity, badgeDescription, 3000);
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    TextView badge = (TextView) description(root, badgeDescription);
                    require(badge != null && badge.getText().toString().contains("同行乙的超长…"),
                            "review badge did not show the authenticated teammate with a shortened label");
                });
                PeerSync sync = transport(activity).nearby;
                teamExchange(sync, companion, peer, other, PeerRoutePolicy.Channel.HOTSPOT);
                awaitBadge(instrumentation, activity, teammate + "刚通过热点确认连通。点击重新连接", 3000);
                checkBadgeFallback(instrumentation, activity, sync, trip, companion, peer, other, teammate, badgeDescription);
            } finally {
                instrumentation.runOnMainSync(activity::finish);
                instrumentation.waitForIdleSync();
            }
        } finally { other.cleanup(); }
    }

    private static void checkBadgeFallback(Instrumentation instrumentation, Activity activity, PeerSync sync,
                                           LedgerStore.Trip trip, LedgerStore companion, LedgerStore.Trip peer,
                                           Context other, String teammate, String wifiBadge) throws Exception {
        Team.Identity identity = Team.Identity.load(other);
        String route = TeamProtocol.routeId(trip.team, identity.deviceId);
        java.util.concurrent.atomic.AtomicBoolean wifiDown = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger failedWifiDials = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger bluetoothDials = new java.util.concurrent.atomic.AtomicInteger();
        try (ServerSocket listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            Thread server = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(8000);
                        new TeamProtocol(companion, peer, identity, QUIET, () -> { }).accept(
                                new PeerWire(socket.getInputStream(), socket.getOutputStream(), () -> { }));
                    } catch (Exception error) {
                        if (listener.isClosed()) return;
                        android.util.Log.e("RideLedgerTest", "Fixture peer exchange failed", error);
                    }
                }
            }, "badge-test-peer");
            server.setDaemon(true);
            server.start();
            try {
                sync.offer(new PeerRoutePolicy.Endpoint("wifi:badge-test", PeerRoutePolicy.Channel.WIFI, route, true, () -> {
                    if (wifiDown.get()) {
                        failedWifiDials.incrementAndGet();
                        throw new java.io.IOException("Simulated Wi-Fi isolation");
                    }
                    return openBadgeTestSocket(listener);
                }));
                awaitBadge(instrumentation, activity, wifiBadge, 4000);
                sync.offer(new PeerRoutePolicy.Endpoint("bluetooth:badge-test", PeerRoutePolicy.Channel.BLUETOOTH, route, true, () -> {
                    bluetoothDials.incrementAndGet();
                    return openBadgeTestSocket(listener);
                }));
                LedgerStore.Trip registered = companion.getTrip(peer.id);
                new LedgerActions(companion).cash(registered, registered.localPerson, "CNY", "17.29");
                wifiDown.set(true);
                clickBadgeAndExpectSearch(instrumentation, activity, wifiBadge);
                String bluetoothBadge = teammate + "刚通过蓝牙确认连通。点击重新连接";
                awaitBadge(instrumentation, activity, bluetoothBadge, 7000);
                waitForText(instrumentation, activity, "1 笔");
                require(failedWifiDials.get() > 0 && bluetoothDials.get() > 0,
                        "refresh did not attempt Wi-Fi then fall back to Bluetooth");
                int previousDials = bluetoothDials.get();
                clickBadgeAndExpectSearch(instrumentation, activity, bluetoothBadge);
                awaitBadge(instrumentation, activity, bluetoothBadge, 7000);
                require(bluetoothDials.get() > previousDials, "a second tap reused an old contact without searching");
                String offlineBadge = "无连接。点击重新连接";
                awaitBadge(instrumentation, activity, offlineBadge, PeerPresence.RECENT_MILLIS + 6000);
                listener.close();
                clickBadgeAndExpectSearch(instrumentation, activity, offlineBadge);
                awaitBadge(instrumentation, activity, offlineBadge, 8000);
            } finally {
                listener.close();
                server.join(3000);
            }
        }
    }

    private static void clickBadgeAndExpectSearch(Instrumentation instrumentation, Activity activity, String current) {
        instrumentation.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            View badge = description(root, current);
            require(badge instanceof ConnectionBadge && badge.performClick(), "review badge could not request a reconnection");
            require(description(root, "正在查找队友。点击重新连接") == badge,
                    "review badge did not immediately replace the previous status with searching");
        });
    }

    private static final PeerProtocol.Listener QUIET = new PeerProtocol.Listener() {
        @Override public void authenticated(String id) { }
        @Override public void stored(int count) { }
    };

    private static PeerConnection openBadgeTestSocket(ServerSocket listener) throws Exception {
        Socket socket = new Socket("127.0.0.1", listener.getLocalPort());
        try {
            socket.setSoTimeout(8000);
            return new PeerConnection(socket.getInputStream(), socket.getOutputStream(), socket, null);
        } catch (Exception error) {
            socket.close();
            throw error;
        }
    }

    private static void awaitBadge(Instrumentation instrumentation, Activity activity, String expected, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        java.util.concurrent.atomic.AtomicBoolean matched = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            instrumentation.runOnMainSync(() -> matched.set(
                    description(activity.getWindow().getDecorView(), expected) instanceof ConnectionBadge));
            if (matched.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("connection badge did not show " + expected);
    }

    private static Spinner paymentPayer(View root) {
        View payer = description(root, "付款人");
        TextView label = text(root, "谁付款");
        require(payer instanceof Spinner && label != null && countText(root, "谁付款") == 1 &&
                        payer.getParent() == label.getParent() && ((ViewGroup) payer.getParent()).getChildCount() == 2,
                "payer settings did not use one compact dropdown row");
        return (Spinner) payer;
    }

    private static View paymentControl(View root) {
        View control = text(root, "付款与分摊");
        while (control != null && !control.isClickable() && control.getParent() instanceof View)
            control = (View) control.getParent();
        require(control != null && control.isClickable(), "missing payment settings control");
        return control;
    }

    private static void checkRecordExpansion(Instrumentation instrumentation, Activity activity,
                                             LedgerStore store, LedgerStore.Trip trip, PeerSync sync,
                                             LedgerStore companion, LedgerStore.Trip peer, Context other) throws Exception {
        JSONArray events = new JSONArray();
        long start = System.currentTimeMillis();
        for (int i = 1; i <= 65; i++) {
            events.put(new LedgerStore.Event(java.util.UUID.randomUUID().toString(), trip.id, "expense", start + i,
                    new JSONObject().put("payer", trip.localPerson).put("currency", "CNY").put("amount", 100)
                            .put("mode", "personal").put("shares", trip.team.shares(100, trip.localPerson, true))
                            .put("note", "展开测试#" + i)).json());
        }
        store.mergeStrict(trip.id, events);
        instrumentation.runOnMainSync(() -> text(activity.getWindow().getDecorView(), "复盘").performClick());
        waitForText(instrumentation, activity, "70 笔");
        instrumentation.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            View newest = recordContaining(root, "展开测试#65");
            View fifth = recordContaining(root, "展开测试#61");
            require(newest != null && fifth != null && recordContaining(root, "展开测试#60") == null,
                    "review did not start with the five most recent records");
            ViewGroup records = (ViewGroup) newest.getParent();
            require(records.indexOfChild(newest) == 0 && records.indexOfChild(fifth) == 4 &&
                    text(root, "小队结算") != null, "recent records were out of order or settlement was missing");
            require(text(root, "显示更多").performClick(), "expand action missing");
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            TextView title = text(root, "全部旅行记录");
            require(title != null, "all records overlay did not open");
            ViewGroup overlay = (ViewGroup) title.getParent().getParent();
            android.widget.ListView list = (android.widget.ListView) overlay.getChildAt(overlay.getChildCount() - 1);
            Rect panelBounds = new Rect(), navBounds = new Rect();
            overlay.getGlobalVisibleRect(panelBounds);
            ((View) text(root, "记账").getParent()).getGlobalVisibleRect(navBounds);
            require(panelBounds.bottom == navBounds.top && fullyVisible(text(overlay, "日志导出")) &&
                    fullyVisible(text(overlay, "退出")), "overlay covered navigation or clipped its actions");
            require(list.getCount() == 70 && list.getChildCount() < list.getCount() &&
                    ((LedgerReview.Row) list.getItemAtPosition(0)).label.endsWith("展开测试#65") &&
                    ((LedgerReview.Row) list.getItemAtPosition(10)).label.endsWith("展开测试#55"),
                    "overlay did not expose the full chronological list");
            list.setSelection(list.getCount() - 1);
        });
        instrumentation.waitForIdleSync();
        companion.add(peer.id, "topup", new JSONObject().put("person", peer.localPerson)
                .put("currency", "USD").put("amount", 12345).put("note", "弹层打开后收到"));
        teamExchange(sync, companion, peer, other);
        waitForText(instrumentation, activity, "71 笔");
        instrumentation.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            require(text(root, "全部旅行记录") != null && text(root, "退出") != null,
                    "receiving records closed the history overlay");
        });
        java.io.File export = java.io.File.createTempFile("ride-records-", ".txt", activity.getCacheDir());
        android.content.IntentFilter createDocument = new android.content.IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
        createDocument.addCategory(Intent.CATEGORY_OPENABLE);
        createDocument.addDataType("text/plain");
        Instrumentation.ActivityMonitor picker = instrumentation.addMonitor(createDocument,
                new Instrumentation.ActivityResult(Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(export))), true);
        try {
            instrumentation.runOnMainSync(() ->
                    require(text(activity.getWindow().getDecorView(), "日志导出").performClick(), "export action missing"));
            long deadline = SystemClock.elapsedRealtime() + 5000;
            String contents;
            do {
                contents = new String(java.nio.file.Files.readAllBytes(export.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                int details = contents.indexOf("逐笔明细");
                if (details >= 0 && contents.indexOf("5.00 USD", details) >= 0) break;
                SystemClock.sleep(40);
            } while (SystemClock.elapsedRealtime() < deadline);
            int details = contents.indexOf("逐笔明细");
            int newest = contents.indexOf("展开测试#65", details);
            int older = contents.indexOf("展开测试#55", details);
            require(picker.getHits() == 1 && contents.contains("消费分类") && contents.contains("弹层打开后收到") &&
                    details >= 0 && newest > details && older > newest && contents.indexOf("5.00 USD", details) > older,
                    "report export omitted statistics or changed record order");
        } finally {
            instrumentation.removeMonitor(picker);
            export.delete();
        }
        instrumentation.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            require(text(root, "退出").performClick() && text(root, "全部旅行记录") == null &&
                    text(root, "小队结算") != null, "exit did not return to the review preview");
        });
    }

    private static void checkCurrencyForms(Instrumentation instrumentation, Activity activity, LedgerStore store,
                                           LedgerStore.Trip trip) {
        Context context = instrumentation.getTargetContext();
        CurrencyHistory history = new CurrencyHistory(context.getSharedPreferences("ui", 0), store);
        history.remember(trip.id, "EUR", "CNY");
        require(history.options(trip.id, "CNY").equals(Arrays.asList("EUR", "USD")),
                "local and synchronized currencies were not combined");
        history.remove(trip.id, "USD");
        require(!history.options(trip.id, "CNY").contains("USD"), "removed synchronized currency came back");
        history.remember(trip.id, "USD", "EUR");
        require(history.options(trip.id, "CNY").contains("USD"), "explicit reuse did not restore the currency");

        instrumentation.runOnMainSync(() -> {
            text(activity.getWindow().getDecorView(), "记账").performClick();
            amount(activity.getWindow().getDecorView()).setText("7.77");
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            View root = activity.getWindow().getDecorView();
            View cash = text(root, "现金管理");
            ScrollView scroll = containingScroll(cash);
            scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
            require(fullyVisible(cash) && fullyVisible(text(root, "现金入账")) &&
                    fullyVisible(text(root, "成员还款")) && fullyVisible(text(root, "换汇")) &&
                    fullyVisible(text(root, "记账")), "cash actions or bottom navigation were not reachable");
        });
        for (String action : new String[]{"现金入账", "成员还款", "换汇"}) {
            instrumentation.runOnMainSync(() -> {
                View button = description(activity.getWindow().getDecorView(), action);
                require(button != null && button.performClick(), "missing ledger action " + action);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                View dialog = windowWithText("保存");
                require(dialog != null, "form did not open for " + action);
                String[] labels = "换汇".equals(action) ? new String[]{"付出货币", "得到货币"} : new String[]{"货币代码"};
                for (String label : labels) {
                    TextView caption = text(dialog, label);
                    require(caption != null && fullyVisible(dialog.findViewById(caption.getLabelFor())) &&
                            fullyVisible(description(dialog, "选择曾用" + label)), "currency field or arrow clipped for " + label);
                }
                require(fullyVisible(text(dialog, "保存")), "save action clipped for " + action);
                if ("换汇".equals(action)) {
                    requireNext(labeledInput(dialog, "付出金额"), labeledInput(dialog, "得到金额"));
                    require(text(dialog, "付出金额").getTop() == text(dialog, "付出货币").getTop() &&
                            text(dialog, "得到金额").getTop() == text(dialog, "得到货币").getTop(), "currency labels are misaligned");
                    require(description(dialog, "选择曾用得到货币").performClick(), "history was not clickable");
                } else {
                    requireNext(labeledInput(dialog, "成员还款".equals(action) ? "还款金额" : "金额"),
                            labeledInput(dialog, "货币代码"));
                    if ("现金入账".equals(action)) {
                        labeledInput(dialog, "金额").setText("2.50");
                        text(dialog, "保存").performClick();
                        View root = activity.getWindow().getDecorView();
                        require("7.77".contentEquals(amount(root).getText()) &&
                                fullyVisible(text(root, "现金管理")), "saving cash reset the quick entry draft or scroll position");
                    } else text(dialog, "取消").performClick();
                }
            });
            if ("现金入账".equals(action)) {
                java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
                long deadline = SystemClock.elapsedRealtime() + 5000;
                do {
                    instrumentation.runOnMainSync(() -> closed.set(windowWithText("保存") == null));
                    if (!closed.get()) SystemClock.sleep(40);
                } while (!closed.get() && SystemClock.elapsedRealtime() < deadline);
                require(closed.get(), "cash save did not finish");
            }
        }
        long end = SystemClock.elapsedRealtime() + 4000;
        java.util.concurrent.atomic.AtomicBoolean selected = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            instrumentation.runOnMainSync(() -> {
                View menu = windowWithText("×");
                if (menu != null && text(menu, "EUR") != null) selected.set(text(menu, "EUR").performClick());
            });
            if (!selected.get()) SystemClock.sleep(40);
        } while (!selected.get() && SystemClock.elapsedRealtime() < end);
        require(selected.get(), "exchange history did not offer the shared currency");
        instrumentation.runOnMainSync(() -> {
            View dialog = windowWithText("保存");
            require("EUR".contentEquals(labeledInput(dialog, "得到货币").getText()),
                    "selecting a history item did not fill the exchange field");
            EditText from = labeledInput(dialog, "付出金额");
            // This test constrains the viewport itself instead of relying on the host IME.
            from.setShowSoftInputOnFocus(false);
            labeledInput(dialog, "得到金额").setShowSoftInputOnFocus(false);
            ScrollView scroll = containingScroll(from);
            require(scroll != null, "exchange fields lack a scroll viewport");
            ViewGroup.LayoutParams params = scroll.getLayoutParams();
            params.height = Math.round(116 * scroll.getResources().getDisplayMetrics().density);
            scroll.setLayoutParams(params);
            require(from.requestFocus(), "first amount could not receive focus");
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            View dialog = windowWithText("保存");
            require(!fullyVisible(labeledInput(dialog, "得到金额")), "small viewport did not exercise clipping");
            labeledInput(dialog, "付出金额").onEditorAction(EditorInfo.IME_ACTION_NEXT);
        });
        java.util.concurrent.atomic.AtomicBoolean nextVisible = new java.util.concurrent.atomic.AtomicBoolean();
        long scrollDeadline = SystemClock.elapsedRealtime() + 2000;
        do {
            instrumentation.runOnMainSync(() -> {
                EditText to = labeledInput(windowWithText("保存"), "得到金额");
                nextVisible.set(to.hasFocus() && fullyVisible(to) && containingScroll(to).getScrollY() > 0);
            });
            if (!nextVisible.get()) SystemClock.sleep(40);
        } while (!nextVisible.get() && SystemClock.elapsedRealtime() < scrollDeadline);
        instrumentation.runOnMainSync(() -> {
            View dialog = windowWithText("保存");
            EditText to = labeledInput(dialog, "得到金额");
            Rect visible = new Rect();
            to.getGlobalVisibleRect(visible);
            require(nextVisible.get(), "IME Next did not reveal the second amount (focused=" + to.hasFocus() +
                    ", bounds=" + visible.toShortString() + ", expected height=" + to.getHeight() +
                    ", scroll=" + containingScroll(to).getScrollY() + ")");
            require(fullyVisible(text(dialog, "保存")), "scrolling hid the save action");
            text(dialog, "取消").performClick();
        });
    }

    private static EditText labeledInput(View root, String label) {
        java.util.ArrayList<View> labels = new java.util.ArrayList<>();
        root.findViewsWithText(labels, label, View.FIND_VIEWS_WITH_TEXT);
        for (View caption : labels) {
            if (caption instanceof TextView && label.contentEquals(((TextView) caption).getText()) &&
                    caption.getLabelFor() != View.NO_ID) {
                View field = root.findViewById(caption.getLabelFor());
                if (field instanceof EditText) return (EditText) field;
            }
        }
        throw new AssertionError("missing labeled input " + label);
    }

    private static void requireNext(EditText field, EditText next) {
        require(field.getImeOptions() == EditorInfo.IME_ACTION_NEXT && field.focusSearch(View.FOCUS_FORWARD) == next,
                "keyboard Next has the wrong destination");
    }

    private static ScrollView containingScroll(View view) {
        for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent())
            if (parent instanceof ScrollView) return (ScrollView) parent;
        return null;
    }

    private static View windowWithText(String label) {
        for (View window : WindowInspector.getGlobalWindowViews()) if (text(window, label) != null) return window;
        return null;
    }

    private static View description(View root, String expected) {
        java.util.ArrayList<View> matches = new java.util.ArrayList<>();
        root.findViewsWithText(matches, expected, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        for (View view : matches) if (expected.contentEquals(view.getContentDescription())) return view;
        return null;
    }

    private static boolean fullyVisible(View view) {
        Rect bounds = new Rect();
        return view != null && view.getGlobalVisibleRect(bounds) &&
                bounds.width() == view.getWidth() && bounds.height() == view.getHeight() && bounds.height() > 0;
    }

    private static void waitForText(Instrumentation instrumentation, Activity activity, String label) {
        java.util.concurrent.atomic.AtomicBoolean visible = new java.util.concurrent.atomic.AtomicBoolean();
        long end = SystemClock.elapsedRealtime() + 5000;
        do {
            instrumentation.runOnMainSync(() -> visible.set(text(activity.getWindow().getDecorView(), label) != null));
            if (visible.get()) return;
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < end);
        throw new AssertionError("Review did not show " + label);
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
                requireNext(input(root, "旅行名称"), input(root, "本人名称"));
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
                // This fixture commits the grant directly. Notify the coordinator just as
                // the protocol's enrollment callback does after a real exchange.
                PeerSync joined = setupTransport(page).nearby;
                require(joined != null, "retry did not restart nearby enrollment");
                joined.syncNow();
                waitForText(instrumentation, page, "已成功加入，可返回记账");
                require(!page.isFinishing(), "success not visible before return");
                long end = SystemClock.elapsedRealtime() + 4000;
                while (!page.isFinishing() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
                require(page.isFinishing(), "join page did not return after success");
            } finally { instrumentation.runOnMainSync(page::finish); instrumentation.waitForIdleSync(); }
        } finally { organizer.cleanup(); }
    }

    static void runTeamFullAfterReinstall(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        PeerProtocolChecks.Ctx organizer = new PeerProtocolChecks.Ctx(context);
        PeerProtocolChecks.Ctx originalInstall = new PeerProtocolChecks.Ctx(context);
        try (LedgerStore remote = new LedgerStore(organizer); LedgerStore currentInstall = new LedgerStore(context);
             ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            LedgerStore.Trip created = remote.createOpenTeam("重装后的两人旅行", "组织者甲", 2);
            Team.Identity issuer = Team.Identity.load(organizer);
            Team.Identity original = Team.Identity.load(originalInstall);
            remote.enroll(created, issuer, original.deviceId, original.key, "", "Eureka");
            new LedgerActions(remote).expense(created, created.localPerson, "CNY", "15", false, "旧账");
            LedgerStore.Trip pending = currentInstall.joinTrip(remote.inviteCode(created), "Eureka");

            java.util.concurrent.FutureTask<TeamProtocol.Admission.Reason> response = new java.util.concurrent.FutureTask<>(() -> {
                try (Socket client = listener.accept()) {
                    client.setSoTimeout(6000);
                    new TeamProtocol(remote, created, issuer, QUIET, () -> { }).accept(
                            new PeerWire(client.getInputStream(), client.getOutputStream(), () -> { }));
                    throw new AssertionError("a fresh installation took over the registered member");
                } catch (TeamProtocol.Admission denied) { return denied.reason; }
            });
            Thread server = new Thread(response, "full-team-join-fixture");
            server.setDaemon(true);
            server.start();

            Activity page = instrumentation.startActivitySync(new Intent(context, TeamSetupActivity.class)
                    .putExtra(TeamSetupActivity.MODE, TeamSetupActivity.JOIN).putExtra(TeamSetupActivity.TRIP, pending.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                PeerSync joining = setupTransport(page).nearby;
                require(joining != null, "the pending device did not start enrollment");
                String route = TeamProtocol.routeId(created.team, issuer.deviceId);
                joining.offer(new PeerRoutePolicy.Endpoint("wifi:full-team-test", PeerRoutePolicy.Channel.WIFI, route,
                        true, () -> openBadgeTestSocket(listener)));
                waitForText(instrumentation, page, "小队设备名额已满");
                require(response.get(3, java.util.concurrent.TimeUnit.SECONDS) == TeamProtocol.Admission.Reason.TEAM_FULL,
                        "the organizer did not reject the fresh installation as full");
                instrumentation.runOnMainSync(() -> {
                    View root = page.getWindow().getDecorView();
                    require(!text(root, "重试连接").isShown() && !text(root, "修改本人名称").isShown(),
                            "a full team still suggested retrying or renaming");
                    View anotherTrip = text(root, "加入其他旅行");
                    require(anotherTrip != null && anotherTrip.isShown() && anotherTrip.performClick(),
                            "no action to join a different trip");
                    require(text(page.getWindow().getDecorView(), "扫码加入") != null,
                            "the alternative action did not reopen the invite chooser");
                });
                require(currentInstall.getTrip(pending.id) != null && currentInstall.events(pending.id).isEmpty() &&
                                remote.seat(created.id, original.deviceId) != null && remote.events(created.id).size() == 1,
                        "leaving the blocked join altered the old seat or ledger");
            } finally { instrumentation.runOnMainSync(page::finish); instrumentation.waitForIdleSync(); }
        } finally { organizer.cleanup(); originalInstall.cleanup(); }
    }

    private static TeamTransportAdapter setupTransport(Activity activity) throws ReflectiveOperationException {
        Field field = TeamSetupActivity.class.getDeclaredField("transport");
        field.setAccessible(true);
        return (TeamTransportAdapter) field.get(activity);
    }

    private static EditText input(View view, String hint) {
        if (view instanceof EditText) {
            CharSequence currentHint = ((EditText) view).getHint();
            if (currentHint != null && hint.contentEquals(currentHint)) return (EditText) view;
        }
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
        teamExchange(sync, peerStore, peer, context, PeerRoutePolicy.Channel.WIFI);
    }

    private static void teamExchange(PeerSync sync, LedgerStore peerStore, LedgerStore.Trip peer, Context context,
                                     PeerRoutePolicy.Channel channel) throws Exception {
        teamExchange(sync, peerStore, peer, context, channel, () -> { });
    }

    private static void teamExchange(PeerSync sync, LedgerStore peerStore, LedgerStore.Trip peer, Context context,
                                     PeerRoutePolicy.Channel channel, Runnable journalsCaptured) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             Socket client = new Socket("127.0.0.1", server.getLocalPort());
             Socket accepted = server.accept()) {
            client.setSoTimeout(8000);
            sync.accept(new PeerConnection(accepted.getInputStream(), accepted.getOutputStream(), accepted, null),
                    channel, "");
            TeamProtocol protocol = new TeamProtocol(peerStore, peer, Team.Identity.load(context), new PeerProtocol.Listener() {
                @Override public void authenticated(String id) { journalsCaptured.run(); }
                @Override public void stored(int count) { }
            }, () -> { });
            require(protocol.exchange(new PeerWire(client.getInputStream(), client.getOutputStream(), () -> { }), "").complete,
                    "group screen did not accept automatic exchange");
            // Let the coordinator consume the last receipt before this fixture closes its socket.
            require(client.getInputStream().read() == -1, "coordinator did not close the completed exchange");
        }
    }

    private static TextView text(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView result = text(((ViewGroup) view).getChildAt(i), expected); if (result != null) return result;
        }
        return null;
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(expected)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsText(group.getChildAt(i), expected)) return true;
        }
        return false;
    }

    private static View recordContaining(View view, String expected) {
        if (view.getTag() instanceof LedgerReview.Row &&
                ((LedgerReview.Row) view.getTag()).label.contains(expected)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View result = recordContaining(((ViewGroup) view).getChildAt(i), expected);
            if (result != null) return result;
        }
        return null;
    }

    private static void waitForRecordColor(Instrumentation instrumentation, Activity activity, String label, int color) {
        java.util.concurrent.atomic.AtomicInteger actual = new java.util.concurrent.atomic.AtomicInteger();
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            instrumentation.runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView();
                TextView title = text(root, "全部旅行记录");
                if (title != null && title.isShown()) root = (View) title.getParent().getParent();
                View record = recordContaining(root, label);
                actual.set(record == null ? 0 : ((GradientDrawable) record.getBackground()).getColor().getDefaultColor());
            });
            if (actual.get() == color) return;
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("expected record color " + Integer.toHexString(color) +
                " but saw " + Integer.toHexString(actual.get()));
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
