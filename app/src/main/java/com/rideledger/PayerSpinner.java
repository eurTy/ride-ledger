package com.rideledger;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Spinner;

/** Compact payer selector for the quick-entry card. The selected value remains a trip member ID. */
final class PayerSpinner extends Spinner {
    private final LedgerStyle style;

    PayerSpinner(Context context) {
        super(context, Spinner.MODE_DROPDOWN);
        style = new LedgerStyle(context);
        setId(View.generateViewId());
        setBackgroundTintList(null);
        setBackground(style.ripple(null, LedgerStyle.CONTROL_RADIUS));
        setPadding(0, 0, 0, 0);
        setMinimumHeight(style.dp(LedgerStyle.COMPACT_HEIGHT));
        setPopupBackgroundDrawable(style.menu());
        setDropDownWidth(ViewGroup.LayoutParams.MATCH_PARENT);
        setDropDownVerticalOffset(style.dp(LedgerStyle.ITEM_GAP));
    }

    void setRoster(LedgerStore.Trip trip, String selected) {
        setAdapter(new BaseAdapter() {
            @Override public int getCount() { return trip.people(); }
            @Override public String getItem(int position) { return trip.member(position); }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View recycled, ViewGroup parent) {
                return style.option(label(position), trip.people() > 1 ? "⌄" : "", false, false, true);
            }

            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                boolean selected = position == getSelectedItemPosition();
                return style.option(label(position), selected ? "✓" : "", selected, true, false);
            }

            private String label(int position) {
                String member = getItem(position);
                return trip.nameOf(member) + (member.equals(trip.localPerson) ? "（我）" : "");
            }
        });
        setSelection(Math.max(0, trip.indexOf(selected)));
        setEnabled(trip.people() > 1);
    }

}
