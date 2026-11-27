package com.rideledger;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A live view of the existing sync state. Opening it doesn't trigger a sync. */
final class SyncDetailsDialog {
    private final Activity activity;
    private final LedgerStyle style;
    private final SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
    private AlertDialog dialog;
    private LinearLayout body;
    private List<String> rendered = new ArrayList<>();

    SyncDetailsDialog(Activity activity, LedgerStyle style) {
        this.activity = activity;
        this.style = style;
    }

    void show() {
        if (isShowing()) return;
        ScrollView scroll = new ScrollView(activity);
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int inset = style.dp(LedgerStyle.PAGE_INSET);
        body.setPadding(inset, style.dp(LedgerStyle.CONTROL_GAP), inset, inset);
        scroll.addView(body);
        AlertDialog shown = new AlertDialog.Builder(activity).setTitle("同步详情").setView(scroll)
                .setNeutralButton("复制诊断", null)
                .setPositiveButton("关闭", null).create();
        dialog = shown;
        shown.setOnDismissListener(ignored -> { if (dialog == shown) clear(); });
        dialog.show();
        style.alertActions(dialog, LedgerStyle.Action.QUIET);
        Button copy = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (copy != null) {
            style.styleAction(copy, LedgerStyle.Action.QUIET);
            copy.setOnClickListener(v -> copyDiagnostics());
        }
    }

    private void copyDiagnostics() {
        StringBuilder report = new StringBuilder();
        report.append("RideLedger 同步诊断 (").append(time.format(new Date())).append(")\n");
        report.append("Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        BluetoothManager btManager = activity.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = btManager == null ? null : btManager.getAdapter();
        report.append("蓝牙: ").append(adapter != null && adapter.isEnabled() ? "已开启" : "未开启")
                .append(" · 权限: ").append(TeamBle.hasPermissions(activity) ? "完整" : "缺失")
                .append(" · 后台扫描: ").append(TeamBle.canScan(activity, true) ? "可用" : "受限").append("\n");
        for (int i = 0; i < rendered.size(); i++) {
            String line = rendered.get(i);
            if (!line.isEmpty()) report.append(line).append("\n");
        }
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("RideLedger 同步诊断", report.toString().trim()));
            Toast.makeText(activity, "已复制同步诊断信息", Toast.LENGTH_SHORT).show();
        }
    }

    boolean isShowing() { return dialog != null && dialog.isShowing(); }

    void dismiss() {
        if (dialog == null) return;
        dialog.dismiss();
        clear();
    }

    private void clear() {
        dialog = null;
        body = null;
        rendered.clear();
    }

    void update(List<SyncEngine.PeerDetail> peers, boolean searching, int pendingReceipts) {
        if (!isShowing()) return;
        List<String> rows = new ArrayList<>();
        String header = searching ? "正在查找队友" : "";
        if (peers.isEmpty() && header.isEmpty()) header = "暂未获取队友的同步信息";
        rows.add(header);
        for (SyncEngine.PeerDetail peer : peers) {
            rows.add(peer.name);
            rows.add(describe(peer));
        }
        rows.add(pendingReceipts > 0 ? "小票待补传 " + pendingReceipts + " 张" : "");
        if (rows.equals(rendered)) return;
        rendered = rows;

        body.removeAllViews();
        if (!header.isEmpty()) addCaption(header);
        for (int i = 0; i < peers.size(); i++) {
            LinearLayout member = new LinearLayout(activity);
            member.setOrientation(LinearLayout.VERTICAL);
            member.setBackground(style.card());
            int inset = style.dp(LedgerStyle.CARD_INSET);
            member.setPadding(inset, inset, inset, inset);
            member.addView(style.heading(rows.get(2 * i + 1), LedgerStyle.BODY));
            TextView detail = style.text(rows.get(2 * i + 2), LedgerStyle.CAPTION, style.muted);
            detail.setPadding(0, style.dp(LedgerStyle.ITEM_GAP), 0, 0);
            member.addView(detail);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
            size.topMargin = style.dp(LedgerStyle.CONTROL_GAP);
            body.addView(member, size);
        }
        String receipts = rows.get(rows.size() - 1);
        if (!receipts.isEmpty()) addCaption(receipts);
    }

    private void addCaption(String text) {
        TextView caption = style.text(text, LedgerStyle.CAPTION, style.muted);
        caption.setPadding(0, style.dp(LedgerStyle.CONTROL_GAP), 0, 0);
        body.addView(caption, new LinearLayout.LayoutParams(-1, -2));
    }

    private String describe(SyncEngine.PeerDetail peer) {
        if (!peer.registered) return "尚未登记";
        StringBuilder detail = new StringBuilder();
        if (peer.link != null && peer.link.active) {
            detail.append("正在通过 ").append(ConnectionBadge.channelName(peer.link.channel)).append(" 核对");
        } else if (peer.connecting) detail.append("正在尝试连接");
        else if (peer.link != null) {
            detail.append("刚通过 ").append(ConnectionBadge.channelName(peer.link.channel)).append(" 连通");
        } else detail.append("无连接");

        if (peer.pending > 0) detail.append("\n账目待确认 ").append(peer.pending).append(" 项变更");
        else detail.append("\n暂无待确认的账目变更");
        if (peer.checkedAt > 0) {
            detail.append("\n上次核对 ").append(time.format(new Date(peer.checkedAt)));
            if (peer.checkedChannel != null)
                detail.append(" · ").append(ConnectionBadge.channelName(peer.checkedChannel));
        } else detail.append("\n暂无核对时间");
        if (peer.failure != null) {
            detail.append("\n").append(ConnectionBadge.channelName(peer.failure.channel)).append(" · ")
                    .append(failureText(peer.failure.stage));
        }
        return detail.toString();
    }

    private static String failureText(SyncEngine.FailureStage stage) {
        switch (stage) {
            case CONNECT: return "上次未能建立连接";
            case HANDSHAKE: return "上次连接后未完成握手";
            case LEDGER: return "上次账目核对中断";
            case CONFIRMATION: return "上次未收到完整的保存确认";
            case RECEIPTS: return "上次小票同步中断";
            default: throw new AssertionError(stage);
        }
    }
}
