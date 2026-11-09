package com.rideledger;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Renders cash and split forms. LedgerActions is the only write entry point. */
final class LedgerForms {
    private final Activity activity;
    private final LedgerActions ledgerActions;
    private final CurrencyHistory currencyHistory;
    private final Executor readExecutor;
    private final Consumer<LedgerStore.Trip> saved;
    private final Consumer<Exception> showError;
    private int currencyMenuRequest;
    private AlertDialog activeForm;
    private PopupWindow historyPopup;

    LedgerForms(Activity activity, LedgerActions ledgerActions, CurrencyHistory currencyHistory,
                Executor readExecutor, Consumer<LedgerStore.Trip> saved, Consumer<Exception> showError) {
        this.activity = activity;
        this.ledgerActions = ledgerActions;
        this.currencyHistory = currencyHistory;
        this.readExecutor = readExecutor;
        this.saved = saved;
        this.showError = showError;
    }

    void showCustomSplit(LedgerStore.Trip trip, String code,
                                 LedgerActions.CustomSplit draft, Consumer<LedgerActions.CustomSplit> apply) {
        LinearLayout form = form();
        text(form, "填写每人的分摊金额（" + code + "），空白按 0 计算", 14, Color.DKGRAY);
        EditText[] inputs = new EditText[trip.people()];
        for (int i = 0; i < inputs.length; i++) {
            String member = trip.member(i);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            inputs[i] = compactAmount(row, personChoiceLabel(trip, member), "0");
            row.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(0, -2, 2));
            inputs[i].setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            form.addView(row, fullMargin());
            if (draft != null && code.equals(draft.currency))
                inputs[i].setText(Money.amountText(draft.share(member), code));
            if (i > 0) FormInput.next(inputs[i - 1], inputs[i]);
        }
        FormInput.done(inputs[inputs.length - 1]);
        TextView total = text(form, "", 16, 0xff154b3c);
        Runnable updateTotal = () -> {
            try { total.setText("合计 " + Money.format(readSplit(trip, code, inputs).total, code)); }
            catch (Exception error) { total.setText(error.getMessage()); }
        };
        for (EditText input : inputs) input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) { updateTotal.run(); }
        });
        updateTotal.run();
        showForm("自定义分摊", form, "应用分摊", () -> {
            LedgerActions.CustomSplit split = readSplit(trip, code, inputs);
            if (split.total == 0) throw new IllegalArgumentException("分摊合计必须大于零");
            apply.accept(split);
        });
    }

    private LedgerActions.CustomSplit readSplit(LedgerStore.Trip trip, String code, EditText[] inputs) throws Exception {
        String[] values = new String[inputs.length];
        for (int i = 0; i < inputs.length; i++) values[i] = inputs[i].getText().toString();
        return LedgerActions.customSplit(trip, code, values);
    }

    EditText quickField(LinearLayout form, String label, String hint, boolean decimal) {
        int labelIndex = form.getChildCount();
        EditText input = field(form, label, hint, decimal);
        form.getChildAt(labelIndex).setPadding(0, dp(3), 0, 0);
        return input;
    }

    void moneyIn(LedgerStore.Trip trip) {
        LinearLayout form = form();
        Spinner person = personChoice(form, trip, "现金持有人");
        LinearLayout amountBox = new LinearLayout(activity);
        LinearLayout currencyBox = amountColumns(form, amountBox);
        EditText amount = compactAmount(amountBox, "金额", "例如：1000");
        EditText currency = currencyField(currencyBox, trip.id, "货币代码", currencyHistory.quickDefault(trip.id), false);
        FormInput.next(amount, currency);
        FormInput.done(currency);
        showForm(LedgerReview.CASH_LABEL, form, () -> {
            String code = Money.code(currency.getText().toString());
            ledgerActions.cash(trip, trip.member(person.getSelectedItemPosition()), code, amount.getText().toString());
            currencyHistory.remember(trip.id, code, code);
            saved.accept(trip);
        });
    }

    void transfer(LedgerStore.Trip trip) {
        LinearLayout form = form();
        Spinner from = personChoice(form, trip, "付款人");
        Spinner to = personChoice(form, trip, "收款人");
        to.setSelection((trip.indexOf(trip.localPerson) + 1) % trip.people());
        LinearLayout amountBox = new LinearLayout(activity);
        LinearLayout currencyBox = amountColumns(form, amountBox);
        EditText amount = compactAmount(amountBox, "还款金额", "例如：50");
        EditText currency = currencyField(currencyBox, trip.id, "货币代码", currencyHistory.quickDefault(trip.id), false);
        FormInput.next(amount, currency);
        FormInput.done(currency);
        showForm("记录成员还款", form, () -> {
            String code = Money.code(currency.getText().toString());
            ledgerActions.transfer(trip, trip.member(from.getSelectedItemPosition()), trip.member(to.getSelectedItemPosition()),
                    code, amount.getText().toString());
            currencyHistory.remember(trip.id, code, code);
            saved.accept(trip);
        });
    }

    void exchange(LedgerStore.Trip trip) {
        LinearLayout form = form();
        Spinner person = personChoice(form, trip, "换汇人");
        String[] defaults = ledgerActions.exchangeDefaults(trip.id);
        LinearLayout fromBox = new LinearLayout(activity);
        LinearLayout fromCodeBox = amountColumns(form, fromBox);
        EditText fromAmount = compactAmount(fromBox, "付出金额", "例如：100");
        EditText fromCurrency = currencyField(fromCodeBox, trip.id, "付出货币", defaults[0], false);
        LinearLayout toBox = new LinearLayout(activity);
        LinearLayout toCodeBox = amountColumns(form, toBox);
        EditText toAmount = compactAmount(toBox, "得到金额", "例如：13");
        EditText toCurrency = currencyField(toCodeBox, trip.id, "得到货币", defaults[1], false);
        FormInput.next(fromAmount, toAmount);
        FormInput.next(fromCurrency, toAmount);
        FormInput.done(toAmount);
        FormInput.done(toCurrency);
        showForm("记录换汇", form, () -> {
            String from = Money.code(fromCurrency.getText().toString());
            String to = Money.code(toCurrency.getText().toString());
            ledgerActions.exchange(trip, trip.member(person.getSelectedItemPosition()), from, to,
                    fromAmount.getText().toString(), toAmount.getText().toString());
            currencyHistory.remember(trip.id, to, from);
            saved.accept(trip);
        });
    }

    private interface SaveAction { void run() throws Exception; }
    private void showForm(String title, View view, SaveAction save) {
        showForm(title, view, "保存", save);
    }

    private void showForm(String title, View view, String confirmLabel, SaveAction save) {
        dismissCurrencyMenu();
        ScrollView scroll = new ScrollView(activity);
        scroll.setScrollbarFadingEnabled(false);
        scroll.addView(view);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton(confirmLabel, null).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try { save.run(); dialog.dismiss(); }
            catch (Exception e) { showError.accept(e); }
        }));
        activeForm = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (activeForm != dialog) return;
            activeForm = null;
            dismissCurrencyMenu();
        });
        dialog.show();
    }

    void dismissCurrencyMenu() {
        currencyMenuRequest++;
        if (historyPopup != null) historyPopup.dismiss();
        historyPopup = null;
    }

    void close() {
        dismissCurrencyMenu();
        if (activeForm != null) activeForm.dismiss();
        activeForm = null;
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));
        form.setFocusableInTouchMode(true);
        form.setDescendantFocusability(LinearLayout.FOCUS_BEFORE_DESCENDANTS);
        return form;
    }

    private EditText field(LinearLayout form, String label, String hint, boolean decimal) {
        TextView caption = text(form, label, 14, Color.DKGRAY);
        EditText field = new EditText(activity);
        field.setId(View.generateViewId());
        caption.setLabelFor(field.getId());
        field.setSingleLine(true);
        field.setHint(hint);
        if (decimal) field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(field, fullMargin());
        return field;
    }

    private Spinner personChoice(LinearLayout form, LedgerStore.Trip trip, String title) {
        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = text(line, title, 14, Color.DKGRAY);
        caption.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        caption.setPadding(0, 0, dp(12), 0);
        Spinner spinner = personSpinner(trip, trip.localPerson);
        spinner.setId(View.generateViewId());
        caption.setLabelFor(spinner.getId());
        spinner.setMinimumHeight(dp(44));
        line.addView(spinner, new LinearLayout.LayoutParams(0, -2, 1));
        form.addView(line, fullMargin());
        return spinner;
    }

    Spinner personSpinner(LedgerStore.Trip trip, String selected) {
        Spinner spinner = new Spinner(activity);
        updatePeople(spinner, trip, selected);
        return spinner;
    }

    void updatePeople(Spinner spinner, LedgerStore.Trip trip, String selected) {
        List<String> people = new ArrayList<>();
        for (int i = 0; i < trip.people(); i++) people.add(personChoiceLabel(trip, trip.member(i)));
        spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, people));
        spinner.setSelection(Math.max(0, trip.indexOf(selected)));
    }

    private String personChoiceLabel(LedgerStore.Trip trip, String person) {
        return trip.nameOf(person) + (person.equals(trip.localPerson) ? "（我）" : "");
    }

    private LinearLayout amountColumns(LinearLayout form, LinearLayout amountBox) {
        LinearLayout line = new LinearLayout(activity);
        form.addView(line, fullMargin());
        return addAmountColumns(line, amountBox);
    }

    LinearLayout addAmountColumns(LinearLayout row, LinearLayout amountBox) {
        amountBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout codeBox = new LinearLayout(activity);
        codeBox.setOrientation(LinearLayout.VERTICAL);
        row.addView(amountBox, new LinearLayout.LayoutParams(0, -2, 3));
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(0, -2, 2);
        codeParams.leftMargin = dp(10);
        row.addView(codeBox, codeParams);
        return codeBox;
    }

    private EditText compactAmount(LinearLayout box, String label, String hint) {
        EditText amount = quickField(box, label, hint, true);
        amount.setMinimumHeight(dp(44));
        return amount;
    }

    EditText currencyField(LinearLayout box, String tripId, String label, String initial, boolean quick) {
        TextView caption = text(box, label, 14, Color.DKGRAY);
        caption.setPadding(0, dp(3), 0, 0);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText currency = new EditText(activity);
        currency.setId(View.generateViewId());
        caption.setLabelFor(currency.getId());
        currency.setSingleLine(true);
        currency.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        currency.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(3)});
        currency.setTextSize(18);
        currency.setSelectAllOnFocus(true);
        currency.setMinimumHeight(dp(44));
        currency.setText(initial);
        row.addView(currency, new LinearLayout.LayoutParams(0, -2, 1));

        TextView historyButton = new TextView(activity);
        historyButton.setText("▾");
        historyButton.setTextSize(18);
        historyButton.setTextColor(Color.rgb(34, 108, 69));
        historyButton.setIncludeFontPadding(false);
        historyButton.setGravity(Gravity.CENTER);
        historyButton.setContentDescription("选择曾用" + label);
        historyButton.setFocusable(true);
        historyButton.setBackground(chip(Color.rgb(233, 244, 235), Color.rgb(233, 244, 235), false));
        row.addView(historyButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        box.addView(row, fullMargin());

        currency.addTextChangedListener(new TextWatcher() {
            private String previous = initial;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                String selected = CurrencyHistory.valid(value.toString());
                if (selected.isEmpty() || selected.equals(previous)) return;
                currencyHistory.remember(tripId, selected, previous);
                if (quick) currencyHistory.setQuickDefault(tripId, selected);
                previous = selected;
            }
        });
        historyButton.setOnClickListener(v -> {
            String current = currency.getText().toString();
            int request = ++currencyMenuRequest;
            AlertDialog form = activeForm;
            readExecutor.execute(() -> {
                try {
                    List<String> options = currencyHistory.options(tripId, current);
                    activity.runOnUiThread(() -> {
                        if (request == currencyMenuRequest && form == activeForm && !activity.isFinishing() &&
                                !activity.isDestroyed() && historyButton.isAttachedToWindow() && historyButton.isShown() &&
                                current.contentEquals(currency.getText()))
                            showCurrencyHistory(tripId, currency, historyButton, options);
                    });
                } catch (RuntimeException error) { SyncLog.write(activity, SyncLog.Event.UI_REFRESH, error); }
            });
        });
        return currency;
    }

    private void showCurrencyHistory(String tripId, EditText currency, TextView anchor, List<String> history) {
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(4), dp(4), dp(4));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        int width = dp(150);
        int gap = dp(4);
        PopupWindow popup = new PopupWindow(scroll, width, -2, true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        popup.setBackgroundDrawable(chip(Color.WHITE, Color.rgb(218, 232, 222), false));
        popup.setElevation(dp(10));
        popup.setOutsideTouchable(true);
        if (history.isEmpty()) {
            TextView empty = text(list, "暂无曾用货币", 14, Color.GRAY);
            empty.setGravity(Gravity.CENTER);
        }
        for (String code : history) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView choose = new TextView(activity);
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
            TextView remove = new TextView(activity);
            remove.setText("×");
            remove.setTextSize(20);
            remove.setTextColor(Color.rgb(183, 98, 81));
            remove.setGravity(Gravity.CENTER);
            remove.setContentDescription("移除曾用货币 " + code);
            remove.setOnClickListener(v -> {
                currencyHistory.remove(tripId, code);
                list.removeView(row);
                if (list.getChildCount() == 0) popup.dismiss();
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(44), dp(44)));
            list.addView(row, new LinearLayout.LayoutParams(-1, dp(44)));
        }
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int available = popup.getMaxAvailableHeight(anchor, gap);
        if (available <= 0) return;
        popup.setHeight(Math.min(list.getMeasuredHeight(), available));
        if (historyPopup != null) historyPopup.dismiss();
        historyPopup = popup;
        popup.setOnDismissListener(() -> { if (historyPopup == popup) historyPopup = null; });
        popup.showAsDropDown(anchor, anchor.getWidth() - width, gap);
    }
    GradientDrawable chip(int fill, int stroke, boolean compact) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(compact ? 24 : 12));
        background.setStroke(dp(1), stroke);
        return background;
    }

    private TextView text(LinearLayout body, String value, int sp, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setPadding(0, dp(8), 0, dp(8));
        body.addView(view, fullMargin());
        return view;
    }

    private LinearLayout.LayoutParams fullMargin() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(4);
        return params;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
