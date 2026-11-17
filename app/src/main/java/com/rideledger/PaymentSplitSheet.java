package com.rideledger;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Edits the payment selection locally until the user finishes the sheet. */
final class PaymentSplitSheet {
    static final class Result {
        final String payerId;
        final boolean personal;
        final LedgerActions.CustomSplit custom;

        Result(String payerId, boolean personal, LedgerActions.CustomSplit custom) {
            this.payerId = payerId;
            this.personal = personal;
            this.custom = custom;
        }
    }

    private static final String[] MODES = {"全员平摊", "仅付款人", "自定义分摊"};
    private final Activity activity;
    private final LedgerForms forms;
    private final LedgerStyle style;
    private final Consumer<Result> apply;
    private final Consumer<Exception> showError;
    private final String currencyCode;
    private final LedgerActions.CustomSplit originalCustom;
    private final LinearLayout content, customFields;
    private final Spinner payer;
    private final Button[] modes = new Button[MODES.length];
    private final TextView[] shareNames;
    private EditText[] shares;
    private LedgerStore.Trip trip;
    private String splitCurrency;
    private boolean personal;
    private int mode;
    private Dialog dialog;

    PaymentSplitSheet(Activity activity, LedgerForms forms, LedgerStore.Trip trip, String payerId,
                      boolean personal, LedgerActions.CustomSplit custom, String currencyCode,
                      Consumer<Result> apply, Consumer<Exception> showError) {
        this.activity = activity;
        this.forms = forms;
        this.trip = trip;
        this.personal = personal;
        this.originalCustom = custom;
        this.currencyCode = currencyCode;
        this.apply = apply;
        this.showError = showError;
        mode = custom != null ? 2 : personal ? 1 : 0;
        style = new LedgerStyle(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), dp(8));
        content.setFocusableInTouchMode(true);
        content.setDescendantFocusability(LinearLayout.FOCUS_BEFORE_DESCENDANTS);
        LinearLayout payerRow = choicesRow();
        payerRow.setMinimumHeight(dp(LedgerStyle.CONTROL_HEIGHT));
        TextView payerLabel = style.text("谁付款", LedgerStyle.LABEL, style.muted);
        payerRow.addView(payerLabel, new LinearLayout.LayoutParams(-2, -2));
        String initialPayer = trip.indexOf(payerId) < 0 ? trip.localPerson : payerId;
        payer = new Spinner(activity, Spinner.MODE_DROPDOWN);
        style.spinner(payer);
        payer.setId(View.generateViewId());
        payer.setContentDescription("付款人");
        payer.setBackground(style.ripple(null, LedgerStyle.CONTROL_RADIUS));
        payer.setMinimumHeight(dp(LedgerStyle.CONTROL_HEIGHT));
        payer.setPaddingRelative(dp(LedgerStyle.CONTROL_GAP), 0, 0, 0);
        payerLabel.setLabelFor(payer.getId());
        payerRow.addView(payer, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(payerRow, fullWidth(4));
        updatePayer(initialPayer);
        shareNames = new TextView[trip.people()];
        TextView splitLabel = caption("分摊规则");
        ((LinearLayout.LayoutParams) splitLabel.getLayoutParams()).topMargin = dp(8);
        LinearLayout modeRow = choicesRow();
        content.addView(modeRow, fullWidth(8));
        for (int i = 0; i < modes.length; i++) {
            int selection = i;
            Button option = choice(MODES[i], "分摊规则，" + MODES[i]);
            option.setOnClickListener(view -> {
                try {
                    if (selection == 2) ensureCustomFields();
                    else this.personal = selection == 1;
                    mode = selection;
                    updateSelection();
                } catch (IllegalArgumentException invalid) { showError.accept(invalid); }
            });
            addChoice(modeRow, option);
            modes[i] = option;
        }
        customFields = new LinearLayout(activity);
        customFields.setOrientation(LinearLayout.VERTICAL);
        content.addView(customFields, fullWidth(0));
        if (mode == 2) ensureCustomFields();
        updateSelection();
    }

    void show() {
        if (dialog != null && dialog.isShowing()) return;
        dialog = forms.showSheet("付款与分摊", null, content, "完成", (sheet, confirm, cancel, error) -> {
            try {
                LedgerActions.CustomSplit split = mode == 2 ? readSplit() : null;
                if (split != null && split.total == 0) throw new IllegalArgumentException("分摊合计必须大于零");
                String member = trip.member(payer.getSelectedItemPosition());
                apply.accept(new Result(member, personal, split));
                sheet.dismiss();
            } catch (Exception failure) {
                error.setText(failure.getMessage() == null ? "请检查付款与分摊设置" : failure.getMessage());
                error.setVisibility(View.VISIBLE);
            }
        });
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    void updateRoster(LedgerStore.Trip latest) {
        if (!trip.id.equals(latest.id)) return;
        String selected = trip.member(payer.getSelectedItemPosition());
        trip = latest;
        updatePayer(selected);
        for (int i = 0; i < shareNames.length; i++) {
            String member = trip.member(i);
            if (shareNames[i] != null) shareNames[i].setText(memberName(member));
            if (shares != null) shares[i].setContentDescription("分摊金额，" + memberName(member));
        }
    }

    private void updatePayer(String selected) {
        List<String> people = new ArrayList<>();
        for (int i = 0; i < trip.people(); i++) people.add(memberName(trip.member(i)));
        payer.setAdapter(new ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, people) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                return style.option(getItem(position), trip.people() > 1 ? "⌄" : "", false, false, true);
            }

            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                boolean checked = position == payer.getSelectedItemPosition();
                return style.option(getItem(position), checked ? "✓" : "", checked, true, false);
            }
        });
        payer.setSelection(Math.max(0, trip.indexOf(selected)));
        payer.setEnabled(trip.people() > 1);
    }

    private void ensureCustomFields() {
        if (shares != null) return;
        String code = CurrencyHistory.valid(currencyCode);
        if (code.isEmpty() && originalCustom != null) code = originalCustom.currency;
        splitCurrency = Money.code(code.isEmpty() ? currencyCode : code);
        String displayCurrency = splitCurrency;
        TextView caption = style.text("每个人的金额（" + displayCurrency + "）", LedgerStyle.LABEL, style.muted);
        customFields.addView(caption, fullWidth(8));
        shares = new EditText[trip.people()];
        for (int i = 0; i < shares.length; i++) {
            String member = trip.member(i);
            LinearLayout row = choicesRow();
            shares[i] = forms.compactAmount(row, memberName(member), "0", LedgerStyle.LABEL);
            shares[i].setContentDescription("分摊金额，" + memberName(member));
            shareNames[i] = (TextView) row.getChildAt(0);
            shareNames[i].setLayoutParams(new LinearLayout.LayoutParams(0, -2, 2));
            shares[i].setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            shares[i].setGravity(Gravity.END);
            if (originalCustom != null && displayCurrency.equals(originalCustom.currency))
                shares[i].setText(Money.amountText(originalCustom.share(member), displayCurrency));
            if (i > 0) FormInput.next(shares[i - 1], shares[i]);
            customFields.addView(row, fullWidth(4));
        }
        FormInput.done(shares[shares.length - 1]);
        TextView total = style.heading("", LedgerStyle.SECTION_TITLE);
        customFields.addView(total, fullWidth(4));
        Runnable update = () -> {
            try { total.setText("合计 " + Money.format(readSplit().total, displayCurrency)); }
            catch (Exception invalid) { total.setText(invalid.getMessage()); }
        };
        for (EditText input : shares) input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { update.run(); }
        });
        update.run();
    }

    private LedgerActions.CustomSplit readSplit() throws Exception {
        ensureCustomFields();
        String[] values = new String[shares.length];
        for (int i = 0; i < shares.length; i++) values[i] = shares[i].getText().toString();
        return LedgerActions.customSplit(trip, splitCurrency, values);
    }

    private void updateSelection() {
        for (int i = 0; i < modes.length; i++) selected(modes[i], i == mode);
        customFields.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);
    }

    private void selected(Button option, boolean selected) {
        option.setSelected(selected);
        option.setTypeface(selected ? LedgerStyle.MEDIUM : Typeface.DEFAULT);
    }

    private Button choice(String label, String description) {
        Button option = style.button(label, LedgerStyle.Action.OUTLINE, null);
        option.setTextSize(LedgerStyle.LABEL);
        option.setContentDescription(description);
        option.setIncludeFontPadding(false);
        option.setSingleLine(false);
        option.setEllipsize(null);
        option.setPadding(dp(6), dp(6), dp(6), dp(6));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_selected}, style.rounded(style.selected, 0, LedgerStyle.CONTROL_RADIUS));
        background.addState(new int[0], style.rounded(style.surface, style.stroke, LedgerStyle.CONTROL_RADIUS));
        option.setBackground(style.ripple(background, LedgerStyle.CONTROL_RADIUS));
        option.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_selected}, {}},
                new int[]{style.primary, style.muted}));
        return option;
    }

    private LinearLayout choicesRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);
        return row;
    }

    private void addChoice(LinearLayout row, Button choice) {
        LinearLayout.LayoutParams width = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) width.setMarginStart(dp(6));
        row.addView(choice, width);
    }

    private TextView caption(String value) {
        TextView title = style.text(value, LedgerStyle.LABEL, style.muted);
        content.addView(title, fullWidth(6));
        return title;
    }

    private String memberName(String member) {
        return trip.nameOf(member) + (member.equals(trip.localPerson) ? "（我）" : "");
    }

    private LinearLayout.LayoutParams fullWidth(int margin) {
        LinearLayout.LayoutParams width = new LinearLayout.LayoutParams(-1, -2);
        width.bottomMargin = dp(margin);
        return width;
    }

    private int dp(int value) { return style.dp(value); }
}
