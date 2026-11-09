package com.rideledger;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/** Compact payer selector for the quick-entry card. The selected value remains a trip member ID. */
final class PayerSpinner extends Spinner {
    private static final int INK = 0xff154b3c, MUTED = 0xff60796b, STROKE = 0xffdae7dd;

    PayerSpinner(Context context) {
        super(context, Spinner.MODE_DROPDOWN);
        setId(View.generateViewId());
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x1f167053), null, surface(Color.WHITE, 0, 10)));
        setPadding(0, 0, 0, 0);
        setPopupBackgroundDrawable(new InsetDrawable(surface(Color.WHITE, STROKE, 14), 0, dp(4), 0, dp(4)));
        setDropDownWidth(ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // Material's Spinner popup overlaps its anchor. Place the rounded list just below it.
        setDropDownVerticalOffset(height + dp(3));
    }

    void setRoster(LedgerStore.Trip trip, String selected) {
        setAdapter(new BaseAdapter() {
            @Override public int getCount() { return trip.people(); }
            @Override public String getItem(int position) { return trip.member(position); }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View recycled, ViewGroup parent) {
                return row(label(position), trip.people() > 1 ? "⌄" : "", false, false);
            }

            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                boolean selected = position == getSelectedItemPosition();
                LinearLayout inset = new LinearLayout(getContext());
                inset.setPadding(dp(5), dp(2), dp(5), dp(2));
                LinearLayout option = row(label(position), selected ? "✓" : "", selected, true);
                option.setBackground(ripple(surface(selected ? 0xffe7f4ec : Color.WHITE, 0, 10)));
                inset.addView(option, new LinearLayout.LayoutParams(-1, -2));
                return inset;
            }

            private String label(int position) {
                String member = getItem(position);
                return trip.nameOf(member) + (member.equals(trip.localPerson) ? "（我）" : "");
            }
        });
        setSelection(Math.max(0, trip.indexOf(selected)));
        setEnabled(trip.people() > 1);
    }

    private LinearLayout row(String name, String mark, boolean selected, boolean dropdown) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setPadding(dp(dropdown ? 12 : 4), 0, dp(dropdown ? 10 : 8), 0);
        TextView label = new TextView(context);
        label.setText(name);
        label.setTextSize(15);
        label.setTextColor(INK);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        if (selected) label.setTypeface(label.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams labelWidth = new LinearLayout.LayoutParams(0, -2, 1);
        if (!dropdown) {
            label.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            labelWidth.setMarginEnd(dp(8));
        }
        row.addView(label, labelWidth);
        TextView indicator = new TextView(context);
        indicator.setText(mark);
        indicator.setTextSize(16);
        indicator.setGravity(Gravity.CENTER);
        indicator.setTextColor(selected ? INK : MUTED);
        indicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(indicator, new LinearLayout.LayoutParams(dp(dropdown ? 20 : 16), -2));
        return row;
    }

    private static RippleDrawable ripple(GradientDrawable surface) {
        return new RippleDrawable(ColorStateList.valueOf(0x1f167053), surface, null);
    }

    private GradientDrawable surface(int fill, int stroke, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        return background;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
