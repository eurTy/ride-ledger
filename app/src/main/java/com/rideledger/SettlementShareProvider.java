package com.rideledger;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Shares only a particular generated settlement PNG through a read-only URI grant. */
public final class SettlementShareProvider extends ContentProvider {
    private static final String SUFFIX = ".png";
    private static final String FILE_NAME = "旅行结算单.png";

    static Uri save(Context context, Bitmap bitmap) throws IOException {
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("无法创建结算图片目录");
        File[] expired = directory.listFiles(file -> valid(file.getName())
                && System.currentTimeMillis() - file.lastModified() > TimeUnit.DAYS.toMillis(1));
        if (expired != null) for (File file : expired) file.delete();

        String name = UUID.randomUUID() + SUFFIX;
        File file = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("无法生成结算图片");
        } catch (IOException failure) {
            file.delete();
            throw failure;
        }
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(context.getPackageName() + ".settlement-share").appendPath(name).build();
    }

    private static File directory(Context context) { return new File(context.getCacheDir(), "settlement-share"); }

    private static boolean valid(String name) {
        return name.endsWith(SUFFIX) && PeerIdentity.valid(name.substring(0, name.length() - SUFFIX.length()));
    }

    private File find(Uri uri) {
        if (uri.getPathSegments().size() != 1 || !valid(uri.getLastPathSegment())) return null;
        File file = new File(directory(getContext()), uri.getLastPathSegment());
        return file.isFile() ? file : null;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return "image/png"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = find(uri);
        if (!"r".equals(mode) || file == null) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) {
        File file = find(uri);
        if (file == null) return null;
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor rows = new MatrixCursor(columns, 1);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = FILE_NAME;
            if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        rows.addRow(values);
        return rows;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
