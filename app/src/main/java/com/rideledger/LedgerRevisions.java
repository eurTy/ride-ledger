package com.rideledger;

import com.rideledger.LedgerStore.Event;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves immutable edits. Financial fields move together, independently of the note. */
final class LedgerRevisions {
    static final int FINANCIAL = 1, NOTE = 2;
    private final Set<String> deleted = new HashSet<>();
    private final Map<String, List<Event>> edits = new HashMap<>();

    LedgerRevisions(List<Event> events) {
        for (Event event : events) {
            String target = event.data.optString("target");
            if ("delete".equals(event.kind)) deleted.add(target);
            if ("edit".equals(event.kind))
                edits.computeIfAbsent(key(target, event.data.optString("kind")), ignored -> new ArrayList<>()).add(event);
        }
    }

    private static String key(String target, String kind) { return target + "/" + kind; }

    private static int compare(Event left, Event right) {
        int revision = Long.compare(left.data.optLong("revision"), right.data.optLong("revision"));
        return revision != 0 ? revision : left.id.compareTo(right.id);
    }

    static JSONObject financial(JSONObject data) throws JSONException {
        JSONObject copy = new JSONObject(data.toString());
        copy.remove("note");
        return copy;
    }

    static JSONObject combine(JSONObject data, String note, boolean hasNote) throws JSONException {
        JSONObject copy = financial(data);
        if (hasNote || !note.isEmpty()) copy.put("note", note);
        return copy;
    }

    static final class Resolution {
        final Event base;
        final List<Event> finances = new ArrayList<>(), notes = new ArrayList<>();
        long revision, row;
        JSONObject baseline;

        Resolution(Event base, List<Event> changes) {
            this.base = base;
            this.row = base.localRow;
            baseline = base.data;
            Event old = null;
            Map<String, Event> grouped = new HashMap<>();
            for (Event change : changes) {
                row = Math.max(row, change.localRow);
                revision = Math.max(revision, change.data.optLong("revision"));
                if (change.data.has("parts")) grouped.put(change.id, change);
                else if (old == null || compare(change, old) > 0) old = change;
            }
            if (old != null) baseline = old.data.optJSONObject("record");
            findHeads(grouped, FINANCIAL, finances);
            findHeads(grouped, NOTE, notes);
        }

        private static void findHeads(Map<String, Event> changes, int part, List<Event> heads) {
            Set<String> superseded = new HashSet<>();
            for (Event edit : changes.values()) {
                if ((edit.data.optInt("parts") & part) == 0) continue;
                JSONArray parents = edit.data.optJSONArray("parents");
                for (int i = 0; i < parents.length(); i++) {
                    Event parent = changes.get(parents.optString(i));
                    if (parent != null && parent.data.optLong("revision") < edit.data.optLong("revision"))
                        superseded.add(parent.id);
                }
            }
            for (Event edit : changes.values())
                if ((edit.data.optInt("parts") & part) != 0 && !superseded.contains(edit.id)) heads.add(edit);
            heads.sort((a, b) -> compare(b, a));
        }

        JSONArray parents(int parts) {
            Set<String> ids = new LinkedHashSet<>();
            if ((parts & FINANCIAL) != 0) for (Event edit : finances) ids.add(edit.id);
            if ((parts & NOTE) != 0) for (Event edit : notes) ids.add(edit.id);
            return new JSONArray(ids);
        }

        Event current() throws Exception {
            JSONObject money = value(finances), note = value(notes);
            List<JSONObject> financialOptions = new ArrayList<>();
            Set<String> distinct = new HashSet<>();
            for (Event edit : finances) {
                JSONObject fields = financial(edit.data.getJSONObject("record"));
                if (distinct.add(PeerWire.canonical(fields))) financialOptions.add(fields);
            }
            Set<String> noteOptions = new LinkedHashSet<>();
            for (Event edit : notes) noteOptions.add(edit.data.getJSONObject("record").optString("note"));
            return new Event(base.id, base.tripId, base.kind, base.at,
                    combine(money, note.optString("note"), note.has("note")), row,
                    financialOptions.size() < 2 ? Collections.emptyList() : financialOptions,
                    noteOptions.size() < 2 ? Collections.emptyList() : new ArrayList<>(noteOptions));
        }

        private JSONObject value(List<Event> heads) {
            return heads.isEmpty() ? baseline : heads.get(0).data.optJSONObject("record");
        }
    }

    Resolution resolve(Event base) {
        if ("edit".equals(base.kind) || "delete".equals(base.kind) || deleted.contains(base.id)) return null;
        List<Event> changes = edits.get(key(base.id, base.kind));
        return new Resolution(base, changes == null ? Collections.emptyList() : changes);
    }

    static List<Event> visibleRecords(List<Event> events) {
        LedgerRevisions revisions = new LedgerRevisions(events);
        List<Event> visible = new ArrayList<>();
        for (Event base : events) {
            if ("edit".equals(base.kind) || "delete".equals(base.kind) || revisions.deleted.contains(base.id)) continue;
            List<Event> changes = revisions.edits.get(key(base.id, base.kind));
            if (changes == null) visible.add(base);
            else try { visible.add(new Resolution(base, changes).current()); }
            catch (Exception error) { throw new IllegalStateException("无法读取账目修订", error); }
        }
        return visible;
    }
}
