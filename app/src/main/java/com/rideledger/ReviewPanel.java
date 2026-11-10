package com.rideledger;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;

/** Review UI renders a snapshot and reports actions. It never opens the database. */
final class ReviewPanel extends ListView {
    interface Actions {
        void filter(String key);
        void delete(List<String> ids);
        void edit(LedgerReview.Row row);
        void openAll();
        void reconnect();
    }

    private static final int PREVIEW_SIZE = 5;
    private static final int EMPTY_STATE_TEXT_SIZE = 15;
    private static final int EMPTY_STATE_TEXT_COLOR = Color.GRAY;
    private static final String[] KEYS = {"all", "shared", LedgerReview.CASH_FILTER, "exchange"};
    private static final String[] LABELS = {"全部", "共同支出", LedgerReview.CASH_LABEL, "换汇记录"};

    private final LedgerReview model;
    private final Actions actions;
    private final TextView count;
    final TextView syncStatus;
    private final ConnectionBadge connection;
    private final LinearLayout preview;
    private final List<TextView> chips = new ArrayList<>();
    private final List<LedgerReview.Row> filtered = new ArrayList<>();
    private long confirmedThrough;
    private RecordsOverlay records;
    ReviewPanel(Context context, String tripName, LedgerReview model, String initialFilter,
                long confirmedThrough, Actions actions) {
        super(context);
        this.model = model;
        this.actions = actions;
        this.confirmedThrough = confirmedThrough;
        setPadding(dp(18), 0, dp(18), 0);
        setDivider(null);
        setSelector(new ColorDrawable(Color.TRANSPARENT));
        LinearLayout header = column();
        header.setPadding(0, dp(18), 0, 0);
        LinearLayout ending = column();
        ending.setPadding(0, 0, 0, dp(30));
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
        TextView title = text(titleRow, tripName, 26, 0xff154b3c);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        connection = new ConnectionBadge(context, actions::reconnect);
        int availableWidth = dp(getResources().getConfiguration().screenWidthDp) - getPaddingLeft() - getPaddingRight();
        connection.setMaxWidth(Math.max(dp(60), availableWidth / 2));
        LinearLayout.LayoutParams badgeWidth = new LinearLayout.LayoutParams(-2, -2);
        badgeWidth.setMarginStart(dp(8));
        titleRow.addView(connection, badgeWidth);
        if (model.incomplete) text(header, "部分记录超出金额范围，合计不完整。", 14, 0xff9b4b25);
        LinearLayout spending = card(header, "总支出");
        text(spending, model.spending, model.hasSpending ? 20 : 15, model.hasSpending ? Color.BLACK : Color.GRAY);
        LinearLayout balances = card(header, "现金余额");
        for (String balance : model.balances) text(balances, balance, 16, Color.BLACK);
        LinearLayout history = card(header, "旅行记录");
        count = text(history, "", 13, Color.GRAY);
        buildFilters(history);
        preview = column();
        history.addView(preview, fullMargin());
        buildSettlement(card(ending, "小队结算"));
        syncStatus = text(ending, "", 13, Color.GRAY);
        header.addView(ending);
        selectFilter(initialFilter);
        addHeaderView(header, null, false);
        setAdapter(new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1));
    }

    void updateConnections(LedgerStore.Trip trip, SyncEngine.Status state) {
        connection.update(trip, state);
    }

    private void buildSettlement(LinearLayout card) {
        if (model.settlement.isEmpty()) {
            text(card, "目前已结清", EMPTY_STATE_TEXT_SIZE, EMPTY_STATE_TEXT_COLOR);
            return;
        }
        for (int i = 0; i < model.settlement.size(); i++) {
            if (i > 0) divider(card);
            LedgerReview.Debt debt = model.settlement.get(i);
            TextView direction = text(card, debt.payer + " → " + debt.recipient, 14, 0xff52665a);
            direction.setContentDescription(debt.payer + " 应还 " + debt.recipient);
            direction.setPadding(0, dp(10), 0, 0);
            ((LinearLayout.LayoutParams) direction.getLayoutParams()).bottomMargin = dp(2);
            String amounts = String.join("　", debt.amounts).replace(' ', '\u00a0');
            TextView amount = text(card, amounts, 17, 0xff154b3c);
            amount.setTypeface(amount.getTypeface(), Typeface.BOLD);
            amount.setPadding(0, 0, 0, dp(8));
        }
    }

    private void buildFilters(LinearLayout history) {
        LinearLayout filters = new LinearLayout(getContext());
        filters.setOrientation(LinearLayout.VERTICAL);
        filters.setPadding(0, dp(8), 0, dp(6));
        history.addView(filters, fullMargin());
        Configuration configuration = getResources().getConfiguration();
        int perRow = configuration.screenWidthDp >= 360 && configuration.fontScale <= 1.15f ? 4 : 2;
        LinearLayout row = null;
        for (int i = 0; i < KEYS.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(getContext());
                filters.addView(row, fullMargin());
            }
            String key = KEYS[i];
            TextView chip = new TextView(getContext());
            chip.setText(LABELS[i]);
            chip.setTextSize(14);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(2), dp(8), dp(2), dp(8));
            chip.setMinHeight(dp(44));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % perRow != perRow - 1) params.setMarginEnd(dp(5));
            row.addView(chip, params);
            chips.add(chip);
            chip.setOnClickListener(view -> {
                actions.filter(key);
                selectFilter(key);
            });
        }
    }

    private void selectFilter(String key) {
        boolean all = "all".equals(key);
        String label = "";
        for (int i = 0; i < chips.size(); i++) {
            boolean selected = KEYS[i].equals(key);
            TextView chip = chips.get(i);
            chip.setBackground(surface(selected ? 0xffe8f5eb : Color.WHITE, selected ? 0xffa8d1b2 : 0xffdce8df, 24));
            chip.setTextColor(selected ? 0xff176344 : 0xff607969);
            chip.setSelected(selected);
            if (selected) label = LABELS[i];
        }
        filtered.clear();
        for (LedgerReview.Row record : model.records) if (all || key.equals(record.filter)) filtered.add(record);
        preview.removeAllViews();
        count.setText(all ? filtered.size() + " 笔" : filtered.size() + " / " + model.records.size() + " 笔");
        if (filtered.isEmpty()) {
            text(preview, all ? "还没有记录" : "暂无" + label + "。可切换到“全部”查看完整流水。",
                    EMPTY_STATE_TEXT_SIZE, EMPTY_STATE_TEXT_COLOR);
        } else {
            int end = Math.min(PREVIEW_SIZE, filtered.size());
            for (int i = 0; i < end; i++) {
                RecordCard card = new RecordCard();
                preview.addView(card, fullMargin());
                bindRecord(card, filtered.get(i), true);
            }
        }
        if (!model.records.isEmpty())
            preview.addView(button(model.records.size() > PREVIEW_SIZE ? "显示更多" : "查看全部",
                    actions::openAll), fullMargin());
    }

    View allRecords(Runnable close, Runnable export) {
        records = new RecordsOverlay(close, export);
        return records;
    }

    boolean cancelRecordSelection() {
        return records != null && records.cancelSelection();
    }

    void recordsClosed() { records = null; }

    void restoreRecordsPosition(ReviewPanel previous) {
        if (records != null && previous.records != null) records.restore(previous.records);
    }

    private final class RecordsOverlay extends LinearLayout {
        private final ListView list;
        private final TextView title;
        private final ImageButton trash;
        private final Button exportButton, closeButton, cancel, delete;
        private final BaseAdapter adapter;
        private final Set<String> selected = new LinkedHashSet<>();
        private boolean selecting;
        private AlertDialog confirmation;

        RecordsOverlay(Runnable close, Runnable export) {
            super(ReviewPanel.this.getContext());
            setOrientation(VERTICAL);
            setBackgroundColor(Color.WHITE);
            setPadding(dp(16), dp(8), dp(16), 0);
            setClickable(true);
            if (android.os.Build.VERSION.SDK_INT >= 28) setAccessibilityPaneTitle("全部旅行记录");

            LinearLayout toolbar = new LinearLayout(getContext());
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            addView(toolbar, fullMargin());
            title = text(toolbar, "全部旅行记录", 18, 0xff154b3c);
            title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            trash = new ImageButton(getContext());
            trash.setImageResource(android.R.drawable.ic_menu_delete);
            trash.setColorFilter(0xffa53831);
            trash.setContentDescription("选择要删除的记录");
            trash.setOnClickListener(view -> {
                selecting = true;
                updateToolbar();
                adapterChanged();
            });
            TypedValue ripple = new TypedValue();
            if (getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true))
                trash.setBackgroundResource(ripple.resourceId);
            toolbar.addView(trash, new LinearLayout.LayoutParams(dp(44), dp(44)));
            exportButton = toolbarButton("导出", export);
            closeButton = toolbarButton("退出", close);
            cancel = toolbarButton("取消", this::cancelSelection);
            delete = toolbarButton("删除", this::confirmSelection);
            delete.setTextColor(0xffa53831);
            toolbar.addView(exportButton);
            toolbar.addView(closeButton);
            toolbar.addView(cancel);
            toolbar.addView(delete);
            text(this, model.records.size() + " 笔", 13, Color.GRAY);

            list = new ListView(getContext());
            list.setSelector(new ColorDrawable(Color.TRANSPARENT));
            list.setDivider(new ColorDrawable(Color.WHITE));
            list.setDividerHeight(dp(4));
            list.setItemsCanFocus(true);
            adapter = new BaseAdapter() {
                @Override public int getCount() { return model.records.size(); }
                @Override public LedgerReview.Row getItem(int position) { return model.records.get(position); }
                @Override public long getItemId(int position) { return position; }
                // The card and checkbox own their touch targets.
                @Override public boolean areAllItemsEnabled() { return false; }
                @Override public boolean isEnabled(int position) { return false; }
                @Override public View getView(int position, View convertView, ViewGroup parent) {
                    LinearLayout row = (LinearLayout) convertView;
                    if (row == null) row = newSelectableRecord();
                    CheckBox box = (CheckBox) row.getChildAt(0);
                    RecordCard card = (RecordCard) row.getChildAt(1);
                    LedgerReview.Row record = getItem(position);
                    bindRecord(card, record, !selecting);
                    box.setOnCheckedChangeListener(null);
                    box.setChecked(selected.contains(record.id));
                    box.setVisibility(selecting ? VISIBLE : GONE);
                    box.setContentDescription("选择这笔记录：" + record.label);
                    box.setOnCheckedChangeListener((view, checked) -> {
                        if (checked) selected.add(record.id);
                        else selected.remove(record.id);
                        updateToolbar();
                    });
                    return row;
                }
            };
            list.setAdapter(adapter);
            addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            updateToolbar();
        }

        private void updateToolbar() {
            title.setText(selecting ? "已选 " + selected.size() + " 笔" : "全部旅行记录");
            trash.setVisibility(!selecting && !model.records.isEmpty() ? VISIBLE : GONE);
            exportButton.setVisibility(selecting ? GONE : VISIBLE);
            closeButton.setVisibility(selecting ? GONE : VISIBLE);
            cancel.setVisibility(selecting ? VISIBLE : GONE);
            delete.setVisibility(selecting ? VISIBLE : GONE);
            delete.setEnabled(!selected.isEmpty());
            delete.setAlpha(delete.isEnabled() ? 1f : 0.45f);
        }

        private void adapterChanged() { adapter.notifyDataSetChanged(); }

        boolean cancelSelection() {
            if (!selecting) return false;
            selecting = false;
            selected.clear();
            updateToolbar();
            adapterChanged();
            return true;
        }

        private void confirmSelection() {
            if (selected.isEmpty()) return;
            List<String> ids = new ArrayList<>(selected);
            confirmation = new AlertDialog.Builder(getContext()).setTitle("删除所选记录？")
                    .setMessage("将删除 " + ids.size() + " 笔记录。删除会在下次与队友同步时传到对方的账本。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认删除", (dialog, which) -> actions.delete(ids)).show();
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xffa53831);
        }

        void restore(RecordsOverlay previous) {
            if (previous.selecting && !model.records.isEmpty()) {
                selecting = true;
                for (LedgerReview.Row record : model.records)
                    if (previous.selected.contains(record.id)) selected.add(record.id);
                updateToolbar();
                adapterChanged();
            }
            int position = previous.list.getFirstVisiblePosition();
            View first = previous.list.getChildAt(0);
            if (position == 0 || first == null || position >= previous.list.getCount()) return;
            LedgerReview.Row anchor = (LedgerReview.Row) previous.list.getItemAtPosition(position);
            for (int i = 0; i < model.records.size(); i++) {
                if (model.records.get(i).id.equals(anchor.id)) {
                    list.setSelectionFromTop(i, first.getTop());
                    return;
                }
            }
            if (!model.records.isEmpty()) list.setSelectionFromTop(Math.min(position, model.records.size() - 1), first.getTop());
        }

        @Override protected void onDetachedFromWindow() {
            if (confirmation != null) confirmation.dismiss();
            super.onDetachedFromWindow();
        }
    }

    void confirmThrough(long row) {
        if (row <= confirmedThrough) return;
        long previous = confirmedThrough;
        confirmedThrough = row;
        if (records != null) records.list.invalidateViews();
        for (int i = 0; i < preview.getChildCount(); i++) {
            View view = preview.getChildAt(i);
            if (view != null && view.getTag() instanceof LedgerReview.Row) {
                LedgerReview.Row record = (LedgerReview.Row) view.getTag();
                if (record.localRow > previous && record.localRow <= row) styleRecord(view, record);
            }
        }
    }

    private void styleRecord(View item, LedgerReview.Row record) {
        boolean pending = record.localRow > confirmedThrough;
        item.setBackground(surface(pending ? 0xffe9ebed : 0xfff4f8f5, 0, 8));
        item.setContentDescription(record.label + (pending ? "，待同步" : ""));
    }

    private final class RecordCard extends LinearLayout {
        final TextView type, time, details, arrow;

        RecordCard() {
            super(ReviewPanel.this.getContext());
            setOrientation(VERTICAL);
            setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout meta = new LinearLayout(getContext());
            meta.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams metaSize = new LinearLayout.LayoutParams(-1, -2);
            metaSize.bottomMargin = dp(3);
            addView(meta, metaSize);
            type = new TextView(getContext());
            type.setTextSize(12);
            type.setTypeface(type.getTypeface(), Typeface.BOLD);
            type.setTextColor(0xff28634e);
            LinearLayout.LayoutParams typeSize = new LinearLayout.LayoutParams(0, -2, 1);
            typeSize.setMarginEnd(dp(8));
            meta.addView(type, typeSize);
            time = new TextView(getContext());
            time.setTextSize(12);
            time.setTextColor(0xff586b61);
            time.setGravity(Gravity.END);
            meta.addView(time, new LinearLayout.LayoutParams(-2, -2));
            arrow = new TextView(getContext());
            arrow.setText("›");
            arrow.setTextSize(16);
            arrow.setIncludeFontPadding(false);
            arrow.setTextColor(0xff426d5b);
            LinearLayout.LayoutParams arrowSize = new LinearLayout.LayoutParams(-2, -2);
            arrowSize.setMarginStart(dp(8));
            meta.addView(arrow, arrowSize);
            details = new TextView(getContext());
            details.setTextSize(15);
            details.setLineSpacing(dp(2), 1f);
            addView(details, new LinearLayout.LayoutParams(-1, -2));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            meta.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            details.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                int radius = dp(8);
                float dx = Math.max(0, radius - Math.min(event.getX(), getWidth() - event.getX()));
                float dy = Math.max(0, radius - Math.min(event.getY(), getHeight() - event.getY()));
                if (dx * dx + dy * dy > radius * radius) return false;
            }
            return super.onTouchEvent(event);
        }
    }

    private void bindRecord(RecordCard card, LedgerReview.Row record, boolean editing) {
        card.type.setText(record.type);
        card.time.setText(record.time);
        CharSequence body = recordText(record);
        card.details.setText(body);
        card.details.setVisibility(body.length() == 0 ? GONE : VISIBLE);
        card.setTag(record);
        boolean editable = editing && LedgerRules.editable(record.event.kind);
        card.arrow.setVisibility(editable ? VISIBLE : GONE);
        card.setOnClickListener(editable ? view -> actions.edit(record) : null);
        card.setClickable(editable);
        card.setFocusable(editable);
        card.setForeground(editable ? new RippleDrawable(ColorStateList.valueOf(0x26176344), null,
                surface(Color.WHITE, 0, 8)) : null);
        styleRecord(card, record);
    }

    private static CharSequence recordText(LedgerReview.Row record) {
        SpannableStringBuilder line = new SpannableStringBuilder();
        if (!record.amount.isEmpty()) {
            appendAmount(line, record.amount);
            if (!record.received.isEmpty()) {
                append(line, "  →\u00a0", 15, 0xff607969, false);
                appendAmount(line, record.received);
            }
        }
        if (!record.people.isEmpty()) {
            if (line.length() > 0) line.append('\n');
            append(line, record.people, 14, 0xff293f36, false);
        }
        if (!record.split.isEmpty()) {
            if (line.length() > 0) line.append('\n');
            append(line, "分摊  ", 12, 0xff586b61, true);
            append(line, record.split, 13, 0xff43594e, false);
        }
        if (!record.note.isEmpty()) {
            if (line.length() > 0) line.append('\n');
            append(line, record.note, 13, Color.BLACK, false);
        }
        return line;
    }

    private static void appendAmount(SpannableStringBuilder line, String amount) {
        int currency = amount.lastIndexOf(' ');
        if (currency < 0) {
            append(line, amount, 19, 0xff154b3c, true);
            return;
        }
        append(line, amount.substring(0, currency), 19, 0xff154b3c, true);
        append(line, "\u00a0" + amount.substring(currency + 1), 12, 0xff52665a, false);
    }

    private static void append(SpannableStringBuilder line, String value, int size, int color, boolean bold) {
        int start = line.length();
        line.append(value);
        int end = line.length();
        line.setSpan(new RelativeSizeSpan(size / 15f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) line.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private LinearLayout newSelectableRecord() {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipToOutline(true);
        CheckBox box = new CheckBox(getContext());
        box.setGravity(Gravity.CENTER);
        row.addView(box, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.addView(new RecordCard(), new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(10), dp(16), dp(8));
        card.setBackground(surface(Color.WHITE, 0xffdce6df, 16));
        LinearLayout.LayoutParams params = fullMargin();
        params.bottomMargin = dp(14);
        parent.addView(card, params);
        TextView label = text(card, title, 18, 0xff154b3c);
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        divider(card);
        return card;
    }

    private void divider(LinearLayout card) {
        View divider = new View(getContext());
        divider.setBackgroundColor(0xffe6eee8);
        card.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private GradientDrawable surface(int fill, int stroke, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        return background;
    }

    private TextView text(LinearLayout parent, String label, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view, fullMargin());
        return view;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private Button toolbarButton(String label, Runnable action) {
        Button button = button(label, action);
        button.setMinWidth(dp(54));
        button.setMinimumWidth(dp(54));
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private LinearLayout.LayoutParams fullMargin() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(4);
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
