package com.rideledger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Portable archive of one ledger snapshot, independent of device enrollment. */
final class LedgerBackup {
    private LedgerBackup() { }

    static boolean isBackupName(String displayName) {
        if (displayName == null) return false;
        String name = displayName.toLowerCase(Locale.ROOT);
        return name.endsWith(".rlbackup") || name.endsWith(".rlbackup.txt") || isArchiveName(name);
    }

    static boolean isArchiveName(String displayName) {
        return displayName != null && displayName.toLowerCase(Locale.ROOT).endsWith(".rlbackup.zip");
    }

    static void writeArchive(OutputStream destination, LedgerStore.Snapshot snapshot, LedgerReceipts receipts) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(destination))) {
            zip.putNextEntry(new ZipEntry("ledger.jsonl"));
            write(zip, snapshot);
            zip.closeEntry();
            JSONArray entries = new JSONArray();
            for (LedgerReceipts.Entry entry : snapshot.receipts) {
                JSONObject item = entry.json();
                if (entry.available) {
                    String path = "receipts/" + entry.eventId + ".jpg";
                    byte[] photo = receipts.bytes(snapshot.trip.id, entry, false);
                    if (photo == null) throw new IOException("备份期间小票有变动");
                    zip.putNextEntry(new ZipEntry(path));
                    zip.write(photo);
                    zip.closeEntry();
                    item.put("path", path);
                }
                entries.put(item);
            }
            zip.putNextEntry(new ZipEntry("receipts.json"));
            zip.write(line(new JSONObject().put("format", "ride-ledger-backup").put("version", 2)
                    .put("tripId", snapshot.trip.id).put("receipts", entries)));
            zip.closeEntry();
        }
    }

    static void write(OutputStream destination, LedgerStore.Snapshot snapshot) throws Exception {
        LedgerStore.Trip trip = snapshot.trip;
        if (trip == null) throw new IllegalArgumentException("旅行不存在");
        JSONObject members = new JSONObject();
        for (int i = 0; i < trip.people(); i++) {
            String member = trip.member(i);
            members.put(member, trip.nameOf(member));
        }
        JSONObject header = new JSONObject().put("format", "ride-ledger-backup").put("version", 1)
                .put("tripId", trip.id).put("tripName", trip.name).put("members", members)
                .put("localMember", trip.localPerson).put("operations", snapshot.events.size());
        BufferedOutputStream stream = new BufferedOutputStream(destination);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        writeLine(stream, digest, header);
        // The raw journal contains superseded revisions and tombstones as well as visible records.
        for (LedgerStore.Event operation : snapshot.events) writeLine(stream, digest, operation.json());
        JSONObject footer = new JSONObject().put("sha256", hex(digest.digest()));
        stream.write(line(footer));
        stream.flush();
    }

    private static void writeLine(OutputStream output, MessageDigest digest, JSONObject value) throws Exception {
        byte[] bytes = line(value);
        digest.update(bytes);
        output.write(bytes);
    }

    private static byte[] line(JSONObject value) { return (value.toString() + "\n").getBytes(StandardCharsets.UTF_8); }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }
}
