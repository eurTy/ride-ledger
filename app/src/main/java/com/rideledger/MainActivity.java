package com.rideledger;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int QUICK = 0, REVIEW = 1, TRIPS = 2;
    private LedgerStore store;
    private LedgerActions ledgerActions;
    private final ExecutorService reviewReader = Executors.newSingleThreadExecutor();
    private Future<?> reviewLoad;
    private long reviewGeneration;
    private boolean reviewDirty;
    private FrameLayout reviewContent;
    private View recordsOverlay;
    private String exportTripId;
    private List<LedgerReview.Row> pendingExport;
    private boolean deletingRecords;
    private ReviewPanel reviewPanel;
    private TeamTransportAdapter teamTransport;
    private Map<String, String> renderedSeatNames = Collections.emptyMap();
    private boolean started, awaitingEnrollment;
    private SharedPreferences uiPrefs;
    private CurrencyHistory currencyHistory;
    private LedgerForms forms;
    private QuickEntryPanel quickEntry;
    private String currentTripId;
    private String recordFilter = "all";
    private boolean deletingTrips;
    private int screen = TRIPS;
    private TextView syncView;
    private static final int REQUEST_TEAM = 74;
    private static final int REQUEST_STARTUP_PERMISSIONS = 75;
    private static final int REQUEST_BACKGROUND_BLUETOOTH = 76;
    private static final int REQUEST_EXPORT = 77;
    private static final String BACKGROUND_BLUETOOTH_PROMPTED = "background_bluetooth_prompted";
    private boolean startupPermissionsInFlight;
    static final String STARTUP_PERMISSIONS_REQUESTED = "startup_permissions_requested";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(248, 249, 247));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        store = LedgerStore.application(this);
        ledgerActions = new LedgerActions(store);
        teamTransport = new TeamTransportAdapter(this, store, this::onSyncChanged);
        uiPrefs = getSharedPreferences("ui", MODE_PRIVATE);
        currencyHistory = new CurrencyHistory(uiPrefs, store);
        forms = new LedgerForms(this, ledgerActions, currencyHistory, reviewReader,
                trip -> afterSave(trip, false), this::error);
        startupPermissionsInFlight = state != null && state.getBoolean("startupPermissionsInFlight");
        exportTripId = state == null ? null : state.getString("exportTripId");
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
        SyncBackground.foregroundEntered(this);
        if (currentTripId != null && screen != QUICK && recordsOverlay == null && exportTripId == null) {
            deletingTrips = false;
            screen = QUICK;
            render();
        }
        started = true;
        startSelectedSync();
        updateSync();
        if (reviewContent != null && currentTripId != null) refreshReview(currentTripId);
    }
    @Override public void onStop() {
        started = false;
        cancelReviewLoad();
        forms.dismissCurrencyMenu();
        teamTransport.stop();
        SyncBackground.foregroundLeft();
        super.onStop();
    }

    @Override protected void onDestroy() {
        cancelReviewLoad();
        forms.close();
        reviewReader.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        teamTransport.permissionsChanged();
        if (startupPermissionsInFlight) return;
        if (uiPrefs.getBoolean(STARTUP_PERMISSIONS_REQUESTED, false)) {
            requestBackgroundBluetoothOnce();
            return;
        }

        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            for (String permission : TeamBle.permissions()) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
            }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.CAMERA);

        uiPrefs.edit().putBoolean(STARTUP_PERMISSIONS_REQUESTED, true).apply();
        if (missing.isEmpty()) {
            requestBackgroundBluetoothOnce();
        } else {
            startupPermissionsInFlight = true;
            requestPermissions(missing.toArray(new String[0]), REQUEST_STARTUP_PERMISSIONS);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_STARTUP_PERMISSIONS) {
            startupPermissionsInFlight = false;
            teamTransport.permissionsChanged();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("startupPermissionsInFlight", startupPermissionsInFlight);
        state.putString("exportTripId", exportTripId);
    }

    private void requestBackgroundBluetoothOnce() {
        if (Build.VERSION.SDK_INT < 29 || Build.VERSION.SDK_INT > 30 ||
                uiPrefs.getBoolean(BACKGROUND_BLUETOOTH_PROMPTED, false)) return;
        uiPrefs.edit().putBoolean(BACKGROUND_BLUETOOTH_PROMPTED, true).apply();
        if (!TeamBle.hasPermissions(this) || TeamBle.canScan(this, true)) return;
        if (Build.VERSION.SDK_INT == 29) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_BACKGROUND_BLUETOOTH);
        } else {
            new AlertDialog.Builder(this).setTitle("后台蓝牙同步")
                    .setMessage("如需在后台自动发现附近队友，请在系统权限中将位置设为「始终允许」。")
                    .setNegativeButton("跳过", null)
                    .setPositiveButton("去授权", (dialog, which) -> openBluetoothSettings())
                    .show();
        }
    }

    @Override public void onBackPressed() {
        if (recordsOverlay != null) {
            if (!reviewPanel.cancelRecordSelection()) closeRecords();
            return;
        }
        if (currentTripId != null && screen != QUICK) {
            deletingTrips = false;
            screen = QUICK;
            render();
            teamTransport.recordsChanged();
        }
        else super.onBackPressed();
    }

    private void onSyncChanged(int count) {
        LedgerStore.Trip trip = currentTripId == null ? null : store.getTrip(currentTripId);
        boolean rosterChanged = trip != null && !trip.seatNames.equals(renderedSeatNames);
        if ((awaitingEnrollment && trip != null && store.teamReady(trip)) || (screen == TRIPS && rosterChanged)) {
            render();
        } else {
            if (quickEntry != null && rosterChanged) {
                quickEntry.updateRoster(trip);
                renderedSeatNames = trip.seatNames;
            }
            if (reviewContent != null && trip != null && (count > 0 || rosterChanged)) refreshReview(trip.id);
            if (reviewPanel != null) reviewPanel.confirmThrough(teamTransport.syncState().confirmedThrough);
            updateSync();
        }
    }

    private void updateSync() {
        if (syncView == null || currentTripId == null) return;
        LedgerStore.Trip trip = store.getTrip(currentTripId);
        if (trip == null || trip.team == null) return;
        if (!store.teamReady(trip)) {
            String denied = teamTransport.error();
            syncView.setText(denied.isEmpty() ? "请打开登记进度继续首次登记。" : denied);
        } else {
            SyncEngine.Status state = teamTransport.syncState();
            syncView.setText(state.label());
            if (reviewPanel != null) reviewPanel.updateConnections(trip, state);
        }
    }

    private void render() {
        LedgerStore.Trip trip = currentTripId == null ? null : store.getTrip(currentTripId);
        renderedSeatNames = trip == null ? Collections.emptyMap() : trip.seatNames;
        if (trip == null) { currentTripId = null; screen = TRIPS; renderTrips(); }
        else if (screen == TRIPS) renderTrips();
        else if (screen == REVIEW) renderReview(trip);
        else renderQuick(trip);
    }

    private LinearLayout page() {
        return page(new ScrollView(this));
    }

    private LinearLayout page(ScrollView scroll) {
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(18), dp(18), dp(30));
        scroll.addView(body);
        displayPage(scroll);
        return body;
    }

    private void displayPage(View content) {
        cancelReviewLoad();
        reviewContent = null;
        quickEntry = null;
        recordsOverlay = null;
        reviewPanel = null;
        syncView = null;
        awaitingEnrollment = false;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(248, 249, 247));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        if (currentTripId != null) root.addView(bottomBar());
        FormInput.fitActivityWindow(getWindow(), root);
        setContentView(root);
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
            boolean enteringLedger = target == QUICK && screen != QUICK;
            screen = target;
            render();
            if (enteringLedger) teamTransport.recordsChanged();
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
        teamTransport.recordsChanged();
        updateSync();
    }

    private void startSelectedSync() {
        if (started && Build.VERSION.SDK_INT >= 29)
            teamTransport.select(currentTripId == null ? null : store.getTrip(currentTripId));
    }

    private void openTeam(String mode, String tripId) {
        teamTransport.stop();
        startActivityForResult(new Intent(this, TeamSetupActivity.class)
                .putExtra(TeamSetupActivity.MODE, mode).putExtra(TeamSetupActivity.TRIP, tripId), REQUEST_TEAM);
    }

    private void renderTrips() {
        LinearLayout body = page();
        heading(body, "我的旅行");
        text(body, "选择旅行后，之后每次打开都会直接进入快速记账。", 14, Color.GRAY);
        List<LedgerStore.Trip> trips = store.trips();
        Button createTrip = button("新建小队旅行", () -> openTeam(TeamSetupActivity.CREATE, null));
        createTrip.setEnabled(Build.VERSION.SDK_INT >= 29);
        createTrip.setTextColor(Color.WHITE);
        int actionGreen = Color.rgb(33, 132, 91);
        createTrip.setBackground(forms.chip(actionGreen, actionGreen, false));
        if (trips.isEmpty()) body.addView(createTrip, fullMargin());
        else {
            Button deleteTrip = button(deletingTrips ? "完成" : "删除旅行", () -> {
                deletingTrips = !deletingTrips;
                renderTrips();
            });
            deleteTrip.setTextColor(deletingTrips ? actionGreen : Color.rgb(174, 73, 57));
            int border = deletingTrips ? Color.rgb(199, 223, 208) : Color.rgb(239, 183, 168);
            deleteTrip.setBackground(forms.chip(Color.WHITE, border, false));
            row(body, createTrip, deleteTrip);
            if (deletingTrips) text(body, "点右侧 × 选择旅行，随后确认删除。", 13,
                    Color.rgb(183, 98, 81));
        }
        if (Build.VERSION.SDK_INT < 29) text(body, "创建和加入小队需要 Android 10 或以上。", 14, Color.GRAY);
        section(body, "旅行列表");
        if (trips.isEmpty()) text(body, "新建小队旅行，或使用同伴的邀请二维码加入。", 15, Color.GRAY);
        for (LedgerStore.Trip trip : trips) {
            LinearLayout tripRow = new LinearLayout(this);
            tripRow.setOrientation(LinearLayout.HORIZONTAL);
            tripRow.setGravity(Gravity.CENTER_VERTICAL);
            Button item = button(trip.name + (trip.id.equals(currentTripId) ? " · 当前" : "") +
                    "\n我：" + trip.nameOf(trip.localPerson) + " · " + trip.people() + " 人小队", () -> selectTrip(trip));
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
        if (Build.VERSION.SDK_INT >= 29) {
            section(body, "同行与同步");
            LedgerStore.Trip current = currentTripId == null ? null : store.getTrip(currentTripId);
            Button join = button("加入小队旅行", () -> openTeam(TeamSetupActivity.JOIN, null));
            if (current == null || current.team == null || current.people() == 1 || !store.teamReady(current)) body.addView(join, fullMargin());
            else row(body, join, button("邀请同伴", () -> openTeam(TeamSetupActivity.INVITE, current.id)));
            if (current != null && current.team != null) {
                List<String> names = new ArrayList<>();
                for (int i = 0; i < current.people(); i++) names.add(current.nameOf(current.member(i)));
                text(body, String.join("、", names), 14, Color.GRAY);
                syncView = text(body, "", 13, Color.GRAY);
                updateSync();
            }
        }
    }

    private void openBluetoothSettings() {
        Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(settings);
        } catch (android.content.ActivityNotFoundException ignored) {
            toast("请在系统设置中打开旅行账本的应用权限。");
        }
    }

    private void confirmDeleteTrip(LedgerStore.Trip trip) {
        new AlertDialog.Builder(this).setTitle("删除旅行？")
                .setMessage("将从这台手机删除“" + trip.name + "”和它的全部本地记录。此操作无法撤销；同伴设备上的副本仍可在重新加入后同步。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除旅行", (dialog, which) -> {
                    if (!store.deleteTrip(trip.id)) return;
                    SharedPreferences.Editor prefs = uiPrefs.edit()
                            .remove("payer:" + trip.id).remove("mode:" + trip.id)
                            .remove("share:" + trip.id);
                    currencyHistory.clear(prefs, trip.id);
                    new NoteHistory(uiPrefs).clear(prefs, trip.id);
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
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(8), dp(14), dp(8));
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // Keep the quick-entry section at least as tall as the area above navigation.
                int viewport = MeasureSpec.getSize(heightMeasureSpec);
                if (body.getMinimumHeight() != viewport) body.setMinimumHeight(viewport);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        LinearLayout sections = page(scroll);
        sections.setPadding(0, 0, 0, 0);
        sections.addView(body, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(header, fullMargin());
        TextView tripTitle = text(header, trip.name, 21, 0xff154b3c);
        tripTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        tripTitle.setSingleLine(true);
        tripTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView quickLabel = text(header, "快速记账", 14, 0xff1c5938);
        quickLabel.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        quickLabel.setPadding(dp(8), dp(6), 0, dp(6));
        if (!store.teamReady(trip)) {
            waitingForTeam(body);
            return;
        }

        quickEntry = new QuickEntryPanel(this, trip, uiPrefs, currencyHistory, ledgerActions, forms,
                saved -> {
                    afterSave(saved, true);
                    toast("已记账");
                }, this::error);
        body.addView(quickEntry, new LinearLayout.LayoutParams(-1, -2, 1));
        addCashActions(sections, trip);
    }

    private void addCashActions(LinearLayout sections, LedgerStore.Trip trip) {
        LinearLayout cash = new LinearLayout(this);
        cash.setOrientation(LinearLayout.VERTICAL);
        cash.setPadding(dp(16), dp(10), dp(16), dp(8));
        GradientDrawable background = forms.chip(Color.WHITE, 0xffdce6df, false);
        background.setCornerRadius(dp(16));
        cash.setBackground(background);
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, -2);
        margin.setMargins(dp(14), dp(8), dp(14), dp(18));
        sections.addView(cash, margin);
        TextView title = text(cash, "现金与结算", 18, 0xff154b3c);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LedgerIcon.attach(title, R.drawable.ic_ledger_split);
        View divider = new View(this);
        divider.setBackgroundColor(0xffe6eee8);
        cash.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        Button deposit = button(LedgerReview.CASH_LABEL, () -> openCashForm(trip.id, forms::moneyIn));
        Button exchange = button("换汇", () -> openCashForm(trip.id, forms::exchange));
        if (trip.people() > 1) {
            cash.addView(deposit, fullMargin());
            row(cash, button("成员还款", () -> openCashForm(trip.id, forms::transfer)), exchange);
        } else row(cash, deposit, exchange);
    }

    private void openCashForm(String tripId, Consumer<LedgerStore.Trip> action) {
        LedgerStore.Trip latest = store.getTrip(tripId);
        if (latest != null) action.accept(latest);
    }

    private void waitingForTeam(LinearLayout body) {
        awaitingEnrollment = true;
        syncView = text(body, "等待首次登记", 15, Color.GRAY);
        body.addView(button("查看登记进度", () -> openTeam(TeamSetupActivity.JOIN, currentTripId)), fullMargin());
        updateSync();
    }

    private void renderReview(LedgerStore.Trip trip) {
        if (!store.teamReady(trip)) {
            LinearLayout body = page();
            heading(body, trip.name);
            waitingForTeam(body);
            return;
        }
        FrameLayout content = new FrameLayout(this);
        displayPage(content);
        reviewContent = content;
        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setPadding(dp(18), dp(18), dp(18), 0);
        reviewContent.addView(loading, new FrameLayout.LayoutParams(-1, -2));
        heading(loading, trip.name);
        text(loading, "正在读取流水…", 14, Color.GRAY);
        refreshReview(trip.id);
    }

    private void cancelReviewLoad() {
        reviewGeneration++;
        reviewDirty = false;
        if (reviewLoad != null) reviewLoad.cancel(false);
        reviewLoad = null;
    }

    private void refreshReview(String tripId) {
        if (reviewLoad != null) {
            reviewDirty = true;
            return;
        }
        long generation = reviewGeneration;
        reviewLoad = reviewReader.submit(() -> {
            try {
                LedgerStore.Snapshot snapshot = store.snapshot(tripId);
                if (snapshot.trip == null) throw new IllegalStateException("旅行不存在");
                LedgerReview review = new LedgerReview(snapshot.trip, snapshot.totals());
                long confirmed = SyncEngine.confirmedThrough(this, store, snapshot.trip);
                runOnUiThread(() -> {
                    if (!acceptReview(generation)) return;
                    showReview(snapshot.trip, review, confirmed);
                    refreshIfDirty(tripId);
                });
            } catch (RuntimeException error) {
                SyncLog.write(this, SyncLog.Event.UI_REFRESH, error);
                runOnUiThread(() -> {
                    if (!acceptReview(generation)) return;
                    if (reviewPanel == null) {
                        reviewContent.removeAllViews();
                        reviewContent.addView(button("重试读取流水", () -> refreshReview(tripId)),
                                new FrameLayout.LayoutParams(-1, -2));
                    }
                    refreshIfDirty(tripId);
                });
            }
        });
    }

    private boolean acceptReview(long generation) {
        if (generation != reviewGeneration || reviewContent == null) return false;
        reviewLoad = null;
        return true;
    }

    private void refreshIfDirty(String tripId) {
        if (!reviewDirty) return;
        reviewDirty = false;
        refreshReview(tripId);
    }

    private void showReview(LedgerStore.Trip trip, LedgerReview review, long confirmed) {
        ReviewPanel previous = reviewPanel;
        boolean keepAllRecords = recordsOverlay != null;
        if (keepAllRecords) {
            reviewContent.removeView(recordsOverlay);
            recordsOverlay = null;
        }
        View first = reviewPanel == null ? null : reviewPanel.getChildAt(0);
        int position = first == null ? 0 : reviewPanel.getFirstVisiblePosition();
        int offset = first == null ? 0 : first.getTop();
        long latest = Math.max(confirmed, teamTransport.syncState().confirmedThrough);
        reviewPanel = new ReviewPanel(this, trip.name, review, recordFilter, latest, new ReviewPanel.Actions() {
            @Override public void filter(String key) { recordFilter = key; }
            @Override public void openAll() { openRecords(trip, review); }
            @Override public void reconnect() {
                teamTransport.reconnect();
                updateSync();
            }
            @Override public void edit(LedgerReview.Row row) { forms.editRecord(trip, row); }
            @Override public void delete(List<String> ids) {
                if (deletingRecords) return;
                deletingRecords = true;
                ledgerActions.submit(() -> ledgerActions.delete(trip, ids), () -> {
                    deletingRecords = false;
                    if (isDestroyed() || !trip.id.equals(currentTripId)) return;
                    if (reviewPanel != null) reviewPanel.cancelRecordSelection();
                    afterSave(trip, false);
                }, failure -> {
                    deletingRecords = false;
                    if (!isDestroyed()) error(failure);
                });
            }
        });
        renderedSeatNames = trip.seatNames;
        if (reviewContent.getChildCount() != 0) reviewContent.removeViewAt(0);
        reviewContent.addView(reviewPanel, 0, new FrameLayout.LayoutParams(-1, -1));
        if (first != null) reviewPanel.setSelectionFromTop(Math.min(position, reviewPanel.getCount() - 1), offset);
        if (keepAllRecords) {
            openRecords(trip, review);
            reviewPanel.restoreRecordsPosition(previous);
        }
        syncView = reviewPanel.syncStatus;
        updateSync();
    }

    private void openRecords(LedgerStore.Trip trip, LedgerReview review) {
        if (reviewContent == null || recordsOverlay != null) return;
        recordsOverlay = reviewPanel.allRecords(this::closeRecords, () -> chooseExportFile(trip, review.records));
        reviewPanel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        reviewContent.addView(recordsOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void closeRecords() {
        reviewContent.removeView(recordsOverlay);
        recordsOverlay = null;
        reviewPanel.recordsClosed();
        reviewPanel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    private void chooseExportFile(LedgerStore.Trip trip, List<LedgerReview.Row> records) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, trip.name.replace('/', '_').replace('\\', '_') + "-旅行记录.txt");
        exportTripId = trip.id;
        pendingExport = records;
        try { startActivityForResult(intent, REQUEST_EXPORT); }
        catch (android.content.ActivityNotFoundException error) {
            exportTripId = null;
            pendingExport = null;
            toast("系统文件选择器不可用");
        }
    }

    private void exportRecords(Uri destination, String tripId, List<LedgerReview.Row> records) {
        new Thread(() -> {
            String status;
            try {
                boolean backup = LedgerBackup.isBackupName(exportFileName(destination));
                if (backup) {
                    LedgerStore.Snapshot snapshot = store.snapshot(tripId);
                    if (snapshot.trip == null) throw new IOException("旅行不存在");
                    try (OutputStream stream = getContentResolver().openOutputStream(destination, "wt")) {
                        if (stream == null) throw new IOException("无法打开文件");
                        LedgerBackup.write(stream, snapshot);
                    }
                } else {
                    writeRecordText(destination, tripId, records);
                }
                status = backup ? "账本备份已保存" : "旅行记录已导出";
            } catch (Exception error) { status = "导出失败，请重试"; }
            String message = status;
            runOnUiThread(() -> { if (!isDestroyed()) toast(message); });
        }, "ledger-export").start();
    }

    private String exportFileName(Uri destination) {
        try (Cursor cursor = getContentResolver().query(destination, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (name >= 0) return cursor.getString(name);
            }
        } catch (RuntimeException ignored) { }
        return destination.getLastPathSegment();
    }

    private void writeRecordText(Uri destination, String tripId, List<LedgerReview.Row> records) throws IOException {
        List<LedgerReview.Row> rows = records;
        if (rows == null) {
            LedgerStore.Snapshot snapshot = store.snapshot(tripId);
            if (snapshot.trip == null) throw new IOException("旅行不存在");
            rows = new LedgerReview(snapshot.trip, snapshot.totals()).records;
        }
        try (OutputStream stream = getContentResolver().openOutputStream(destination, "wt")) {
            if (stream == null) throw new IOException("无法打开文件");
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                for (LedgerReview.Row row : rows) {
                    writer.write(row.label);
                    writer.newLine();
                    writer.newLine();
                }
            }
        }
    }

    private void afterSave(LedgerStore.Trip trip, boolean resetQuickEntry) {
        if (!trip.id.equals(currentTripId)) return;
        try { teamTransport.recordsChanged(); }
        catch (RuntimeException syncError) { SyncLog.write(this, SyncLog.Event.UI_REFRESH, syncError); }
        try {
            if (reviewContent != null) refreshReview(trip.id);
            else if (resetQuickEntry) render();
        } catch (RuntimeException refreshError) {
            SyncLog.write(this, SyncLog.Event.UI_REFRESH, refreshError);
            toast("账目已保存，界面刷新失败");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_EXPORT) {
            String tripId = exportTripId;
            List<LedgerReview.Row> records = pendingExport;
            exportTripId = null;
            pendingExport = null;
            if (tripId != null && result == RESULT_OK && data != null && data.getData() != null)
                exportRecords(data.getData(), tripId, records);
            return;
        }
        if (request != REQUEST_TEAM) return;
        LedgerStore.Trip trip = result == RESULT_OK && data != null ? store.getTrip(data.getStringExtra(TeamSetupActivity.TRIP)) : null;
        if (trip != null) selectTrip(trip);
        else render();
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
