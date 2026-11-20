package com.rideledger;

import org.json.JSONObject;
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
    final List<String> balances;
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
        List<String> balanceRows = new ArrayList<>();
        for (int i = 0; i < trip.people(); i++) {
            String member = trip.member(i);
            if (trip.team != null && !trip.seatNames.containsKey(member)) continue;
            String name = trip.nameOf(member) + (member.equals(trip.localPerson) ? "（我）" : "");
            balanceRows.add(name + "：" + formatBalances(summary.teamCash.getOrDefault(member, Collections.emptyMap())));
        }
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
        if (balances.isEmpty()) return "尚未填写";
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
                    String payer = trip.nameOf(d.getString("payer"));
                    type = "personal".equals(mode) ? "个人支出" : "custom".equals(mode) ? "自定义分摊" : "共同支出";
                    people = payer + " 付款";
                    amount = Money.format(d.getLong("amount"), code);
                    export = payer + " 支付 " + amount +
                            ("personal".equals(mode) ? " · 个人" : "custom".equals(mode) ? " · 自定义分摊" : " · 共同");
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

}
