package com.rideledger;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Renders cash and split forms. LedgerActions is the only write entry point. */
final class LedgerForms {
    private final Activity activity;
    private final LedgerStyle style;
    private final LedgerActions ledgerActions;
    private final CurrencyHistory currencyHistory;
    private final NoteHistory noteHistory;
    private final ReceiptUi receipts;
    private final Executor readExecutor;
    private final Consumer<LedgerStore.Trip> saved;
    private final Consumer<Exception> showError;
    private int currencyMenuRequest;
    private Dialog activeForm;
    private PopupWindow historyPopup;

    LedgerForms(Activity activity, LedgerActions ledgerActions, CurrencyHistory currencyHistory, NoteHistory noteHistory, ReceiptUi receipts,
                Executor readExecutor, Consumer<LedgerStore.Trip> saved, Consumer<Exception> showError) {
        this.activity = activity;
        style = new LedgerStyle(activity);
        this.ledgerActions = ledgerActions;
        this.currencyHistory = currencyHistory;
        this.noteHistory = noteHistory;
        this.receipts = receipts;
        this.readExecutor = readExecutor;
        this.saved = saved;
        this.showError = showError;
    }

    void showCustomSplit(LedgerStore.Trip trip, String code,
                                 LedgerActions.CustomSplit draft, Consumer<LedgerActions.CustomSplit> apply) {
        LinearLayout form = form();
        text(form, "填写每人的分摊金额（" + code + "），空白按 0 计算", LedgerStyle.LABEL, style.muted);
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
        TextView total = text(form, "", 16, style.title);
        total.setTypeface(LedgerStyle.MEDIUM);
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
        showSheet("自定义分摊", null, form, "应用分摊", (dialog, confirm, cancel, error) -> {
            try {
                LedgerActions.CustomSplit split = readSplit(trip, code, inputs);
                if (split.total == 0) throw new IllegalArgumentException("分摊合计必须大于零");
                apply.accept(split);
                dialog.dismiss();
            } catch (Exception failure) { editError(error, failure); }
        });
    }

    private LedgerActions.CustomSplit readSplit(LedgerStore.Trip trip, String code, EditText[] inputs) throws Exception {
        String[] values = new String[inputs.length];
        for (int i = 0; i < inputs.length; i++) values[i] = inputs[i].getText().toString();
        return LedgerActions.customSplit(trip, code, values);
    }

    EditText compactAmount(LinearLayout form, String label, String hint, int captionSize) {
        TextView caption = text(form, label, captionSize, style.muted);
        caption.setPadding(0, dp(3), 0, 0);
        EditText input = field(caption, hint, true);
        form.addView(input, fullMargin());
        return input;
    }

    void editRecord(LedgerStore.Trip trip, LedgerReview.Row record) {
        if (record.event.conflicted()) {
            chooseFinancialVersion(trip, record);
            return;
        }
        switch (record.event.kind) {
            case "opening": case "topup": moneyIn(trip, record); break;
            case "transfer": transfer(trip, record); break;
            case "exchange": exchange(trip, record); break;
            case "expense": expenseEditor(trip, record); break;
        }
    }

    private void chooseFinancialVersion(LedgerStore.Trip trip, LedgerReview.Row record) {
        List<JSONObject> versions = record.event.financialOptions;
        if (versions.isEmpty()) { chooseNoteVersion(trip, record, record.event.data); return; }
        String[] labels = new String[versions.size()];
        for (int i = 0; i < versions.size(); i++) {
            LedgerReview.Row candidate = LedgerReview.withFields(trip, record, versions.get(i));
            labels[i] = candidate.type + " · " + candidate.amount +
                    (candidate.received.isEmpty() ? "" : " → " + candidate.received) +
                    "\n" + candidate.people + (candidate.split.isEmpty() ? "" : "\n" + candidate.split);
        }
        chooseVersion("选择账目版本", labels, index -> chooseNoteVersion(trip, record, versions.get(index)));
    }

    private void chooseNoteVersion(LedgerStore.Trip trip, LedgerReview.Row record, JSONObject financial) {
        List<String> notes = record.event.noteOptions;
        if (notes.isEmpty()) {
            openVersion(trip, record, financial, record.note);
            return;
        }
        String[] labels = new String[notes.size()];
        for (int i = 0; i < notes.size(); i++)
            labels[i] = notes.get(i).isEmpty() ? "（空备注）" : ExpenseCategory.visibleNote(notes.get(i));
        chooseVersion("选择备注版本", labels, index -> openVersion(trip, record, financial, notes.get(index)));
    }

    private void chooseVersion(String title, String[] choices, Consumer<Integer> select) {
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setNegativeButton("取消", null)
                .setItems(choices, (ignored, index) -> select.accept(index)).create();
        activeForm = dialog;
        dialog.setOnDismissListener(ignored -> { if (activeForm == dialog) activeForm = null; });
        dialog.show();
        style.alertActions(dialog, LedgerStyle.Action.OUTLINE);
    }

    private void openVersion(LedgerStore.Trip trip, LedgerReview.Row record, JSONObject fields, String note) {
        try {
            editRecord(trip, LedgerReview.withFields(trip, record, LedgerRevisions.combine(fields, note, record.event.data.has("note"))));
        } catch (Exception failure) { showError.accept(failure); }
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
        TextView rule = text(splitRow, "分摊规则", LedgerStyle.LABEL, style.muted);
        rule.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        rule.setPadding(0, 0, dp(LedgerStyle.CONTROL_GAP), 0);
        Spinner modes = new Spinner(activity, Spinner.MODE_DROPDOWN);
        modes.setContentDescription("分摊规则");
        style.spinner(modes);
        modes.setAdapter(style.options(Arrays.asList("全员平摊", "仅付款人", "自定义分摊"), modes, -1));
        splitRow.addView(modes, new LinearLayout.LayoutParams(0, -2, 1));
        form.addView(splitRow, fullMargin());
        LinearLayout shares = new LinearLayout(activity);
        shares.setOrientation(LinearLayout.VERTICAL);
        shares.setPadding(dp(12), 0, dp(12), 0);
        shares.setBackground(style.rounded(style.soft, 0, LedgerStyle.CONTROL_RADIUS));
        form.addView(shares, fullMargin());
        EditText[] inputs = new EditText[trip.people()];
        JSONObject originalShares = data.optJSONObject("shares");
        for (int i = 0; i < inputs.length; i++) {
            String member = trip.member(i);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(row, personChoiceLabel(trip, member), LedgerStyle.LABEL, style.ink);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 2));
            EditText input = new EditText(activity);
            input.setId(View.generateViewId());
            name.setLabelFor(input.getId());
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setGravity(Gravity.END);
            input.setHint("0");
            style.input(input);
            if (originalShares != null && originalShares.optLong(member) != 0)
                setAmount(input, originalShares, member, initialCode);
            row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
            shares.addView(row, fullMargin());
            inputs[i] = input;
            if (i > 0) FormInput.next(inputs[i - 1], input);
        }
        TextView total = text(shares, "", LedgerStyle.BODY, style.title);
        NoteInput noteInput = expenseNote(form, trip.id, data.optString("note"), true);
        EditText note = noteInput.field;
        receipts.besideNote(noteInput.input, trip, record.id, record.receipt != null);
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
        showRecordForm(trip, record, "编辑记录", form, () -> {
            String code = Money.code(value(currency));
            String[] values = new String[inputs.length];
            for (int i = 0; i < inputs.length; i++) values[i] = value(inputs[i]);
            String member = trip.member(payer.getSelectedItemPosition()), rawAmount = value(amount), rawNote = noteInput.value();
            int mode = modes.getSelectedItemPosition();
            return () -> {
                ledgerActions.editExpense(trip, record.event, member, code, rawAmount, mode, values, rawNote);
                currencyHistory.remember(trip.id, code, initialCode);
            };
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
        showRecordForm(trip, record, LedgerReview.CASH_LABEL, form, () -> {
            String code = Money.code(currency.getText().toString());
            String member = trip.member(person.getSelectedItemPosition()), rawAmount = value(amount);
            return () -> {
                ledgerActions.cash(trip, member, code, rawAmount, record == null ? null : record.event);
                currencyHistory.remember(trip.id, code, code);
            };
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
        showRecordForm(trip, record, "记录成员还款", form, () -> {
            String code = Money.code(currency.getText().toString());
            String payer = trip.member(from.getSelectedItemPosition()), receiver = trip.member(to.getSelectedItemPosition());
            String rawAmount = value(amount);
            return () -> {
                ledgerActions.transfer(trip, payer, receiver, code, rawAmount, record == null ? null : record.event);
                currencyHistory.remember(trip.id, code, code);
            };
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
        showRecordForm(trip, record, "记录换汇", form, () -> {
            String from = Money.code(fromCurrency.getText().toString());
            String to = Money.code(toCurrency.getText().toString());
            String member = trip.member(person.getSelectedItemPosition());
            String spent = value(fromAmount), received = value(toAmount);
            return () -> {
                ledgerActions.exchange(trip, member, from, to, spent, received, record == null ? null : record.event);
                currencyHistory.remember(trip.id, to, from);
            };
        });
    }

    private static String value(EditText input) { return input.getText().toString(); }

    private static void selectPerson(Spinner spinner, LedgerStore.Trip trip, JSONObject data, String key) {
        spinner.setSelection(Math.max(0, trip.indexOf(data.optString(key))));
    }

    private static void setAmount(EditText input, JSONObject data, String key, String code) {
        input.setText(Money.amountText(data.optLong(key), code));
    }

    private interface EditInput { LedgerActions.Write capture() throws Exception; }
    private interface SheetAction { void perform(Dialog dialog, Button confirm, Button cancel, TextView error); }

    private void showRecordForm(LedgerStore.Trip trip, LedgerReview.Row record, String title, View form, EditInput input) {
        String subtitle = record == null ? null : record.type + "  ·  " + record.time;
        showSheet(record == null ? title : "编辑记录", subtitle, form, record == null ? "保存" : "保存修改",
                (dialog, confirm, cancel, error) -> submitForm(trip, dialog, confirm, cancel, input,
                        failure -> editError(error, failure)));
    }

    private void showSheet(String title, String subtitle, View form, String confirmLabel, SheetAction action) {
        dismissCurrencyMenu();
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(style.sheet());
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(12), dp(20), dp(4));
        sheet.addView(header, fullMargin());
        View handle = new View(activity);
        handle.setBackground(style.rounded(style.stroke, 0, LedgerStyle.CONTROL_RADIUS));
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams handleSize = new LinearLayout.LayoutParams(dp(30), dp(4));
        handleSize.gravity = Gravity.CENTER_HORIZONTAL;
        header.addView(handle, handleSize);
        TextView heading = style.heading(title, 22);
        heading.setPadding(0, dp(LedgerStyle.CONTROL_GAP), 0, dp(LedgerStyle.ITEM_GAP));
        header.addView(heading, fullMargin());
        if (subtitle != null) {
            TextView caption = text(header, subtitle, LedgerStyle.CAPTION, style.muted);
            caption.setPadding(0, 0, 0, dp(LedgerStyle.ITEM_GAP));
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        View rule = new View(activity);
        rule.setBackgroundColor(style.divider);
        sheet.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(20), dp(8), dp(20), dp(12));
        sheet.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        TextView error = text(footer, "", LedgerStyle.LABEL, style.danger);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        LinearLayout controls = new LinearLayout(activity);
        footer.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        Button cancel = style.button("取消", LedgerStyle.Action.OUTLINE, dialog::dismiss);
        cancel.setId(android.R.id.button2);
        cancel.setMinWidth(dp(80));
        controls.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
        Button confirm = style.button(confirmLabel, LedgerStyle.Action.PRIMARY, null);
        confirm.setId(android.R.id.button1);
        LinearLayout.LayoutParams confirmSize = new LinearLayout.LayoutParams(0, -2, 1);
        confirmSize.setMarginStart(dp(LedgerStyle.CONTROL_GAP));
        controls.addView(confirm, confirmSize);
        confirm.setOnClickListener(view -> action.perform(dialog, confirm, cancel, error));
        dialog.setContentView(sheet);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM);
        window.setDimAmount(.28f);
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

    private void submitForm(LedgerStore.Trip trip, Dialog dialog, View confirm, View cancel,
                            EditInput input, Consumer<Exception> error) {
        if (!confirm.isEnabled()) return;
        LedgerActions.Write write;
        try { write = input.capture(); }
        catch (Exception failure) { error.accept(failure); return; }
        confirm.setEnabled(false);
        cancel.setEnabled(false);
        dialog.setCancelable(false);
        ledgerActions.submit(write, () -> {
            if (activity.isDestroyed()) return;
            if (activeForm == dialog) dialog.dismiss();
            saved.accept(trip);
        }, failure -> {
            if (activity.isDestroyed() || activeForm != dialog) return;
            confirm.setEnabled(true);
            cancel.setEnabled(true);
            dialog.setCancelable(true);
            error.accept(failure);
        });
    }

    private static void editError(TextView error, Exception failure) {
        error.setText(failure.getMessage() == null ? "无法保存，请检查后重试" : failure.getMessage());
        error.setVisibility(View.VISIBLE);
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
        form.setPadding(dp(20), dp(LedgerStyle.ITEM_GAP), dp(20), dp(LedgerStyle.CONTROL_GAP));
        form.setFocusableInTouchMode(true);
        form.setDescendantFocusability(LinearLayout.FOCUS_BEFORE_DESCENDANTS);
        return form;
    }

    private EditText field(LinearLayout form, String label, String hint, boolean decimal) {
        TextView caption = text(form, label, LedgerStyle.LABEL, style.muted);
        EditText field = field(caption, hint, decimal);
        form.addView(field, fullMargin());
        return field;
    }

    private EditText field(TextView caption, String hint, boolean decimal) {
        EditText field = new EditText(activity);
        field.setId(View.generateViewId());
        caption.setLabelFor(field.getId());
        field.setSingleLine(true);
        field.setHint(hint);
        if (decimal) field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        style.input(field);
        return field;
    }

    NoteInput expenseNote(LinearLayout form, String tripId, String value, boolean multipleLines) {
        TextView caption = style.text("备注（可选）", multipleLines ? LedgerStyle.LABEL : LedgerStyle.CAPTION, style.muted);
        EditText field = field(caption, "", false);
        if (multipleLines) {
            field.setSingleLine(false);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            field.setMinLines(1);
            field.setMaxLines(5);
        }
        NoteInput note = new NoteInput(field, style, value, () -> noteHistory.options(tripId));
        form.addView(caption, fullMargin());
        form.addView(note.input, fullMargin());
        return note;
    }

    private Spinner personChoice(LinearLayout form, LedgerStore.Trip trip, String title) {
        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = text(line, title, LedgerStyle.LABEL, style.muted);
        caption.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        caption.setPadding(0, 0, dp(LedgerStyle.SECTION_GAP), 0);
        Spinner spinner = personSpinner(trip, trip.localPerson);
        spinner.setId(View.generateViewId());
        caption.setLabelFor(spinner.getId());
        line.addView(spinner, new LinearLayout.LayoutParams(0, -2, 1));
        form.addView(line, fullMargin());
        return spinner;
    }

    Spinner personSpinner(LedgerStore.Trip trip, String selected) {
        Spinner spinner = new Spinner(activity, Spinner.MODE_DROPDOWN);
        style.spinner(spinner);
        updatePeople(spinner, trip, selected);
        return spinner;
    }

    void updatePeople(Spinner spinner, LedgerStore.Trip trip, String selected) {
        List<String> people = new ArrayList<>();
        for (int i = 0; i < trip.people(); i++) people.add(personChoiceLabel(trip, trip.member(i)));
        spinner.setAdapter(style.options(people, spinner, -1));
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
        codeParams.setMarginStart(dp(LedgerStyle.CONTROL_GAP));
        row.addView(codeBox, codeParams);
        return codeBox;
    }

    private EditText compactAmount(LinearLayout box, String label, String hint) {
        return compactAmount(box, label, hint, LedgerStyle.LABEL);
    }

    EditText currencyField(LinearLayout box, String tripId, String label, String initial, boolean quick) {
        return currencyField(box, tripId, label, initial, quick, true);
    }

    private EditText currencyField(LinearLayout box, String tripId, String label, String initial,
                                   boolean quick, boolean rememberTyping) {
        TextView caption = text(box, label, quick ? LedgerStyle.CAPTION : LedgerStyle.LABEL, style.muted);
        caption.setPadding(0, dp(3), 0, 0);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText currency = new EditText(activity);
        currency.setId(View.generateViewId());
        caption.setLabelFor(currency.getId());
        currency.setSingleLine(true);
        currency.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        currency.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(3)});
        style.input(currency);
        currency.setTextSize(quick ? 16 : 18);
        currency.setSelectAllOnFocus(true);
        currency.setText(initial);
        row.addView(currency, new LinearLayout.LayoutParams(0, -2, 1));

        TextView historyButton = style.text("▾", 16, style.primary);
        historyButton.setIncludeFontPadding(false);
        historyButton.setGravity(Gravity.CENTER);
        historyButton.setContentDescription("选择曾用" + label);
        historyButton.setFocusable(true);
        historyButton.setBackground(style.ripple(style.rounded(style.selected, 0, LedgerStyle.CONTROL_RADIUS), LedgerStyle.CONTROL_RADIUS));
        int control = dp(LedgerStyle.COMPACT_HEIGHT);
        LinearLayout.LayoutParams historySize = new LinearLayout.LayoutParams(control, control);
        historySize.setMarginStart(dp(LedgerStyle.ITEM_GAP));
        row.addView(historyButton, historySize);
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
            if (!currency.isEnabled()) return;
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
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        android.content.res.Configuration config = activity.getResources().getConfiguration();
        int width = dp(Math.min(config.screenWidthDp - 2 * LedgerStyle.PAGE_INSET, Math.round(164 * config.fontScale)));
        int gap = dp(LedgerStyle.ITEM_GAP);
        PopupWindow popup = new PopupWindow(scroll, width, -2, true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        popup.setBackgroundDrawable(style.menu());
        popup.setElevation(dp(6));
        popup.setOutsideTouchable(true);
        if (history.isEmpty()) {
            TextView empty = text(list, "暂无曾用货币", LedgerStyle.LABEL, style.muted);
            empty.setGravity(Gravity.CENTER);
        }
        for (String code : history) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            boolean selected = code.contentEquals(currency.getText());
            TextView choose = style.text(code, 16, selected ? style.primary : style.ink);
            choose.setTypeface(LedgerStyle.MEDIUM);
            choose.setGravity(Gravity.CENTER_VERTICAL);
            choose.setPadding(dp(10), dp(4), 0, dp(4));
            choose.setMinHeight(dp(LedgerStyle.COMPACT_HEIGHT));
            choose.setSelected(selected);
            choose.setBackground(style.ripple(selected ? style.rounded(style.selected, 0, LedgerStyle.CONTROL_RADIUS) : null,
                    LedgerStyle.CONTROL_RADIUS));
            choose.setOnClickListener(v -> {
                currency.setText(code);
                currency.setSelection(code.length());
                popup.dismiss();
            });
            row.addView(choose, new LinearLayout.LayoutParams(0, -2, 1));
            TextView remove = style.text("×", 20, style.danger);
            remove.setGravity(Gravity.CENTER);
            remove.setContentDescription("移除曾用货币 " + code);
            remove.setBackground(style.ripple(null, LedgerStyle.CONTROL_RADIUS));
            remove.setOnClickListener(v -> {
                currencyHistory.remove(tripId, code);
                list.removeView(row);
                if (list.getChildCount() == 0) popup.dismiss();
            });
            int control = dp(LedgerStyle.COMPACT_HEIGHT);
            row.addView(remove, new LinearLayout.LayoutParams(control, control));
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
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
    private TextView text(LinearLayout body, String value, int sp, int color) {
        TextView view = style.text(value, sp, color);
        view.setPadding(0, dp(8), 0, dp(8));
        body.addView(view, fullMargin());
        return view;
    }

    private LinearLayout.LayoutParams fullMargin() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(LedgerStyle.ITEM_GAP);
        return params;
    }

    private int dp(int value) { return style.dp(value); }
}
