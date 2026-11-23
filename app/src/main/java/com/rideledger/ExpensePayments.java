package com.rideledger;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual payments for one expense, in the same minor units as the expense amount. */
final class ExpensePayments {
    private final Map<String, Long> amounts;
    final long total;

    private ExpensePayments(Map<String, Long> amounts, long total) {
        this.amounts = Collections.unmodifiableMap(amounts);
        this.total = total;
    }

    static ExpensePayments read(JSONObject expense) throws JSONException {
        if (expense.has("payments")) {
            if (expense.has("payer")) throw new JSONException("付款记录格式有误");
            JSONObject values = expense.getJSONObject("payments");
            ExpensePayments payments = fromAmounts(values);
            if (!payments.isMultiple() || payments.amounts.size() != values.length())
                throw new JSONException("多人付款金额有误");
            return payments;
        }
        long amount = expense.getLong("amount");
        return new ExpensePayments(Collections.singletonMap(expense.getString("payer"), amount), amount);
    }

    static ExpensePayments fromAmounts(JSONObject values) throws JSONException {
        Map<String, Long> amounts = new LinkedHashMap<>();
        long total = 0;
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String member = keys.next();
            long amount = LedgerRules.exactLong(values, member);
            if (amount < 0 || amount > LedgerStore.MAX_TEAM_AMOUNT - total)
                throw new JSONException("付款金额超出范围");
            if (amount > 0) amounts.put(member, amount);
            total += amount;
        }
        return new ExpensePayments(amounts, total);
    }

    Map<String, Long> amounts() { return amounts; }
    long minor(String member) { return amounts.getOrDefault(member, 0L); }
    boolean isMultiple() { return amounts.size() > 1; }
    JSONObject json() { return new JSONObject(amounts); }

    ExpensePayments requireMultiple() {
        if (!isMultiple()) throw new IllegalArgumentException("请填写至少两人的付款金额");
        return this;
    }
}
