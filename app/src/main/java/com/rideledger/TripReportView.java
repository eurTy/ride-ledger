package com.rideledger;

import android.app.Activity;
import android.app.Dialog;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;

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
                caption(summary, entry.value + "支出", style.muted);
            } else {
                summary.addView(new AmountTable(entry.amountColumns, 20), new LinearLayout.LayoutParams(FULL, WRAP));
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
            if (entry.amountColumns.isEmpty()) stat(card, entry, LedgerStyle.BODY);
            else amountGroup(card, entry);
        }
    }

    private void amountGroup(LinearLayout card, LedgerReport.Entry entry) {
        LinearLayout heading = new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(0, style.dp(LedgerStyle.CONTROL_GAP), 0, style.dp(LedgerStyle.ITEM_GAP));
        heading.addView(style.heading(entry.label, LedgerStyle.LABEL), new LinearLayout.LayoutParams(0, WRAP, 1));
        if (!entry.amountNote.isEmpty()) {
            TextView note = style.text(entry.amountNote, LedgerStyle.CAPTION, style.muted);
            note.setPadding(style.dp(LedgerStyle.CONTROL_GAP), 0, 0, 0);
            heading.addView(note, new LinearLayout.LayoutParams(WRAP, WRAP));
        }
        card.addView(heading, new LinearLayout.LayoutParams(FULL, WRAP));
        card.addView(new AmountTable(entry.amountColumns, LedgerStyle.LABEL), new LinearLayout.LayoutParams(FULL, WRAP));
    }

    /** Keeps metric columns aligned, with a per-currency layout when those columns cannot fit. */
    private final class AmountTable extends LinearLayout {
        private final List<LedgerReport.AmountColumn> columns;
        private final SortedSet<String> currencies = new TreeSet<>();
        private final int valueSize, tableWidth;
        private final HorizontalScrollView table;
        private Boolean stacked;

        AmountTable(List<LedgerReport.AmountColumn> columns, int valueSize) {
            super(TripReportView.this.activity);
            setOrientation(VERTICAL);
            this.columns = columns;
            this.valueSize = valueSize;
            for (LedgerReport.AmountColumn column : columns) currencies.addAll(column.values.keySet());
            TableLayout grid = grid();
            int natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            grid.measure(natural, natural);
            tableWidth = grid.getMeasuredWidth();
            table = overflow(grid);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            boolean useStack = columns.size() > 1 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
                    && tableWidth > width;
            if (stacked == null || stacked != useStack) {
                stacked = useStack;
                removeAllViews();
                addView(useStack ? stacked() : table, new LinearLayout.LayoutParams(FULL, WRAP));
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        private TableLayout grid() {
            TableLayout grid = new TableLayout(activity);
            for (int i = 1; i <= columns.size(); i++) grid.setColumnStretchable(i, true);
            List<TableRow> rows = new ArrayList<>();
            if (columns.size() > 1) {
                List<String> headers = new ArrayList<>();
                headers.add("币种");
                for (LedgerReport.AmountColumn column : columns) headers.add(column.label);
                rows.add(amountRow(headers, true, valueSize));
            }
            for (String code : currencies) {
                List<String> values = new ArrayList<>();
                values.add(code);
                for (LedgerReport.AmountColumn column : columns) values.add(amount(column, code));
                TableRow row = amountRow(values, false, valueSize);
                describe(row, description(code));
                rows.add(row);
            }
            // Equal metric widths also align separate member tables within the same card.
            int metricWidth = 0;
            int natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            for (TableRow row : rows) {
                for (int i = 1; i < row.getChildCount(); i++) {
                    View cell = row.getChildAt(i);
                    cell.measure(natural, natural);
                    metricWidth = Math.max(metricWidth, cell.getMeasuredWidth());
                }
            }
            TextView currency = style.text("WWW", LedgerStyle.LABEL, style.muted);
            int currencyWidth = (int) Math.ceil(currency.getPaint().measureText("WWW"));
            for (TableRow row : rows) {
                ((TextView) row.getChildAt(0)).setMinWidth(currencyWidth);
                for (int i = 1; i < row.getChildCount(); i++) ((TextView) row.getChildAt(i)).setMinWidth(metricWidth);
                grid.addView(row);
            }
            return grid;
        }

        private LinearLayout stacked() {
            LinearLayout stack = column();
            for (String code : currencies) {
                LinearLayout group = column();
                TextView currency = style.heading(code, LedgerStyle.LABEL);
                currency.setTextColor(style.muted);
                currency.setPadding(0, style.dp(LedgerStyle.ITEM_GAP), 0, style.dp(LedgerStyle.ITEM_GAP));
                group.addView(currency);
                TableLayout metrics = new TableLayout(activity);
                metrics.setColumnStretchable(1, true);
                for (LedgerReport.AmountColumn column : columns) {
                    List<String> values = new ArrayList<>();
                    values.add(column.label);
                    values.add(amount(column, code));
                    metrics.addView(amountRow(values, false, valueSize));
                }
                group.addView(overflow(metrics), new LinearLayout.LayoutParams(FULL, WRAP));
                describe(group, description(code));
                stack.addView(group, new LinearLayout.LayoutParams(FULL, WRAP));
            }
            return stack;
        }

        private String description(String code) {
            StringBuilder text = new StringBuilder(code);
            for (LedgerReport.AmountColumn column : columns)
                text.append("，").append(column.label.isEmpty() ? "金额" : column.label)
                        .append(' ').append(amount(column, code));
            return text.toString();
        }
    }

    private TableRow amountRow(List<String> values, boolean header, int valueSize) {
        TableRow row = new TableRow(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int gap = style.dp(LedgerStyle.ITEM_GAP);
        row.setPadding(0, gap, 0, gap);
        for (int i = 0; i < values.size(); i++) {
            boolean numeric = !header && i > 0;
            TextView cell = style.text(values.get(i), header ? LedgerStyle.CAPTION : numeric ? valueSize : LedgerStyle.LABEL,
                    numeric ? style.title : style.muted);
            cell.setSingleLine(true);
            cell.setIncludeFontPadding(false);
            cell.setGravity(i == 0 ? Gravity.START : Gravity.END);
            if (numeric) {
                cell.setTypeface(LedgerStyle.MEDIUM);
                cell.setFontFeatureSettings("tnum");
            }
            TableRow.LayoutParams size = new TableRow.LayoutParams(WRAP, WRAP);
            if (i > 0) size.setMarginStart(style.dp(LedgerStyle.CONTROL_GAP));
            row.addView(cell, size);
        }
        return row;
    }

    private HorizontalScrollView overflow(TableLayout table) {
        // Normal figures fill the available width; only exceptionally long integers need scrolling.
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(table, new HorizontalScrollView.LayoutParams(WRAP, WRAP));
        return scroll;
    }

    private static String amount(LedgerReport.AmountColumn column, String code) {
        int scale = Money.scale(code);
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        format.setMinimumFractionDigits(scale);
        format.setMaximumFractionDigits(scale);
        return format.format(new BigDecimal(column.values.getOrDefault(code, BigInteger.ZERO), scale));
    }

    private static void describe(ViewGroup group, String text) {
        group.setContentDescription(text);
        group.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (Build.VERSION.SDK_INT >= 28) group.setScreenReaderFocusable(true);
        for (int i = 0; i < group.getChildCount(); i++)
            group.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
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
