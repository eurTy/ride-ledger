package com.rideledger;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** App-private daily diagnostics. Callers pass only operation names, error types and platform codes. */
final class SyncLog {
    private static final long LIMIT = 64 * 1024;
    private static final String DIRECTORY = "nearby-sync";

    static void write(Context context, String operation, Throwable error) {
        write(context, operation, error.getClass().getSimpleName());
    }

    static synchronized void write(Context context, String operation, String code) {
        LocalDate today = LocalDate.now();
        File directory = prune(context, today);
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        File path = new File(directory, today + ".log");
        byte[] entry = (System.currentTimeMillis() + " " + operation + " " + code + "\n").getBytes(StandardCharsets.UTF_8);
        if (path.length() + entry.length > LIMIT) return;
        try (FileOutputStream stream = new FileOutputStream(path, true)) {
            stream.write(entry);
        } catch (IOException ignored) { }
    }

    static synchronized void maintain(Context context) { prune(context, LocalDate.now()); }

    private static File prune(Context context, LocalDate today) {
        File files = context.getFilesDir();
        File old = new File(files, "nearby-sync.log");
        if (old.isFile()) old.delete();
        File directory = new File(files, DIRECTORY);
        File[] logs = directory.listFiles((parent, name) -> name.endsWith(".log"));
        if (logs != null) for (File log : logs) {
            String name = log.getName();
            try {
                LocalDate date = LocalDate.parse(name.substring(0, name.length() - 4));
                if (date.isBefore(today.minusDays(6)) || date.isAfter(today)) log.delete();
            } catch (DateTimeParseException ignored) { }
        }
        return directory;
    }
}
