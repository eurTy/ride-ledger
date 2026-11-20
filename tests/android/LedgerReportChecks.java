package com.rideledger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class LedgerReportChecks {
    static void run() throws Exception {
        revisedTrip();
        exchangesByMember();
        overflowAndEmpty();
    }

    private static void revisedTrip() throws Exception {
        String id = UUID.randomUUID().toString();
        String key = Team.encode(KeyPairGenerator.getInstance("EC").generateKeyPair().getPublic().getEncoded());
        Team team = new Team(id, UUID.randomUUID().toString(), key, Arrays.asList("阿青", "阿林", "阿海"));
        LedgerStore.Trip trip = new LedgerStore.Trip(id, "冬日旅行", "", "", "", "m1", team);
        SimpleDateFormat calendar = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        long first = calendar.parse("2026-01-10 12:00").getTime();
        long last = calendar.parse("2026-01-12 12:00").getTime();
        long minute = 60_000L;
        JSONObject initial = expense("m1", "CNY", 10101, "shared", "旧备注", team.shares(10101, "m1", false));
        List<LedgerStore.Event> events = Arrays.asList(
                event(trip, "opening", "opening", first, money("CNY", 30000).put("person", "m1")),
                event(trip, "initial", "expense", first + minute, initial),
                event(trip, "cash", "topup", first + 2 * minute, money("CNY", 10000).put("person", "m2")),
                event(trip, "repayment", "transfer", first + 3 * minute,
                        money("CNY", 3000).put("from", "m2").put("to", "m1")),
                event(trip, "exchange", "exchange", first + 4 * minute, new JSONObject().put("person", "m1")
                        .put("fromCurrency", "CNY").put("fromAmount", 5000).put("toCurrency", "JPY").put("toAmount", 103)),
                event(trip, "custom", "expense", last, expense("m2", "JPY", 101, "custom", "【住宿】 酒店 湖边",
                        new JSONObject().put("m1", 51).put("m2", 0).put("m3", 50))),
                event(trip, "personal", "expense", last + minute,
                        expense("m3", "KWD", 1234, "personal", "午餐 咖啡", team.shares(1234, "m3", true))),
                event(trip, "deleted", "expense", last + 2 * minute,
                        expense("m1", "USD", 99, "personal", "已删除的备注", team.shares(99, "m1", true))),
                event(trip, "delete-marker", "delete", last + 3 * minute, new JSONObject().put("target", "deleted")),
                noteEdit(trip, "note-a", last + 4 * minute, initial, "【餐食】 清晨"),
                noteEdit(trip, "note-z", last + 5 * minute, initial, "【交通】 地铁\n换乘"));
        List<LedgerReceipts.Entry> receipts = Arrays.asList(
                new LedgerReceipts.Entry("initial", 1, "photo1", true, 1),
                new LedgerReceipts.Entry("custom", 1, "photo2", false, 0),
                new LedgerReceipts.Entry("personal", 2, "", false, 0),
                new LedgerReceipts.Entry("deleted", 1, "photo3", true, 2));
        LedgerReport report = LedgerReport.from(new LedgerStore.Snapshot(trip, events, receipts));

        equal("冬日旅行", report.tripName, "trip name");
        equal("阿青、阿林、阿海", report.members, "member order");
        equal("2026-01-10 至 2026-01-12", report.period, "original date range");
        equal("3 笔", entry(report, "支出总计", "支出笔数"), "expense count");
        equal("101.01", entry(report, "支出总计", "CNY"), "CNY expense total");
        equal("101", entry(report, "支出总计", "JPY"), "JPY expense total");
        equal("1.234", entry(report, "支出总计", "KWD"), "KWD expense total");
        equal("101.01 CNY", entry(report, "支出类型", "共同支出"), "shared type total");
        equal("101 JPY", entry(report, "消费分类", "住宿"), "explicit lodging category");
        equal("1.234 KWD", entry(report, "消费分类", "未分类"), "plain notes remain unclassified");
        equal("101.01 CNY", entry(report, "消费分类", "交通"), "visible edited note category");
        equal("已付 101.01 CNY\n应承担 33.67 CNY、51 JPY", entry(report, "成员付款与承担", "阿青"), "member amounts");
        equal("101 JPY、1.234 KWD", entry(report, "每日支出", "2026-01-12"), "daily currencies");
        equal("2 笔 · 400.00 CNY", entry(report, "资金往来", "现金入账"), "opening and topup totals");
        equal("1 笔 · 30.00 CNY", entry(report, "资金往来", "还款"), "repayment totals");
        equal("1 笔\n换出 50.00 CNY\n换入 103 JPY", entry(report, "资金往来", "换汇"), "exchange legs");
        equal("178.99 CNY、103 JPY", entry(report, "当前现金", "阿青"), "cash arithmetic");
        equal("3.67 CNY", entry(report, "当前结算", "阿林 → 阿青"), "settlement after repayment");
        LedgerReport.Entry member = reportEntry(report, "成员付款与承担", "阿青");
        require(member.amountColumns.size() == 2 && "已付".equals(member.amountColumns.get(0).label)
                        && "分摊".equals(member.amountColumns.get(1).label),
                "member table columns were missing or reordered");
        equal("10101", member.amountColumns.get(0).values.get("CNY").toString(), "paid CNY minor units");
        equal("3367", member.amountColumns.get(1).values.get("CNY").toString(), "shared CNY minor units");
        equal("0", member.amountColumns.get(0).values.getOrDefault("JPY", BigInteger.ZERO).toString(),
                "a JPY share was mistaken for a JPY payment");
        equal("51", member.amountColumns.get(1).values.get("JPY").toString(), "JPY share stays in its currency row");
        equal("1234", reportEntry(report, "消费分类", "未分类").amountColumns.get(0).values.get("KWD").toString(),
                "three-decimal currency lost precision");
        LedgerReport.Entry funds = reportEntry(report, "资金往来", "现金入账");
        equal("2 笔", funds.amountNote, "funds table count");
        require(funds.memberTotals.size() == 2, "each cash holder has a subtotal");
        equal("1 笔 · 300.00 CNY", entry(funds.memberTotals, "阿青"), "initial cash is assigned to its holder");
        equal("1 笔 · 100.00 CNY", entry(funds.memberTotals, "阿林"), "topup is assigned to its holder");
        LedgerReport.Entry exchange = reportEntry(report, "资金往来", "换汇");
        require(exchange.amountColumns.size() == 2 && "换出".equals(exchange.amountColumns.get(0).label)
                        && "换入".equals(exchange.amountColumns.get(1).label),
                "exchange table columns were missing or reversed");
        equal("5000", exchange.amountColumns.get(0).values.get("CNY").toString(), "exchanged-out CNY");
        equal("103", exchange.amountColumns.get(1).values.get("JPY").toString(), "exchanged-in JPY");
        equal("2 张", entry(report, "小票", "小票张数"), "visible receipt count");
        equal("1 张", entry(report, "小票", "待接收"), "missing image count");
        equal("1 张", entry(report, "小票", "待同步给同伴"), "awaiting confirmation count");
        require(entry(report, "账本提醒", "编辑冲突").startsWith("1 笔"), "missing conflict warning");
        equal("personal,custom,exchange,repayment,cash,initial,opening",
                String.join(",", report.records.stream().map(row -> row.id).toArray(String[]::new)), "detail order");
        equal("午餐 咖啡", entry(LedgerReport.recordDetails(report.records.get(0)), "备注"), "plain note text preserved");
        List<LedgerReport.Entry> details = LedgerReport.recordDetails(report.records.get(5));
        equal("2026-01-10 12:01", entry(details, "时间"), "edit preserved original time");
        equal("阿青 33.67 · 阿林 33.67 · 阿海 33.67 CNY", entry(details, "分摊"), "shared detail split");
        equal("交通", entry(details, "分类"), "explicit category");
        equal("地铁\n换乘", entry(details, "备注"), "note text without category tag");
        equal("【交通】 地铁\n换乘", report.records.get(5).note, "raw note preserved");
        equal("有编辑冲突", entry(details, "状态"), "per-record conflict");
        String exported = export(report);
        require(exported.startsWith("旅行日志" + System.lineSeparator()) && exported.contains("冬日旅行") &&
                exported.contains("逐笔明细（7 笔）") &&
                exported.contains("备注：地铁" + System.lineSeparator() + "  换乘") &&
                !exported.contains("【交通】") && !exported.contains("已删除的备注"),
                "text export is missing current sections or includes deleted details");
        require(exported.indexOf("个人支出  1.234 KWD") < exported.indexOf("共同支出  101.01 CNY"),
                "text export details are out of order");
        require(exported.contains("  阿青：1 笔 · 300.00 CNY") && exported.contains("  阿林：1 笔 · 100.00 CNY"),
                "cash holder subtotals are missing from the export");
    }

    private static void exchangesByMember() throws Exception {
        LedgerStore.Trip trip = new LedgerStore.Trip("legacy", "换汇测试", "甲", "乙", "", "a");
        List<LedgerStore.Event> events = Arrays.asList(
                event(trip, "first", "exchange", 1000, new JSONObject().put("person", "a")
                        .put("fromCurrency", "CNY").put("fromAmount", 5000).put("toCurrency", "JPY").put("toAmount", 103)),
                event(trip, "second", "exchange", 2000, new JSONObject().put("person", "b")
                        .put("fromCurrency", "CNY").put("fromAmount", 7000).put("toCurrency", "JPY").put("toAmount", 219)),
                event(trip, "third", "exchange", 3000, new JSONObject().put("person", "a")
                        .put("fromCurrency", "CNY").put("fromAmount", 2300).put("toCurrency", "KWD").put("toAmount", 1234)));
        LedgerReport report = LedgerReport.from(new LedgerStore.Snapshot(trip, events));
        LedgerReport.Entry exchange = reportEntry(report, "资金往来", "换汇");
        require(exchange.memberTotals.size() == 2, "each person who exchanged has a subtotal");
        equal("2 笔\n换出 73.00 CNY\n换入 103 JPY、1.234 KWD", entry(exchange.memberTotals, "甲"),
                "multiple exchange currencies stay with the correct member");
        equal("1 笔\n换出 70.00 CNY\n换入 219 JPY", entry(exchange.memberTotals, "乙"),
                "exchange totals stay separate even with the same currency pair");
    }

    private static void overflowAndEmpty() throws Exception {
        LedgerStore.Trip trip = new LedgerStore.Trip("legacy", "旧旅行", "甲", "乙", "", "a");
        LedgerReport empty = LedgerReport.from(new LedgerStore.Snapshot(trip, Collections.emptyList()));
        equal("暂无账目", empty.period, "empty date range");
        equal("0 笔", entry(empty, "支出总计", "支出笔数"), "empty expense count");

        LedgerStore.Event first = event(trip, "first", "expense", 1000,
                money("CNY", Long.MAX_VALUE).put("payer", "a").put("shareA", 5000));
        LedgerStore.Event second = event(trip, "second", "expense", 2000,
                money("CNY", Long.MAX_VALUE).put("payer", "a").put("shareA", 5000));
        LedgerReport large = LedgerReport.from(new LedgerStore.Snapshot(trip, Arrays.asList(first, second)));
        equal("184467440737095516.14", entry(large, "支出总计", "CNY"), "overflow expense total");
        equal("金额超出汇总范围", entry(large, "当前现金", ""), "overflow cash warning");
        equal("金额超出汇总范围", entry(large, "当前结算", ""), "overflow settlement warning");
        require(large.records.size() == 2 && entry(large, "账本提醒", "汇总范围").contains("汇总范围"),
                "overflow hid records or omitted the warning");
    }

    private static JSONObject money(String code, long amount) throws Exception {
        return new JSONObject().put("currency", code).put("amount", amount);
    }

    private static JSONObject expense(String payer, String code, long amount, String mode, String note, JSONObject shares)
            throws Exception {
        return money(code, amount).put("payer", payer).put("mode", mode).put("note", note).put("shares", shares);
    }

    private static LedgerStore.Event event(LedgerStore.Trip trip, String id, String kind, long at, JSONObject data) {
        return new LedgerStore.Event(id, trip.id, kind, at, data);
    }

    private static LedgerStore.Event noteEdit(LedgerStore.Trip trip, String id, long at, JSONObject original, String note)
            throws Exception {
        return event(trip, id, "edit", at, new JSONObject().put("target", "initial").put("kind", "expense")
                .put("revision", 1).put("parts", LedgerRevisions.NOTE).put("parents", new JSONArray())
                .put("record", LedgerRevisions.combine(original, note, true)));
    }

    private static String entry(LedgerReport report, String title, String label) {
        return reportEntry(report, title, label).value;
    }

    private static LedgerReport.Entry reportEntry(LedgerReport report, String title, String label) {
        for (LedgerReport.Section section : report.sections) if (title.equals(section.title)) {
            for (LedgerReport.Entry entry : section.entries) if (label.equals(entry.label)) return entry;
            throw new AssertionError("Missing report entry: " + label);
        }
        throw new AssertionError("Missing report section: " + title);
    }

    private static String entry(List<LedgerReport.Entry> entries, String label) {
        for (LedgerReport.Entry entry : entries) if (label.equals(entry.label)) return entry.value;
        throw new AssertionError("Missing report entry: " + label);
    }

    private static String export(LedgerReport report) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            report.write(writer);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static void equal(String expected, String actual, String description) {
        require(expected.equals(actual), description + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
