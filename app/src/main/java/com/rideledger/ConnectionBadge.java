package com.rideledger;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;

/** Small visual marks, with full teammate names and channel descriptions for accessibility. */
final class ConnectionBadge extends TextView {
    private static final int NAME_LIMIT = 6;
    private static final int MUTED = 0xff60776a, ACTIVE = 0xff167053;
    private enum Symbol { WIFI, HOTSPOT, BLUETOOTH, OFFLINE, REFRESH, SEARCH }
    private boolean searching;

    ConnectionBadge(Context context, Runnable reconnect) {
        super(context);
        setTextSize(11);
        setTextColor(MUTED);
        setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        setMaxLines(2);
        setEllipsize(TextUtils.TruncateAt.END);
        setMinHeight(dp(44));
        setPadding(dp(4), 0, dp(4), 0);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true))
            setBackgroundResource(ripple.resourceId);
        setOnClickListener(view -> reconnect.run());
    }

    void update(LedgerStore.Trip trip, SyncEngine.Status state) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        StringBuilder description = new StringBuilder();
        boolean canReconnect = trip.people() > 1;
        searching = canReconnect && state.searching;
        if (searching) {
            icon(text, Symbol.SEARCH, ACTIVE);
            text.append(" 查找队友…");
            description.append("正在查找队友");
        } else if (state.links.isEmpty()) {
            icon(text, Symbol.OFFLINE, MUTED);
            String label = canReconnect ? "无连接" : "单人旅行";
            text.append(" ").append(label);
            description.append(label);
        } else {
            for (PeerPresence.Link link : state.links) {
                if (text.length() > 0) {
                    text.append("   ");
                    description.append("，");
                }
                String name = trip.nameOf(link.member);
                icon(text, Symbol.valueOf(link.channel.name()), link.active ? ACTIVE : MUTED);
                text.append('\u00a0').append(shortName(name));
                description.append(name).append(link.active ? "正在通过" : "刚通过")
                        .append(channelName(link.channel)).append(link.active ? "同步" : "确认连通");
            }
        }
        if (canReconnect) {
            if (!searching) {
                text.append("   ");
                icon(text, Symbol.REFRESH, MUTED);
            }
            description.append("。点击重新连接");
        }
        setText(text);
        setContentDescription(description);
        setEnabled(canReconnect);
        setFocusable(canReconnect);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (searching && isShown() && getWindowVisibility() == VISIBLE && ValueAnimator.areAnimatorsEnabled())
            postInvalidateOnAnimation();
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (isEnabled()) info.setClassName(Button.class.getName());
    }

    private static String shortName(String name) {
        if (name.codePointCount(0, name.length()) <= NAME_LIMIT) return name;
        return name.substring(0, name.offsetByCodePoints(0, NAME_LIMIT)) + "…";
    }

    private static String channelName(PeerRoutePolicy.Channel channel) {
        if (channel == PeerRoutePolicy.Channel.BLUETOOTH) return "蓝牙";
        return channel == PeerRoutePolicy.Channel.HOTSPOT ? "热点" : "Wi-Fi";
    }

    private void icon(SpannableStringBuilder text, Symbol symbol, int color) {
        Drawable mark = new Mark(symbol, color);
        mark.setBounds(0, 0, dp(12), dp(12));
        int start = text.length();
        text.append('\ufffc');
        text.setSpan(new ImageSpan(mark, ImageSpan.ALIGN_BOTTOM), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class Mark extends Drawable {
        private final Symbol symbol;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Mark(Symbol symbol, int color) {
            this.symbol = symbol;
            paint.setColor(color);
            paint.setStrokeWidth(1.6f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            int save = canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 16f, bounds.height() / 16f);
            switch (symbol) {
                case WIFI:
                    canvas.drawArc(0, 3, 16, 19, 218, 104, false, paint);
                    canvas.drawArc(3, 6, 13, 16, 218, 104, false, paint);
                    canvas.drawPoint(8, 11, paint);
                    break;
                case HOTSPOT:
                    canvas.drawCircle(8, 8, 6, paint);
                    canvas.drawCircle(8, 8, 3.1f, paint);
                    canvas.drawPoint(8, 8, paint);
                    break;
                case BLUETOOTH:
                    canvas.drawLine(7, 1.7f, 7, 14.3f, paint);
                    canvas.drawLine(7, 1.7f, 11, 5, paint);
                    canvas.drawLine(7, 14.3f, 11, 11, paint);
                    canvas.drawLine(4, 10, 11, 5, paint);
                    canvas.drawLine(4, 6, 11, 11, paint);
                    break;
                case OFFLINE:
                    canvas.drawCircle(8, 8, 5.7f, paint);
                    canvas.drawLine(4, 12, 12, 4, paint);
                    break;
                case REFRESH:
                    canvas.drawArc(2.8f, 2.8f, 13.2f, 13.2f, 20, 300, false, paint);
                    canvas.drawLine(13, 2.7f, 13, 5.7f, paint);
                    canvas.drawLine(13, 5.7f, 10, 5.7f, paint);
                    break;
                case SEARCH:
                    float angle = ValueAnimator.areAnimatorsEnabled() ? SystemClock.uptimeMillis() % 900L * 0.4f : 0f;
                    canvas.drawArc(2.8f, 2.8f, 13.2f, 13.2f, angle, 265, false, paint);
                    break;
            }
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
