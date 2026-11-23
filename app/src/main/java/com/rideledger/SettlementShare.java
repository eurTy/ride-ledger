package com.rideledger;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.widget.Button;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Shares the displayed exchange settlement as a portable, locally generated image. */
final class SettlementShare {
    private static final int WIDTH = 900;
    private static final int INSET = 64;
    private static final int MAX_HEIGHT = 8192;

    private SettlementShare() { }

    static void send(Activity activity, Executor executor, Button button, String tripName, String currency,
                     List<LedgerSettlement.Debt> debts, Map<String, String> rates, List<String> scope) {
        button.setEnabled(false);
        Context context = activity.getApplicationContext();
        executor.execute(() -> {
            Uri uri;
            try {
                Bitmap bitmap = render(context, tripName, currency, debts, rates, scope);
                try { uri = SettlementShareProvider.save(context, bitmap); }
                finally { bitmap.recycle(); }
            } catch (IOException | IllegalArgumentException failure) {
                activity.runOnUiThread(() -> {
                    button.setEnabled(true);
                    if (button.isAttachedToWindow()) showFailure(activity);
                });
                return;
            }
            activity.runOnUiThread(() -> {
                button.setEnabled(true);
                if (activity.isFinishing() || activity.isDestroyed() || !button.isAttachedToWindow()) return;
                Intent share = new Intent(Intent.ACTION_SEND).setType("image/png")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                share.setClipData(ClipData.newUri(activity.getContentResolver(), "旅行结算单", uri));
                try { activity.startActivity(Intent.createChooser(share, "分享结算单")); }
                catch (android.content.ActivityNotFoundException unavailable) { showFailure(activity); }
            });
        });
    }

    private static void showFailure(Context context) {
        Toast.makeText(context, "无法分享结算单，请重试", Toast.LENGTH_SHORT).show();
    }

    static Bitmap render(Context context, String tripName, String currency,
                         List<LedgerSettlement.Debt> debts, Map<String, String> rates, List<String> scope) throws IOException {
        LedgerStyle style = new LedgerStyle(context);
        Card card = new Card();
        card.text(tripName, 54, style.title, Typeface.DEFAULT_BOLD, 10);
        boolean originalCurrency = currency.isEmpty();
        card.text(originalCurrency ? "原币结算" : "汇率结算 · " + currency,
                30, style.primary, LedgerStyle.MEDIUM, 32);
        card.rule(style.stroke);

        if (debts.isEmpty()) {
            card.text(originalCurrency ? "当前统计范围内无需转账" : "折算后无需转账",
                    38, style.title, Typeface.DEFAULT_BOLD, 24);
        }
        for (LedgerSettlement.Debt debt : debts) {
            card.text(debt.payer + " 应还 " + debt.recipient, 34, style.ink, LedgerStyle.MEDIUM, 4);
            for (String amount : debt.amounts)
                card.text(amount, 48, style.title, Typeface.DEFAULT_BOLD, 0);
            card.y += 24;
        }

        if (!rates.isEmpty()) {
            card.rule(style.stroke);
            card.text("本次汇率", 28, style.primary, LedgerStyle.MEDIUM, 12);
            rates.forEach((source, rate) -> card.text("1 " + source + " = " + rate.trim() + " " + currency,
                    30, style.ink, Typeface.DEFAULT, 8));
            card.y += 16;
        }

        card.rule(style.stroke);
        for (String detail : scope) card.text(detail, 27, style.muted, Typeface.DEFAULT, 6);
        int height = card.y + 44;
        if (height > MAX_HEIGHT) throw new IOException("结算内容超出图片尺寸");
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(style.page);
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(style.surface);
        canvas.drawRoundRect(22, 22, WIDTH - 22, height - 22, 24, 24, background);
        card.draw(canvas);
        return bitmap;
    }

    private static final class Card {
        final List<Line> lines = new ArrayList<>();
        int y = 58;

        void text(String value, int size, int color, Typeface face, int spacing) {
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(size);
            paint.setColor(color);
            paint.setTypeface(face);
            StaticLayout text = StaticLayout.Builder.obtain(value, 0, value.length(), paint, WIDTH - 2 * INSET)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build();
            lines.add(new Line(text, y, color));
            y += text.getHeight() + spacing;
        }

        void rule(int color) {
            lines.add(new Line(null, y, color));
            y += 26;
        }

        void draw(Canvas canvas) {
            Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
            rule.setStrokeWidth(2);
            for (Line line : lines) {
                if (line.text == null) {
                    rule.setColor(line.color);
                    canvas.drawLine(INSET, line.y, WIDTH - INSET, line.y, rule);
                } else {
                    canvas.save();
                    canvas.translate(INSET, line.y);
                    line.text.draw(canvas);
                    canvas.restore();
                }
            }
        }
    }

    private static final class Line {
        final StaticLayout text;
        final int y, color;

        Line(StaticLayout text, int y, int color) {
            this.text = text;
            this.y = y;
            this.color = color;
        }
    }
}
