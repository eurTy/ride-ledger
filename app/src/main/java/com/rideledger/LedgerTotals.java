package com.rideledger;

import com.rideledger.LedgerStore.Event;
import com.rideledger.LedgerStore.Summary;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Replays a stable ledger snapshot without accessing SQLite or the UI. */
final class LedgerTotals {
    static Summary calculate(boolean team, List<Event> all) {
        Summary summary = new Summary();
        Set<String> deleted = new HashSet<>();
        for (Event event : all) if ("delete".equals(event.kind)) deleted.add(event.data.optString("target"));
        for (Event event : all) {
            if ("delete".equals(event.kind) || deleted.contains(event.id)) continue;
            JSONObject d = event.data;
            Changes changes = new Changes();
            try {
                if (team) {
                    summarizeTeam(summary, event, changes);
                    changes.commit();
                    summary.visible.add(event);
                    continue;
                }
                switch (event.kind) {
                    case "opening": case "topup":
                        cash(changes, summary, d.getString("person"), d.getString("currency"), d.getLong("amount"));
                        break;
                    case "expense": {
                        String payer = d.getString("payer"), currency = d.getString("currency");
                        long amount = d.getLong("amount");
                        long aShare = Money.shareA(amount, d.getInt("shareA"));
                        cash(changes, summary, payer, currency, -amount);
                        changes.add(summary.spent, currency, amount);
                        changes.add(summary.debtBToA, currency, "a".equals(payer) ? amount - aShare : -aShare);
                        break;
                    }
                    case "transfer": {
                        String from = d.getString("from"), currency = d.getString("currency");
                        long amount = d.getLong("amount");
                        cash(changes, summary, from, currency, -amount);
                        cash(changes, summary, "a".equals(from) ? "b" : "a", currency, amount);
                        changes.add(summary.debtBToA, currency, "b".equals(from) ? -amount : amount);
                        break;
                    }
                    case "exchange": {
                        String person = d.getString("person");
                        cash(changes, summary, person, d.getString("fromCurrency"), -d.getLong("fromAmount"));
                        cash(changes, summary, person, d.getString("toCurrency"), d.getLong("toAmount"));
                        break;
                    }
                }
                changes.commit();
                summary.visible.add(event);
            } catch (ArithmeticException overflow) {
                summary.incomplete = true;
                summary.visible.add(event);
            } catch (JSONException invalid) { }
        }
        Collections.reverse(summary.visible);
        return summary;
    }

    private static void summarizeTeam(Summary summary, Event event, Changes changes) throws JSONException {
        JSONObject d = event.data;
        if ("exchange".equals(event.kind)) {
            Map<String, Long> cash = summary.teamCash.computeIfAbsent(d.getString("person"), id -> new HashMap<>());
            changes.add(cash, d.getString("fromCurrency"), -d.getLong("fromAmount"));
            changes.add(cash, d.getString("toCurrency"), d.getLong("toAmount"));
            return;
        }
        String currency = d.getString("currency");
        long amount = d.getLong("amount");
        switch (event.kind) {
            case "opening": case "topup":
                changes.add(summary.teamCash.computeIfAbsent(d.getString("person"), id -> new HashMap<>()), currency, amount);
                break;
            case "expense": {
                String payer = d.getString("payer");
                changes.add(summary.teamCash.computeIfAbsent(payer, id -> new HashMap<>()), currency, -amount);
                changes.add(summary.spent, currency, amount);
                changes.add(summary.teamNet.computeIfAbsent(payer, id -> new HashMap<>()), currency, amount);
                JSONObject shares = d.getJSONObject("shares");
                java.util.Iterator<String> keys = shares.keys();
                while (keys.hasNext()) {
                    String member = keys.next();
                    changes.add(summary.teamNet.computeIfAbsent(member, id -> new HashMap<>()), currency, -shares.getLong(member));
                }
                break;
            }
            case "transfer": {
                String from = d.getString("from"), to = d.getString("to");
                changes.add(summary.teamCash.computeIfAbsent(from, id -> new HashMap<>()), currency, -amount);
                changes.add(summary.teamCash.computeIfAbsent(to, id -> new HashMap<>()), currency, amount);
                changes.add(summary.teamNet.computeIfAbsent(from, id -> new HashMap<>()), currency, amount);
                changes.add(summary.teamNet.computeIfAbsent(to, id -> new HashMap<>()), currency, -amount);
                break;
            }
        }
    }

    private static void cash(Changes changes, Summary summary, String person, String currency, long delta) {
        changes.add("a".equals(person) ? summary.cashA : summary.cashB, currency, delta);
    }

    private static final class Changes {
        private final Map<Map<String, Long>, Map<String, Long>> totals = new IdentityHashMap<>();

        void add(Map<String, Long> account, String currency, long delta) {
            Map<String, Long> pending = totals.computeIfAbsent(account, ignored -> new HashMap<>());
            pending.put(currency, Math.addExact(pending.getOrDefault(currency, 0L), delta));
        }

        void commit() {
            // Validate all balances before mutating any of them.
            for (Map.Entry<Map<String, Long>, Map<String, Long>> entry : totals.entrySet()) {
                Map<String, Long> account = entry.getKey();
                entry.getValue().replaceAll((currency, delta) ->
                        Math.addExact(account.getOrDefault(currency, 0L), delta));
            }
            totals.forEach(Map::putAll);
        }
    }
}
