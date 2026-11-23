package com.rideledger;

import android.app.Activity;
import android.graphics.Rect;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/** Edits the rates for one on-screen settlement estimate. */
final class SettlementSheet {
    final String tripId;
    private final Activity activity;
    private final LedgerStore.Trip trip;
    private final Executor executor;
    private final List<String> shareScope;
    private final LinearLayout form, result;
    private final TableLayout rates;
    private final EditText target;
    private final LedgerStyle style;
    private final int rateWidthLimit;
    private final TextView conflict;
    private final Map<String, EditText> inputs = new LinkedHashMap<>();
    private LedgerSettlement settlement;
    private TextView error;
    private String targetCode = "";

    SettlementSheet(Activity activity, LedgerStore.Trip trip, LinearLayout form, LinearLayout fields, EditText target,
                    LedgerStyle style, LedgerSettlement settlement, boolean financialConflict,
                    Executor executor, List<String> shareScope) {
        tripId = trip.id;
        this.activity = activity;
        this.trip = trip;
        this.executor = executor;
        this.shareScope = shareScope;
        this.form = form;
        this.target = target;
        this.style = style;
        rateWidthLimit = Math.max(style.dp(56), fields.getLayoutParams().width * 2 / 5);
        this.settlement = settlement;
        conflict = style.text("有账目待核对，暂按当前显示版本计算", LedgerStyle.CAPTION, style.warning);
        conflict.setVisibility(financialConflict ? View.VISIBLE : View.GONE);
        fields.addView(conflict, new LinearLayout.LayoutParams(-1, -2));
        rates = new TableLayout(form.getContext());
        rates.setColumnStretchable(0, true);
        rates.setColumnShrinkable(1, true);
        fields.addView(rates, new LinearLayout.LayoutParams(-1, -2));
        result = column();
        result.setPadding(0, style.dp(LedgerStyle.SECTION_GAP), 0, 0);
        result.setVisibility(View.GONE);
        form.addView(result, new LinearLayout.LayoutParams(-1, -2));
        rebuildRates();
        onEdit(target, () -> {
            clearResult();
            rebuildRates();
        });
    }

    void update(LedgerReview review) {
        conflict.setVisibility(review.financialConflict ? View.VISIBLE : View.GONE);
        if (settlement.sameBalances(review.settlementAmounts)) return;
        boolean hadResult = result.getVisibility() == View.VISIBLE;
        settlement = review.settlementAmounts;
        clearResult();
        rebuildRates();
        if (hadResult) showError("待结算金额已更新，请重新计算");
    }

    void chooseTarget(View anchor) {
        Set<String> currencies = new LinkedHashSet<>();
        currencies.add("CNY");
        currencies.addAll(settlement.currencies());
        if (!targetCode.isEmpty()) currencies.add(targetCode);
        List<String> options = new ArrayList<>(currencies);
        style.showChoices(anchor, options, options.indexOf(targetCode), index -> {
            String code = options.get(index);
            target.setText(code);
            target.setSelection(code.length());
        });
    }

    void calculate(TextView error) {
        this.error = error;
        clearResult();
        String currency;
        Map<String, String> usedQuotes = quotes();
        List<LedgerSettlement.Debt> debts;
        try {
            currency = Money.code(target.getText().toString());
            debts = settlement.convert(currency, usedQuotes);
        }
        catch (IllegalArgumentException invalid) { showError(invalid.getMessage()); return; }

        TextView title = style.heading("结算结果", LedgerStyle.LABEL);
        title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        result.addView(title, new LinearLayout.LayoutParams(-1, -2));
        if (debts.isEmpty()) {
            result.addView(style.text("折算后无需转账", LedgerStyle.BODY, style.muted));
        }
        for (LedgerSettlement.Debt debt : debts) {
            TextView direction = style.text(debt.payer + " 应还 " + debt.recipient, LedgerStyle.LABEL, style.muted);
            direction.setPadding(0, style.dp(LedgerStyle.CONTROL_GAP), 0, 0);
            result.addView(direction);
            TextView amount = style.heading(debt.amounts.get(0), 20);
            result.addView(amount, new LinearLayout.LayoutParams(-1, -2));
        }
        if (shareScope != null) {
            Button share = style.button("分享结算单", LedgerStyle.Action.OUTLINE, null);
            LinearLayout.LayoutParams shareSize = new LinearLayout.LayoutParams(-2, -2);
            shareSize.topMargin = style.dp(LedgerStyle.CONTROL_GAP);
            result.addView(share, shareSize);
            share.setOnClickListener(view ->
                    SettlementShare.send(activity, executor, share, trip.name, currency, debts, usedQuotes, shareScope));
        }
        form.requestFocus();
        InputMethodManager keyboard = form.getContext().getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(form.getWindowToken(), 0);
        result.setVisibility(View.VISIBLE);
        title.post(() -> {
            Rect bounds = new Rect();
            title.getDrawingRect(bounds);
            title.requestRectangleOnScreen(bounds, false);
        });
    }

    private void rebuildRates() {
        String nextCode = CurrencyHistory.valid(target.getText().toString());
        Set<String> required = new LinkedHashSet<>(settlement.currencies());
        required.remove(nextCode);
        if (nextCode.isEmpty()) required.clear();
        if (nextCode.equals(targetCode) && required.equals(inputs.keySet())) return;
        Map<String, String> previous = nextCode.equals(targetCode) ? quotes() : Collections.emptyMap();
        targetCode = nextCode;
        rates.removeAllViews();
        inputs.clear();
        EditText prior = target;
        prior.setNextFocusForwardId(View.NO_ID);
        for (String source : required) {
            EditText input = rateRow(source, previous.getOrDefault(source, ""));
            inputs.put(source, input);
            FormInput.next(prior, input);
            prior = input;
        }
        FormInput.done(prior);
    }

    private EditText rateRow(String source, String previous) {
        TableRow row = new TableRow(form.getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);
        TextView caption = style.text("1 " + source + " =", LedgerStyle.BODY, style.title);
        caption.setSingleLine(true);
        caption.setPaddingRelative(0, 0, style.dp(LedgerStyle.CONTROL_GAP), 0);
        row.addView(caption, new TableRow.LayoutParams(-2, -2));
        EditText input = new EditText(form.getContext());
        input.setId(View.generateViewId());
        input.setContentDescription(source + " 兑 " + targetCode + " 的汇率");
        caption.setLabelFor(input.getId());
        input.setSingleLine(true);
        input.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setGravity(Gravity.CENTER);
        input.setHint("汇率");
        input.setText(previous);
        style.input(input);
        int digitsWidth = LedgerStyle.textWidth(input, "0.00000000");
        int inputWidth = Math.min(Math.max(style.dp(96), digitsWidth), rateWidthLimit);
        row.addView(input, new TableRow.LayoutParams(inputWidth, -2));
        TextView code = style.text(targetCode, LedgerStyle.LABEL, style.muted);
        code.setSingleLine(true);
        code.setPaddingRelative(style.dp(LedgerStyle.CONTROL_GAP), 0, 0, 0);
        row.addView(code, new TableRow.LayoutParams(-2, -2));
        rates.addView(row, new TableLayout.LayoutParams(-1, -2));
        onEdit(input, this::clearResult);
        return input;
    }

    private Map<String, String> quotes() {
        Map<String, String> values = new LinkedHashMap<>();
        inputs.forEach((code, input) -> values.put(code, input.getText().toString()));
        return values;
    }

    private void clearResult() {
        result.setVisibility(View.GONE);
        result.removeAllViews();
        if (error != null) error.setVisibility(View.GONE);
    }

    private void showError(String message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(form.getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private static void onEdit(EditText field, Runnable action) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { action.run(); }
        });
    }
}
