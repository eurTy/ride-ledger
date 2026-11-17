package com.rideledger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Attachment metadata and private JPEG blobs live outside the financial journal. */
final class LedgerReceipts {
    static final class Entry {
        final String eventId, digest;
        final long version;
        final boolean available;
        final int confirmed;

        Entry(String eventId, long version, String digest, boolean available, int confirmed) {
            this.eventId = eventId;
            this.version = version;
            this.digest = digest;
            this.available = available;
            this.confirmed = confirmed;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", eventId).put("version", version)
                    .put("sha256", digest).put("available", available);
        }

        static Entry parse(JSONObject body) throws JSONException {
            String id = body.getString("id"), digest = body.getString("sha256");
            long version = body.getLong("version");
            boolean available = body.getBoolean("available");
            if (!PeerIdentity.valid(id) || version < 1 || version > 1_000_000_000 ||
                    (digest.isEmpty() ? available : !digest.matches("[a-f0-9]{64}")))
                throw new JSONException("小票同步数据无效");
            return new Entry(id, version, digest, available, 0);
        }

        boolean matches(Entry other) {
            return other != null && version == other.version && digest.equals(other.digest);
        }

        boolean changedAfter(Entry other) {
            if (other == null || version > other.version) return true;
            if (version < other.version || digest.equals(other.digest)) return false;
            // Resolve concurrent replaces consistently and keep a concurrent removal removed.
            return digest.isEmpty() || !other.digest.isEmpty() && digest.compareTo(other.digest) > 0;
        }
    }

    private static final String COLUMNS = "r.event_id,r.version,r.digest,r.photo IS NOT NULL," +
            "(SELECT COUNT(*) FROM receipt_acks a WHERE a.trip_id=r.trip_id AND a.event_id=r.event_id)";
    private final LedgerStore store;

    LedgerReceipts(LedgerStore store) { this.store = store; }

    static void createTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS receipts (trip_id TEXT NOT NULL, event_id TEXT NOT NULL, " +
                "version INTEGER NOT NULL, digest TEXT NOT NULL, photo BLOB, thumb BLOB, PRIMARY KEY(trip_id,event_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS receipt_acks (trip_id TEXT NOT NULL, event_id TEXT NOT NULL, " +
                "peer TEXT NOT NULL, PRIMARY KEY(trip_id,event_id,peer))");
    }

    List<Entry> list(String tripId) {
        synchronized (store) {
            List<Entry> result = new ArrayList<>();
            try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT " + COLUMNS +
                    " FROM receipts r WHERE r.trip_id=? ORDER BY r.event_id", new String[]{tripId})) {
                while (cursor.moveToNext()) result.add(read(cursor));
            }
            return result;
        }
    }

    Entry get(String tripId, String eventId) {
        synchronized (store) { return get(store.getReadableDatabase(), tripId, eventId); }
    }

    private static Entry get(SQLiteDatabase db, String tripId, String eventId) {
        try (Cursor cursor = db.rawQuery("SELECT " + COLUMNS + " FROM receipts r WHERE r.trip_id=? AND r.event_id=?",
                new String[]{tripId, eventId})) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    private static Entry read(Cursor cursor) {
        return new Entry(cursor.getString(0), cursor.getLong(1), cursor.getString(2),
                cursor.getInt(3) != 0, cursor.getInt(4));
    }

    byte[] bytes(String tripId, Entry entry, boolean thumbnail) {
        synchronized (store) {
            try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT " + (thumbnail ? "thumb" : "photo") +
                    " FROM receipts WHERE trip_id=? AND event_id=? AND version=? AND digest=?",
                    new String[]{tripId, entry.eventId, Long.toString(entry.version), entry.digest})) {
                return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getBlob(0) : null;
            }
        }
    }

    boolean change(String tripId, String eventId, ReceiptImage image) {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                boolean changed = change(db, tripId, eventId, image);
                db.setTransactionSuccessful();
                return changed;
            } finally { db.endTransaction(); }
        }
    }

    boolean change(SQLiteDatabase db, String tripId, String eventId, ReceiptImage image) {
        if (!isExpense(db, tripId, eventId)) throw new IllegalStateException("这笔支出已删除");
        Entry current = get(db, tripId, eventId);
        String digest = image == null ? "" : image.digest;
        if (current == null && image == null ||
                current != null && digest.equals(current.digest) && (image == null || current.available)) return false;
        Entry entry = new Entry(eventId, current == null ? 1 : current.version + 1, digest, image != null, 0);
        replace(db, tripId, entry);
        if (image != null) putImage(db, tripId, entry, image);
        return true;
    }

    /** Accept metadata only after the matching expense has arrived in the ledger phase. */
    boolean apply(String tripId, Entry entry) {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            if (!entry.changedAfter(get(db, tripId, entry.eventId)) || !isExpense(db, tripId, entry.eventId)) return false;
            db.beginTransaction();
            try {
                replace(db, tripId, entry);
                db.setTransactionSuccessful();
                return true;
            } finally { db.endTransaction(); }
        }
    }

    boolean savePhoto(String tripId, Entry entry, ReceiptImage image) {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            Entry current = get(db, tripId, entry.eventId);
            if (!entry.matches(current) || current.available || !entry.digest.equals(image.digest)) return false;
            putImage(db, tripId, entry, image);
            return true;
        }
    }

    boolean acknowledge(String tripId, Entry entry, String peer) {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            if (entry.digest.isEmpty() || !entry.matches(get(db, tripId, entry.eventId))) return false;
            ContentValues values = idValues(tripId, entry.eventId);
            values.put("peer", peer);
            return db.insertWithOnConflict("receipt_acks", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
        }
    }

    private static void replace(SQLiteDatabase db, String tripId, Entry entry) {
        ContentValues values = new ContentValues();
        values.put("version", entry.version);
        values.put("digest", entry.digest);
        // Preserve locally available bytes when the same content gets a newer reference.
        values.putNull("photo");
        values.putNull("thumb");
        String[] key = {tripId, entry.eventId};
        Entry old = get(db, tripId, entry.eventId);
        if (old != null && old.digest.equals(entry.digest)) {
            values.remove("photo");
            values.remove("thumb");
        }
        if (db.update("receipts", values, "trip_id=? AND event_id=?", key) == 0) {
            values.put("trip_id", tripId);
            values.put("event_id", entry.eventId);
            db.insertOrThrow("receipts", null, values);
        }
        db.delete("receipt_acks", "trip_id=? AND event_id=?", key);
    }

    private static void putImage(SQLiteDatabase db, String tripId, Entry entry, ReceiptImage image) {
        ContentValues values = new ContentValues();
        values.put("photo", image.photo);
        values.put("thumb", image.thumb);
        db.update("receipts", values, "trip_id=? AND event_id=? AND version=?",
                new String[]{tripId, entry.eventId, Long.toString(entry.version)});
    }

    private static ContentValues idValues(String tripId, String eventId) {
        ContentValues values = new ContentValues();
        values.put("trip_id", tripId);
        values.put("event_id", eventId);
        return values;
    }

    private static boolean isExpense(SQLiteDatabase db, String tripId, String eventId) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM events e WHERE e.trip_id=? AND e.id=? AND e.kind='expense' " +
                "AND NOT EXISTS(SELECT 1 FROM events d WHERE d.trip_id=e.trip_id AND d.target_id=e.id AND d.kind='delete')",
                new String[]{tripId, eventId})) {
            return cursor.moveToFirst();
        }
    }

    static void deleteForExpense(SQLiteDatabase db, String tripId, String eventId) {
        String[] key = {tripId, eventId};
        db.delete("receipts", "trip_id=? AND event_id=?", key);
        db.delete("receipt_acks", "trip_id=? AND event_id=?", key);
    }

    static void deleteForTrip(SQLiteDatabase db, String tripId) {
        db.delete("receipts", "trip_id=?", new String[]{tripId});
        db.delete("receipt_acks", "trip_id=?", new String[]{tripId});
    }
}
