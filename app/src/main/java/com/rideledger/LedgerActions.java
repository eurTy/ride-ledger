package com.rideledger;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Converts user input to validated ledger events. UI preferences and navigation are separate. */
final class LedgerActions {
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
        String code = Money.code(rawCurrency);
        long amount = Money.parse(rawAmount, code);
        saveExpense(trip, payer, code, amount, personal ? "personal" : "shared", note,
                trip.team.shares(amount, payer, personal));
    }

    static CustomSplit customSplit(LedgerStore.Trip trip, String code, String[] inputs) throws Exception {
        code = Money.code(code);
        if (inputs == null || inputs.length != trip.people())
            throw new IllegalArgumentException("分摊人数与小队不一致，请重新填写");
        JSONObject shares = new JSONObject();
        long total = 0;
        for (int i = 0; i < trip.people(); i++) {
            long share;
            try { share = Money.parseShare(inputs[i], code); }
            catch (NumberFormatException | ArithmeticException error) {
                throw new IllegalArgumentException(trip.nameOf(trip.member(i)) + "的金额格式有误，请检查小数位数");
            }
            if (share > LedgerStore.MAX_TEAM_AMOUNT - total)
                throw new IllegalArgumentException("分摊合计超出金额范围");
            total += share;
            shares.put(trip.member(i), share);
        }
        return new CustomSplit(trip, code, total, shares);
    }

    void expense(LedgerStore.Trip trip, String payer, CustomSplit split, String note) throws Exception {
        if (!trip.id.equals(split.tripId) || !trip.team.fingerprint.equals(split.team))
            throw new IllegalArgumentException("分摊属于另一趟旅行，请重新填写");
        if (split.total <= 0) throw new IllegalArgumentException("分摊合计必须大于零");
        saveExpense(trip, payer, split.currency, split.total, "custom", note, split.shares);
    }

    private void saveExpense(LedgerStore.Trip trip, String payer, String code, long amount,
                             String mode, String note, JSONObject shares) throws Exception {
        store.add(trip.id, "expense", expenseData(payer, code, amount, mode, note, shares));
    }

    private static JSONObject expenseData(String payer, String code, long amount, String mode,
                                          String note, JSONObject shares) throws Exception {
        return new JSONObject().put("payer", payer).put("currency", code)
                .put("amount", amount).put("mode", mode).put("note", note.trim()).put("shares", shares);
    }

    void editExpense(LedgerStore.Trip trip, LedgerStore.Event original, String payer, String code,
                     String rawAmount, int splitMode, String[] inputs, String note) throws Exception {
        if (splitMode == 2) {
            CustomSplit split = customSplit(trip, code, inputs);
            if (split.total <= 0) throw new IllegalArgumentException("分摊合计必须大于零");
            store.edit(original, expenseData(payer, code, split.total, "custom", note, split.shares));
            return;
        }
        boolean personal = splitMode == 1;
        long amount = Money.parse(rawAmount, code);
        String mode = personal ? "personal" : "shared";
        JSONObject old = original.data;
        boolean sameSplit = mode.equals(old.optString("mode", "shared")) && amount == old.optLong("amount") &&
                payer.equals(old.optString("payer")) && code.equals(old.optString("currency"));
        JSONObject shares = sameSplit ? old.getJSONObject("shares") : trip.team.shares(amount, payer, personal);
        store.edit(original, expenseData(payer, code, amount, mode, note, shares));
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
