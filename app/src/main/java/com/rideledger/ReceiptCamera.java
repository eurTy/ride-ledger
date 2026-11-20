package com.rideledger;

import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Gives the system camera temporary access to exactly one capture file. */
public final class ReceiptCamera extends ContentProvider {
    static Uri uri(Context context, String token) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".receipt-camera")
                .appendPath(token + ".jpg").build();
    }

    static File file(Context context, String token) {
        if (!PeerIdentity.valid(token)) throw new IllegalArgumentException("Invalid receipt capture token");
        return new File(new File(context.getCacheDir(), "receipt-camera"), token + ".jpg");
    }

    private File file(Uri uri) {
        if (uri.getPathSegments().size() != 1) throw new IllegalArgumentException("Invalid capture URI");
        String name = uri.getLastPathSegment();
        return file(getContext(), name.endsWith(".jpg") ? name.substring(0, name.length() - 4) : name);
    }

    static byte[] readResult(Context context, String token, Intent result) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri output = uri(context, token);
        Uri returned = result == null ? null : result.getData();
        ClipData clip = result == null ? null : result.getClipData();
        Uri shared = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getUri();
        IOException failure = null;
        for (Uri candidate : new Uri[]{output, returned, shared}) {
            if (candidate == null || !ContentResolver.SCHEME_CONTENT.equals(candidate.getScheme())) continue;
            if (output.equals(candidate) && file(context, token).length() == 0) continue;
            try {
                return ReceiptImage.importPhoto(resolver, candidate);
            } catch (IOException | RuntimeException unreadable) {
                failure = new IOException("无法读取相机返回的照片", unreadable);
            }
        }
        if (result != null) {
            Bitmap thumbnail = Build.VERSION.SDK_INT >= 33 ? result.getParcelableExtra("data", Bitmap.class)
                    : result.getParcelableExtra("data");
            if (thumbnail != null) return ReceiptImage.importBitmap(thumbnail);
        }
        throw new IOException("相机没有返回可用照片，请重新拍照或从相册选择", failure);
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/jpeg"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = file(uri);
        if (!file.isFile()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File file = file(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns, 1);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "receipt.jpg";
            if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        result.addRow(values);
        return result;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
