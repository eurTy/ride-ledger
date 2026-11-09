package com.rideledger;

import com.rideledger.LedgerStore.Event;
import com.rideledger.LedgerStore.Trip;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared validation for locally entered and received ledger operations. */
final class LedgerRules {
    private static final String[] CURRENCY_FIELDS = {"currency", "fromCurrency", "toCurrency"};

    private LedgerRules() { }

    static void normalizeCurrencies(JSONObject data) throws JSONException {
        for (String field : CURRENCY_FIELDS)
            if ("RMB".equalsIgnoreCase(data.optString(field))) data.put(field, "CNY");
    }

    static boolean validEvent(Trip trip, Event event) {
        try {
            JSONObject d = event.data;
            switch (event.kind) {
                case "opening": case "topup":
                    return trip.indexOf(d.getString("person")) >= 0 && currency(d.getString("currency")) && validAmount(trip, d, "amount");
                case "expense":
                    if (trip.team != null) return validTeamExpense(trip, d);
                    int share = d.getInt("shareA");
                    return person(d.getString("payer")) && currency(d.getString("currency"))
                            && d.getLong("amount") > 0 && share >= 0 && share <= 10000;
                case "transfer":
                    if (trip.team != null) return trip.indexOf(d.getString("from")) >= 0 && trip.indexOf(d.getString("to")) >= 0 &&
                            !d.getString("from").equals(d.getString("to")) && currency(d.getString("currency")) && validAmount(trip, d, "amount");
                    return person(d.getString("from")) && currency(d.getString("currency")) && d.getLong("amount") > 0;
                case "exchange":
                    return trip.indexOf(d.getString("person")) >= 0 && currency(d.getString("fromCurrency"))
                            && currency(d.getString("toCurrency")) && validAmount(trip, d, "fromAmount")
                            && validAmount(trip, d, "toAmount")
                            && (trip.team == null || !d.getString("fromCurrency").equals(d.getString("toCurrency")));
                case "delete":
                    return trip.team == null ? !d.getString("target").isEmpty() : PeerIdentity.valid(d.getString("target"));
                default: return false;
            }
        } catch (Exception ignored) { return false; }
    }

    private static boolean validTeamExpense(Trip trip, JSONObject data) throws JSONException {
        long amount = data.getLong("amount");
        String payer = data.getString("payer");
        JSONObject shares = data.getJSONObject("shares");
        if (!validAmount(trip, data, "amount") || trip.indexOf(payer) < 0 || !currency(data.getString("currency")) ||
                shares.length() == 0 || shares.length() > trip.people()) return false;
        long total = 0;
        java.util.Iterator<String> keys = shares.keys();
        while (keys.hasNext()) {
            String member = keys.next();
            long share = exactLong(shares, member);
            if (trip.indexOf(member) < 0 || share < 0 || share > amount - total) return false;
            total += share;
        }
        return total == amount && (!"personal".equals(data.optString("mode")) ||
                shares.length() == 1 && shares.optLong(payer, -1) == amount);
    }

    private static boolean validAmount(Trip trip, JSONObject data, String field) throws JSONException {
        if (trip.team == null) return data.getLong(field) > 0;
        long amount = exactLong(data, field);
        return amount > 0 && amount <= LedgerStore.MAX_TEAM_AMOUNT;
    }

    private static long exactLong(JSONObject data, String field) throws JSONException {
        Object value = data.get(field);
        if (value instanceof Integer || value instanceof Long) return ((Number) value).longValue();
        throw new JSONException("金额应为整数最小货币单位");
    }

    private static boolean person(String value) { return "a".equals(value) || "b".equals(value); }
    private static boolean currency(String value) {
        try { return Money.code(value).equals(value); } catch (Exception ignored) { return false; }
    }

}
