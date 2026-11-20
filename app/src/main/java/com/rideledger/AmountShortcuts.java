package com.rideledger;

import android.content.Context;
import android.text.Editable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.LinearLayout;

final class AmountShortcuts extends LinearLayout {
    private static final String[] UNITS = {"万", "十万", "百万", "千万"};
    private final ViewTreeObserver.OnGlobalLayoutListener layoutChanged = this::updateVisibility;
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusChanged = (old, next) -> updateVisibility();

    AmountShortcuts(Context context, LedgerStyle style) {
        super(context);
        setGravity(Gravity.CENTER);
        setBaselineAligned(false);
        setBackgroundColor(style.surface);
        setPadding(style.dp(10), style.dp(4), style.dp(10), style.dp(4));
        setVisibility(GONE);

        for (int i = 0; i < UNITS.length; i++) {
            int places = 4 + i;
            Button key = style.button("×" + UNITS[i], LedgerStyle.Action.OUTLINE, () -> applyUnit(places));
            key.setTextSize(LedgerStyle.CAPTION);
            key.setMinHeight(style.dp(40));
            key.setMinimumHeight(style.dp(40));
            key.setPadding(style.dp(4), 0, style.dp(4), 0);
            key.setFocusable(false);
            LayoutParams size = new LayoutParams(0, -2, 1);
            if (i > 0) size.setMarginStart(style.dp(6));
            addView(key, size);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalLayoutListener(layoutChanged);
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusChanged);
        updateVisibility();
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalLayoutListener(layoutChanged);
        getViewTreeObserver().removeOnGlobalFocusChangeListener(focusChanged);
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        updateVisibility();
    }

    private void updateVisibility() {
        View focused = getRootView().findFocus();
        boolean typingAmount = focused instanceof FormInput.AmountField && focused.isEnabled();
        setVisibility(typingAmount && hasWindowFocus() && FormInput.keyboardVisible(this) ? VISIBLE : GONE);
    }

    private void applyUnit(int places) {
        View focused = getRootView().findFocus();
        if (!(focused instanceof FormInput.AmountField) || !focused.isEnabled()) return;
        FormInput.AmountField amount = (FormInput.AmountField) focused;
        Editable text = amount.getText();
        try {
            text.replace(0, text.length(), Money.applyUnit(text.toString(), places));
            amount.setSelection(text.length());
        } catch (NumberFormatException invalid) {
            amount.setError("请检查金额是否有效或超出范围");
        }
    }
}
