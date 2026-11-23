package com.rideledger;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Supplier;

/** Compact per-member payment fields shared by quick entry and editing. */
final class ExpensePaymentFields extends LinearLayout {
    private final TextView[] names;
    private final EditText[] amounts;
    private final TextView total;
    private final Supplier<String> currency;
    private LedgerStore.Trip trip;

    ExpensePaymentFields(Activity activity, LedgerForms forms, LedgerStyle style, LedgerStore.Trip trip,
                         Supplier<String> currency, String initialCurrency, ExpensePayments initial) {
        super(activity);
        this.trip = trip;
        this.currency = currency;
        setOrientation(VERTICAL);
        names = new TextView[trip.people()];
        amounts = new EditText[trip.people()];
        for (int i = 0; i < trip.people(); i++) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            EditText amount = forms.compactAmount(row, "", "0", LedgerStyle.LABEL);
            TextView name = (TextView) row.getChildAt(0);
            name.setLayoutParams(new LayoutParams(0, -2, 2));
            amount.setLayoutParams(new LayoutParams(0, -2, 1));
            amount.setGravity(Gravity.END);
            long value = initial == null ? 0 : initial.minor(trip.member(i));
            if (value > 0) amount.setText(Money.amountText(value, initialCurrency));
            names[i] = name;
            amounts[i] = amount;
            if (i > 0) FormInput.next(amounts[i - 1], amount);
            addView(row, new LayoutParams(-1, -2));
        }
        FormInput.done(amounts[amounts.length - 1]);
        total = style.text("", LedgerStyle.LABEL, style.title);
        total.setGravity(Gravity.END);
        addView(total, new LayoutParams(-1, -2));
        updateRoster(trip);
        for (EditText input : amounts) input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) { refresh(); }
        });
        showPayments(initial != null);
    }

    void showPayments(boolean show) {
        setVisibility(show ? VISIBLE : GONE);
        refresh();
    }

    void updateRoster(LedgerStore.Trip latest) {
        trip = latest;
        for (int i = 0; i < names.length; i++) {
            String member = trip.member(i);
            String name = trip.nameOf(member) + (member.equals(trip.localPerson) ? "（我）" : "");
            names[i].setText(name);
            amounts[i].setContentDescription("实付金额，" + name);
        }
    }

    ExpensePayments read(String code) throws Exception {
        return values(code).requireMultiple();
    }

    void refresh() {
        if (getVisibility() == VISIBLE) {
            try {
                String code = Money.code(currency.get());
                total.setText("实付合计 " + Money.format(values(code).total, code));
            } catch (Exception invalid) { total.setText(invalid.getMessage()); }
        }
    }

    private ExpensePayments values(String code) throws Exception {
        String[] values = new String[amounts.length];
        for (int i = 0; i < amounts.length; i++) values[i] = amounts[i].getText().toString();
        return LedgerActions.payments(trip, code, values);
    }

}
