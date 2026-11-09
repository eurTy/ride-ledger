package com.rideledger;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.Decoder;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.detector.Detector;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/** Carries the existing invitation as-is. Encoding and decoding run on the device. */
final class InviteQr {
    private static final int MAX_IMAGE_SIDE = 2048;
    private static final Map<DecodeHintType, Boolean> SCAN_HINTS =
            Collections.singletonMap(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    private InviteQr() { }

    static boolean isTeamCode(String value) {
        return value != null && value.length() <= 4096 && !Team.invitePrefix(value).isEmpty();
    }

    static Bitmap image(String invite, int size) throws WriterException {
        BitMatrix matrix = new QRCodeWriter().encode(invite, BarcodeFormat.QR_CODE, size, size,
                Collections.singletonMap(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        int width = matrix.getWidth(), height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    static String readFrame(byte[] frame, int width, int height) {
        if (frame == null || width <= 0 || height <= 0 || frame.length < (long) width * height) return null;
        return decode(new PlanarYUVLuminanceSource(frame, width, height, 0, 0, width, height, false));
    }

    static String readImage(ContentResolver resolver, Uri image) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(image)) {
            BitmapFactory.decodeStream(input, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        int longest = Math.max(options.outWidth, options.outHeight);
        options.inSampleSize = 1;
        while (longest / options.inSampleSize > MAX_IMAGE_SIDE) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap;
        try (InputStream input = resolver.openInputStream(image)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) return null;
        try {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            return decode(new RGBLuminanceSource(width, height, pixels));
        } finally { bitmap.recycle(); }
    }

    private static String decode(LuminanceSource source) {
        BitMatrix image;
        try { image = new HybridBinarizer(source).getBlackMatrix(); }
        catch (ReaderException ignored) { return null; }
        Decoder decoder = new Decoder();
        for (int turn = 0; turn < 4; turn++) {
            // Finder-pattern detection scans by row. Reuse the thresholded image at each angle.
            if (turn > 0) image.rotate90();
            try {
                BitMatrix modules = new Detector(image).detect(SCAN_HINTS).getBits();
                return decoder.decode(modules, SCAN_HINTS).getText();
            } catch (ReaderException ignored) { }
        }
        return null;
    }
}
