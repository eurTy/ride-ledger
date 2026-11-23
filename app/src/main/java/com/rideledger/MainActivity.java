package com.rideledger;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int QUICK = 0, REVIEW = 1, TRIPS = 2;
    private LedgerStyle style;
    private LedgerStore store;
    private LedgerActions ledgerActions;
    private final ExecutorService reviewReader = Executors.newSingleThreadExecutor();
    private Future<?> reviewLoad;
    private long reviewGeneration;
    private boolean reviewDirty;
    private FrameLayout reviewContent;
    private View recordsOverlay;
    private String exportTripId;
    private ExportFormat exportFormat = ExportFormat.TEXT;
    private boolean deletingRecords;
    private ReviewPanel reviewPanel;
    private SyncDetailsDialog syncDetails;
    private TeamTransportAdapter teamTransport;
    private final Set<String> shownSyncPeers = new HashSet<>();
    private Map<String, String> renderedSeatNames = Collections.emptyMap();
    private boolean started, awaitingEnrollment;
    private SharedPreferences uiPrefs;
    private CurrencyHistory currencyHistory;
    private NoteHistory noteHistory;
    private TripReports tripReports;
    private String restoredReportTripId;
    private LedgerForms forms;
    private ReceiptUi receipts;
    private QuickEntryPanel quickEntry;
    private QuickEntryDrafts quickDrafts;
    private final QuickEntryDrafts.Listener quickSaveListener = this::onQuickSaveFinished;
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
        style = new LedgerStyle(this);
        syncDetails = new SyncDetailsDialog(this, style);
        store = LedgerStore.application(this);
        ledgerActions = new LedgerActions(store);
        Object retained = getLastNonConfigurationInstance();
        quickDrafts = retained instanceof QuickEntryDrafts ? (QuickEntryDrafts) retained : new QuickEntryDrafts(state);
        quickDrafts.listen(quickSaveListener);
        receipts = new ReceiptUi(this, store, ledgerActions, quickDrafts, trip -> afterSave(trip, false), this::error);
        teamTransport = new TeamTransportAdapter(this, store, new PeerSync.Listener() {
            @Override public void changed(int count) { onSyncChanged(count); }
            @Override public void receiptsChanged() {
                if (reviewContent != null && currentTripId != null) refreshReview(currentTripId);
            }
            @Override public void synced(String tripId, String peer, boolean exchanged) {
                if (!started || !tripId.equals(currentTripId)) return;
                boolean first = shownSyncPeers.add(tripId + "/" + peer);
                if (first || exchanged) toast("账目同步成功");
            }
        });
        uiPrefs = getSharedPreferences("ui", MODE_PRIVATE);
        currencyHistory = new CurrencyHistory(uiPrefs, store);
        noteHistory = new NoteHistory(uiPrefs);
        forms = new LedgerForms(this, ledgerActions, currencyHistory, noteHistory, receipts, reviewReader,
                trip -> afterSave(trip, false), this::error);
        tripReports = new TripReports(this, store, reviewReader, forms);
        startupPermissionsInFlight = state != null && state.getBoolean("startupPermissionsInFlight");
        exportTripId = state == null ? null : state.getString("exportTripId");
        if (state != null) exportFormat = ExportFormat.valueOf(state.getString("exportFormat", ExportFormat.TEXT.name()));
        restoredReportTripId = state == null ? null : state.getString("reportTripId");
        currentTripId = uiPrefs.getString("last_trip", null);
        if (currentTripId == null || store.getTrip(currentTripId) == null) {
            List<LedgerStore.Trip> trips = store.trips();
            currentTripId = trips.isEmpty() ? null : trips.get(0).id;
        }
        screen = currentTripId == null ? TRIPS : state == null ? QUICK : state.getInt("screen", QUICK);
        if (state != null) recordFilter = state.getString("recordFilter", "all");
        render();
    }

    @Override public void onStart() {
        super.onStart();
        SyncBackground.beginMainSession(this);
        SyncBackground.foregroundEntered(this);
        started = true;
        tripReports.start();
        startSelectedSync();
        updateSync();
        if (reviewContent != null && currentTripId != null) refreshReview(currentTripId);
        if (restoredReportTripId != null) {
            tripReports.show(restoredReportTripId);
            restoredReportTripId = null;
        }
    }
    @Override public void onStop() {
        started = false;
        tripReports.stop();
        rememberQuickEntry();
        cancelReviewLoad();
        forms.dismissCurrencyMenu();
        syncDetails.dismiss();
        teamTransport.stop();
        SyncBackground.foregroundLeft(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        cancelReviewLoad();
        quickDrafts.unlisten(quickSaveListener);
        tripReports.close();
        forms.close();
        receipts.close();
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
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS);

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
        if (receipts.permissionResult(request)) return;
        if (request == REQUEST_STARTUP_PERMISSIONS) {
            startupPermissionsInFlight = false;
            teamTransport.permissionsChanged();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("startupPermissionsInFlight", startupPermissionsInFlight);
        state.putString("exportTripId", exportTripId);
        state.putString("exportFormat", exportFormat.name());
        state.putString("reportTripId", tripReports.openTripId());
        state.putInt("screen", screen);
        state.putString("recordFilter", recordFilter);
        rememberQuickEntry();
        quickDrafts.saveState(state);
        receipts.saveState(state);
    }

    @Override public Object onRetainNonConfigurationInstance() { return quickDrafts; }

    private void requestBackgroundBluetoothOnce() {
        if (Build.VERSION.SDK_INT < 29 || Build.VERSION.SDK_INT > 30 ||
                uiPrefs.getBoolean(BACKGROUND_BLUETOOTH_PROMPTED, false)) return;
        if (!TeamBle.hasPermissions(this) || TeamBle.canScan(this, true)) return;
        uiPrefs.edit().putBoolean(BACKGROUND_BLUETOOTH_PROMPTED, true).apply();
        if (Build.VERSION.SDK_INT == 29) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_BACKGROUND_BLUETOOTH);
        } else {
            AlertDialog prompt = new AlertDialog.Builder(this).setTitle("后台蓝牙同步")
                    .setMessage("如需在后台自动发现附近队友，请在系统权限中将位置设为「始终允许」。")
                    .setNegativeButton("跳过", null)
                    .setPositiveButton("去授权", (dialog, which) -> openBluetoothSettings())
                    .show();
            style.alertActions(prompt, LedgerStyle.Action.OUTLINE);
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
            if (reviewPanel == null) syncView.setText(state.label());
            else {
                reviewPanel.updateSync(trip, state, SyncEngine.lastCheckedAt(this, trip));
                if (syncDetails.isShowing()) updateSyncDetails(trip, state);
            }
        }
    }

    private void updateSyncDetails(LedgerStore.Trip trip, SyncEngine.Status state) {
        List<SyncEngine.PeerDetail> peers = Collections.emptyList();
        try { peers = teamTransport.syncDetails(trip); }
        catch (org.json.JSONException | RuntimeException error) { SyncLog.write(this, SyncLog.Event.QUEUE_READ, error); }
        syncDetails.update(peers, state.searching, reviewPanel.pendingReceipts());
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
        int inset = dp(LedgerStyle.PAGE_INSET);
        body.setPadding(inset, inset, inset, dp(24));
        scroll.addView(body);
        displayPage(scroll);
        return body;
    }

    private void displayPage(View content) {
        cancelReviewLoad();
        syncDetails.dismiss();
        tripReports.invalidatePendingReport();
        reviewContent = null;
        rememberQuickEntry();
        quickEntry = null;
        recordsOverlay = null;
        reviewPanel = null;
        syncView = null;
        awaitingEnrollment = false;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(style.page);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        if (currentTripId != null) root.addView(bottomBar());
        if (currentTripId != null && screen == QUICK)
            root.addView(new AmountShortcuts(this, style), new LinearLayout.LayoutParams(-1, -2));
        FormInput.fitActivityWindow(getWindow(), root);
        setContentView(root);
    }

    private void rememberQuickEntry() {
        if (quickEntry != null) quickEntry.rememberDraft();
    }

    private LinearLayout bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int gap = dp(LedgerStyle.CONTROL_GAP);
        bar.setPadding(gap, dp(6), gap, dp(6));
        bar.setBackgroundColor(style.surface);
        navItem(bar, "记账", QUICK);
        navItem(bar, "复盘", REVIEW);
        navItem(bar, "旅行", TRIPS);
        return bar;
    }

    private void navItem(LinearLayout bar, String title, int target) {
        boolean selected = screen == target;
        TextView item = style.text(title, LedgerStyle.LABEL, selected ? style.primary : style.muted);
        item.setGravity(Gravity.CENTER);
        item.setTypeface(LedgerStyle.MEDIUM);
        item.setSelected(selected);
        item.setMinimumHeight(dp(LedgerStyle.CONTROL_HEIGHT));
        item.setBackground(style.ripple(selected ? style.rounded(style.selected, 0, LedgerStyle.CONTROL_RADIUS) : null,
                LedgerStyle.CONTROL_RADIUS));
        item.setOnClickListener(v -> {
            if (target != TRIPS) deletingTrips = false;
            boolean enteringLedger = target == QUICK && screen != QUICK;
            screen = target;
            render();
            if (enteringLedger) teamTransport.recordsChanged();
        });
        item.setFocusable(true);
        bar.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
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
        if (started && Build.VERSION.SDK_INT >= 29) {
            LedgerStore.Trip trip = currentTripId == null ? null : store.getTrip(currentTripId);
            teamTransport.select(trip);
            NearbySyncService.select(this, trip);
        }
    }

    private void openTeam(String mode, String tripId) {
        teamTransport.stop();
        startActivityForResult(new Intent(this, TeamSetupActivity.class)
                .putExtra(TeamSetupActivity.MODE, mode).putExtra(TeamSetupActivity.TRIP, tripId), REQUEST_TEAM);
    }

    private void renderTrips() {
        LinearLayout body = page();
        int inset = style.contentInset(style.windowWidth(), LedgerStyle.PAGE_INSET, LedgerStyle.PAGE_MAX_WIDTH);
        body.setPadding(inset, body.getPaddingTop(), inset, body.getPaddingBottom());
        heading(body, "我的旅行");
        List<LedgerStore.Trip> trips = store.trips();
        Button createTrip = style.button("新建小队旅行", LedgerStyle.Action.PRIMARY, () -> openTeam(TeamSetupActivity.CREATE, null));
        createTrip.setEnabled(Build.VERSION.SDK_INT >= 29);
        if (trips.isEmpty()) body.addView(createTrip, fullMargin());
        else {
            Button deleteTrip = style.button(deletingTrips ? "完成" : "删除旅行",
                    deletingTrips ? LedgerStyle.Action.OUTLINE : LedgerStyle.Action.DANGER, () -> {
                        deletingTrips = !deletingTrips;
                        renderTrips();
                    });
            row(body, createTrip, deleteTrip);
            if (deletingTrips) text(body, "点右侧 × 选择旅行，随后确认删除。", LedgerStyle.CAPTION, style.danger);
        }
        if (Build.VERSION.SDK_INT < 29) text(body, "创建和加入小队需要 Android 10 或以上。", LedgerStyle.LABEL, style.muted);
        section(body, "旅行列表");
        if (trips.isEmpty()) text(body, "新建小队旅行，或使用同伴的邀请二维码加入。", LedgerStyle.BODY, style.muted);
        for (LedgerStore.Trip trip : trips) addTripCard(body, trip);
        LedgerStore.Trip current = currentTripId == null ? null : store.getTrip(currentTripId);
        if (Build.VERSION.SDK_INT >= 29) {
            section(body, "同行与同步");
            Button join = button("加入小队旅行", () -> openTeam(TeamSetupActivity.JOIN, null));
            if (current == null || current.team == null || current.people() == 1 || !store.teamReady(current))
                body.addView(join, fullMargin());
            else row(body, join, button("邀请同伴", () -> openTeam(TeamSetupActivity.INVITE, current.id)));
            if (current != null && current.team != null) {
                syncView = text(body, "", LedgerStyle.CAPTION, style.muted);
                updateSync();
            }
        }
    }

    private Button tripReportAction(LedgerStore.Trip trip) {
        Button report = style.button("查看报告", LedgerStyle.Action.QUIET, () -> tripReports.show(trip.id));
        report.setTextSize(LedgerStyle.LABEL);
        report.setPadding(dp(LedgerStyle.CONTROL_GAP), report.getPaddingTop(), 0, report.getPaddingBottom());
        report.setSingleLine();
        return report;
    }

    private void addTripCard(LinearLayout body, LedgerStore.Trip trip) {
        boolean current = trip.id.equals(currentTripId);
        boolean showReports = !deletingTrips;
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(style.card());
        LinearLayout.LayoutParams margin = fullMargin();
        margin.bottomMargin = dp(LedgerStyle.CONTROL_GAP);
        body.addView(card, margin);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(LedgerStyle.CARD_INSET);
        info.setPadding(inset, dp(showReports ? 10 : 12), inset, dp(showReports ? 2 : 12));
        info.setForeground(style.ripple(null, LedgerStyle.CARD_RADIUS));
        info.setFocusable(true);
        info.setOnClickListener(view -> selectTrip(trip));
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(titleRow, fullMargin());
        TextView title = style.heading(trip.name, LedgerStyle.SECTION_TITLE);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (current) {
            TextView mark = style.text("当前", 12, style.primary);
            mark.setTypeface(LedgerStyle.MEDIUM);
            mark.setPadding(dp(8), dp(2), dp(8), dp(2));
            mark.setBackground(style.rounded(style.selected, 0, LedgerStyle.CONTROL_RADIUS));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
            size.setMarginStart(dp(LedgerStyle.CONTROL_GAP));
            titleRow.addView(mark, size);
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < trip.people(); i++) {
            String member = trip.member(i);
            if (trip.team != null && trip.team.openSeats && !trip.seatNames.containsKey(member) &&
                    !member.equals(trip.localPerson)) continue;
            names.add(trip.nameOf(member) + (member.equals(trip.localPerson) ? "（我）" : ""));
        }
        String members = trip.people() + " 人小队" + (names.isEmpty() ? "" : " · " + String.join("、", names));
        TextView detail = style.text(members, LedgerStyle.CAPTION, style.muted);
        detail.setMaxLines(2);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout memberRow = new LinearLayout(this);
        memberRow.setGravity(Gravity.CENTER_VERTICAL);
        if (showReports) {
            Button report = tripReportAction(trip);
            int available = tripContentWidth(body) - 2 * inset - dp(LedgerStyle.CONTROL_GAP) - LedgerStyle.textWidth(report);
            CharSequence label = detail.getText();
            boolean fitsBesideReport = available > 0
                    && android.text.StaticLayout.Builder.obtain(label, 0, label.length(), detail.getPaint(), available)
                            .setBreakStrategy(detail.getBreakStrategy()).build().getLineCount() <= detail.getMaxLines();
            if (fitsBesideReport) {
                memberRow.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
                memberRow.addView(report, new LinearLayout.LayoutParams(-2, -2));
            } else {
                memberRow.setOrientation(LinearLayout.VERTICAL);
                memberRow.addView(detail, new LinearLayout.LayoutParams(-1, -2));
                LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(-2, -2);
                action.gravity = Gravity.END;
                memberRow.addView(report, action);
            }
        } else memberRow.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        info.addView(memberRow, new LinearLayout.LayoutParams(-1, -2));
        info.setContentDescription(trip.name + (current ? "，当前旅行" : "") + "，" + members);
        if (deletingTrips) {
            Button remove = style.button("×", LedgerStyle.Action.DANGER, () -> confirmDeleteTrip(trip));
            remove.setTextSize(20);
            remove.setContentDescription("删除旅行 " + trip.name);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(LedgerStyle.CONTROL_HEIGHT), -2);
            size.setMarginEnd(dp(LedgerStyle.CONTROL_GAP));
            card.addView(remove, size);
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
        AlertDialog confirmation = new AlertDialog.Builder(this).setTitle("删除旅行？")
                .setMessage("将从这台手机删除“" + trip.name + "”和它的全部本地记录。此操作无法撤销；同伴设备上的副本仍可在重新加入后同步。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除旅行", (dialog, which) -> {
                    if (!store.deleteTrip(trip.id)) return;
                    quickDrafts.clearForm(trip.id);
                    receipts.clearDraft(trip.id, receipts.draft(trip.id));
                    SharedPreferences.Editor prefs = uiPrefs.edit()
                            .remove("payer:" + trip.id).remove("mode:" + trip.id)
                            .remove("share:" + trip.id);
                    currencyHistory.clear(prefs, trip.id);
                    noteHistory.clear(prefs, trip.id);
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
        style.alertActions(confirmation, LedgerStyle.Action.DANGER);
    }

    private void renderQuick(LedgerStore.Trip trip) {
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                int inset = style.contentInset(width, LedgerStyle.PAGE_INSET, LedgerStyle.FORM_MAX_WIDTH);
                View body = getChildAt(0);
                if (body != null && body.getPaddingStart() != inset)
                    body.setPaddingRelative(inset, body.getPaddingTop(), inset, body.getPaddingBottom());
                QuickEntryPanel panel = quickEntry;
                if (panel != null && panel.getParent() == body)
                    panel.fitViewport(width - 2 * inset, MeasureSpec.getSize(heightSpec), FormInput.keyboardVisible(this));
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        LinearLayout body = page(scroll);
        int inset = dp(LedgerStyle.PAGE_INSET);
        body.setPadding(inset, 0, inset, dp(LedgerStyle.CONTROL_GAP));
        TextView quickLabel = style.text("快速记账", LedgerStyle.CAPTION, style.muted);
        quickLabel.setPadding(0, dp(6), 0, dp(6));
        body.addView(style.tripTitleRow(trip.name, quickLabel), fullMargin());
        if (!store.teamReady(trip)) {
            waitingForTeam(body);
            return;
        }

        quickEntry = new QuickEntryPanel(this, trip, uiPrefs, currencyHistory, noteHistory, ledgerActions, forms, receipts, quickDrafts,
                () -> store.getTrip(trip.id), this::error);
        body.addView(quickEntry, new LinearLayout.LayoutParams(-1, -2));
    }

    private void waitingForTeam(LinearLayout body) {
        awaitingEnrollment = true;
        syncView = text(body, "等待首次登记", LedgerStyle.BODY, style.muted);
        body.addView(button("查看登记进度", () -> openTeam(TeamSetupActivity.JOIN, currentTripId)), fullMargin());
        updateSync();
    }

    private void renderReview(LedgerStore.Trip trip) {
        int inset = style.contentInset(style.windowWidth(), LedgerStyle.PAGE_INSET, LedgerStyle.FORM_MAX_WIDTH);
        if (!store.teamReady(trip)) {
            LinearLayout body = page();
            body.setPadding(inset, 0, inset, body.getPaddingBottom());
            body.addView(style.tripTitleRow(trip.name, null), fullMargin());
            waitingForTeam(body);
            return;
        }
        FrameLayout content = new FrameLayout(this);
        displayPage(content);
        reviewContent = content;
        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setPadding(inset, 0, inset, 0);
        reviewContent.addView(loading, new FrameLayout.LayoutParams(-1, -2));
        loading.addView(style.tripTitleRow(trip.name, null), fullMargin());
        text(loading, "正在读取流水…", LedgerStyle.LABEL, style.muted);
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
                LedgerStore.Snapshot snapshot = store.snapshotWithReceipts(tripId);
                if (snapshot.trip == null) throw new IllegalStateException("旅行不存在");
                LedgerReview review = new LedgerReview(snapshot.trip, snapshot.totals(), snapshot.receipts);
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
        forms.updateSettlement(trip, review);
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
        reviewPanel = new ReviewPanel(this, trip.name, review, recordFilter, latest, true, new ReviewPanel.Actions() {
            @Override public void filter(String key) { recordFilter = key; }
            @Override public void openAll() { openRecords(trip, review); }
            @Override public void reconnect() {
                teamTransport.reconnect();
                updateSync();
            }
            @Override public void showConnectionDetails() {
                syncDetails.show();
                updateSync();
            }
            @Override public void convertSettlement() { forms.showSettlement(trip, review); }
            @Override public void edit(LedgerReview.Row row) { forms.editRecord(trip, row); }
            @Override public void thumbnail(LedgerReview.Row row, ImageView view) {
                receipts.bindThumbnail(trip.id, row.receipt, view);
            }
            @Override public void openReceipt(LedgerReview.Row row) { receipts.view(trip.id, row.receipt); }
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
        recordsOverlay = reviewPanel.allRecords(this::closeRecords, () -> chooseExportFormat(trip, review.hasReceipts));
        reviewPanel.setVisibility(View.INVISIBLE);
        reviewContent.addView(recordsOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void closeRecords() {
        reviewContent.removeView(recordsOverlay);
        recordsOverlay = null;
        reviewPanel.recordsClosed();
        reviewPanel.setVisibility(View.VISIBLE);
    }

    private enum ExportFormat {
        TEXT("旅行日志（文本）", "text/plain", "-旅行日志.txt"),
        CSV("旅行日志（CSV）", "text/csv", "-旅行日志.csv"),
        BACKUP("账本备份", "text/plain", ".rlbackup.txt"),
        ARCHIVE("账本备份（含小票）", "application/zip", ".rlbackup.zip");

        final String label, mime, suffix;

        ExportFormat(String label, String mime, String suffix) {
            this.label = label;
            this.mime = mime;
            this.suffix = suffix;
        }
    }

    private void chooseExportFormat(LedgerStore.Trip trip, boolean hasReceipts) {
        ExportFormat backup = hasReceipts ? ExportFormat.ARCHIVE : ExportFormat.BACKUP;
        ExportFormat[] formats = {ExportFormat.TEXT, ExportFormat.CSV, backup};
        AlertDialog options = new AlertDialog.Builder(this).setTitle("导出")
                .setItems(new String[]{formats[0].label, formats[1].label, formats[2].label},
                        (dialog, choice) -> chooseExportFile(trip, formats[choice]))
                .setNegativeButton("取消", null).show();
        style.alertActions(options, LedgerStyle.Action.OUTLINE);
    }

    private void chooseExportFile(LedgerStore.Trip trip, ExportFormat format) {
        String name = trip.name.replace('/', '_').replace('\\', '_');
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(format.mime).putExtra(Intent.EXTRA_TITLE, name + format.suffix);
        exportTripId = trip.id;
        exportFormat = format;
        try { startActivityForResult(intent, REQUEST_EXPORT); }
        catch (android.content.ActivityNotFoundException error) {
            exportTripId = null;
            exportFormat = ExportFormat.TEXT;
            toast("系统文件选择器不可用");
        }
    }

    private void exportRecords(Uri destination, String tripId, ExportFormat selected) {
        new Thread(() -> {
            String status;
            try {
                ExportFormat format = selected;
                if (format == ExportFormat.TEXT) {
                    String name = exportFileName(destination);
                    if (LedgerBackup.isArchiveName(name)) format = ExportFormat.ARCHIVE;
                    else if (LedgerBackup.isBackupName(name)) format = ExportFormat.BACKUP;
                }
                if (format == ExportFormat.ARCHIVE) {
                    LedgerStore.Snapshot snapshot = store.snapshotWithReceipts(tripId);
                    if (snapshot.trip == null) throw new IOException("旅行不存在");
                    writeReceiptBackup(destination, snapshot);
                    status = snapshot.receipts.stream().anyMatch(entry -> !entry.digest.isEmpty() && !entry.available)
                            ? "已备份，部分图片待同步" : "账本备份已保存";
                } else if (format == ExportFormat.BACKUP) {
                    LedgerStore.Snapshot snapshot = store.snapshot(tripId);
                    if (snapshot.trip == null) throw new IOException("旅行不存在");
                    try (OutputStream stream = getContentResolver().openOutputStream(destination, "wt")) {
                        if (stream == null) throw new IOException("无法打开文件");
                        LedgerBackup.write(stream, snapshot);
                    }
                    status = "账本备份已保存";
                } else {
                    writeVisibleRecords(destination, tripId, format);
                    status = "旅行日志已导出";
                }
            } catch (Exception error) { status = "导出失败，请重试"; }
            String message = status;
            runOnUiThread(() -> { if (!isDestroyed()) toast(message); });
        }, "ledger-export").start();
    }

    private void writeReceiptBackup(Uri destination, LedgerStore.Snapshot snapshot) throws Exception {
        File archive = File.createTempFile("ledger-backup-", ".zip", getCacheDir());
        try {
            LedgerBackup.writeArchive(new FileOutputStream(archive), snapshot, store.receipts);
            try (OutputStream stream = getContentResolver().openOutputStream(destination, "wt")) {
                if (stream == null) throw new IOException("无法打开文件");
                Files.copy(archive.toPath(), stream);
            }
        } finally { archive.delete(); }
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

    private void writeVisibleRecords(Uri destination, String tripId, ExportFormat format)
            throws IOException, org.json.JSONException {
        LedgerStore.Snapshot snapshot = store.snapshotWithReceipts(tripId);
        if (snapshot.trip == null) throw new IOException("旅行不存在");
        LedgerReport report = format == ExportFormat.TEXT ? LedgerReport.from(snapshot) : null;
        try (OutputStream stream = getContentResolver().openOutputStream(destination, "wt")) {
            if (stream == null) throw new IOException("无法打开文件");
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                if (report == null) LedgerCsv.write(writer, snapshot);
                else report.write(writer);
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

    private void onQuickSaveFinished(String tripId, Exception failure) {
        if (!tripId.equals(currentTripId)) return;
        boolean showingQuick = screen == QUICK;
        if (failure != null) {
            if (quickEntry != null) quickEntry.saveFailed();
            if (started) error(failure);
            return;
        }
        // Don't capture the submitted values when replacing the form.
        if (showingQuick) quickEntry = null;
        LedgerStore.Trip trip = store.getTrip(tripId);
        if (trip == null) return;
        afterSave(trip, showingQuick);
        if (started) toast("已记账");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (receipts.activityResult(request, result, data)) return;
        if (request == REQUEST_EXPORT) {
            String tripId = exportTripId;
            ExportFormat format = exportFormat;
            exportTripId = null;
            exportFormat = ExportFormat.TEXT;
            if (tripId != null && result == RESULT_OK && data != null && data.getData() != null)
                exportRecords(data.getData(), tripId, format);
            return;
        }
        if (request != REQUEST_TEAM) return;
        LedgerStore.Trip trip = result == RESULT_OK && data != null ? store.getTrip(data.getStringExtra(TeamSetupActivity.TRIP)) : null;
        if (trip != null) selectTrip(trip);
        else render();
    }

    private void heading(LinearLayout body, String value) { addHeading(body, value, LedgerStyle.PAGE_TITLE); }
    private void section(LinearLayout body, String value) { addHeading(body, value, LedgerStyle.SECTION_TITLE); }

    private void addHeading(LinearLayout body, String value, int size) {
        TextView view = style.heading(value, size);
        view.setPadding(0, dp(8), 0, dp(6));
        body.addView(view, fullMargin());
    }

    private TextView text(LinearLayout body, String value, int sp, int color) {
        TextView view = style.text(value, sp, color);
        view.setPadding(0, dp(8), 0, dp(8));
        body.addView(view, fullMargin());
        return view;
    }

    private Button button(String value, Runnable onClick) {
        return style.button(value, LedgerStyle.Action.OUTLINE, onClick);
    }

    private int tripContentWidth(LinearLayout body) {
        return style.windowWidth() - body.getPaddingLeft() - body.getPaddingRight();
    }

    private void row(LinearLayout body, Button first, Button second) {
        LinearLayout line = new LinearLayout(this);
        int gap = dp(LedgerStyle.CONTROL_GAP);
        int width = tripContentWidth(body) - gap;
        int firstWidth = LedgerStyle.textWidth(first), secondWidth = LedgerStyle.textWidth(second);
        if (firstWidth + secondWidth <= width) {
            boolean equal = Math.max(firstWidth, secondWidth) <= width / 2;
            line.addView(first, new LinearLayout.LayoutParams(equal ? 0 : firstWidth, -2, 1));
            LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(equal ? 0 : secondWidth, -2, 1);
            next.setMarginStart(gap);
            line.addView(second, next);
        } else {
            line.setOrientation(LinearLayout.VERTICAL);
            line.addView(first, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(-1, -2);
            next.topMargin = gap;
            line.addView(second, next);
        }
        body.addView(line, fullMargin());
    }

    private LinearLayout.LayoutParams fullMargin() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(LedgerStyle.ITEM_GAP);
        return params;
    }

    private int dp(int value) { return style.dp(value); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private void error(Exception e) { toast(e.getMessage() == null ? "操作失败" : e.getMessage()); }
}
