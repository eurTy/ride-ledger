package com.rideledger;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Build;
import android.content.pm.PackageManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class MainActivity extends Activity {
    private static final int QUICK = 0, REVIEW = 1, TRIPS = 2;
    private LedgerStore store;
    private PeerSync peers;
    private PeerSync teamPeers;
    private String teamPeerTrip;
    private boolean started, awaitingEnrollment;
    private CloudSync cloud;
    private SharedPreferences uiPrefs;
    private String currentTripId;
    private String recordFilter = "all";
    private boolean deletingTrips;
    private int screen = TRIPS;
    private TextView syncView;
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 71, REQUEST_BLUETOOTH_ENABLE = 72, REQUEST_DISCOVERABLE = 73;
    private static final int REQUEST_TEAM_BLUETOOTH = 74;
    private Runnable pendingBluetoothAction;
    private String[] pendingBluetoothPermissions;
    private TextView bluetoothStatus;
    private Button bluetoothToggle;
    private AlertDialog bluetoothDevicesDialog;
    private ArrayAdapter<String> bluetoothDeviceLabels;
    private final List<BluetoothDevice> bluetoothDeviceItems = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(248, 249, 247));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        store = new LedgerStore(this);
        peers = new PeerSync(this, store, this::onSyncChanged);
        cloud = new CloudSync(this, store, (message, count) -> onSyncChanged(count));
        uiPrefs = getSharedPreferences("ui", MODE_PRIVATE);
        currentTripId = uiPrefs.getString("last_trip", null);
        if (currentTripId == null || store.getTrip(currentTripId) == null) {
            List<LedgerStore.Trip> trips = store.trips();
            currentTripId = trips.isEmpty() ? null : trips.get(0).id;
        }
        screen = currentTripId == null ? TRIPS : QUICK;
        render();
    }

    @Override public void onStart() {
        super.onStart();
        if (currentTripId != null && screen != QUICK) {
            deletingTrips = false;
            screen = QUICK;
            render();
        }
        started = true;
        startSelectedSync();
        cloud.start();
        updateSync();
    }
    @Override public void onStop() {
        started = false;
        cloud.stop();
        peers.stop();
        if (teamPeers != null) teamPeers.stop();
        super.onStop();
    }
    @Override public void onDestroy() { cloud.close(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (currentTripId != null && screen != QUICK) {
            deletingTrips = false;
            screen = QUICK;
            render();
        }
        else super.onBackPressed();
    }

    private void onSyncChanged(int count) {
        LedgerStore.Trip trip = currentTripId == null ? null : store.getTrip(currentTripId);
        if ((awaitingEnrollment && trip != null && store.teamReady(trip)) || (count > 0 && screen == REVIEW)) render();
        else updateSync();
    }

    private void updateSync() {
        if (syncView != null) {
            LedgerStore.Trip trip = currentTripId == null ? null : store.getTrip(currentTripId);
            if (trip != null && trip.team != null) {
                if (!store.teamReady(trip)) {
                    String state = teamPeers == null ? "" : teamPeers.status();
                    syncView.setText(state.contains("已满") || state.contains("已登记在") ? state :
                            "首次登记时，请和建队设备打开同一旅行并保持靠近。");
                } else {
                    String permissions = TeamBle.hasPermissions(this) ? "" : "\n在系统应用权限中允许附近设备（安卓 10/11 为位置），即可使用蓝牙。";
                    syncView.setText((teamPeers == null ? "本机账目已保存" : teamPeers.details()) + permissions);
                }
            } else syncView.setText(peers.details() + (trip != null && !cloud.effectiveEndpoint(trip).isEmpty() ? "\n" + cloud.status() : ""));
        }
        refreshBluetoothControls();
    }

    private void render() {
        LedgerStore.Trip trip = currentTripId == null ? null : store.getTrip(currentTripId);
        if (trip == null) { currentTripId = null; screen = TRIPS; renderTrips(); }
        else if (screen == TRIPS) renderTrips();
        else if (screen == REVIEW) renderReview(trip);
        else renderQuick(trip);
    }

    private LinearLayout page() {
        syncView = null;
        awaitingEnrollment = false;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(248, 249, 247));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(18), dp(18), dp(30));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (currentTripId != null) root.addView(bottomBar());
        setContentView(root);
        return body;
    }

    private LinearLayout bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), dp(7), dp(8), dp(7));
        bar.setBackgroundColor(Color.WHITE);
        navItem(bar, "记账", QUICK);
        navItem(bar, "复盘", REVIEW);
        navItem(bar, "旅行", TRIPS);
        return bar;
    }

    private void navItem(LinearLayout bar, String title, int target) {
        TextView item = new TextView(this);
        item.setText(title);
        item.setTextSize(15);
        item.setGravity(Gravity.CENTER);
        item.setTextColor(screen == target ? Color.rgb(23, 99, 68) : Color.rgb(108, 137, 117));
        GradientDrawable background = new GradientDrawable();
        background.setColor(screen == target ? Color.rgb(231, 245, 235) : Color.WHITE);
        background.setCornerRadius(dp(11));
        item.setBackground(background);
        item.setOnClickListener(v -> {
            if (target != TRIPS) deletingTrips = false;
            screen = target;
            render();
        });
        item.setFocusable(true);
        bar.addView(item, new LinearLayout.LayoutParams(0, dp(52), 1));
    }

    private void selectTrip(LedgerStore.Trip trip) {
        currentTripId = trip.id;
        recordFilter = "all";
        deletingTrips = false;
        uiPrefs.edit().putString("last_trip", trip.id).apply();
        screen = QUICK;
        render();
        startSelectedSync();
        updateSync();
    }

    private void startSelectedSync() {
        if (!started) return;
        LedgerStore.Trip trip = currentTripId == null ? null : store.getTrip(currentTripId);
        if (trip == null || trip.team == null || Build.VERSION.SDK_INT < 29) {
            if (teamPeers != null) teamPeers.stop();
            teamPeers = null;
            teamPeerTrip = null;
            peers.start();
            return;
        }
        peers.stop();
        if (!trip.id.equals(teamPeerTrip)) {
            if (teamPeers != null) teamPeers.stop();
            teamPeers = new PeerSync(this, store, this::onSyncChanged, true, trip);
            teamPeerTrip = trip.id;
        }
        teamPeers.start();
        if (!TeamBle.hasPermissions(this) && !uiPrefs.getBoolean("team_ble_prompted", false)) {
            getWindow().getDecorView().post(() -> {
                if (!started || teamPeers == null || uiPrefs.getBoolean("team_ble_prompted", false)) return;
                uiPrefs.edit().putBoolean("team_ble_prompted", true).apply();
                requestPermissions(TeamBle.permissions(), REQUEST_TEAM_BLUETOOTH);
            });
        }
    }

    private void renderTrips() {
        LinearLayout body = page();
        heading(body, "我的旅行");
        text(body, "选择旅行后，之后每次打开都会直接进入快速记账。", 14, Color.GRAY);
        List<LedgerStore.Trip> trips = store.trips();
        Button createTrip = button("新建旅行", this::newTrip);
        createTrip.setTextColor(Color.WHITE);
        createTrip.setBackground(chip(Color.rgb(33, 132, 91), Color.rgb(33, 132, 91), false));
        if (trips.isEmpty()) body.addView(createTrip, fullMargin());
        else {
            Button deleteTrip = button(deletingTrips ? "完成" : "删除旅行", () -> {
                deletingTrips = !deletingTrips;
                renderTrips();
            });
            if (!deletingTrips) {
                deleteTrip.setTextColor(Color.rgb(174, 73, 57));
                deleteTrip.setBackground(chip(Color.WHITE, Color.rgb(239, 183, 168), false));
            }
            row(body, createTrip, deleteTrip);
            if (deletingTrips) text(body, "点右侧 × 选择旅行，随后确认删除。", 13,
                    Color.rgb(183, 98, 81));
        }
        if (Build.VERSION.SDK_INT >= 29) buttonInto(body, "新建小队旅行", this::newTeam);
        section(body, "旅行列表");
        if (trips.isEmpty()) text(body, "还没有旅行。先新建一个旅行，再给同伴分享邀请码。", 15, Color.GRAY);
        for (LedgerStore.Trip trip : trips) {
            LinearLayout tripRow = new LinearLayout(this);
            tripRow.setOrientation(LinearLayout.HORIZONTAL);
            tripRow.setGravity(Gravity.CENTER_VERTICAL);
            String details = trip.team == null
                    ? "同伴：" + trip.nameOf("a".equals(trip.localPerson) ? "b" : "a")
                    : trip.people() + " 人小队";
            Button item = button(trip.name + (trip.id.equals(currentTripId) ? " · 当前" : "") +
                    "\n我：" + trip.nameOf(trip.localPerson) + " · " + details, () -> selectTrip(trip));
            tripRow.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
            if (deletingTrips) {
                TextView remove = new TextView(this);
                remove.setText("×");
                remove.setTextSize(24);
                remove.setGravity(Gravity.CENTER);
                remove.setMinWidth(dp(44));
                remove.setMinHeight(dp(44));
                remove.setTextColor(Color.rgb(186, 87, 69));
                remove.setContentDescription("删除旅行 " + trip.name);
                GradientDrawable removeBackground = new GradientDrawable();
                removeBackground.setColor(Color.rgb(255, 246, 242));
                removeBackground.setStroke(dp(1), Color.rgb(240, 197, 187));
                removeBackground.setCornerRadius(dp(13));
                remove.setBackground(removeBackground);
                remove.setOnClickListener(v -> confirmDeleteTrip(trip));
                LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(44), dp(44));
                removeParams.leftMargin = dp(8);
                tripRow.addView(remove, removeParams);
            }
            body.addView(tripRow, fullMargin());
        }
        section(body, "同行与同步");
        if (currentTripId == null) buttonInto(body, "凭邀请码加入", this::joinTrip);
        else {
            LedgerStore.Trip current = store.getTrip(currentTripId);
            if (store.teamReady(current)) row(body, button("凭邀请码加入", this::joinTrip), button("分享邀请码", () -> share(current)));
            else buttonInto(body, "凭邀请码加入", this::joinTrip);
            if (current.team == null) {
                row(body, button("热点地址直连", this::manualConnect), button("蓝牙连接", this::bluetoothSettings));
                row(body, button("重新连接并补传", () -> { peers.retryNow(); toast("正在连接同伴，记录已保存在本机"); }),
                        button("跨网连接设置（可选）", this::cloudSettings));
            } else text(body, String.join("、", current.team.names), 14, Color.GRAY);
        }
        syncView = text(body, "", 13, Color.GRAY);
        updateSync();
    }

    private void confirmDeleteTrip(LedgerStore.Trip trip) {
        new AlertDialog.Builder(this).setTitle("删除旅行？")
                .setMessage("将从这台手机删除“" + trip.name + "”和它的全部本地记录。此操作无法撤销；同伴设备和同步服务中的副本不会删除，重新加入可能恢复记录。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除旅行", (dialog, which) -> {
                    if (!store.deleteTrip(trip.id)) return;
                    SharedPreferences.Editor prefs = uiPrefs.edit()
                            .remove("payer:" + trip.id).remove("mode:" + trip.id)
                            .remove("share:" + trip.id).remove("currency:" + trip.id)
                            .remove("currency_history:" + trip.id).remove("currencies:" + trip.id)
                            .remove(exchangeHistoryKey(trip.id, "from"))
                            .remove(exchangeHistoryKey(trip.id, "to"));
                    if (trip.id.equals(currentTripId)) {
                        List<LedgerStore.Trip> remaining = store.trips();
                        currentTripId = remaining.isEmpty() ? null : remaining.get(0).id;
                        if (currentTripId == null) prefs.remove("last_trip");
                        else prefs.putString("last_trip", currentTripId);
                        screen = TRIPS;
                    }
                    prefs.apply();
                    deletingTrips = false;
                    recordFilter = "all";
                    render();
                    startSelectedSync();
                    updateSync();
                    toast("旅行及本机记录已删除");
                }).show();
    }

    private void renderQuick(LedgerStore.Trip trip) {
        LinearLayout body = page();
        text(body, "当前旅行", 13, Color.rgb(104, 146, 115));
        heading(body, trip.name);
        text(body, "快速记账", 19, Color.rgb(28, 89, 56));
        if (!store.teamReady(trip)) {
            waitingForTeam(body);
            return;
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(20));
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(Color.WHITE);
        cardBackground.setCornerRadius(dp(18));
        cardBackground.setStroke(dp(1), Color.rgb(222, 233, 224));
        card.setBackground(cardBackground);
        body.addView(card, fullMargin());

        LinearLayout amountRow = new LinearLayout(this);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout amountBox = new LinearLayout(this);
        amountBox.setOrientation(LinearLayout.VERTICAL);
        EditText amount = field(amountBox, "支付金额", "0.00", true);
        amount.setTextSize(30);
        LinearLayout currencyBox = new LinearLayout(this);
        currencyBox.setOrientation(LinearLayout.VERTICAL);
        text(currencyBox, "货币", 14, Color.DKGRAY);
        LinearLayout currencyRow = new LinearLayout(this);
        currencyRow.setOrientation(LinearLayout.HORIZONTAL);
        currencyRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText currency = new EditText(this);
        currency.setSingleLine(true);
        currency.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        currency.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(3)});
        currency.setTextSize(18);
        currency.setSelectAllOnFocus(true);
        currency.setText(quickCurrency(trip.id));
        currencyRow.addView(currency, new LinearLayout.LayoutParams(0, -2, 1));
        TextView historyButton = new TextView(this);
        historyButton.setText("⌄");
        historyButton.setTextSize(22);
        historyButton.setTextColor(Color.rgb(34, 108, 69));
        historyButton.setGravity(Gravity.CENTER);
        historyButton.setContentDescription("选择曾用货币");
        historyButton.setBackground(chip(Color.rgb(233, 244, 235), Color.rgb(233, 244, 235), true));
        currencyRow.addView(historyButton, new LinearLayout.LayoutParams(dp(34), dp(40)));
        currencyBox.addView(currencyRow, fullMargin());
        refreshCurrencyHistoryButton(trip.id, historyButton);
        currency.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                try {
                    rememberQuickCurrency(trip.id, value.toString());
                    refreshCurrencyHistoryButton(trip.id, historyButton);
                } catch (IllegalArgumentException ignored) { /* Wait for three letters. */ }
            }
        });
        historyButton.setOnClickListener(v -> showCurrencyHistory(trip.id, currency, historyButton));
        amountRow.addView(amountBox, new LinearLayout.LayoutParams(0, -2, 2));
        LinearLayout.LayoutParams currencyParams = new LinearLayout.LayoutParams(0, -2, 1);
        currencyParams.leftMargin = dp(10);
        amountRow.addView(currencyBox, currencyParams);
        card.addView(amountRow, fullMargin());

        LinearLayout routing = new LinearLayout(this);
        routing.setOrientation(LinearLayout.VERTICAL);
        routing.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable routingBackground = new GradientDrawable();
        routingBackground.setColor(Color.rgb(249, 251, 249));
        routingBackground.setCornerRadius(dp(15));
        routingBackground.setStroke(dp(1), Color.rgb(220, 232, 223));
        routing.setBackground(routingBackground);
        card.addView(routing, fullMargin());

        LinearLayout payerRow = new LinearLayout(this);
        payerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView payerLabel = new TextView(this);
        payerLabel.setText("谁付款");
        payerLabel.setTextSize(14);
        payerLabel.setTextColor(Color.rgb(78, 113, 96));
        payerRow.addView(payerLabel, new LinearLayout.LayoutParams(0, -2, 1));
        String savedPayer = uiPrefs.getString("payer:" + trip.id, trip.localPerson);
        RadioGroup payer = trip.team == null ? payerChoice(personChoiceLabel(trip, "a"),
                personChoiceLabel(trip, "b"), "b".equals(savedPayer)) : null;
        Spinner teamPayer = trip.team == null ? null : personSpinner(trip, savedPayer);
        payerRow.addView(payer == null ? teamPayer : payer, new LinearLayout.LayoutParams(0, dp(50), 3));
        routing.addView(payerRow, fullMargin());

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(228, 237, 229));
        routing.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setMinimumHeight(dp(56));
        TextView modeLabel = new TextView(this);
        modeLabel.setText("怎么分摊");
        modeLabel.setTextSize(14);
        modeLabel.setTextColor(Color.rgb(78, 113, 96));
        modeRow.addView(modeLabel, new LinearLayout.LayoutParams(0, -2, 1));
        String savedMode = uiPrefs.getString("mode:" + trip.id, "shared");
        String sharedLabel = trip.team == null ? "两人平摊" : "全员平摊";
        boolean[] personalMode = {"personal".equals(savedMode)};
        TextView modeButton = new TextView(this);
        modeButton.setText((personalMode[0] ? "仅付款人" : sharedLabel) + "  ⌄");
        modeButton.setTextSize(14);
        modeButton.setTextColor(Color.rgb(25, 93, 61));
        modeButton.setGravity(Gravity.CENTER);
        modeButton.setMinWidth(dp(122));
        modeButton.setMinHeight(dp(44));
        modeButton.setPadding(dp(10), 0, dp(10), 0);
        modeButton.setContentDescription("怎么分摊，" + (personalMode[0] ? "仅付款人" : sharedLabel));
        modeButton.setBackground(chip(Color.WHITE, Color.rgb(220, 232, 223), false));
        modeRow.addView(modeButton, new LinearLayout.LayoutParams(-2, dp(44)));
        routing.addView(modeRow, fullMargin());

        TextView adjust = text(routing, "改分摊比例  ▾", 13, Color.rgb(77, 119, 88));
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.VERTICAL);
        EditText percent = field(split, trip.personA + " 分摊百分比", "50", true);
        int savedShare = uiPrefs.getInt("share:" + trip.id, 50);
        percent.setText(String.valueOf(savedShare));
        routing.addView(split, fullMargin());
        boolean[] splitExpanded = {savedShare != 50};
        split.setVisibility(splitExpanded[0] ? View.VISIBLE : View.GONE);
        adjust.setOnClickListener(v -> {
            splitExpanded[0] = !splitExpanded[0];
            split.setVisibility(splitExpanded[0] ? View.VISIBLE : View.GONE);
        });
        if (personalMode[0] || trip.team != null) {
            adjust.setVisibility(View.GONE);
            split.setVisibility(View.GONE);
        }
        modeButton.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, modeButton);
            menu.getMenu().add(0, 0, 0, sharedLabel);
            menu.getMenu().add(0, 1, 1, "仅付款人");
            menu.getMenu().setGroupCheckable(0, true, true);
            menu.getMenu().findItem(personalMode[0] ? 1 : 0).setChecked(true);
            menu.setOnMenuItemClickListener(item -> {
                personalMode[0] = item.getItemId() == 1;
                String label = personalMode[0] ? "仅付款人" : sharedLabel;
                modeButton.setText(label + "  ⌄");
                modeButton.setContentDescription("怎么分摊，" + label);
                boolean showRatio = trip.team == null && !personalMode[0];
                adjust.setVisibility(showRatio ? View.VISIBLE : View.GONE);
                split.setVisibility(showRatio && splitExpanded[0] ? View.VISIBLE : View.GONE);
                return true;
            });
            menu.show();
        });
        EditText note = field(card, "备注（可选）", "例如：房费、饭费", false);

        Button save = button("保存这笔支出", () -> {
            try {
                String code = Money.code(currency.getText().toString());
                long minor = Money.parse(amount.getText().toString(), code);
                String who = payer == null ? trip.member(teamPayer.getSelectedItemPosition())
                        : ((RadioButton) payer.getChildAt(0)).isChecked() ? "a" : "b";
                boolean personal = personalMode[0];
                int share = trip.team != null || personal ? savedShare : Integer.parseInt(percent.getText().toString().trim());
                if (share < 0 || share > 100) throw new IllegalArgumentException("分摊比例需在 0 至 100 之间");
                JSONObject data = new JSONObject().put("payer", who).put("currency", code).put("amount", minor)
                        .put("mode", personal ? "personal" : "shared")
                        .put("note", note.getText().toString().trim());
                if (trip.team == null) data.put("shareA", personal ? ("a".equals(who) ? 10000 : 0) : share * 100);
                else data.put("shares", trip.team.shares(minor, who, personal));
                rememberQuickCurrency(trip.id, code);
                uiPrefs.edit().putString("payer:" + trip.id, who)
                        .putString("mode:" + trip.id, personal ? "personal" : "shared")
                        .putInt("share:" + trip.id, share).apply();
                add(trip, "expense", data);
                toast("已记账");
            } catch (Exception e) { error(e); }
        });
        save.setTextSize(17);
        save.setTextColor(Color.WHITE);
        save.setMinHeight(dp(56));
        GradientDrawable saveBackground = new GradientDrawable();
        saveBackground.setColor(Color.rgb(236, 107, 83));
        saveBackground.setCornerRadius(dp(12));
        save.setBackground(saveBackground);
        card.addView(save, fullMargin());
        text(body, "无网时先保存在本机；连接后自动同步。", 12, Color.GRAY);
    }

    private void waitingForTeam(LinearLayout body) {
        awaitingEnrollment = true;
        syncView = text(body, "等待首次登记", 15, Color.GRAY);
        updateSync();
    }

    private void renderReview(LedgerStore.Trip trip) {
        LedgerStore.Summary sum = store.summary(trip.id);
        LinearLayout body = page();
        heading(body, "旅行复盘");
        text(body, trip.name, 15, Color.GRAY);
        if (!store.teamReady(trip)) {
            waitingForTeam(body);
            return;
        }
        section(body, "总支出");
        text(body, sum.spent.isEmpty() ? "暂无支出" : formatBalances(sum.spent), 18, Color.BLACK);
        section(body, "现金余额");
        if (trip.team == null) {
            text(body, trip.personA + "：" + formatBalances(sum.cashA) + "\n" +
                    trip.personB + "：" + formatBalances(sum.cashB), 17, Color.BLACK);
            section(body, "两人结算");
            text(body, formatDebts(trip, sum.debtBToA), 17, Color.rgb(25, 82, 64));
        } else {
            for (int i = 0; i < trip.people(); i++) {
                String member = trip.member(i);
                text(body, personChoiceLabel(trip, member) + "：" +
                        formatBalances(sum.teamCash.getOrDefault(member, Collections.emptyMap())), 16, Color.BLACK);
            }
            section(body, "小队结算");
            text(body, formatTeamDebts(trip, sum.teamNet), 17, Color.rgb(25, 82, 64));
        }
        section(body, "现金与结算");
        row(body, button("原始资金", () -> moneyIn(trip, true)), button("补充现金", () -> moneyIn(trip, false)));
        row(body, button(trip.team == null ? "两人还款" : "成员还款", () -> transfer(trip)), button("换汇", () -> exchange(trip)));
        section(body, "旅行记录");
        TextView count = text(body, "", 13, Color.GRAY);
        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(0, dp(10), 0, dp(12));
        filterScroll.addView(filterRow);
        body.addView(filterScroll, fullMargin());
        LinearLayout records = new LinearLayout(this);
        records.setOrientation(LinearLayout.VERTICAL);
        body.addView(records, fullMargin());
        String[] filterKeys = {"all", "shared", "opening", "exchange"};
        String[] filterLabels = {"全部", "共同支出", "原始资金", "换汇记录"};
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < filterKeys.length; i++) {
            String key = filterKeys[i];
            TextView chip = new TextView(this);
            chip.setText(filterLabels[i]);
            chip.setTextSize(14);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(15), dp(9), dp(15), dp(9));
            styleRecordFilter(chip, key.equals(recordFilter));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.rightMargin = dp(8);
            filterRow.addView(chip, params);
            chips.add(chip);
            chip.setOnClickListener(v -> {
                recordFilter = key;
                for (int j = 0; j < chips.size(); j++)
                    styleRecordFilter(chips.get(j), filterKeys[j].equals(recordFilter));
                renderRecordItems(trip, sum, count, records);
            });
        }
        renderRecordItems(trip, sum, count, records);
        syncView = text(body, "", 13, Color.GRAY);
        updateSync();
    }

    private void styleRecordFilter(TextView chip, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? Color.rgb(232, 245, 235) : Color.WHITE);
        background.setStroke(dp(1), selected ? Color.rgb(168, 209, 178) : Color.rgb(220, 232, 223));
        background.setCornerRadius(dp(24));
        chip.setBackground(background);
        chip.setTextColor(selected ? Color.rgb(23, 99, 68) : Color.rgb(96, 121, 105));
        chip.setSelected(selected);
    }

    private boolean matchesRecordFilter(LedgerStore.Event event) {
        if ("all".equals(recordFilter)) return true;
        if ("shared".equals(recordFilter))
            return "expense".equals(event.kind) && !"personal".equals(event.data.optString("mode"));
        return recordFilter.equals(event.kind);
    }

    private void renderRecordItems(LedgerStore.Trip trip, LedgerStore.Summary sum,
                                   TextView count, LinearLayout records) {
        records.removeAllViews();
        int visibleCount = 0;
        for (LedgerStore.Event event : sum.visible) {
            if (!matchesRecordFilter(event)) continue;
            visibleCount++;
            TextView item = text(records, describe(trip, event), 15, Color.BLACK);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) item.getLayoutParams();
            params.bottomMargin = dp(6);
            item.setLayoutParams(params);
            item.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle("删除这笔记录？")
                        .setMessage(describe(trip, event))
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (dialog, which) -> {
                            try { add(trip, "delete", new JSONObject().put("target", event.id)); }
                            catch (Exception e) { error(e); }
                        }).show();
                return true;
            });
        }
        count.setText("all".equals(recordFilter) ? visibleCount + " 笔"
                : visibleCount + " / " + sum.visible.size() + " 笔");
        if (visibleCount == 0) {
            if ("all".equals(recordFilter)) text(records, "还没有记录。", 15, Color.GRAY);
            else {
                String label = "shared".equals(recordFilter) ? "共同支出" :
                        "opening".equals(recordFilter) ? "原始资金" : "换汇记录";
                text(records, "暂无" + label + "。可切换到“全部”查看完整流水。", 15, Color.GRAY);
            }
        }
    }

    private String formatBalances(Map<String, Long> balances) {
        if (balances.isEmpty()) return "尚未填写";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> entry : new TreeMap<>(balances).entrySet())
            parts.add(Money.format(entry.getValue(), entry.getKey()));
        return String.join("、", parts);
    }

    private String formatDebts(LedgerStore.Trip trip, Map<String, Long> debts) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> entry : new TreeMap<>(debts).entrySet()) {
            long value = entry.getValue();
            if (value > 0) parts.add(trip.personB + " 应还 " + trip.personA + " " + Money.format(value, entry.getKey()));
            if (value < 0) parts.add(trip.personA + " 应还 " + trip.personB + " " + Money.format(-value, entry.getKey()));
        }
        return parts.isEmpty() ? "目前已结清" : String.join("\n", parts);
    }

    private String formatTeamDebts(LedgerStore.Trip trip, Map<String, Map<String, Long>> nets) {
        Map<String, long[]> currencies = new TreeMap<>();
        for (int i = 0; i < trip.people(); i++) {
            Map<String, Long> member = nets.get(trip.member(i));
            if (member == null) continue;
            for (Map.Entry<String, Long> entry : member.entrySet())
                currencies.computeIfAbsent(entry.getKey(), key -> new long[trip.people()])[i] = entry.getValue();
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : currencies.entrySet()) {
            long[] remaining = entry.getValue();
            for (int debtor = 0; debtor < remaining.length; debtor++) {
                for (int creditor = 0; creditor < remaining.length && remaining[debtor] < 0; creditor++) {
                    if (remaining[creditor] <= 0) continue;
                    long amount = Math.min(-remaining[debtor], remaining[creditor]);
                    remaining[debtor] += amount;
                    remaining[creditor] -= amount;
                    parts.add(trip.nameOf(trip.member(debtor)) + " 应还 " + trip.nameOf(trip.member(creditor)) +
                            " " + Money.format(amount, entry.getKey()));
                }
            }
        }
        return parts.isEmpty() ? "目前已结清" : String.join("\n", parts);
    }

    private String describe(LedgerStore.Trip trip, LedgerStore.Event event) {
        JSONObject d = event.data;
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(event.at));
        String note = d.optString("note", "");
        String title;
        try {
            switch (event.kind) {
                case "opening": title = trip.nameOf(d.getString("person")) + " 原始资金 +" +
                        Money.format(d.getLong("amount"), d.getString("currency")); break;
                case "topup": title = trip.nameOf(d.getString("person")) + " 补充现金 +" +
                        Money.format(d.getLong("amount"), d.getString("currency")); break;
                case "expense": title = trip.nameOf(d.getString("payer")) + " 支付 " +
                        Money.format(d.getLong("amount"), d.getString("currency")) +
                        ("personal".equals(d.optString("mode")) ? " · 个人" : " · 共同"); break;
                case "transfer": title = trip.nameOf(d.getString("from")) + " 还款 " +
                        (trip.team == null ? "" : "给 " + trip.nameOf(d.getString("to")) + " ") +
                        Money.format(d.getLong("amount"), d.getString("currency")); break;
                case "exchange": title = trip.nameOf(d.getString("person")) + " 换汇 " +
                        Money.format(d.getLong("fromAmount"), d.getString("fromCurrency")) + " → " +
                        Money.format(d.getLong("toAmount"), d.getString("toCurrency")); break;
                default: title = "未知记录";
            }
        } catch (Exception e) { title = "记录有误"; }
        return time + "  " + title + (note.isEmpty() ? "" : "\n" + note);
    }

    private void newTrip() {
        LinearLayout form = form();
        text(form, "只需一人新建旅行并填写两人的名字；另一人凭邀请码加入，不要分别新建同名旅行。", 14, Color.GRAY);
        EditText name = field(form, "旅行名称", "例如：东南亚骑行", false);
        EditText a = field(form, "我的名字", "例如：小明", false);
        EditText b = field(form, "同伴名字", "例如：阿杰", false);
        showForm("新建旅行", form, () -> {
            if (name.getText().toString().trim().isEmpty() || a.getText().toString().trim().isEmpty() ||
                    b.getText().toString().trim().isEmpty()) throw new IllegalArgumentException("请填写旅行和两个人的名字");
            if (a.getText().toString().trim().equalsIgnoreCase(b.getText().toString().trim()))
                throw new IllegalArgumentException("两人的名字请不同，避免记账混淆");
            LedgerStore.Trip trip = store.createTrip(name.getText().toString(), a.getText().toString(), b.getText().toString());
            selectTrip(trip);
            peers.syncNow();
            cloud.syncNow();
        });
    }

    private void newTeam() {
        LinearLayout form = form();
        text(form, "填写 2 至 5 个不同的名字，一行一位。第一行是你，每位成员登记一台手机。", 14, Color.GRAY);
        EditText name = field(form, "旅行名称", "例如：荒原骑行", false);
        EditText names = field(form, "小队成员", "我的名字\n同伴名字", false);
        names.setSingleLine(false);
        names.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        names.setMinLines(3);
        names.setMaxLines(5);
        showForm("新建小队旅行", form, () -> {
            List<String> members = Arrays.asList(names.getText().toString().trim().split("\\r?\\n"));
            selectTrip(store.createTeam(name.getText().toString(), members));
        });
    }

    private void joinTrip() {
        LinearLayout form = form();
        text(form, "粘贴邀请码后确认这台设备由谁使用。和同伴各用一台设备时选同伴的名字；自己的另一台设备选自己的名字。", 14, Color.GRAY);
        EditText code = field(form, "邀请码", "粘贴同伴分享的完整邀请码", false);
        code.setSingleLine(false);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        code.setMinLines(3);
        code.setMaxLines(5);
        LinearLayout identityBox = new LinearLayout(this);
        identityBox.setOrientation(LinearLayout.VERTICAL);
        form.addView(identityBox);
        Spinner identity = choice(identityBox, "这台设备由谁使用", new String[]{"第一位", "第二位"});
        identityBox.setVisibility(View.GONE);
        TextView preview = text(form, "", 14, Color.rgb(44, 118, 80));
        LedgerStore.Trip[] candidate = new LedgerStore.Trip[1];
        identity.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (candidate[0] != null && position < candidate[0].people())
                    preview.setText(joinPerspective(candidate[0], candidate[0].member(position)));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        code.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                try {
                    LedgerStore.Trip trip = store.previewInvite(value.toString());
                    if (trip.team != null && Build.VERSION.SDK_INT < 29) {
                        candidate[0] = null;
                        identityBox.setVisibility(View.GONE);
                        preview.setText("小队旅行需要安卓 10 或以上。");
                        return;
                    }
                    LedgerStore.Trip existing = store.getTrip(trip.id);
                    String selectedRole = existing == null ? trip.localPerson : existing.localPerson;
                    String[] labels = new String[trip.people()];
                    for (int i = 0; i < labels.length; i++) {
                        String role = trip.member(i);
                        String label = trip.team != null ? (i == 0 ? " · 建队者" : "") :
                                (role.equals(trip.localPerson) ? " · 同伴（默认）" : " · 分享者的另一台设备");
                        labels[i] = trip.nameOf(role) + label;
                    }
                    candidate[0] = existing == null ? trip : existing;
                    identity.setAdapter(new ArrayAdapter<>(MainActivity.this,
                            android.R.layout.simple_spinner_dropdown_item, labels));
                    identity.setSelection(trip.indexOf(selectedRole));
                    identity.setEnabled(existing == null);
                    identityBox.setVisibility(View.VISIBLE);
                    preview.setText(joinPerspective(candidate[0], selectedRole) +
                            (trip.syncUrl.isEmpty() ? "" : existing == null
                                    ? "\n连接信息已包含，无需填写服务地址。"
                                    : "\n将更新连接，保留当前身份和账目。"));
                } catch (Exception ignored) {
                    candidate[0] = null;
                    identityBox.setVisibility(View.GONE);
                    preview.setText("");
                }
            }
        });
        showForm("加入旅行", form, () -> {
            if (candidate[0] == null || identity.getSelectedItemPosition() < 0)
                throw new IllegalArgumentException("请填写有效邀请码并选择使用者");
            LedgerStore.Trip trip = store.joinTrip(code.getText().toString(),
                    candidate[0].member(identity.getSelectedItemPosition()));
            selectTrip(trip);
            syncAfterSave(trip);
        });
    }

    private String joinPerspective(LedgerStore.Trip trip, String localPerson) {
        if (trip.team != null) return "你将以“" + trip.nameOf(localPerson) + "”加入。请和建队设备打开这趟旅行，自动完成首次登记。";
        return "你将以“" + trip.nameOf(localPerson) + "”加入；另一位是“" +
                trip.nameOf("a".equals(localPerson) ? "b" : "a") + "”。";
    }

    private void moneyIn(LedgerStore.Trip trip, boolean opening) {
        LinearLayout form = form();
        Spinner person = personChoice(form, trip, "现金持有人");
        EditText amount = field(form, "金额", "例如：1000", true);
        EditText currency = field(form, "货币代码", "CNY / EUR / JPY", false); currency.setText("CNY");
        showForm(opening ? "填写原始资金" : "补充现金", form, () -> {
            String code = Money.code(currency.getText().toString());
            JSONObject data = new JSONObject().put("person", trip.member(person.getSelectedItemPosition()))
                    .put("currency", code).put("amount", Money.parse(amount.getText().toString(), code));
            add(trip, opening ? "opening" : "topup", data);
        });
    }

    private void transfer(LedgerStore.Trip trip) {
        LinearLayout form = form();
        Spinner from = personChoice(form, trip, "付款人");
        Spinner to = trip.team == null ? null : personChoice(form, trip, "收款人");
        if (to != null) to.setSelection((trip.indexOf(trip.localPerson) + 1) % trip.people());
        EditText amount = field(form, "还款金额", "例如：50", true);
        EditText currency = field(form, "货币代码", "CNY / EUR / JPY", false); currency.setText("CNY");
        showForm(trip.team == null ? "两人之间还款" : "记录成员还款", form, () -> {
            String code = Money.code(currency.getText().toString());
            String sender = trip.member(from.getSelectedItemPosition());
            JSONObject data = new JSONObject().put("from", sender)
                    .put("currency", code).put("amount", Money.parse(amount.getText().toString(), code));
            if (to != null) {
                String recipient = trip.member(to.getSelectedItemPosition());
                if (sender.equals(recipient)) throw new IllegalArgumentException("请选择不同的付款人和收款人");
                data.put("to", recipient);
            }
            add(trip, "transfer", data);
        });
    }

    private void exchange(LedgerStore.Trip trip) {
        LinearLayout form = form();
        Spinner person = personChoice(form, trip, "换汇人");
        String[] defaults = exchangeDefaults(trip.id);
        String[] draftCurrencies = defaults.clone();
        EditText fromAmount = field(form, "付出金额", "例如：100", true);
        EditText fromCurrency = exchangeCurrencyField(form, trip.id, "from", "付出货币",
                defaults[0], draftCurrencies, 0);
        EditText toAmount = field(form, "得到金额", "例如：13", true);
        EditText toCurrency = exchangeCurrencyField(form, trip.id, "to", "得到货币",
                defaults[1], draftCurrencies, 1);
        showForm("记录换汇", form, () -> {
            String from = Money.code(fromCurrency.getText().toString());
            String to = Money.code(toCurrency.getText().toString());
            if (from.equals(to)) throw new IllegalArgumentException("请选择两种不同货币");
            JSONObject data = new JSONObject().put("person", trip.member(person.getSelectedItemPosition()))
                    .put("fromCurrency", from).put("fromAmount", Money.parse(fromAmount.getText().toString(), from))
                    .put("toCurrency", to).put("toAmount", Money.parse(toAmount.getText().toString(), to));
            store.add(trip.id, "exchange", data);
            rememberExchangeCurrency(trip.id, "from", draftCurrencies[0], from);
            rememberExchangeCurrency(trip.id, "to", draftCurrencies[1], to);
            render();
            syncAfterSave(trip);
        });
    }

    private String[] exchangeDefaults(String tripId) {
        for (LedgerStore.Event event : store.summary(tripId).visible) {
            if (!"exchange".equals(event.kind)) continue;
            try {
                String from = Money.code(event.data.getString("fromCurrency"));
                String to = Money.code(event.data.getString("toCurrency"));
                if (!from.equals(to)) return new String[]{from, to};
            } catch (Exception ignored) { }
        }
        return new String[]{"CNY", "USD"};
    }

    private void add(LedgerStore.Trip trip, String kind, JSONObject data) {
        store.add(trip.id, kind, data);
        render();
        syncAfterSave(trip);
    }

    private void syncAfterSave(LedgerStore.Trip trip) {
        if (trip.team == null) {
            peers.syncNow();
            cloud.syncNow();
        } else if (teamPeers != null && trip.id.equals(teamPeerTrip)) teamPeers.syncNow();
    }

    private void share(LedgerStore.Trip trip) {
        try {
            String code = store.inviteCode(trip, cloud.effectiveEndpoint(trip));
            TextView content = new TextView(this);
            String help = trip.team != null
                    ? "同伴粘贴邀请码后选自己的名字。首次登记时，请和建队设备打开这趟旅行。邀请码只分享给队友。"
                    : "本机是“" + trip.nameOf(trip.localPerson) + "”；对方默认以“" +
                    trip.nameOf("a".equals(trip.localPerson) ? "b" : "a") +
                    "”加入。自己的另一台设备可在加入时选自己的名字。邀请码含旅行密钥，请只发给同伴。";
            content.setText(help + "\n\n" + code);
            content.setTextIsSelectable(true);
            content.setPadding(dp(20), dp(18), dp(20), dp(18));
            ScrollView invite = new ScrollView(this);
            invite.addView(content);
            new AlertDialog.Builder(this).setTitle("旅行邀请码").setView(invite)
                    .setNegativeButton("关闭", null)
                    .setNeutralButton("复制", (d, w) -> {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("旅行邀请码", code));
                        toast("邀请码已复制");
                    })
                    .setPositiveButton("分享", (d, w) -> {
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("text/plain");
                        intent.putExtra(Intent.EXTRA_TEXT, code);
                        startActivity(Intent.createChooser(intent, "分享旅行邀请码"));
                    }).show();
        } catch (Exception e) { error(e); }
    }

    private void manualConnect() {
        LinearLayout form = form();
        text(form, "同伴手机的局域网地址可在“旅行”页面底部看到。", 14, Color.DKGRAY);
        EditText address = field(form, "同伴地址", "例如：192.168.43.1:12345", false);
        showForm("地址直连", form, () -> { peers.connectManual(address.getText().toString()); toast("正在尝试连接"); });
    }

    private void bluetoothSettings() {
        BluetoothSync bluetooth = peers.bluetooth;
        LinearLayout form = form();
        text(form, "两台手机先加入同一旅行，再开启蓝牙备用。首次选择同伴并确认系统配对；以后热点优先，热点不可用时自动改用蓝牙。两端保持应用打开即可，不需要电脑。", 14, Color.DKGRAY);
        bluetoothStatus = text(form, bluetooth.status(), 14, Color.GRAY);
        bluetoothToggle = button(bluetooth.enabled() ? "关闭蓝牙备用通道" : "开启蓝牙备用通道", () -> {
            if (bluetooth.enabled()) { bluetooth.setEnabled(false); refreshBluetoothControls(); }
            else withBluetooth(BluetoothSync.usePermissions(Build.VERSION.SDK_INT), () -> {
                bluetooth.setEnabled(true); peers.retryNow(); refreshBluetoothControls();
            });
        });
        form.addView(bluetoothToggle, fullMargin());
        buttonInto(form, "选择已配对的同伴", () -> withBluetooth(BluetoothSync.usePermissions(Build.VERSION.SDK_INT), () -> {
            bluetooth.setEnabled(true); bluetooth.refresh(); showBluetoothDevices();
        }));
        buttonInto(form, "查找新手机", () -> withBluetooth(BluetoothSync.scanPermissions(Build.VERSION.SDK_INT), () -> {
            bluetooth.setEnabled(true); bluetooth.discover(); showBluetoothDevices();
        }));
        buttonInto(form, "让同伴发现我（2 分钟）", () -> withBluetooth(BluetoothSync.discoverablePermissions(Build.VERSION.SDK_INT), () -> {
            bluetooth.setEnabled(true);
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
            startActivityForResult(intent, REQUEST_DISCOVERABLE);
        }));
        buttonInto(form, "忘记应用中的同伴连接", () -> {
            bluetooth.forget(); refreshBluetoothControls(); toast("已清除连接选择，账目和系统配对保留");
        });
        text(form, "配对时请选择同行者的手机，核对两边系统显示的配对信息。拒绝授权或关闭蓝牙后，仍可离线记账和使用热点同步。", 12, Color.GRAY);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("蓝牙备用通道").setView(scroll).setNegativeButton("关闭", null).create();
        dialog.setOnDismissListener(ignored -> { bluetoothStatus = null; bluetoothToggle = null; });
        dialog.show();
    }
    private void refreshBluetoothControls() {
        if (peers == null) return;
        if (bluetoothStatus != null) bluetoothStatus.setText(peers.bluetooth.status() + "\n" + peers.bluetooth.selectedName());
        if (bluetoothToggle != null) bluetoothToggle.setText(peers.bluetooth.enabled() ? "关闭蓝牙备用通道" : "开启蓝牙备用通道");
        if (bluetoothDevicesDialog != null && bluetoothDevicesDialog.isShowing() && bluetoothDeviceLabels != null) {
            bluetoothDeviceItems.clear(); bluetoothDeviceItems.addAll(peers.bluetooth.devices());
            bluetoothDeviceLabels.clear();
            for (BluetoothDevice device : bluetoothDeviceItems) bluetoothDeviceLabels.add(peers.bluetooth.deviceLabel(device));
            bluetoothDeviceLabels.notifyDataSetChanged();
        }
    }
    private void showBluetoothDevices() {
        if (bluetoothDevicesDialog != null && bluetoothDevicesDialog.isShowing()) return;
        bluetoothDeviceLabels = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        bluetoothDevicesDialog = new AlertDialog.Builder(this).setTitle("选择同行者的手机")
                .setAdapter(bluetoothDeviceLabels, (dialog, which) -> {
                    if (which < 0 || which >= bluetoothDeviceItems.size()) return;
                    try { peers.bluetooth.select(bluetoothDeviceItems.get(which)); peers.retryNow(); toast("配对完成后将自动连接"); }
                    catch (Exception error) { error(error); }
                }).setNegativeButton("关闭", null).setNeutralButton("重新查找", null).create();
        bluetoothDevicesDialog.setOnShowListener(ignored -> bluetoothDevicesDialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> withBluetooth(BluetoothSync.scanPermissions(Build.VERSION.SDK_INT), () -> {
                    peers.bluetooth.discover(); refreshBluetoothControls();
                })));
        bluetoothDevicesDialog.setOnDismissListener(ignored -> { bluetoothDevicesDialog = null; bluetoothDeviceLabels = null; });
        bluetoothDevicesDialog.show(); refreshBluetoothControls();
        if (bluetoothDeviceItems.isEmpty()) toast("可先在另一台手机允许被发现，或在系统蓝牙设置中配对");
    }
    private void withBluetooth(String[] permissions, Runnable action) {
        if (!peers.bluetooth.supported()) { toast("此手机没有可用蓝牙，可继续用热点同步"); return; }
        if (!peers.bluetooth.has(permissions)) {
            pendingBluetoothPermissions = permissions;
            pendingBluetoothAction = () -> withBluetooth(permissions, action);
            requestPermissions(permissions, REQUEST_BLUETOOTH_PERMISSIONS); return;
        }
        if (!peers.bluetooth.radioEnabled()) {
            pendingBluetoothAction = action;
            try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_BLUETOOTH_ENABLE); }
            catch (Exception error) { pendingBluetoothAction = null; toast("请在系统设置中开启蓝牙后重试"); }
            return;
        }
        try { action.run(); } catch (Exception error) { error(error); }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == REQUEST_TEAM_BLUETOOTH) {
            updateSync();
            return;
        }
        if (code != REQUEST_BLUETOOTH_PERMISSIONS) return;
        Runnable action = pendingBluetoothAction; pendingBluetoothAction = null;
        boolean allowed = pendingBluetoothPermissions != null && peers.bluetooth.has(pendingBluetoothPermissions);
        pendingBluetoothPermissions = null;
        if (allowed && action != null) action.run();
        else toast("蓝牙未授权，仍可离线记账并使用热点同步");
        peers.bluetooth.refresh(); refreshBluetoothControls();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_BLUETOOTH_ENABLE) {
            Runnable action = pendingBluetoothAction; pendingBluetoothAction = null;
            if (peers.bluetooth.radioEnabled() && action != null) {
                try { action.run(); } catch (Exception error) { error(error); }
            } else toast("蓝牙未开启，账目仍保存在本机");
        }
        if (request == REQUEST_BLUETOOTH_ENABLE || request == REQUEST_DISCOVERABLE) {
            peers.bluetooth.refresh(); peers.retryNow(); refreshBluetoothControls();
        }
    }

    private void cloudSettings() {
        LinearLayout form = form();
        LedgerStore.Trip trip = store.getTrip(currentTripId);
        text(form, "新版邀请码会自动带入连接信息。这里可修改当前旅行的同步地址；其他旅行不受影响。", 14, Color.DKGRAY);
        EditText endpoint = field(form, "云同步服务地址", "https://example.com", false);
        endpoint.setText(cloud.effectiveEndpoint(trip));
        showForm("当前旅行连接", form, () -> {
            store.setTripSyncUrl(trip.id, endpoint.getText().toString()); cloud.syncNow(); updateSync();
        });
    }

    private interface SaveAction { void run() throws Exception; }
    private void showForm(String title, View view, SaveAction save) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(view)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try { save.run(); dialog.dismiss(); }
            catch (Exception e) { error(e); }
        }));
        dialog.show();
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        return form;
    }

    private EditText field(LinearLayout form, String label, String hint, boolean decimal) {
        text(form, label, 14, Color.DKGRAY);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        if (decimal) field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(field, fullMargin());
        return field;
    }

    private Spinner personChoice(LinearLayout form, LedgerStore.Trip trip, String title) {
        text(form, title, 14, Color.DKGRAY);
        Spinner spinner = personSpinner(trip, trip.localPerson);
        form.addView(spinner, fullMargin());
        return spinner;
    }

    private Spinner personSpinner(LedgerStore.Trip trip, String selected) {
        Spinner spinner = new Spinner(this);
        List<String> people = new ArrayList<>();
        for (int i = 0; i < trip.people(); i++) people.add(personChoiceLabel(trip, trip.member(i)));
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, people));
        spinner.setSelection(Math.max(0, trip.indexOf(selected)));
        return spinner;
    }

    private String personChoiceLabel(LedgerStore.Trip trip, String person) {
        return trip.nameOf(person) + (person.equals(trip.localPerson) ? "（我）" : "");
    }

    private RadioGroup payerChoice(String first, String second, boolean chooseSecond) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        group.setPadding(dp(3), dp(3), dp(3), dp(3));
        group.setBackground(chip(Color.rgb(234, 243, 236), Color.rgb(219, 233, 222), false));
        String[] options = {first, second};
        for (int i = 0; i < options.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setText(options[i]);
            option.setTextSize(14);
            option.setMinHeight(dp(44));
            option.setPadding(dp(4), 0, dp(4), 0);
            option.setSingleLine(true);
            option.setEllipsize(TextUtils.TruncateAt.END);
            option.setGravity(Gravity.CENTER);
            option.setButtonDrawable(null);
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_checked}, chip(Color.WHITE, Color.WHITE, false));
            states.addState(new int[]{}, chip(Color.TRANSPARENT, Color.TRANSPARENT, false));
            option.setBackground(states);
            option.setTextColor(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{Color.rgb(20, 91, 58), Color.rgb(85, 115, 99)}));
            option.setId(View.generateViewId());
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(0, -1, 1);
            if (i == 0) params.rightMargin = dp(2);
            group.addView(option, params);
        }
        ((RadioButton) group.getChildAt(chooseSecond ? 1 : 0)).setChecked(true);
        return group;
    }

    private String quickCurrency(String tripId) {
        try { return Money.code(uiPrefs.getString("currency:" + tripId, "CNY")); }
        catch (Exception ignored) { return "CNY"; }
    }

    private List<String> quickCurrencyHistory(String tripId, String current) {
        String saved = uiPrefs.getString("currency_history:" + tripId, null);
        boolean legacy = saved == null;
        if (legacy) saved = uiPrefs.getString("currencies:" + tripId, "");
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String raw : saved.split(",")) {
            try {
                String code = Money.code(raw);
                if (!code.equals(current)) codes.add(code);
            } catch (Exception ignored) { }
        }
        if (legacy && !"CNY".equals(current)) codes.add("CNY");
        List<String> history = new ArrayList<>(codes);
        return history.subList(0, Math.min(5, history.size()));
    }

    private void rememberQuickCurrency(String tripId, String raw) {
        String currency = Money.code(raw);
        String previous = quickCurrency(tripId);
        if (currency.equals(previous)) return;
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        codes.add(previous);
        codes.addAll(quickCurrencyHistory(tripId, previous));
        codes.remove(currency);
        List<String> history = new ArrayList<>(codes);
        String saved = String.join(",", history.subList(0, Math.min(5, history.size())));
        uiPrefs.edit().putString("currency:" + tripId, currency)
                .putString("currency_history:" + tripId, saved)
                .remove("currencies:" + tripId).apply();
    }

    private void removeQuickCurrencyHistory(String tripId, String raw) {
        String code = Money.code(raw);
        List<String> history = new ArrayList<>(quickCurrencyHistory(tripId, quickCurrency(tripId)));
        if (!history.remove(code)) return;
        uiPrefs.edit().putString("currency_history:" + tripId, String.join(",", history))
                .remove("currencies:" + tripId).apply();
    }

    private String exchangeHistoryKey(String tripId, String field) {
        return "exchange_" + field + "_history:" + tripId;
    }

    private List<String> exchangeCurrencyHistory(String tripId, String field, String current) {
        String saved = uiPrefs.getString(exchangeHistoryKey(tripId, field), "");
        String selected;
        try { selected = Money.code(current); }
        catch (Exception ignored) { selected = ""; }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String raw : saved.split(",")) {
            try {
                String code = Money.code(raw);
                if (!code.equals(selected)) codes.add(code);
            } catch (Exception ignored) { }
        }
        List<String> history = new ArrayList<>(codes);
        return history.subList(0, Math.min(5, history.size()));
    }

    private void rememberExchangeCurrency(String tripId, String field, String previous, String current) {
        if (previous.equals(current)) return;
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        codes.add(previous);
        codes.addAll(exchangeCurrencyHistory(tripId, field, previous));
        codes.remove(current);
        List<String> history = new ArrayList<>(codes);
        uiPrefs.edit().putString(exchangeHistoryKey(tripId, field),
                String.join(",", history.subList(0, Math.min(5, history.size())))).apply();
    }

    private void removeExchangeCurrencyHistory(String tripId, String field, String raw, String current) {
        String code = Money.code(raw);
        List<String> history = new ArrayList<>(exchangeCurrencyHistory(tripId, field, current));
        if (!history.remove(code)) return;
        uiPrefs.edit().putString(exchangeHistoryKey(tripId, field), String.join(",", history)).apply();
    }

    private EditText exchangeCurrencyField(LinearLayout form, String tripId, String field,
                                           String label, String initial, String[] draftCurrencies,
                                           int index) {
        text(form, label, 14, Color.DKGRAY);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText currency = new EditText(this);
        currency.setSingleLine(true);
        currency.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        currency.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(3)});
        currency.setSelectAllOnFocus(true);
        currency.setText(initial);
        row.addView(currency, new LinearLayout.LayoutParams(0, -2, 1));
        TextView historyButton = new TextView(this);
        historyButton.setText("⌄");
        historyButton.setTextSize(22);
        historyButton.setTextColor(Color.rgb(34, 108, 69));
        historyButton.setGravity(Gravity.CENTER);
        historyButton.setContentDescription("选择曾用" + label);
        historyButton.setBackground(chip(Color.rgb(233, 244, 235), Color.rgb(233, 244, 235), true));
        row.addView(historyButton, new LinearLayout.LayoutParams(dp(34), dp(40)));
        form.addView(row, fullMargin());
        refreshExchangeHistoryButton(tripId, field, currency, historyButton);
        currency.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                try {
                    String selected = Money.code(value.toString());
                    rememberExchangeCurrency(tripId, field, draftCurrencies[index], selected);
                    draftCurrencies[index] = selected;
                } catch (IllegalArgumentException ignored) { /* Wait for three letters. */ }
                refreshExchangeHistoryButton(tripId, field, currency, historyButton);
            }
        });
        historyButton.setOnClickListener(v -> showCurrencyHistory(tripId, currency, historyButton, field));
        return currency;
    }

    private void showCurrencyHistory(String tripId, EditText currency, TextView anchor) {
        showCurrencyHistory(tripId, currency, anchor, null);
    }

    private void showCurrencyHistory(String tripId, EditText currency, TextView anchor,
                                     String exchangeField) {
        List<String> history = exchangeField == null
                ? quickCurrencyHistory(tripId, quickCurrency(tripId))
                : exchangeCurrencyHistory(tripId, exchangeField, currency.getText().toString());
        if (history.isEmpty()) return;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(4), dp(4), dp(4));
        PopupWindow popup = new PopupWindow(list, dp(150), -2, true);
        popup.setBackgroundDrawable(chip(Color.WHITE, Color.rgb(218, 232, 222), false));
        popup.setElevation(dp(10));
        popup.setOutsideTouchable(true);
        for (String code : history) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView choose = new TextView(this);
            choose.setText(code);
            choose.setTextSize(16);
            choose.setTextColor(Color.rgb(25, 78, 55));
            choose.setGravity(Gravity.CENTER_VERTICAL);
            choose.setPadding(dp(10), 0, 0, 0);
            choose.setOnClickListener(v -> {
                currency.setText(code);
                currency.setSelection(code.length());
                popup.dismiss();
            });
            row.addView(choose, new LinearLayout.LayoutParams(0, dp(44), 1));
            TextView remove = new TextView(this);
            remove.setText("×");
            remove.setTextSize(20);
            remove.setTextColor(Color.rgb(183, 98, 81));
            remove.setGravity(Gravity.CENTER);
            remove.setContentDescription("移除曾用货币 " + code);
            remove.setOnClickListener(v -> {
                if (exchangeField == null) removeQuickCurrencyHistory(tripId, code);
                else removeExchangeCurrencyHistory(tripId, exchangeField, code,
                        currency.getText().toString());
                list.removeView(row);
                if (exchangeField == null) refreshCurrencyHistoryButton(tripId, anchor);
                else refreshExchangeHistoryButton(tripId, exchangeField, currency, anchor);
                if (list.getChildCount() == 0) popup.dismiss();
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(40), dp(44)));
            list.addView(row, new LinearLayout.LayoutParams(-1, dp(44)));
        }
        popup.showAsDropDown(anchor, anchor.getWidth() - dp(150), dp(6));
    }

    private void refreshCurrencyHistoryButton(String tripId, TextView button) {
        button.setVisibility(quickCurrencyHistory(tripId, quickCurrency(tripId)).isEmpty()
                ? View.GONE : View.VISIBLE);
    }

    private void refreshExchangeHistoryButton(String tripId, String field, EditText currency,
                                               TextView button) {
        button.setVisibility(exchangeCurrencyHistory(tripId, field, currency.getText().toString()).isEmpty()
                ? View.GONE : View.VISIBLE);
    }

    private GradientDrawable chip(int fill, int stroke, boolean compact) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(compact ? 24 : 12));
        background.setStroke(dp(1), stroke);
        return background;
    }

    private Spinner choice(LinearLayout form, String title, String[] options) {
        text(form, title, 14, Color.DKGRAY);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options);
        spinner.setAdapter(adapter);
        form.addView(spinner, fullMargin());
        return spinner;
    }

    private void heading(LinearLayout body, String value) { text(body, value, 26, Color.rgb(21, 75, 60)); }
    private void section(LinearLayout body, String value) { text(body, value, 19, Color.rgb(21, 75, 60)); }

    private TextView text(LinearLayout body, String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setPadding(0, dp(8), 0, dp(8));
        body.addView(view, fullMargin());
        return view;
    }

    private Button button(String value, Runnable onClick) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    private void buttonInto(LinearLayout body, String value, Runnable onClick) {
        body.addView(button(value, onClick), fullMargin());
    }

    private void row(LinearLayout body, Button left, Button right) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.leftMargin = dp(6);
        line.addView(left, first);
        line.addView(right, second);
        body.addView(line, fullMargin());
    }

    private LinearLayout.LayoutParams fullMargin() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(4);
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private void error(Exception e) { toast(e.getMessage() == null ? "操作失败" : e.getMessage()); }
}
