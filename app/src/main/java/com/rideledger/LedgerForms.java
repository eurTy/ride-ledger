package com.rideledger;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONObject;

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
    private Dialog activeForm;
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

    void editRecord(LedgerStore.Trip trip, LedgerReview.Row record) {
        switch (record.event.kind) {
            case "opening": case "topup": moneyIn(trip, record); break;
            case "transfer": transfer(trip, record); break;
            case "exchange": exchange(trip, record); break;
            case "expense": expenseEditor(trip, record); break;
        }
    }

    private void expenseEditor(LedgerStore.Trip trip, LedgerReview.Row record) {
        JSONObject data = record.event.data;
        String initialCode = data.optString("currency");
        LinearLayout form = form();
        LinearLayout amountBox = new LinearLayout(activity);
        LinearLayout currencyBox = amountColumns(form, amountBox);
        EditText amount = compactAmount(amountBox, "支付金额", "");
        setAmount(amount, data, "amount", initialCode);
        EditText currency = currencyField(currencyBox, trip.id, "货币代码", initialCode, false, false);
        Spinner payer = personChoice(form, trip, "谁付款");
        selectPerson(payer, trip, data, "payer");
        LinearLayout splitRow = new LinearLayout(activity);
        splitRow.setGravity(Gravity.CENTER_VERTICAL);
        text(splitRow, "怎么分摊", 14, Color.DKGRAY).setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        Spinner modes = new Spinner(activity);
        modes.setContentDescription("怎么分摊");
        modes.setMinimumHeight(dp(44));
        modes.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"全员平摊", "仅付款人", "自定义分摊"}));
        splitRow.addView(modes, new LinearLayout.LayoutParams(0, -2, 1));
        form.addView(splitRow, fullMargin());
        LinearLayout shares = new LinearLayout(activity);
        shares.setOrientation(LinearLayout.VERTICAL);
        shares.setPadding(dp(12), 0, dp(12), 0);
        shares.setBackground(chip(0xfff2f7f4, 0xffdae9df, false));
        form.addView(shares, fullMargin());
        EditText[] inputs = new EditText[trip.people()];
        JSONObject originalShares = data.optJSONObject("shares");
        for (int i = 0; i < inputs.length; i++) {
            String member = trip.member(i);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(row, personChoiceLabel(trip, member), 14, 0xff294638);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 2));
            EditText input = new EditText(activity);
            input.setId(View.generateViewId());
            name.setLabelFor(input.getId());
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setGravity(Gravity.END);
            input.setHint("0");
            if (originalShares != null && originalShares.optLong(member) != 0)
                setAmount(input, originalShares, member, initialCode);
            row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
            shares.addView(row, fullMargin());
            inputs[i] = input;
            if (i > 0) FormInput.next(inputs[i - 1], input);
        }
        TextView total = text(shares, "", 15, 0xff154b3c);
        EditText note = quickField(form, "备注（可选）", "", false);
        note.setText(data.optString("note"));
        FormInput.next(amount, currency);
        FormInput.next(inputs[inputs.length - 1], note);
        FormInput.done(note);
        Runnable updateTotal = () -> {
            try {
                String code = Money.code(value(currency));
                total.setText("合计  " + Money.format(readSplit(trip, code, inputs).total, code));
            } catch (Exception error) { total.setText("请检查货币和分摊金额"); }
        };
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { updateTotal.run(); }
        };
        currency.addTextChangedListener(watcher);
        for (EditText input : inputs) input.addTextChangedListener(watcher);
        Consumer<Integer> showMode = position -> {
            shares.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
            amountBox.setVisibility(position == 2 ? View.GONE : View.VISIBLE);
            FormInput.next(currency, position == 2 ? inputs[0] : note);
        };
        modes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showMode.accept(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        String initialMode = data.optString("mode");
        int position = "custom".equals(initialMode) ? 2 : "personal".equals(initialMode) ? 1 : 0;
        modes.setSelection(position);
        showMode.accept(position);
        updateTotal.run();
        showEditor(record, form, () -> {
            String code = Money.code(value(currency));
            String[] values = new String[inputs.length];
            for (int i = 0; i < inputs.length; i++) values[i] = value(inputs[i]);
            ledgerActions.editExpense(trip, record.event, trip.member(payer.getSelectedItemPosition()), code,
                    value(amount), modes.getSelectedItemPosition(), values, value(note));
            currencyHistory.remember(trip.id, code, initialCode);
            saved.accept(trip);
        });
    }

    void moneyIn(LedgerStore.Trip trip) { moneyIn(trip, null); }

    private void moneyIn(LedgerStore.Trip trip, LedgerReview.Row record) {
        LinearLayout form = form();
        Spinner person = personChoice(form, trip, "现金持有人");
        LinearLayout amountBox = new LinearLayout(activity);
        LinearLayout currencyBox = amountColumns(form, amountBox);
        EditText amount = compactAmount(amountBox, "金额", "例如：1000");
        JSONObject data = record == null ? null : record.event.data;
        String initialCode = data == null ? currencyHistory.quickDefault(trip.id) : data.optString("currency");
        EditText currency = currencyField(currencyBox, trip.id, "货币代码", initialCode, false, record == null);
        if (data != null) {
            selectPerson(person, trip, data, "person");
            setAmount(amount, data, "amount", initialCode);
        }
        FormInput.next(amount, currency);
        FormInput.done(currency);
        showRecordForm(record, LedgerReview.CASH_LABEL, form, () -> {
            String code = Money.code(currency.getText().toString());
            ledgerActions.cash(trip, trip.member(person.getSelectedItemPosition()), code, value(amount),
                    record == null ? null : record.event);
            currencyHistory.remember(trip.id, code, code);
            saved.accept(trip);
        });
    }

    void transfer(LedgerStore.Trip trip) { transfer(trip, null); }

    private void transfer(LedgerStore.Trip trip, LedgerReview.Row record) {
        LinearLayout form = form();
        Spinner from = personChoice(form, trip, "付款人");
        Spinner to = personChoice(form, trip, "收款人");
        to.setSelection((trip.indexOf(trip.localPerson) + 1) % trip.people());
        LinearLayout amountBox = new LinearLayout(activity);
        LinearLayout currencyBox = amountColumns(form, amountBox);
        EditText amount = compactAmount(amountBox, "还款金额", "例如：50");
        JSONObject data = record == null ? null : record.event.data;
        String initialCode = data == null ? currencyHistory.quickDefault(trip.id) : data.optString("currency");
        EditText currency = currencyField(currencyBox, trip.id, "货币代码", initialCode, false, record == null);
        if (data != null) {
            selectPerson(from, trip, data, "from");
            selectPerson(to, trip, data, "to");
            setAmount(amount, data, "amount", initialCode);
        }
        FormInput.next(amount, currency);
        FormInput.done(currency);
        showRecordForm(record, "记录成员还款", form, () -> {
            String code = Money.code(currency.getText().toString());
            ledgerActions.transfer(trip, trip.member(from.getSelectedItemPosition()), trip.member(to.getSelectedItemPosition()),
                    code, value(amount), record == null ? null : record.event);
            currencyHistory.remember(trip.id, code, code);
            saved.accept(trip);
        });
    }

    void exchange(LedgerStore.Trip trip) { exchange(trip, null); }

    private void exchange(LedgerStore.Trip trip, LedgerReview.Row record) {
        LinearLayout form = form();
        Spinner person = personChoice(form, trip, "换汇人");
        JSONObject data = record == null ? null : record.event.data;
        String[] defaults = data == null ? ledgerActions.exchangeDefaults(trip.id)
                : new String[]{data.optString("fromCurrency"), data.optString("toCurrency")};
        LinearLayout fromBox = new LinearLayout(activity);
        LinearLayout fromCodeBox = amountColumns(form, fromBox);
        EditText fromAmount = compactAmount(fromBox, "付出金额", "例如：100");
        EditText fromCurrency = currencyField(fromCodeBox, trip.id, "付出货币", defaults[0], false, record == null);
        LinearLayout toBox = new LinearLayout(activity);
        LinearLayout toCodeBox = amountColumns(form, toBox);
        EditText toAmount = compactAmount(toBox, "得到金额", "例如：13");
        EditText toCurrency = currencyField(toCodeBox, trip.id, "得到货币", defaults[1], false, record == null);
        if (data != null) {
            selectPerson(person, trip, data, "person");
            setAmount(fromAmount, data, "fromAmount", defaults[0]);
            setAmount(toAmount, data, "toAmount", defaults[1]);
        }
        FormInput.next(fromAmount, toAmount);
        FormInput.next(fromCurrency, toAmount);
        FormInput.done(toAmount);
        FormInput.done(toCurrency);
        showRecordForm(record, "记录换汇", form, () -> {
            String from = Money.code(fromCurrency.getText().toString());
            String to = Money.code(toCurrency.getText().toString());
            ledgerActions.exchange(trip, trip.member(person.getSelectedItemPosition()), from, to,
                    value(fromAmount), value(toAmount), record == null ? null : record.event);
            currencyHistory.remember(trip.id, to, from);
            saved.accept(trip);
        });
    }

    private static String value(EditText input) { return input.getText().toString(); }

    private static void selectPerson(Spinner spinner, LedgerStore.Trip trip, JSONObject data, String key) {
        spinner.setSelection(Math.max(0, trip.indexOf(data.optString(key))));
    }

    private static void setAmount(EditText input, JSONObject data, String key, String code) {
        input.setText(Money.amountText(data.optLong(key), code));
    }

    private void showRecordForm(LedgerReview.Row record, String title, View form, SaveAction save) {
        if (record == null) showForm(title, form, save);
        else showEditor(record, form, save);
    }

    private void showEditor(LedgerReview.Row record, View form, SaveAction save) {
        dismissCurrencyMenu();
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.WHITE);
        float r = dp(24);
        surface.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(surface);
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(12), dp(20), dp(4));
        sheet.addView(header, fullMargin());
        View handle = new View(activity);
        handle.setBackground(chip(0xffccded3, 0xffccded3, true));
        LinearLayout.LayoutParams handleSize = new LinearLayout.LayoutParams(dp(30), dp(4));
        handleSize.gravity = Gravity.CENTER_HORIZONTAL;
        header.addView(handle, handleSize);
        TextView title = text(header, "编辑记录", 23, 0xff154b3c);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        TextView subtitle = text(header, record.type + "  ·  " + record.time, 13, 0xff52695d);
        subtitle.setPadding(0, 0, 0, dp(5));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(20), dp(4), dp(20), dp(12));
        sheet.addView(footer, fullMargin());
        TextView error = text(footer, "", 14, 0xffa53831);
        error.setVisibility(View.GONE);
        LinearLayout controls = new LinearLayout(activity);
        footer.addView(controls, fullMargin());
        TextView cancel = text(controls, "取消", 15, 0xff315a48);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(chip(0xfff3f7f4, 0xffdce8df, false));
        cancel.setLayoutParams(new LinearLayout.LayoutParams(dp(80), dp(48)));
        cancel.setOnClickListener(view -> dialog.dismiss());
        TextView confirm = text(controls, "保存修改", 16, Color.WHITE);
        confirm.setGravity(Gravity.CENTER);
        confirm.setTypeface(confirm.getTypeface(), Typeface.BOLD);
        confirm.setBackground(chip(0xff186448, 0xff186448, false));
        LinearLayout.LayoutParams confirmSize = new LinearLayout.LayoutParams(0, dp(48), 1);
        confirmSize.setMarginStart(dp(10));
        confirm.setLayoutParams(confirmSize);
        confirm.setOnClickListener(view -> {
            try { save.run(); dialog.dismiss(); }
            catch (Exception failure) {
                error.setText(failure.getMessage() == null ? "无法保存，请检查后重试" : failure.getMessage());
                error.setVisibility(View.VISIBLE);
            }
        });
        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        activeForm = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (activeForm == dialog) { activeForm = null; dismissCurrencyMenu(); }
        });
        dialog.show();
        window.setLayout(-1, -2);
        form.requestFocus();
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
        return currencyField(box, tripId, label, initial, quick, true);
    }

    private EditText currencyField(LinearLayout box, String tripId, String label, String initial,
                                   boolean quick, boolean rememberTyping) {
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

        if (rememberTyping) currency.addTextChangedListener(new TextWatcher() {
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
            Dialog form = activeForm;
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
