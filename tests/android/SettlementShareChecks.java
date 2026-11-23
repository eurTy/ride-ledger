package com.rideledger;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

final class SettlementShareChecks {
    static void run(Context context) throws Exception {
        String trip = "帕米尔高原长途旅行「同行账本」";
        String member = "超长名称测试".repeat(5);
        List<LedgerSettlement.Debt> transfers = List.of(new LedgerSettlement.Debt(member, "Eureka", List.of("76.80 CNY")));
        Bitmap image = SettlementShare.render(context, trip, "CNY", transfers,
                Map.of("USD", "7.20", "TJS", "0.64"), List.of("待同伴确认 2 条", "小票待补传 1 张"));
        int width = image.getWidth();
        int height = image.getHeight();
        Uri uri;
        try { uri = SettlementShareProvider.save(context, image); }
        finally { image.recycle(); }

        ContentResolver reader = context.getContentResolver();
        require("content".equals(uri.getScheme()) && uri.getAuthority().equals(context.getPackageName() + ".settlement-share"),
                "Export URI leaked a filesystem path or used the wrong package");
        require("image/png".equals(reader.getType(uri)), "Expected a PNG MIME type");
        try (Cursor data = reader.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            require(data != null && data.moveToFirst() && data.getString(0).endsWith(".png") && data.getLong(1) > 0,
                    "Missing share metadata");
        }
        try (InputStream input = reader.openInputStream(uri)) {
            Bitmap decoded = BitmapFactory.decodeStream(input);
            require(decoded != null, "PNG could not be decoded");
            try {
                require(decoded.getWidth() == width && decoded.getHeight() == height && height > 350,
                        "PNG does not contain the expected image bounds");
            } finally { decoded.recycle(); }
        }
        rejected(reader, uri, "w");
        rejected(reader, uri.buildUpon().appendPath("more").build(), "r");
        rejected(reader, new Uri.Builder().scheme(uri.getScheme()).authority(uri.getAuthority())
                .appendPath("../" + uri.getLastPathSegment()).build(), "r");

        List<String> confirmedScope = List.of("仅包含已同步账目 · 还有账目待同步");
        Bitmap oneCurrency = SettlementShare.render(context, trip, "", transfers, Map.of(), confirmedScope);
        Bitmap manyCurrencies = SettlementShare.render(context, trip, "",
                List.of(new LedgerSettlement.Debt(member, "Eureka", List.of("76.80 CNY", "25 USD", "600 JPY"))),
                Map.of(), confirmedScope);
        try {
            require(manyCurrencies.getHeight() > oneCurrency.getHeight(),
                    "the image omitted additional currencies for the same recipient");
        } finally {
            oneCurrency.recycle();
            manyCurrencies.recycle();
        }
    }

    private static void rejected(ContentResolver reader, Uri uri, String mode) throws Exception {
        try (ParcelFileDescriptor file = reader.openFileDescriptor(uri, mode)) {
            throw new AssertionError("Provider accepted invalid URI or mode " + mode);
        } catch (FileNotFoundException expected) { }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
