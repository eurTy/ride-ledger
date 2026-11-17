package com.rideledger;

import android.app.Activity;
import android.app.Dialog;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Displays only aggregate figures from the confirmed part of a trip's ledger. */
final class TripReportView {
    private static final String TITLE = "旅行统计报告";
    private static final int FULL = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Activity activity;
    private final LedgerStyle style;

    private TripReportView(Activity activity) {
        this.activity = activity;
        style = new LedgerStyle(activity);
    }

    static Dialog show(Activity activity, TripStatistics statistics) {
        return new TripReportView(activity).display(statistics);
    }

    private Dialog display(TripStatistics statistics) {
        Dialog dialog = new Dialog(activity, R.style.LedgerTheme);
        dialog.setTitle(TITLE);
        LinearLayout root = column();
        root.setBackgroundColor(style.page);
        if (Build.VERSION.SDK_INT >= 28) root.setAccessibilityPaneTitle(TITLE);
        LinearLayout toolbar = style.topBar(TITLE, dialog::dismiss);
        toolbar.setBackgroundColor(style.surface);
        root.addView(toolbar);
        divider(root);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = column();
        int inset = style.dp(LedgerStyle.PAGE_INSET);
        body.setPadding(inset, inset, inset, style.dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(FULL, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(FULL, 0, 1));
        buildReport(body, statistics);

        Window window = dialog.getWindow();
        FormInput.fitActivityWindow(window, root);
        dialog.setContentView(root);
        dialog.show();
        window.setLayout(FULL, FULL);
        return dialog;
    }

    private void buildReport(LinearLayout body, TripStatistics statistics) {
        LinearLayout summary = card(body, true);
        summary.addView(style.heading(statistics.trip.name, 22));
        boolean solo = statistics.trip.people() == 1;
        if (!statistics.canVerifyTeam) {
            caption(summary, "暂无法确认同步状态", style.warning);
            return;
        }
        LedgerReport report = statistics.report;
        if (report.records.isEmpty()) {
            caption(summary, solo ? "暂无账目" : "暂无已同步账目", style.muted);
            if (statistics.pending) caption(summary, "还有账目待同步", style.warning);
            return;
        }
        String scope = statistics.pending ? "仅统计已同步账目 · 还有账目待同步"
                : solo ? "本机账目统计" : "已同步账目统计";
        caption(summary, scope, statistics.pending ? style.warning : style.muted);

        LedgerReport.Section totals = section(report, "支出总计");
        if (totals == null || totals.entries.size() <= 1) {
            caption(summary, solo ? "暂无支出" : "暂无已同步支出", style.muted);
        } else {
            addTotals(summary, totals);
            addSection(body, section(report, "消费分类"));
            addSection(body, section(report, "支出类型"));
            addSection(body, section(report, "成员付款与承担"));
        }
        addSection(body, section(report, "资金往来"));
        addSection(body, section(report, "账本提醒"));
    }

    private void addTotals(LinearLayout summary, LedgerReport.Section totals) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(FULL, WRAP);
        size.topMargin = style.dp(LedgerStyle.CONTROL_GAP);
        summary.addView(style.heading(totals.title, LedgerStyle.SECTION_TITLE), size);
        for (LedgerReport.Entry entry : totals.entries) {
            if ("支出笔数".equals(entry.label)) {
                caption(summary, entry.value + "支出 · 按原币种", style.muted);
            } else {
                stat(summary, entry, 22);
            }
        }
    }

    private void addSection(LinearLayout body, LedgerReport.Section section) {
        if (section == null) return;
        List<LedgerReport.Entry> rows = new ArrayList<>();
        for (LedgerReport.Entry entry : section.entries) {
            if ("统计口径".equals(entry.label) || "汇总范围".equals(entry.label) || "0 笔".equals(entry.value))
                continue;
            rows.add(entry);
        }
        if (rows.isEmpty()) return;
        LinearLayout card = card(body, false);
        card.addView(style.heading(section.title, LedgerStyle.SECTION_TITLE));
        for (LedgerReport.Entry entry : rows) {
            divider(card);
            stat(card, entry, LedgerStyle.BODY);
        }
    }

    private static LedgerReport.Section section(LedgerReport report, String title) {
        for (LedgerReport.Section section : report.sections) if (title.equals(section.title)) return section;
        return null;
    }

    private LinearLayout card(LinearLayout body, boolean highlighted) {
        LinearLayout card = column();
        int inset = style.dp(LedgerStyle.CARD_INSET);
        card.setPadding(inset, inset, inset, inset);
        card.setBackground(highlighted ? style.rounded(style.soft, style.stroke, LedgerStyle.CARD_RADIUS) : style.card());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(FULL, WRAP);
        size.bottomMargin = style.dp(LedgerStyle.SECTION_GAP);
        body.addView(card, size);
        return card;
    }

    private void caption(LinearLayout section, String text, int color) {
        TextView label = style.text(text, LedgerStyle.CAPTION, color);
        label.setPadding(0, style.dp(LedgerStyle.ITEM_GAP), 0, 0);
        section.addView(label);
    }

    private void stat(LinearLayout card, LedgerReport.Entry entry, int valueSize) {
        LinearLayout row = new LinearLayout(activity);
        int gap = style.dp(LedgerStyle.ITEM_GAP);
        row.setPadding(0, gap, 0, gap);
        row.setGravity(Gravity.TOP);
        card.addView(row, new LinearLayout.LayoutParams(FULL, WRAP));
        if (!entry.label.isEmpty()) {
            LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, WRAP, 2);
            labelSize.setMarginEnd(style.dp(LedgerStyle.CONTROL_GAP));
            row.addView(style.text(entry.label, LedgerStyle.LABEL, style.muted), labelSize);
        }
        TextView value = style.text(entry.value, valueSize, style.title);
        value.setGravity(entry.label.isEmpty() ? Gravity.START : Gravity.END);
        value.setTypeface(LedgerStyle.MEDIUM);
        row.addView(value, new LinearLayout.LayoutParams(0, WRAP, 3));
    }

    private void divider(LinearLayout parent) {
        View rule = new View(activity);
        rule.setBackgroundColor(style.divider);
        parent.addView(rule, new LinearLayout.LayoutParams(FULL, style.dp(1)));
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
}
