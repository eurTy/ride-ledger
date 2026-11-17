package com.rideledger;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.List;

/** Review UI renders a snapshot and reports actions. It never opens the database. */
final class ReviewPanel extends ListView {
    interface Actions {
        void filter(String key);
        void delete(List<String> ids);
        void edit(LedgerReview.Row row);
        void thumbnail(LedgerReview.Row row, ImageView view);
        void openReceipt(LedgerReview.Row row);
        void openAll();
        void reconnect();
    }

    private static final int PREVIEW_SIZE = 5;
    private static final int EMPTY_STATE_TEXT_SIZE = 15;
    private static final int RECORD_RADIUS = 8;
    private static final String[] KEYS = {"all", "shared", LedgerReview.CASH_FILTER, "exchange"};
    private static final String[] LABELS = {"全部", "共同支出", LedgerReview.CASH_LABEL, "换汇记录"};

    private final LedgerReview model;
    private final boolean editable;
    private final LedgerStyle style;
    private final SimpleDateFormat checkTime = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
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
                long confirmedThrough, boolean editable, Actions actions) {
        super(context);
        style = new LedgerStyle(context);
        this.model = model;
        this.editable = editable;
        this.actions = actions;
        this.confirmedThrough = confirmedThrough;
        setPadding(dp(LedgerStyle.PAGE_INSET), 0, dp(LedgerStyle.PAGE_INSET), 0);
        setDivider(null);
        setSelector(new ColorDrawable(Color.TRANSPARENT));
        LinearLayout header = column();
        header.setPadding(0, dp(18), 0, 0);
        LinearLayout ending = column();
        ending.setPadding(0, 0, 0, dp(30));
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
        TextView title = style.heading(tripName, LedgerStyle.PAGE_TITLE);
        title.setPadding(0, dp(LedgerStyle.CONTROL_GAP), 0, dp(LedgerStyle.CONTROL_GAP));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        connection = new ConnectionBadge(context, actions::reconnect);
        int availableWidth = dp(getResources().getConfiguration().screenWidthDp) - getPaddingLeft() - getPaddingRight();
        connection.setMaxWidth(Math.max(dp(60), availableWidth / 2));
        LinearLayout.LayoutParams badgeWidth = new LinearLayout.LayoutParams(-2, -2);
        badgeWidth.setMarginStart(dp(8));
        titleRow.addView(connection, badgeWidth);
        if (model.incomplete) text(header, "部分记录超出金额范围，合计不完整。", LedgerStyle.LABEL, style.warning);
        LinearLayout spending = card(header, "总支出", R.drawable.ic_ledger_receipt);
        TextView total = text(spending, model.spending, model.hasSpending ? 20 : LedgerStyle.BODY,
                model.hasSpending ? style.title : style.muted);
        if (model.hasSpending) total.setTypeface(LedgerStyle.MEDIUM);
        LinearLayout balances = card(header, "现金余额", R.drawable.ic_ledger_balance);
        for (String balance : model.balances) text(balances, balance, 16, style.ink);
        LinearLayout history = card(header, "旅行记录", R.drawable.ic_ledger_book);
        count = text(history, "", LedgerStyle.CAPTION, style.muted);
        buildFilters(history);
        preview = column();
        history.addView(preview, fullMargin());
        buildSettlement(card(ending, "小队结算", R.drawable.ic_ledger_reconcile));
        syncStatus = text(ending, "", LedgerStyle.CAPTION, style.muted);
        header.addView(ending);
        selectFilter(initialFilter);
        addHeaderView(header, null, false);
        setAdapter(new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1));
    }

    void updateSync(LedgerStore.Trip trip, SyncEngine.Status state, long lastCheckedAt) {
        connection.update(trip, state);
        StringBuilder status = new StringBuilder("本机已保存");
        boolean teamTrip = trip.people() > 1;
        if (teamTrip) {
            if (state.phase == SyncEngine.Phase.SYNCING) status.append("\n正在与同伴核对");
            if (state.pending > 0) status.append("\n").append("待同伴确认 ").append(state.pending).append(" 条");
        }
        if (model.pendingReceipts > 0) status.append("\n小票待补传 ").append(model.pendingReceipts).append(" 张");
        if (teamTrip) {
            if (lastCheckedAt > 0) status.append("\n上次与同伴核对  ").append(checkTime.format(new Date(lastCheckedAt)));
            else status.append("\n暂无同伴核对时间");
        }
        if (!status.toString().contentEquals(syncStatus.getText())) syncStatus.setText(status.toString());
    }

    private void buildSettlement(LinearLayout card) {
        if (model.records.stream().anyMatch(row -> !row.event.financialOptions.isEmpty()))
            text(card, "有账目待核对，结算暂按当前显示版本计算", LedgerStyle.CAPTION, style.warning);
        if (model.settlement.isEmpty()) {
            text(card, "目前已结清", EMPTY_STATE_TEXT_SIZE, style.muted);
            return;
        }
        for (int i = 0; i < model.settlement.size(); i++) {
            if (i > 0) divider(card);
            LedgerReview.Debt debt = model.settlement.get(i);
            TextView direction = text(card, debt.payer + " → " + debt.recipient, LedgerStyle.LABEL, style.muted);
            direction.setContentDescription(debt.payer + " 应还 " + debt.recipient);
            direction.setPadding(0, dp(10), 0, 0);
            ((LinearLayout.LayoutParams) direction.getLayoutParams()).bottomMargin = dp(2);
            String amounts = String.join("　", debt.amounts).replace(' ', '\u00a0');
            TextView amount = text(card, amounts, 17, style.title);
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
            chip.setTextSize(LedgerStyle.LABEL);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(2), dp(8), dp(2), dp(8));
            chip.setMinHeight(dp(LedgerStyle.COMPACT_HEIGHT));
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
            chip.setBackground(style.ripple(style.rounded(selected ? style.selected : style.surface,
                    style.stroke, LedgerStyle.CONTROL_RADIUS), LedgerStyle.CONTROL_RADIUS));
            chip.setTextColor(selected ? style.primary : style.muted);
            chip.setSelected(selected);
            if (selected) label = LABELS[i];
        }
        filtered.clear();
        for (LedgerReview.Row record : model.records) if (all || key.equals(record.filter)) filtered.add(record);
        preview.removeAllViews();
        count.setText(all ? filtered.size() + " 笔" : filtered.size() + " / " + model.records.size() + " 笔");
        if (filtered.isEmpty()) {
            text(preview, all ? "还没有记录" : "暂无" + label + "。可切换到“全部”查看完整流水。",
                    EMPTY_STATE_TEXT_SIZE, style.muted);
        } else {
            int end = Math.min(PREVIEW_SIZE, filtered.size());
            for (int i = 0; i < end; i++) {
                RecordCard card = new RecordCard(false);
                preview.addView(card, fullMargin());
                bindRecord(card, filtered.get(i), true);
            }
        }
        if (!model.records.isEmpty())
            preview.addView(style.button(model.records.size() > PREVIEW_SIZE ? "显示更多" : "查看全部",
                    LedgerStyle.Action.TINT, actions::openAll), fullMargin());
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
            setBackgroundColor(style.surface);
            setPadding(dp(LedgerStyle.PAGE_INSET), dp(LedgerStyle.ITEM_GAP), dp(LedgerStyle.PAGE_INSET), 0);
            setClickable(true);
            if (android.os.Build.VERSION.SDK_INT >= 28) setAccessibilityPaneTitle("全部旅行记录");

            title = style.heading("全部旅行记录", 16);
            LinearLayout controls = new LinearLayout(getContext());
            controls.setGravity(Gravity.CENTER_VERTICAL);
            trash = new ImageButton(getContext());
            trash.setImageResource(android.R.drawable.ic_menu_delete);
            trash.setColorFilter(style.danger);
            trash.setContentDescription("选择要删除的记录");
            trash.setOnClickListener(view -> {
                selecting = true;
                updateToolbar();
                adapterChanged();
            });
            trash.setBackground(style.ripple(null, LedgerStyle.CONTROL_RADIUS));
            trash.setPadding(dp(10), dp(10), dp(10), dp(10));
            trash.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int target = dp(LedgerStyle.COMPACT_HEIGHT);
            controls.addView(trash, new LinearLayout.LayoutParams(target, target));
            exportButton = recordAction("日志导出", LedgerStyle.Action.QUIET, export);
            closeButton = recordAction("退出", LedgerStyle.Action.QUIET, close);
            cancel = recordAction("取消", LedgerStyle.Action.QUIET, this::cancelSelection);
            delete = recordAction("删除", LedgerStyle.Action.DANGER, this::confirmSelection);
            controls.addView(exportButton);
            controls.addView(closeButton);
            controls.addView(cancel);
            controls.addView(delete);
            addView(recordsToolbar(controls), new LinearLayout.LayoutParams(-1, -2));
            TextView total = style.text(model.records.size() + " 笔", 11, style.muted);
            total.setPadding(0, 0, 0, dp(5));
            addView(total, new LinearLayout.LayoutParams(-1, -2));

            list = new ListView(getContext());
            list.setSelector(new ColorDrawable(Color.TRANSPARENT));
            list.setDivider(new ColorDrawable(style.surface));
            list.setDividerHeight(dp(3));
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

        private LinearLayout recordsToolbar(LinearLayout controls) {
            LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(0, -2, 1);
            int gap = dp(LedgerStyle.ITEM_GAP);
            LinearLayout toolbar = new LinearLayout(getContext()) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    title.measure(0, 0);
                    controls.measure(0, 0);
                    int needed = title.getMeasuredWidth() + gap + controls.getMeasuredWidth();
                    boolean stack = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
                            && MeasureSpec.getSize(widthSpec) < needed;
                    setOrientation(stack ? VERTICAL : HORIZONTAL);
                    titleSize.width = stack ? -1 : 0;
                    titleSize.weight = stack ? 0 : 1;
                    titleSize.setMarginEnd(stack ? 0 : gap);
                    super.onMeasure(widthSpec, heightSpec);
                }
            };
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            toolbar.addView(title, titleSize);
            LinearLayout.LayoutParams controlsSize = new LinearLayout.LayoutParams(-2, -2);
            controlsSize.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            toolbar.addView(controls, controlsSize);
            return toolbar;
        }

        private Button recordAction(String label, LedgerStyle.Action action, Runnable click) {
            Button button = style.button(label, action, click);
            int target = dp(LedgerStyle.COMPACT_HEIGHT);
            button.setTextSize(LedgerStyle.CAPTION);
            button.setMinWidth(target);
            button.setMinHeight(target);
            button.setMinimumHeight(target);
            button.setPadding(dp(6), dp(4), dp(6), dp(4));
            return button;
        }

        private void updateToolbar() {
            title.setText(selecting ? "已选 " + selected.size() + " 笔" : "全部旅行记录");
            trash.setVisibility(editable && !selecting && !model.records.isEmpty() ? VISIBLE : GONE);
            exportButton.setVisibility(selecting ? GONE : VISIBLE);
            closeButton.setVisibility(selecting ? GONE : VISIBLE);
            cancel.setVisibility(selecting ? VISIBLE : GONE);
            delete.setVisibility(selecting ? VISIBLE : GONE);
            delete.setEnabled(!selected.isEmpty());
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
            style.alertActions(confirmation, LedgerStyle.Action.DANGER);
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
        item.setBackground(style.rounded(pending ? style.pendingRecord : style.confirmedRecord, 0, RECORD_RADIUS));
        item.setContentDescription(record.label + (record.event.conflicted() ? "，有并发修改" : "") +
                (pending ? "，待同步" : ""));
    }

    private final class RecordCard extends LinearLayout {
        final boolean compact;
        final TextView type, time, details, arrow, note, receiptStatus;
        final LinearLayout receipt;
        final ImageView thumbnail;

        RecordCard(boolean compact) {
            super(ReviewPanel.this.getContext());
            this.compact = compact;
            setOrientation(VERTICAL);
            int horizontalPadding = dp(compact ? 10 : 12);
            int verticalPadding = dp(compact ? 6 : 10);
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
            LinearLayout meta = new LinearLayout(getContext());
            meta.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams metaSize = new LinearLayout.LayoutParams(-1, -2);
            metaSize.bottomMargin = dp(compact ? 2 : 3);
            addView(meta, metaSize);
            type = new TextView(getContext());
            type.setTextSize(compact ? 11 : 12);
            type.setIncludeFontPadding(!compact);
            type.setTypeface(type.getTypeface(), Typeface.BOLD);
            type.setTextColor(style.primary);
            LinearLayout.LayoutParams typeSize = new LinearLayout.LayoutParams(0, -2, 1);
            typeSize.setMarginEnd(dp(8));
            meta.addView(type, typeSize);
            time = new TextView(getContext());
            time.setTextSize(compact ? 11 : 12);
            time.setIncludeFontPadding(!compact);
            time.setTextColor(style.muted);
            time.setGravity(Gravity.END);
            meta.addView(time, new LinearLayout.LayoutParams(-2, -2));
            arrow = new TextView(getContext());
            arrow.setText("›");
            arrow.setTextSize(compact ? 14 : 16);
            arrow.setIncludeFontPadding(false);
            arrow.setTextColor(style.muted);
            LinearLayout.LayoutParams arrowSize = new LinearLayout.LayoutParams(-2, -2);
            arrowSize.setMarginStart(dp(8));
            meta.addView(arrow, arrowSize);
            LinearLayout body = new LinearLayout(getContext());
            body.setGravity(Gravity.CENTER_VERTICAL);
            addView(body, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout copy = column();
            body.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            details = new TextView(getContext());
            details.setTextSize(15);
            details.setIncludeFontPadding(!compact);
            details.setLineSpacing(compact ? 0 : dp(2), 1f);
            copy.addView(details, new LinearLayout.LayoutParams(-1, -2));
            note = new TextView(getContext());
            note.setTextSize(compact ? 12 : 13);
            note.setIncludeFontPadding(!compact);
            note.setTextColor(Color.BLACK);
            note.setMaxLines(2);
            note.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(note, new LinearLayout.LayoutParams(-1, -2));
            receipt = column();
            receipt.setGravity(Gravity.CENTER);
            receipt.setMinimumWidth(dp(48));
            receipt.setMinimumHeight(dp(48));
            LinearLayout.LayoutParams receiptSize = new LinearLayout.LayoutParams(-2, -2);
            receiptSize.setMarginStart(dp(compact ? 8 : 10));
            body.addView(receipt, receiptSize);
            thumbnail = new ImageView(getContext());
            thumbnail.setBackground(style.rounded(style.stroke, 0, 7));
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setClipToOutline(true);
            receipt.addView(thumbnail, new LinearLayout.LayoutParams(dp(36), dp(36)));
            receiptStatus = new TextView(getContext());
            receiptStatus.setText("图片待同步");
            receiptStatus.setTextSize(10);
            receiptStatus.setTextColor(style.muted);
            receiptStatus.setGravity(Gravity.CENTER);
            receipt.addView(receiptStatus, new LinearLayout.LayoutParams(-2, -2));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            meta.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            copy.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                int radius = dp(RECORD_RADIUS);
                float dx = Math.max(0, radius - Math.min(event.getX(), getWidth() - event.getX()));
                float dy = Math.max(0, radius - Math.min(event.getY(), getHeight() - event.getY()));
                if (dx * dx + dy * dy > radius * radius) return false;
            }
            return super.onTouchEvent(event);
        }
    }

    private void bindRecord(RecordCard card, LedgerReview.Row record, boolean actionsEnabled) {
        card.type.setText(record.type);
        card.time.setText(record.time);
        CharSequence body = recordText(record, card.compact);
        card.details.setText(body);
        card.details.setVisibility(body.length() == 0 ? GONE : VISIBLE);
        card.note.setText(ExpenseCategory.visibleNote(record.note));
        card.note.setVisibility(record.note.isEmpty() ? GONE : VISIBLE);
        card.receipt.setVisibility(record.receipt == null ? GONE : VISIBLE);
        card.thumbnail.setTag(null);
        boolean canViewReceipt = actionsEnabled && record.receipt != null;
        card.receipt.setOnClickListener(canViewReceipt ? view -> actions.openReceipt(record) : null);
        card.receipt.setClickable(canViewReceipt);
        card.receipt.setFocusable(canViewReceipt);
        if (record.receipt != null) {
            card.receiptStatus.setVisibility(record.receiptPending ? VISIBLE : GONE);
            card.receipt.setContentDescription(record.receipt.available ? "查看小票" : "小票图片待同步");
            actions.thumbnail(record, card.thumbnail);
        }
        card.setTag(record);
        boolean canEdit = actionsEnabled && editable && LedgerRules.editable(record.event.kind);
        card.arrow.setVisibility(canEdit ? VISIBLE : GONE);
        card.setOnClickListener(canEdit ? view -> actions.edit(record) : null);
        card.setClickable(canEdit);
        card.setFocusable(canEdit);
        card.setForeground(canEdit ? style.ripple(null, RECORD_RADIUS) : null);
        styleRecord(card, record);
    }

    private CharSequence recordText(LedgerReview.Row record, boolean compact) {
        SpannableStringBuilder line = new SpannableStringBuilder();
        if (!record.amount.isEmpty()) {
            appendAmount(line, record.amount, compact);
            if (!record.received.isEmpty()) {
                append(line, "  →\u00a0", compact ? 13 : 15, style.muted, false);
                appendAmount(line, record.received, compact);
            }
        }
        if (!record.people.isEmpty()) {
            if (line.length() > 0) line.append('\n');
            append(line, record.people, compact ? 12 : 14, style.ink, false);
        }
        if (!record.split.isEmpty()) {
            if (line.length() > 0) line.append('\n');
            append(line, "分摊  ", compact ? 11 : 12, style.muted, true);
            append(line, record.split, compact ? 12 : 13, style.muted, false);
        }
        if (record.event.conflicted()) {
            if (line.length() > 0) line.append('\n');
            append(line, "有并发修改 · 点开核对", compact ? 12 : 13, style.warning, true);
        }
        return line;
    }

    private void appendAmount(SpannableStringBuilder line, String amount, boolean compact) {
        int size = compact ? 16 : 19;
        int currency = amount.lastIndexOf(' ');
        if (currency < 0) {
            append(line, amount, size, style.title, true);
            return;
        }
        append(line, amount.substring(0, currency), size, style.title, true);
        append(line, "\u00a0" + amount.substring(currency + 1), compact ? 11 : 12, style.muted, false);
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
        row.addView(new RecordCard(true), new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout card(LinearLayout parent, String title, int icon) {
        LinearLayout card = column();
        int inset = dp(LedgerStyle.CARD_INSET);
        card.setPadding(inset, dp(10), inset, dp(8));
        card.setBackground(style.card());
        LinearLayout.LayoutParams params = fullMargin();
        params.bottomMargin = dp(LedgerStyle.SECTION_GAP);
        parent.addView(card, params);
        TextView label = style.heading(title, LedgerStyle.SECTION_TITLE);
        label.setPadding(0, dp(8), 0, dp(8));
        card.addView(label, fullMargin());
        LedgerIcon.attach(label, icon);
        divider(card);
        return card;
    }

    private void divider(LinearLayout card) {
        View divider = new View(getContext());
        divider.setBackgroundColor(style.divider);
        card.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private TextView text(LinearLayout parent, String label, int size, int color) {
        TextView view = style.text(label, size, color);
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view, fullMargin());
        return view;
    }

    private LinearLayout.LayoutParams fullMargin() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(LedgerStyle.ITEM_GAP);
        return params;
    }

    private int dp(int value) { return style.dp(value); }
}
