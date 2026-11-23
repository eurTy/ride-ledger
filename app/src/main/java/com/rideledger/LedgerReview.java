package com.rideledger;

import org.json.JSONObject;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Display snapshot derived from one trip and its ledger totals. No Android views or database handles. */
final class LedgerReview {
    static final String CASH_FILTER = "cash", CASH_LABEL = "现金入账";

    static final class Balance {
        final String name;
        final Map<String, BigInteger> amounts;

        Balance(String name, Map<String, BigInteger> amounts) {
            this.name = name;
            this.amounts = Collections.unmodifiableMap(amounts);
        }
    }

    static final class Row {
        final LedgerStore.Event event;
        final String id, filter, label;
        final String time, type, people, amount, received, split, note;
        final long localRow;
        final LedgerReceipts.Entry receipt;
        final boolean receiptPending;

        Row(LedgerStore.Event event, String filter, String time, String type, String people,
            String amount, String received, String split, String note, String exportLine,
            LedgerReceipts.Entry receipt, boolean receiptPending) {
            this.event = event;
            id = event.id;
            this.filter = filter;
            this.time = time;
            this.type = type;
            this.people = people;
            this.amount = amount;
            this.received = received;
            this.split = split;
            this.note = note;
            this.receipt = receipt;
            this.receiptPending = receiptPending;
            localRow = event.localRow;
            label = time + "  " + exportLine + (split.isEmpty() ? "" : "\n分摊：" + split) +
                    (note.isEmpty() ? "" : "\n" + ExpenseCategory.visibleNote(note));
        }
    }

    final String spending;
    final LedgerSettlement settlementAmounts;
    final List<LedgerSettlement.Debt> settlement;
    final boolean hasSpending, incomplete, financialConflict;
    final Map<String, BigInteger> cashTotal;
    final List<Balance> balances;
    final List<Row> records;
    final boolean hasReceipts;
    final int pendingReceipts;

    LedgerReview(LedgerStore.Trip trip, LedgerStore.Summary summary) {
        this(trip, summary, Collections.emptyList());
    }

    LedgerReview(LedgerStore.Trip trip, LedgerStore.Summary summary, List<LedgerReceipts.Entry> receipts) {
        hasSpending = !summary.spent.isEmpty();
        spending = hasSpending ? formatBalances(summary.spent) : "暂无支出";
        settlementAmounts = new LedgerSettlement(trip, summary);
        settlement = settlementAmounts.byCurrency();
        incomplete = summary.incomplete;
        List<Balance> balanceRows = new ArrayList<>();
        Map<String, BigInteger> totalByCurrency = new TreeMap<>();
        for (int i = 0; i < trip.people(); i++) {
            String member = trip.member(i);
            Map<String, Long> cash = trip.team == null ? (i == 0 ? summary.cashA : summary.cashB)
                    : summary.teamCash.getOrDefault(member, Collections.emptyMap());
            if (trip.team != null && !trip.seatNames.containsKey(member) && cash.isEmpty()) continue;
            String name = trip.nameOf(member) + (member.equals(trip.localPerson) ? "（我）" : "");
            Map<String, BigInteger> amounts = new TreeMap<>();
            cash.forEach((code, minor) -> {
                BigInteger value = BigInteger.valueOf(minor);
                amounts.put(code, value);
                totalByCurrency.merge(code, value, BigInteger::add);
            });
            balanceRows.add(new Balance(name, amounts));
        }
        cashTotal = Collections.unmodifiableMap(totalByCurrency);
        balances = Collections.unmodifiableList(balanceRows);
        List<Row> recordRows = new ArrayList<>();
        Map<String, LedgerReceipts.Entry> byExpense = new HashMap<>();
        for (LedgerReceipts.Entry receipt : receipts)
            if (!receipt.digest.isEmpty()) byExpense.put(receipt.eventId, receipt);
        boolean includesReceipts = false, conflicts = false;
        int pendingImages = 0;
        SimpleDateFormat clock = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        for (LedgerStore.Event event : summary.visible) {
            boolean shared = "expense".equals(event.kind) && !"personal".equals(event.data.optString("mode"));
            String filter = event.kind;
            if (shared) filter = "shared";
            if ("opening".equals(filter) || "topup".equals(filter)) filter = CASH_FILTER;
            LedgerReceipts.Entry receipt = byExpense.get(event.id);
            boolean pending = receipt != null && (!receipt.available || receipt.confirmed < trip.people() - 1);
            if (pending) pendingImages++;
            recordRows.add(describe(trip, event, filter, clock, receipt, pending));
            includesReceipts |= receipt != null;
            conflicts |= !event.financialOptions.isEmpty();
        }
        records = Collections.unmodifiableList(recordRows);
        financialConflict = conflicts;
        hasReceipts = includesReceipts;
        pendingReceipts = pendingImages;
    }

    private static String formatBalances(Map<String, Long> balances) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> entry : new TreeMap<>(balances).entrySet())
            parts.add(Money.format(entry.getValue(), entry.getKey()));
        return String.join("、", parts);
    }

    static Row withFields(LedgerStore.Trip trip, Row source, JSONObject fields) {
        LedgerStore.Event event = source.event;
        return describe(trip, new LedgerStore.Event(event.id, event.tripId, event.kind, event.at, fields, event.localRow),
                source.filter, new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA), source.receipt, source.receiptPending);
    }

    private static Row describe(LedgerStore.Trip trip, LedgerStore.Event event, String filter, SimpleDateFormat clock,
                                LedgerReceipts.Entry receipt, boolean receiptPending) {
        JSONObject d = event.data;
        String time = clock.format(new Date(event.at));
        String note = d.optString("note", "");
        String type, people = "", amount = "", received = "", split = "", export;
        try {
            switch (event.kind) {
                case "opening": case "topup":
                    type = CASH_LABEL;
                    people = trip.nameOf(d.getString("person"));
                    amount = "+" + Money.format(d.getLong("amount"), d.getString("currency"));
                    export = people + " " + type + " " + amount;
                    break;
                case "expense": {
                    String mode = d.optString("mode");
                    String code = d.getString("currency");
                    ExpensePayments payments = ExpensePayments.read(d);
                    type = expenseType(mode, payments.isMultiple());
                    amount = Money.format(d.getLong("amount"), code);
                    if (payments.isMultiple()) {
                        List<String> paid = new ArrayList<>();
                        for (int i = 0; i < trip.people(); i++) {
                            String member = trip.member(i);
                            long value = payments.minor(member);
                            if (value > 0) paid.add(trip.nameOf(member) + " " + Money.amountText(value, code));
                        }
                        people = "实付 " + String.join(" · ", paid);
                        export = type + " " + amount + " · " + people;
                    } else {
                        String payer = trip.nameOf(d.getString("payer"));
                        people = payer + " 付款";
                        export = payer + " 支付 " + amount +
                                ("personal".equals(mode) ? " · 个人" : "custom".equals(mode) ? " · 自定义分摊" : " · 共同");
                    }
                    if ("custom".equals(mode)) {
                        JSONObject shares = d.getJSONObject("shares");
                        List<String> parts = new ArrayList<>();
                        for (int i = 0; i < trip.people(); i++) {
                            String member = trip.member(i);
                            parts.add(trip.nameOf(member) + " " + Money.amountText(shares.optLong(member), code));
                        }
                        split = String.join(" · ", parts);
                    }
                    break;
                }
                case "transfer": {
                    String from = trip.nameOf(d.getString("from"));
                    String to = trip.nameOf(d.getString("to"));
                    type = "还款";
                    people = from + " → " + to;
                    amount = Money.format(d.getLong("amount"), d.getString("currency"));
                    export = from + " 还款 给 " + to + " " + amount;
                    break;
                }
                case "exchange":
                    type = "换汇";
                    people = trip.nameOf(d.getString("person"));
                    amount = Money.format(d.getLong("fromAmount"), d.getString("fromCurrency"));
                    received = Money.format(d.getLong("toAmount"), d.getString("toCurrency"));
                    export = people + " " + type + " " + amount + " → " + received;
                    break;
                default: type = export = "未知记录";
            }
        } catch (Exception e) {
            type = export = "记录有误";
            people = amount = received = split = "";
        }
        return new Row(event, filter, time, type, people, amount, received, split, note, export, receipt, receiptPending);
    }

    static String expenseType(String mode, boolean multiple) {
        if ("personal".equals(mode)) return multiple ? "各自承担" : "个人支出";
        return "custom".equals(mode) ? "自定义分摊" : "共同支出";
    }

}
