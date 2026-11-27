package com.rideledger;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import android.os.Handler;
import android.os.Looper;

/** Converts user input to validated ledger events. UI preferences and navigation are separate. */
final class LedgerActions {
    interface Write { void run() throws Exception; }

    private static final Executor WRITER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    void submit(Write write, Runnable committed, Consumer<Exception> failed) {
        WRITER.execute(() -> {
            try {
                write.run();
                MAIN.post(committed);
            } catch (Exception error) {
                MAIN.post(() -> failed.accept(error));
            }
        });
    }

    static final class CustomSplit {
        final String currency;
        final long total;
        final String tripId, team;
        private final JSONObject shares;

        private CustomSplit(LedgerStore.Trip trip, String currency, long total, JSONObject shares) {
            this.currency = currency;
            this.total = total;
            this.shares = shares;
            this.tripId = trip.id;
            this.team = trip.team.fingerprint;
        }

        long share(String member) { return shares.optLong(member, 0); }
    }

    private final LedgerStore store;

    LedgerActions(LedgerStore store) { this.store = store; }

    void expense(LedgerStore.Trip trip, String payer, String rawCurrency, String rawAmount,
                 boolean personal, String note) throws Exception {
        expense(trip, payer, rawCurrency, rawAmount, personal, note, null);
    }

    void expense(LedgerStore.Trip trip, String payer, String rawCurrency, String rawAmount,
                 boolean personal, String note, ReceiptImage receipt) throws Exception {
        expense(trip, payer, rawCurrency, rawAmount, personal, null, null, note, receipt);
    }

    static CustomSplit customSplit(LedgerStore.Trip trip, String code, String[] inputs) throws Exception {
        code = Money.code(code);
        JSONObject shares = memberAmounts(trip, code, inputs, "分摊");
        long total = 0;
        for (int i = 0; i < trip.people(); i++) total += shares.getLong(trip.member(i));
        return new CustomSplit(trip, code, total, shares);
    }

    static ExpensePayments payments(LedgerStore.Trip trip, String code, String[] inputs) throws Exception {
        return ExpensePayments.fromAmounts(memberAmounts(trip, code, inputs, "付款"));
    }

    private static JSONObject memberAmounts(LedgerStore.Trip trip, String code, String[] inputs, String label) throws Exception {
        if (inputs == null || inputs.length != trip.people())
            throw new IllegalArgumentException(label + "人数与小队不一致，请重新填写");
        JSONObject amounts = new JSONObject();
        long total = 0;
        for (int i = 0; i < trip.people(); i++) {
            long share;
            try { share = Money.parseShare(inputs[i], code); }
            catch (NumberFormatException | ArithmeticException error) {
                throw new IllegalArgumentException(trip.nameOf(trip.member(i)) + "的金额格式有误，请检查小数位数");
            }
            catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(label + "金额不能小于零");
            }
            if (share > LedgerStore.MAX_TEAM_AMOUNT - total)
                throw new IllegalArgumentException(label + "合计超出金额范围");
            total += share;
            amounts.put(trip.member(i), share);
        }
        return amounts;
    }

    void expense(LedgerStore.Trip trip, String payer, CustomSplit split, String note) throws Exception {
        expense(trip, payer, split, note, null);
    }

    void expense(LedgerStore.Trip trip, String payer, CustomSplit split, String note, ReceiptImage receipt) throws Exception {
        expense(trip, payer, split.currency, "", false, split, null, note, receipt);
    }

    void expense(LedgerStore.Trip trip, String payer, String code, String rawAmount, boolean personal,
                 CustomSplit split, ExpensePayments payments, String note, ReceiptImage receipt) throws Exception {
        addExpense(trip, payer, code, rawAmount, personal, split, payments, note, receipt);
    }

    LedgerStore.Event addExpense(LedgerStore.Trip trip, String payer, String code, String rawAmount, boolean personal,
                                 CustomSplit split, ExpensePayments payments, String note, ReceiptImage receipt) throws Exception {
        return store.addExpense(trip.id, expenseData(trip, null, payer, Money.code(code), rawAmount, personal,
                split, payments, note), receipt);
    }

    void receipt(LedgerStore.Trip trip, String eventId, ReceiptImage image) {
        store.receipts.change(trip.id, eventId, image);
    }

    void editExpense(LedgerStore.Trip trip, LedgerStore.Event original, String payer, String code,
                     String rawAmount, int splitMode, String[] inputs, String note) throws Exception {
        editExpense(trip, original, payer, code, rawAmount, splitMode, inputs, null, note);
    }

    void editExpense(LedgerStore.Trip trip, LedgerStore.Event original, String payer, String code,
                     String rawAmount, int splitMode, String[] inputs, ExpensePayments payments, String note) throws Exception {
        code = Money.code(code);
        CustomSplit split = splitMode == 2 ? customSplit(trip, code, inputs) : null;
        store.edit(original, expenseData(trip, original.data, payer, code, rawAmount, splitMode == 1, split, payments, note));
    }

    private static JSONObject expenseData(LedgerStore.Trip trip, JSONObject old, String payer, String code,
                                          String rawAmount, boolean personal, CustomSplit split,
                                          ExpensePayments payments, String note) throws Exception {
        if (payments != null) payments.requireMultiple();
        long amount;
        if (split != null) {
            if (!trip.id.equals(split.tripId) || !trip.team.fingerprint.equals(split.team) || !code.equals(split.currency))
                throw new IllegalArgumentException("请重新确认自定义分摊");
            if (split.total <= 0) throw new IllegalArgumentException("分摊合计必须大于零");
            amount = split.total;
        } else amount = payments == null ? Money.parse(rawAmount, code) : payments.total;
        if (payments != null && payments.total != amount)
            throw new IllegalArgumentException("付款合计和分摊合计必须一致");
        String mode = split != null ? "custom" : personal ? "personal" : "shared";
        JSONObject data = new JSONObject().put("currency", code).put("amount", amount)
                .put("mode", mode).put("note", note.trim());
        if (payments == null) data.put("payer", payer);
        else data.put("payments", payments.json());
        boolean sameSplit = old != null && mode.equals(old.optString("mode", "shared")) &&
                amount == old.optLong("amount") && code.equals(old.optString("currency")) &&
                ExpensePayments.read(data).amounts().equals(ExpensePayments.read(old).amounts());
        JSONObject shares;
        if (split != null) shares = split.shares;
        else if (sameSplit) shares = old.getJSONObject("shares");
        else if (personal && payments != null) shares = payments.json();
        else shares = trip.team.shares(amount, payer, personal);
        return data.put("shares", shares);
    }

    void cash(LedgerStore.Trip trip, String member, String rawCurrency, String rawAmount) throws Exception {
        cash(trip, member, rawCurrency, rawAmount, null);
    }

    void cash(LedgerStore.Trip trip, String member, String rawCurrency, String rawAmount, LedgerStore.Event original) throws Exception {
        String code = Money.code(rawCurrency);
        save(trip, "topup", new JSONObject().put("person", member)
                .put("currency", code).put("amount", Money.parse(rawAmount, code)), original);
    }

    void transfer(LedgerStore.Trip trip, String from, String to, String rawCurrency, String rawAmount) throws Exception {
        transfer(trip, from, to, rawCurrency, rawAmount, null);
    }

    void transfer(LedgerStore.Trip trip, String from, String to, String rawCurrency, String rawAmount,
                  LedgerStore.Event original) throws Exception {
        if (from.equals(to)) throw new IllegalArgumentException("请选择不同的付款人和收款人");
        String code = Money.code(rawCurrency);
        save(trip, "transfer", new JSONObject().put("from", from).put("to", to)
                .put("currency", code).put("amount", Money.parse(rawAmount, code)), original);
    }

    void exchange(LedgerStore.Trip trip, String member, String rawFrom, String rawTo,
                  String fromAmount, String toAmount) throws Exception {
        exchange(trip, member, rawFrom, rawTo, fromAmount, toAmount, null);
    }

    void exchange(LedgerStore.Trip trip, String member, String rawFrom, String rawTo,
                  String fromAmount, String toAmount, LedgerStore.Event original) throws Exception {
        String from = Money.code(rawFrom), to = Money.code(rawTo);
        if (from.equals(to)) throw new IllegalArgumentException("请选择两种不同货币");
        save(trip, "exchange", new JSONObject().put("person", member)
                .put("fromCurrency", from).put("fromAmount", Money.parse(fromAmount, from))
                .put("toCurrency", to).put("toAmount", Money.parse(toAmount, to)), original);
    }

    private void save(LedgerStore.Trip trip, String kind, JSONObject data, LedgerStore.Event original) throws Exception {
        if (original == null) store.add(trip.id, kind, data);
        else {
            if (original.data.has("note")) data.put("note", original.data.get("note"));
            store.edit(original, data);
        }
    }

    void delete(LedgerStore.Trip trip, List<String> records) throws Exception {
        JSONArray batch = new JSONArray();
        long at = System.currentTimeMillis();
        for (String record : new LinkedHashSet<>(records)) {
            batch.put(new LedgerStore.Event(UUID.randomUUID().toString(), trip.id, "delete", at,
                    new JSONObject().put("target", record)).json());
        }
        // Deletions are ledger operations too. Commit all of them together so the
        // same nearby delta exchange can retry them after an offline deletion.
        if (batch.length() != 0 && store.mergeStrict(trip.id, batch) != batch.length())
            throw new IllegalStateException("删除未保存，请重试");
    }

    String[] exchangeDefaults(String tripId) {
        List<LedgerStore.Event> events = store.visibleExchanges(tripId);
        for (int i = events.size() - 1; i >= 0; i--) {
            LedgerStore.Event event = events.get(i);
            try {
                String from = Money.code(event.data.getString("fromCurrency"));
                String to = Money.code(event.data.getString("toCurrency"));
                if (!from.equals(to)) return new String[]{from, to};
            } catch (Exception ignored) { }
        }
        return new String[]{"CNY", "USD"};
    }
}
