package com.rideledger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Spreadsheet export of the visible ledger, with original currencies and exact member amounts. */
final class LedgerCsv {
    static void write(Writer writer, LedgerStore.Snapshot snapshot) throws IOException, JSONException {
        LedgerStore.Trip trip = snapshot.trip;
        List<String> header = new ArrayList<>(Arrays.asList("记账时间", "类型", "分类", "币种", "金额",
                "付款成员", "收款成员", "经手成员", "换入币种", "换入金额", "备注", "小票", "编辑冲突"));
        List<String> members = new ArrayList<>();
        for (int i = 0; i < trip.people(); i++) {
            String member = trip.member(i);
            members.add(member);
            String name = trip.nameOf(member);
            header.add("实付 · " + name);
            header.add("分摊 · " + name);
        }

        Map<String, LedgerReceipts.Entry> receipts = new HashMap<>();
        for (LedgerReceipts.Entry receipt : snapshot.receipts) {
            if (!receipt.digest.isEmpty()) receipts.put(receipt.eventId, receipt);
        }
        List<LedgerStore.Event> records = snapshot.totals().visible;
        records.sort(Comparator.comparingLong((LedgerStore.Event event) -> event.at)
                .thenComparing(event -> event.id).reversed());
        SimpleDateFormat clock = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

        writer.write('\ufeff');
        writeRow(writer, header);
        for (LedgerStore.Event event : records) {
            writeRow(writer, cells(event, trip, members, clock, receipts.get(event.id)));
        }
    }

    private static List<String> cells(LedgerStore.Event event, LedgerStore.Trip trip, List<String> members,
                                      SimpleDateFormat clock, LedgerReceipts.Entry receipt) throws JSONException {
        JSONObject data = event.data;
        String type, category = "", payer = "", recipient = "", handler = "", receivedCurrency = "", receivedAmount = "";
        String currency = data.optString("currency"), note = data.optString("note");
        long amount = data.optLong("amount");
        ExpensePayments payments = null;
        switch (event.kind) {
            case "expense":
                String mode = data.optString("mode");
                payments = ExpensePayments.read(data);
                type = LedgerReview.expenseType(mode, payments.isMultiple());
                ExpenseCategory expenseCategory = ExpenseCategory.fromNote(note);
                category = expenseCategory.label;
                if (expenseCategory != ExpenseCategory.OTHER) note = ExpenseCategory.details(note);
                List<String> payers = new ArrayList<>();
                for (String member : members) {
                    if (payments.minor(member) != 0) payers.add(trip.nameOf(member));
                }
                payer = String.join("、", payers);
                break;
            case "opening": case "topup":
                type = LedgerReview.CASH_LABEL;
                handler = trip.nameOf(data.getString("person"));
                break;
            case "transfer":
                type = "还款";
                String from = data.getString("from");
                payer = trip.nameOf(from);
                recipient = trip.nameOf(trip.team == null ? ("a".equals(from) ? "b" : "a") : data.getString("to"));
                break;
            case "exchange":
                type = "换汇";
                handler = trip.nameOf(data.getString("person"));
                currency = data.getString("fromCurrency");
                amount = data.getLong("fromAmount");
                receivedCurrency = data.getString("toCurrency");
                receivedAmount = Money.amountText(data.getLong("toAmount"), receivedCurrency);
                break;
            default:
                throw new IllegalStateException("无法导出账目类型 " + event.kind);
        }

        String receiptState = receipt == null ? "" : receipt.available ? "有小票" : "图片待接收";
        List<String> cells = new ArrayList<>(Arrays.asList(clock.format(new Date(event.at)), type, category,
                safeText(currency), Money.amountText(amount, currency), safeText(payer), safeText(recipient),
                safeText(handler), safeText(receivedCurrency), receivedAmount, safeText(note), receiptState,
                event.conflicted() ? "有" : ""));
        for (int i = 0; i < members.size(); i++) {
            String member = members.get(i);
            cells.add(payments == null ? "" : Money.amountText(payments.minor(member), currency));
            cells.add(payments == null ? "" : Money.amountText(LedgerReport.share(trip, data, member, i), currency));
        }
        return cells;
    }

    private static String safeText(String value) {
        // Neutralize spreadsheet formulas while keeping amount columns numeric.
        for (int i = 0; i < value.length(); i++) {
            char next = value.charAt(i);
            if ("=+-@＝＋－＠\t\r\n".indexOf(next) >= 0) return "'" + value;
            if (!Character.isWhitespace(next) && !Character.isSpaceChar(next)) break;
        }
        return value;
    }

    private static void writeRow(Writer writer, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) writer.write(',');
            String value = cells.get(i);
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                    || value.indexOf(';') >= 0 || value.indexOf('\t') >= 0) {
                writer.write('"');
                writer.write(value.replace("\"", "\"\""));
                writer.write('"');
            } else writer.write(value);
        }
        writer.write("\r\n");
    }
}
