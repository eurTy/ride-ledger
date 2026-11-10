package com.rideledger;

import android.widget.TextView;

final class LedgerIcon {
    private LedgerIcon() {}

    static void attach(TextView label, int icon) {
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        label.setCompoundDrawablePadding(Math.round(7 * label.getResources().getDisplayMetrics().density));
    }
}
