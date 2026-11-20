package com.rideledger;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** The full image and a small square thumbnail, both sized for storage in SQLite. */
final class ReceiptImage {
    static final int MAX_BYTES = 1_000_000;
    private static final int MAX_SIDE = 2800, THUMB_SIDE = 108;
    final byte[] photo, thumb;
    final String digest;

    private ReceiptImage(byte[] photo, byte[] thumb) {
        this.photo = photo;
        this.thumb = thumb;
        digest = hash(photo);
    }

    static byte[] importPhoto(ContentResolver resolver, Uri uri) throws IOException {
        Bitmap bitmap = Build.VERSION.SDK_INT >= 28
                ? ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri), (decoder, info, source) -> {
                    int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                    int longest = Math.max(width, height);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    if (longest > MAX_SIDE)
                        decoder.setTargetSize(Math.max(1, (int) ((long) width * MAX_SIDE / longest)),
                                Math.max(1, (int) ((long) height * MAX_SIDE / longest)));
                })
                : decodeLegacy(resolver, uri);
        if (bitmap == null) throw new IOException("无法读取这张照片");
        try { return compress(bitmap); }
        finally { bitmap.recycle(); }
    }

    static byte[] importBitmap(Bitmap bitmap) throws IOException {
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest <= MAX_SIDE) return compress(bitmap);
        int width = Math.max(1, (int) ((long) bitmap.getWidth() * MAX_SIDE / longest));
        int height = Math.max(1, (int) ((long) bitmap.getHeight() * MAX_SIDE / longest));
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
        try { return compress(scaled); }
        finally { scaled.recycle(); }
    }

    private static byte[] compress(Bitmap bitmap) throws IOException {
        byte[] photo = jpeg(bitmap, 87);
        if (photo.length > MAX_BYTES) photo = jpeg(bitmap, 72);
        if (photo.length > MAX_BYTES) {
            int width = Math.max(1, bitmap.getWidth() * 2 / 3);
            int height = Math.max(1, bitmap.getHeight() * 2 / 3);
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            try { photo = jpeg(scaled, 75); }
            finally { if (scaled != bitmap) scaled.recycle(); }
        }
        if (photo.length > MAX_BYTES) throw new IOException("照片过大，请选择另一张");
        return photo;
    }

    private static Bitmap decodeLegacy(ContentResolver resolver, Uri uri) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("无法读取这张照片");
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > MAX_SIDE)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap;
        try (InputStream stream = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap == null) return null;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) return bitmap;
            ExifInterface exif = new ExifInterface(stream);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix transform = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: transform.setRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: transform.setRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: transform.setRotate(270); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: transform.setRotate(90); transform.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: transform.setRotate(270); transform.postScale(-1, 1); break;
                default: return bitmap;
            }
            Bitmap corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transform, true);
            if (corrected != bitmap) bitmap.recycle();
            return corrected;
        } catch (IOException noOrientation) {
            return bitmap;
        }
    }

    static ReceiptImage fromJpeg(byte[] photo) throws IOException {
        if (photo == null || photo.length == 0 || photo.length > MAX_BYTES)
            throw new IOException("小票照片大小无效");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(photo, 0, photo.length, options);
        if (!"image/jpeg".equals(options.outMimeType) || options.outWidth <= 0 || options.outHeight <= 0 ||
                Math.max(options.outWidth, options.outHeight) > MAX_SIDE)
            throw new IOException("小票照片格式无效");
        options.inSampleSize = 1;
        while (Math.min(options.outWidth, options.outHeight) / options.inSampleSize >= THUMB_SIDE * 2 &&
                Math.max(options.outWidth, options.outHeight) / options.inSampleSize > THUMB_SIDE * 2)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap preview = BitmapFactory.decodeByteArray(photo, 0, photo.length, options);
        if (preview == null) throw new IOException("无法生成小票缩略图");
        Bitmap thumb = ThumbnailUtils.extractThumbnail(preview, THUMB_SIDE, THUMB_SIDE);
        try { return new ReceiptImage(photo, jpeg(thumb, 80)); }
        finally {
            if (thumb != preview) thumb.recycle();
            preview.recycle();
        }
    }

    static String hash(byte[] photo) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(photo);
            char[] hex = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                hex[i * 2] = Character.forDigit((digest[i] >>> 4) & 15, 16);
                hex[i * 2 + 1] = Character.forDigit(digest[i] & 15, 16);
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private static byte[] jpeg(Bitmap bitmap, int quality) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer))
            throw new IOException("无法保存小票照片");
        return buffer.toByteArray();
    }
}
