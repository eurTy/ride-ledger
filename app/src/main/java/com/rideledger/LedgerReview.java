package com.rideledger;

import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
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

        Row(LedgerStore.Event event, String filter, String time, String type, String people,
            String amount, String received, String split, String note, String exportLine) {
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
            localRow = event.localRow;
            label = time + "  " + exportLine + (split.isEmpty() ? "" : "\n分摊：" + split) +
                    (note.isEmpty() ? "" : "\n" + note);
        }
    }

    static final class Debt {
        final String payer, recipient;
        final List<String> amounts;

        Debt(String payer, String recipient, List<String> amounts) {
            this.payer = payer;
            this.recipient = recipient;
            this.amounts = Collections.unmodifiableList(amounts);
        }
    }

    final String spending;
    final List<Debt> settlement;
    final boolean hasSpending, incomplete;
    final List<String> balances;
    final List<Row> records;

    LedgerReview(LedgerStore.Trip trip, LedgerStore.Summary summary) {
        hasSpending = !summary.spent.isEmpty();
        spending = hasSpending ? formatBalances(summary.spent) : "暂无支出";
        settlement = teamDebts(trip, summary.teamNet);
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
        SimpleDateFormat clock = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        for (LedgerStore.Event event : summary.visible) {
            boolean shared = "expense".equals(event.kind) && !"personal".equals(event.data.optString("mode"));
            String filter = event.kind;
            if (shared) filter = "shared";
            if ("opening".equals(filter) || "topup".equals(filter)) filter = CASH_FILTER;
            recordRows.add(describe(trip, event, filter, clock));
        }
        records = Collections.unmodifiableList(recordRows);
    }

    private static String formatBalances(Map<String, Long> balances) {
        if (balances.isEmpty()) return "尚未填写";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> entry : new TreeMap<>(balances).entrySet())
            parts.add(Money.format(entry.getValue(), entry.getKey()));
        return String.join("、", parts);
    }

    private static List<Debt> teamDebts(LedgerStore.Trip trip, Map<String, Map<String, Long>> nets) {
        Map<String, long[]> currencies = new TreeMap<>();
        int people = trip.people();
        for (int i = 0; i < people; i++) {
            Map<String, Long> member = nets.get(trip.member(i));
            if (member == null) continue;
            for (Map.Entry<String, Long> entry : member.entrySet())
                currencies.computeIfAbsent(entry.getKey(), key -> new long[people])[i] = entry.getValue();
        }
        Map<Integer, List<String>> byPair = new TreeMap<>();
        for (Map.Entry<String, long[]> entry : currencies.entrySet()) {
            long[] remaining = entry.getValue();
            for (int debtor = 0; debtor < remaining.length; debtor++) {
                for (int creditor = 0; creditor < remaining.length && remaining[debtor] < 0; creditor++) {
                    if (remaining[creditor] <= 0) continue;
                    long amount = remaining[debtor] == Long.MIN_VALUE ? remaining[creditor] : Math.min(-remaining[debtor], remaining[creditor]);
                    remaining[debtor] += amount;
                    remaining[creditor] -= amount;
                    byPair.computeIfAbsent(debtor * people + creditor, pair -> new ArrayList<>())
                            .add(Money.format(amount, entry.getKey()));
                }
            }
        }
        List<Debt> debts = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> entry : byPair.entrySet()) {
            int pair = entry.getKey();
            debts.add(new Debt(trip.nameOf(trip.member(pair / people)),
                    trip.nameOf(trip.member(pair % people)), entry.getValue()));
        }
        return Collections.unmodifiableList(debts);
    }

    private static Row describe(LedgerStore.Trip trip, LedgerStore.Event event, String filter, SimpleDateFormat clock) {
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
        return new Row(event, filter, time, type, people, amount, received, split, note, export);
    }

}
