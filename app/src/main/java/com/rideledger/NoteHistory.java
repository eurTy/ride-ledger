package com.rideledger;

import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Local, per-trip shortcuts. Notes enter the shared ledger only when saved as an expense. */
final class NoteHistory {
    private static final int LIMIT = 5;
    private final SharedPreferences prefs;

    NoteHistory(SharedPreferences prefs) { this.prefs = prefs; }

    List<String> options(String tripId) {
        List<String> notes = new ArrayList<>();
        String raw = prefs.getString(key(tripId), "[]");
        try {
            JSONArray saved = new JSONArray(raw);
            for (int i = 0; i < saved.length() && notes.size() < LIMIT; i++) {
                String note = saved.optString(i).trim();
                if (!note.isEmpty() && !notes.contains(note)) notes.add(note);
            }
        } catch (Exception ignored) { }
        return notes;
    }

    void remember(String tripId, String raw) {
        String note = raw.trim();
        if (note.isEmpty()) return;
        List<String> notes = options(tripId);
        notes.remove(note);
        notes.add(0, note);
        save(tripId, notes);
    }

    void clear(SharedPreferences.Editor editor, String tripId) { editor.remove(key(tripId)); }

    private void save(String tripId, List<String> notes) {
        JSONArray value = new JSONArray();
        for (int i = 0; i < notes.size() && i < LIMIT; i++) value.put(notes.get(i));
        prefs.edit().putString(key(tripId), value.toString()).apply();
    }

    private static String key(String tripId) { return "quick_notes:" + tripId; }
}
