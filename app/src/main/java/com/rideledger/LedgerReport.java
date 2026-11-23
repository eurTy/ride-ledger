package com.rideledger;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** Aggregates a ledger snapshot for statistics and formats the full travel log export. */
final class LedgerReport {
    static final class Entry {
        final String label, value;
        final List<AmountColumn> amountColumns;
        final List<Entry> memberTotals;
        final String amountNote;

        Entry(String label, String value) {
            this(label, value, "");
        }

        Entry(String label, String value, String amountNote, AmountColumn... amountColumns) {
            this(label, value, amountNote, Collections.emptyList(), amountColumns);
        }

        Entry(String label, String value, String amountNote, List<Entry> memberTotals, AmountColumn... amountColumns) {
            this.label = label;
            this.value = value;
            this.amountNote = amountNote;
            this.amountColumns = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(amountColumns)));
            this.memberTotals = Collections.unmodifiableList(new ArrayList<>(memberTotals));
        }
    }

    /** Display data kept separate from the original text export. */
    static final class AmountColumn {
        final String label;
        final SortedMap<String, BigInteger> values;

        AmountColumn(String label, Map<String, BigInteger> values) {
            this.label = label;
            this.values = Collections.unmodifiableSortedMap(new TreeMap<>(values));
        }
    }

    static final class Section {
        final String title;
        final List<Entry> entries;

        Section(String title, List<Entry> entries) {
            this.title = title;
            this.entries = Collections.unmodifiableList(entries);
        }
    }

    final String tripName, members, period, generatedAt;
    final List<Section> sections;
    final List<LedgerReview.Row> records;
    final LedgerSettlement settlement;
    final boolean financialConflict;

    private LedgerReport(String tripName, String members, String period, String generatedAt,
                         List<Section> sections, List<LedgerReview.Row> records,
                         LedgerSettlement settlement, boolean financialConflict) {
        this.tripName = tripName;
        this.members = members;
        this.period = period;
        this.generatedAt = generatedAt;
        this.sections = Collections.unmodifiableList(sections);
        this.records = Collections.unmodifiableList(records);
        this.settlement = settlement;
        this.financialConflict = financialConflict;
    }

    static LedgerReport from(LedgerStore.Snapshot snapshot) {
        LedgerStore.Trip trip = snapshot.trip;
        if (trip == null) throw new IllegalArgumentException("旅行不存在");
        LedgerStore.Summary summary = snapshot.totals();
        LedgerReview review = new LedgerReview(trip, summary, snapshot.receipts);
        List<LedgerReview.Row> records = new ArrayList<>(review.records);
        records.sort(Comparator.comparingLong((LedgerReview.Row row) -> row.event.at)
                .thenComparing(row -> row.id).reversed());

        Amounts spending = new Amounts(), funds = new Amounts(), repayments = new Amounts();
        ExchangeAmounts exchanges = new ExchangeAmounts();
        Map<String, Amounts> types = new HashMap<>(), paid = new HashMap<>(), shares = new HashMap<>();
        Map<String, Amounts> fundsByMember = new HashMap<>();
        Map<String, ExchangeAmounts> exchangesByMember = new HashMap<>();
        Map<ExpenseCategory, Amounts> categories = new EnumMap<>(ExpenseCategory.class);
        Map<String, Amounts> days = new TreeMap<>(Collections.reverseOrder());
        int conflicts = 0, receipts = 0, received = 0, awaitingSync = 0;
        for (int index = 0; index < records.size(); index++) {
            LedgerReview.Row row = records.get(index);
            JSONObject data = row.event.data;
            String code = data.optString("currency");
            long amount = data.optLong("amount");
            if (row.event.conflicted()) conflicts++;
            switch (row.event.kind) {
                case "expense": {
                    ExpensePayments payments;
                    try { payments = ExpensePayments.read(data); }
                    catch (org.json.JSONException | IllegalArgumentException invalid) { break; }
                    spending.add(code, amount);
                    add(types, row.type, code, amount);
                    add(categories, ExpenseCategory.fromNote(row.note), code, amount);
                    add(days, day(row), code, amount);
                    List<String> split = new ArrayList<>();
                    for (int i = 0; i < trip.people(); i++) {
                        String member = trip.member(i);
                        long payment = payments.minor(member);
                        if (payment > 0) add(paid, member, code, payment);
                        long share = share(trip, data, member, i);
                        if (share == 0) continue;
                        add(shares, member, code, share);
                        split.add(trip.nameOf(member) + " " + Money.amountText(share, code));
                    }
                    if (!"personal".equals(data.optString("mode")))
                        records.set(index, withSplit(row, String.join(" · ", split) + " " + code));
                    if (row.receipt != null) {
                        receipts++;
                        if (row.receipt.available) {
                            received++;
                            if (row.receiptPending) awaitingSync++;
                        }
                    }
                    break;
                }
                case "opening": case "topup":
                    funds.add(code, amount);
                    add(fundsByMember, data.optString("person"), code, amount);
                    break;
                case "transfer":
                    repayments.add(code, amount);
                    if (trip.team == null) records.set(index, legacyTransfer(trip, row));
                    break;
                case "exchange":
                    exchanges.add(data);
                    exchangesByMember.computeIfAbsent(data.optString("person"), ignored -> new ExchangeAmounts()).add(data);
                    break;
            }
        }

        List<Entry> warnings = new ArrayList<>();
        if (conflicts > 0) warnings.add(new Entry("编辑冲突", conflicts + " 笔账目有冲突，暂按当前显示版本汇总。"));
        if (summary.incomplete) warnings.add(new Entry("汇总范围", "金额超出现金和结算的汇总范围，请查看逐笔明细。"));
        List<Section> sections = new ArrayList<>();
        if (!warnings.isEmpty()) sections.add(new Section("账本提醒", warnings));

        List<Entry> totals = new ArrayList<>();
        totals.add(new Entry("支出笔数", spending.count + " 笔"));
        spending.values.forEach((code, amount) -> totals.add(new Entry(code, decimal(amount, code), "",
                new AmountColumn("", Collections.singletonMap(code, amount)))));
        sections.add(new Section("支出总计", totals));

        List<Entry> byType = new ArrayList<>(), byCategory = new ArrayList<>(), byDay = new ArrayList<>();
        for (String type : new String[]{"共同支出", "个人支出", "各自承担", "自定义分摊"})
            addSubtotal(byType, type, types.get(type));
        categories.forEach((category, amounts) -> addSubtotal(byCategory, category.label, amounts));
        days.forEach((date, amounts) -> addSubtotal(byDay, date, amounts));
        sections.add(section("支出类型", byType, "暂无支出"));
        sections.add(section("消费分类", byCategory, "暂无支出"));

        List<String> memberNames = new ArrayList<>();
        List<Entry> memberSpending = new ArrayList<>(), balances = new ArrayList<>();
        List<Entry> memberFunds = new ArrayList<>(), memberExchanges = new ArrayList<>();
        for (int i = 0; i < trip.people(); i++) {
            String member = trip.member(i);
            Map<String, Long> cash = cash(trip, summary, member, i);
            Amounts memberPaid = paid.get(member), memberShares = shares.get(member);
            Amounts contributed = fundsByMember.get(member);
            ExchangeAmounts converted = exchangesByMember.get(member);
            if (trip.team != null && trip.team.openSeats && !trip.seatNames.containsKey(member) &&
                    memberPaid == null && memberShares == null && contributed == null && converted == null &&
                    cash.isEmpty() && !summary.teamNet.containsKey(member))
                continue;
            String name = trip.nameOf(member);
            memberNames.add(name);
            if (contributed != null) memberFunds.add(movementEntry(name, contributed, Collections.emptyList()));
            if (converted != null) memberExchanges.add(converted.entry(name, Collections.emptyList()));
            if (memberPaid != null || memberShares != null) {
                memberSpending.add(new Entry(name, "已付 " + subtotal(memberPaid) + "\n应承担 " + subtotal(memberShares), "",
                        column("已付", memberPaid), column("分摊", memberShares)));
            }
            if (!summary.incomplete && !cash.isEmpty()) {
                List<String> amounts = new ArrayList<>();
                new TreeMap<>(cash).forEach((code, amount) -> amounts.add(Money.format(amount, code)));
                balances.add(new Entry(name, String.join("、", amounts)));
            }
        }
        sections.add(section("成员付款与承担", memberSpending, "暂无支出"));
        sections.add(section("每日支出", byDay, "暂无支出"));

        List<Entry> movements = new ArrayList<>();
        movements.add(new Entry("统计口径", "支出总计只计消费，金额按原币种汇总。"));
        movements.add(movementEntry("现金入账", funds, memberFunds));
        movements.add(movementEntry("还款", repayments, Collections.emptyList()));
        movements.add(exchanges.entry("换汇", memberExchanges));
        sections.add(new Section("资金往来", movements));
        sections.add(section("当前现金", balances, summary.incomplete ? "金额超出汇总范围" : "暂无现金记录"));
        sections.add(section("当前结算", settlement(trip, summary, review),
                summary.incomplete ? "金额超出汇总范围" : "暂无待还款"));

        List<Entry> images = new ArrayList<>();
        images.add(new Entry("小票张数", receipts + " 张"));
        if (receipts > 0) {
            images.add(new Entry("本机可查看", received + " 张"));
            if (received < receipts) images.add(new Entry("待接收", (receipts - received) + " 张"));
            if (awaitingSync > 0) images.add(new Entry("待同步给同伴", awaitingSync + " 张"));
        }
        sections.add(new Section("小票", images));

        String period = "暂无账目";
        if (!records.isEmpty()) {
            String first = day(records.get(records.size() - 1)), last = day(records.get(0));
            period = first.equals(last) ? first : first + " 至 " + last;
        }
        String generated = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date());
        return new LedgerReport(trip.name, String.join("、", memberNames), period, generated, sections, records,
                review.settlementAmounts, review.financialConflict);
    }

    private static Map<String, Long> cash(LedgerStore.Trip trip, LedgerStore.Summary summary, String member, int index) {
        if (trip.team == null) return index == 0 ? summary.cashA : summary.cashB;
        return summary.teamCash.getOrDefault(member, Collections.emptyMap());
    }

    private static List<Entry> settlement(LedgerStore.Trip trip, LedgerStore.Summary summary, LedgerReview review) {
        List<Entry> entries = new ArrayList<>();
        if (summary.incomplete) return entries;
        for (LedgerSettlement.Debt debt : review.settlement)
            entries.add(new Entry(debt.payer + " → " + debt.recipient, String.join("、", debt.amounts)));
        if (trip.team == null) {
            for (Map.Entry<String, Long> debt : new TreeMap<>(summary.debtBToA).entrySet()) {
                long amount = debt.getValue();
                if (amount == 0) continue;
                String direction = amount > 0 ? trip.personB + " → " + trip.personA : trip.personA + " → " + trip.personB;
                entries.add(new Entry(direction, decimal(BigInteger.valueOf(amount).abs(), debt.getKey()) + " " + debt.getKey()));
            }
        }
        return entries;
    }

    static long share(LedgerStore.Trip trip, JSONObject data, String member, int index) {
        if (trip.team != null) return data.optJSONObject("shares").optLong(member);
        long amount = data.optLong("amount"), first = Money.shareA(amount, data.optInt("shareA"));
        return index == 0 ? first : amount - first;
    }

    private static LedgerReview.Row withSplit(LedgerReview.Row row, String split) {
        return new LedgerReview.Row(row.event, row.filter, row.time, row.type, row.people, row.amount, row.received,
                split, row.note, recordTitle(row) + "  " + row.people, row.receipt, row.receiptPending);
    }

    private static LedgerReview.Row legacyTransfer(LedgerStore.Trip trip, LedgerReview.Row row) {
        JSONObject data = row.event.data;
        String from = data.optString("from");
        String people = trip.nameOf(from) + " → " + trip.nameOf("a".equals(from) ? "b" : "a");
        String amount = Money.format(data.optLong("amount"), data.optString("currency"));
        return new LedgerReview.Row(row.event, row.filter, row.time, "还款", people, amount, "", "",
                row.note, "还款 " + amount + "  " + people, row.receipt, row.receiptPending);
    }

    private static String day(LedgerReview.Row row) { return row.time.substring(0, row.time.indexOf(' ')); }

    private static <K> void add(Map<K, Amounts> map, K key, String code, long amount) {
        map.computeIfAbsent(key, ignored -> new Amounts()).add(code, amount);
    }

    private static Section section(String title, List<Entry> entries, String empty) {
        return new Section(title, entries.isEmpty() ? Collections.singletonList(new Entry("", empty)) : entries);
    }

    private static void addSubtotal(List<Entry> entries, String label, Amounts amounts) {
        if (amounts != null) entries.add(new Entry(label, amounts.format(), "", column("", amounts)));
    }

    private static AmountColumn column(String label, Amounts amounts) {
        return new AmountColumn(label, amounts == null ? Collections.emptyMap() : amounts.values);
    }

    private static String subtotal(Amounts amounts) { return amounts == null ? "0" : amounts.format(); }

    private static Entry movementEntry(String label, Amounts amounts, List<Entry> memberTotals) {
        String count = amounts.count + " 笔";
        String value = amounts.count == 0 ? count : count + " · " + amounts.format();
        return new Entry(label, value, count, memberTotals, column("", amounts));
    }

    private static String decimal(BigInteger minor, String code) {
        return new BigDecimal(minor, Money.scale(code)).toPlainString();
    }

    private static final class Amounts {
        final Map<String, BigInteger> values = new TreeMap<>();
        int count;

        void add(String code, long amount) {
            values.merge(code, BigInteger.valueOf(amount), BigInteger::add);
            count++;
        }

        String format() {
            List<String> amounts = new ArrayList<>();
            values.forEach((code, amount) -> amounts.add(decimal(amount, code) + " " + code));
            return String.join("、", amounts);
        }
    }

    private static final class ExchangeAmounts {
        final Amounts out = new Amounts(), in = new Amounts();

        void add(JSONObject data) {
            out.add(data.optString("fromCurrency"), data.optLong("fromAmount"));
            in.add(data.optString("toCurrency"), data.optLong("toAmount"));
        }

        Entry entry(String label, List<Entry> memberTotals) {
            String count = out.count + " 笔";
            String value = out.count == 0 ? count : count + "\n换出 " + out.format() + "\n换入 " + in.format();
            return new Entry(label, value, count, memberTotals, column("换出", out), column("换入", in));
        }
    }

    static String recordTitle(LedgerReview.Row row) {
        String title = row.amount.isEmpty() ? row.type : row.type + "  " + row.amount;
        return row.received.isEmpty() ? title : title + " → " + row.received;
    }

    static List<Entry> recordDetails(LedgerReview.Row row) {
        List<Entry> details = new ArrayList<>();
        details.add(new Entry("时间", row.time));
        if (!row.people.isEmpty()) details.add(new Entry("成员", row.people));
        if (!row.split.isEmpty()) details.add(new Entry("分摊", row.split));
        String note = row.note;
        if ("expense".equals(row.event.kind)) {
            details.add(new Entry("分类", ExpenseCategory.fromNote(note).label));
            if (ExpenseCategory.explicit(note) != ExpenseCategory.OTHER) note = ExpenseCategory.details(note);
        }
        if (!note.isEmpty()) details.add(new Entry("备注", note));
        if (row.receipt != null) {
            String state = !row.receipt.available ? "待接收" : row.receiptPending ? "本机可查看，待同步给同伴" : "本机可查看";
            details.add(new Entry("小票", state));
        }
        if (row.event.conflicted()) details.add(new Entry("状态", "有编辑冲突"));
        return details;
    }

    void write(BufferedWriter writer) throws IOException {
        writer.write("旅行日志");
        writer.newLine();
        writeEntry(writer, new Entry("旅行", tripName));
        if (!members.isEmpty()) writeEntry(writer, new Entry("成员", members));
        writeEntry(writer, new Entry("账目日期", period));
        writeEntry(writer, new Entry("生成时间", generatedAt));
        for (Section section : sections) {
            writer.newLine();
            writer.write(section.title);
            writer.newLine();
            for (Entry entry : section.entries) writeEntry(writer, entry);
        }
        writer.newLine();
        writer.write("逐笔明细（" + records.size() + " 笔）");
        writer.newLine();
        for (LedgerReview.Row record : records) {
            writer.newLine();
            writer.write(recordTitle(record));
            writer.newLine();
            for (Entry entry : recordDetails(record)) writeEntry(writer, entry);
        }
    }

    private static void writeEntry(BufferedWriter writer, Entry entry) throws IOException {
        writeEntry(writer, entry, "");
    }

    private static void writeEntry(BufferedWriter writer, Entry entry, String indent) throws IOException {
        writer.write(indent);
        if (!entry.label.isEmpty()) writer.write(entry.label + "：");
        String[] lines = entry.value.split("\\r\\n|\\r|\\n", -1);
        writer.write(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            writer.newLine();
            writer.write(indent + "  " + lines[i]);
        }
        writer.newLine();
        for (Entry member : entry.memberTotals) writeEntry(writer, member, indent + "  ");
    }
}
