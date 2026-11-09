package com.rideledger;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One list of editable currency codes per trip, shared by every ledger form. */
final class CurrencyHistory {
    private static final int MENU_LIMIT = 5;
    private static final int STORED_LIMIT = MENU_LIMIT + 1;
    private final SharedPreferences prefs;
    private final LedgerStore store;

    CurrencyHistory(SharedPreferences prefs, LedgerStore store) {
        this.prefs = prefs;
        this.store = store;
    }

    String quickDefault(String tripId) {
        String code = valid(prefs.getString("currency:" + tripId, "CNY"));
        return code.isEmpty() ? "CNY" : code;
    }

    void setQuickDefault(String tripId, String code) {
        prefs.edit().putString("currency:" + tripId, code).apply();
    }

    void remember(String tripId, String raw, String previous) {
        String code = valid(raw);
        if (code.isEmpty()) return;
        long now = System.currentTimeMillis();
        Map<String, Long> recent = new LinkedHashMap<>();
        recent.put(code, now);
        String earlier = valid(previous);
        if (!earlier.isEmpty()) recent.putIfAbsent(earlier, now);
        saved(tripId).forEach(recent::putIfAbsent);
        Set<String> hidden = new HashSet<>(hidden(tripId));
        hidden.remove(code);
        recent.keySet().removeAll(hidden);
        prefs.edit().putString(key(tripId), serialize(recent))
                .putStringSet(hiddenKey(tripId), hidden).apply();
    }

    List<String> options(String tripId, String current) {
        Set<String> excluded = new HashSet<>(hidden(tripId));
        excluded.add(valid(current));
        Map<String, Long> result = saved(tripId);
        result.keySet().removeAll(excluded);
        store.recentCurrencyCodes(tripId, excluded, MENU_LIMIT)
                .forEach((code, at) -> result.merge(code, at, Math::max));
        return newest(result, MENU_LIMIT);
    }

    void remove(String tripId, String code) {
        Set<String> hidden = new HashSet<>(hidden(tripId));
        hidden.add(code);
        Map<String, Long> recent = saved(tripId);
        recent.remove(code);
        prefs.edit().putString(key(tripId), serialize(recent))
                .putStringSet(hiddenKey(tripId), hidden).apply();
    }

    void clear(SharedPreferences.Editor editor, String tripId) {
        editor.remove(key(tripId)).remove(hiddenKey(tripId)).remove("currency:" + tripId)
                .remove("currency_history:" + tripId).remove("currencies:" + tripId)
                .remove("exchange_from_history:" + tripId).remove("exchange_to_history:" + tripId);
    }

    private Map<String, Long> saved(String tripId) {
        Map<String, Long> recent = new LinkedHashMap<>();
        String stored = prefs.getString(key(tripId), null);
        if (stored != null) {
            add(recent, stored);
        } else {
            String quick = prefs.getString("currency:" + tripId, "");
            add(recent, quick);
            String oldHistory = prefs.getString("currency_history:" + tripId, null);
            add(recent, oldHistory != null ? oldHistory : prefs.getString("currencies:" + tripId, ""));
            if (oldHistory == null && !quick.isEmpty()) add(recent, "CNY");
            add(recent, prefs.getString("exchange_from_history:" + tripId, ""));
            add(recent, prefs.getString("exchange_to_history:" + tripId, ""));
        }
        return recent;
    }

    private static void add(Map<String, Long> recent, String csv) {
        for (String entry : csv.split(",")) {
            String[] parts = entry.split(":", 2);
            String code = valid(parts[0]);
            if (code.isEmpty()) continue;
            try { recent.putIfAbsent(code, parts.length == 2 ? Long.parseLong(parts[1]) : 0L); }
            catch (NumberFormatException ignored) { }
        }
    }

    private static List<String> newest(Map<String, Long> codes, int count) {
        List<String> result = new ArrayList<>(codes.keySet());
        result.sort((left, right) -> Long.compare(codes.get(right), codes.get(left)));
        return result.subList(0, Math.min(result.size(), count));
    }

    private static String serialize(Map<String, Long> recent) {
        List<String> entries = new ArrayList<>();
        for (String code : newest(recent, STORED_LIMIT)) entries.add(code + ":" + recent.get(code));
        return String.join(",", entries);
    }

    private Set<String> hidden(String tripId) {
        return prefs.getStringSet(hiddenKey(tripId), Collections.emptySet());
    }

    private static String key(String tripId) { return "trip_currencies:" + tripId; }
    private static String hiddenKey(String tripId) { return "hidden_trip_currencies:" + tripId; }

    static String valid(String raw) {
        try { return Money.code(raw); }
        catch (IllegalArgumentException ignored) { return ""; }
    }
}
