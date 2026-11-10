package com.rideledger;

import com.rideledger.LedgerStore.Event;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves immutable journal operations into the current view of each record. */
final class LedgerRevisions {
    private final Set<String> deleted = new HashSet<>();
    private final Map<String, Event> edits = new HashMap<>();

    LedgerRevisions(List<Event> events) {
        for (Event event : events) {
            String target = event.data.optString("target");
            if ("delete".equals(event.kind)) deleted.add(target);
            if ("edit".equals(event.kind)) {
                String key = target + "/" + event.data.optString("kind");
                Event previous = edits.get(key);
                if (previous == null || compare(event, previous) > 0) edits.put(key, event);
            }
        }
    }

    private static int compare(Event left, Event right) {
        int revision = Long.compare(left.data.optLong("revision"), right.data.optLong("revision"));
        return revision != 0 ? revision : left.id.compareTo(right.id);
    }

    private Event editOf(Event base) { return edits.get(base.id + "/" + base.kind); }

    long revisionOf(Event base) {
        Event edit = editOf(base);
        return edit == null ? 0 : edit.data.optLong("revision");
    }

    Event visible(Event base) {
        if ("edit".equals(base.kind) || "delete".equals(base.kind) || deleted.contains(base.id)) return null;
        Event edit = editOf(base);
        return edit == null ? base : new Event(base.id, base.tripId, base.kind, base.at,
                edit.data.optJSONObject("record"), Math.max(base.localRow, edit.localRow));
    }

    static List<Event> visibleRecords(List<Event> events) {
        LedgerRevisions revisions = new LedgerRevisions(events);
        List<Event> visible = new ArrayList<>();
        for (Event base : events) {
            Event current = revisions.visible(base);
            if (current != null) visible.add(current);
        }
        return visible;
    }
}
