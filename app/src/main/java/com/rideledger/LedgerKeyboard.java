package com.rideledger;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Editable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/** Built-in numeric (with 00/000) and uppercase English QWERTY keyboard for amounts and currencies. */
final class LedgerKeyboard extends LinearLayout {
    private static final int KEY_HEIGHT = 48;
    private static final int ROW_GAP = 6;
    private static final int MAX_AMOUNT_LENGTH = 18;

    private final LedgerStyle style;
    private final View focusSink;
    private final Consumer<Boolean> onVisibilityChanged;
    private final LinearLayout numericPad;
    private final LinearLayout englishPad;
    private final TextView numericAction;
    private final TextView englishAction;
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusChanged = (oldView, newView) -> updateVisibility();

    LedgerKeyboard(Context context, LedgerStyle style, View focusSink) {
        this(context, style, focusSink, null);
    }

    LedgerKeyboard(Context context, LedgerStyle style, View focusSink, Consumer<Boolean> onVisibilityChanged) {
        super(context);
        this.style = style;
        this.focusSink = focusSink;
        this.onVisibilityChanged = onVisibilityChanged;
        setOrientation(VERTICAL);
        setBackgroundColor(style.page);
        setVisibility(GONE);

        View hairline = new View(context);
        hairline.setBackgroundColor(style.divider);
        addView(hairline, new LayoutParams(-1, style.dp(1)));

        FrameLayout deck = new FrameLayout(context);
        deck.setPadding(style.dp(8), style.dp(8), style.dp(8), style.dp(10));
        addView(deck, new LayoutParams(-1, -2));

        numericPad = new LinearLayout(context);
        numericPad.setOrientation(VERTICAL);
        numericAction = buildActionKey();
        buildNumericPad();
        deck.addView(numericPad, new FrameLayout.LayoutParams(-1, -2));

        englishPad = new LinearLayout(context);
        englishPad.setOrientation(VERTICAL);
        englishPad.setVisibility(GONE);
        englishAction = buildActionKey();
        buildEnglishPad();
        deck.addView(englishPad, new FrameLayout.LayoutParams(-1, -2));
    }

    private void buildNumericPad() {
        String[][] rows = {
                {"1", "2", "3", "⌫"},
                {"4", "5", "6", "00"},
                {"7", "8", "9", "000"},
                {".", "0", "⌄", "ACTION"}
        };
        int gap = style.dp(ROW_GAP);
        int keyHeight = style.dp(KEY_HEIGHT);
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setBaselineAligned(false);
            LayoutParams rowParams = new LayoutParams(-1, keyHeight);
            if (r > 0) rowParams.topMargin = gap;
            for (int c = 0; c < rows[r].length; c++) {
                String label = rows[r][c];
                View key;
                if ("ACTION".equals(label)) {
                    key = numericAction;
                } else if ("⌫".equals(label) || "⌄".equals(label) || "00".equals(label) || "000".equals(label)) {
                    boolean symbol = "⌫".equals(label) || "⌄".equals(label);
                    TextView button = keyView(label, symbol ? 18 : 17, symbol ? style.primary : style.title,
                            style.soft, style.stroke, 10);
                    if ("00".equals(label) || "000".equals(label)) button.setFontFeatureSettings("tnum");
                    if ("⌫".equals(label)) {
                        button.setContentDescription("退格，长按清空");
                        button.setOnClickListener(v -> backspace());
                        button.setOnLongClickListener(v -> clearField());
                    } else if ("⌄".equals(label)) {
                        button.setContentDescription("收起键盘");
                        button.setOnClickListener(v -> {
                            LedgerStyle.hapticTick(v);
                            dismiss();
                        });
                    } else {
                        button.setContentDescription("00".equals(label) ? "输入两个零" : "输入三个零");
                        button.setOnClickListener(v -> insertNumeric(label));
                    }
                    key = button;
                } else {
                    TextView digit = keyView(label, 20, style.title, style.surface, style.stroke, 10);
                    digit.setFontFeatureSettings("tnum");
                    digit.setOnClickListener(v -> insertNumeric(label));
                    key = digit;
                }
                LayoutParams params = new LayoutParams(0, -1, 1f);
                if (c > 0) params.setMarginStart(gap);
                row.addView(key, params);
            }
            numericPad.addView(row, rowParams);
        }
    }

    private void buildEnglishPad() {
        int rowGap = style.dp(ROW_GAP);
        int keyGap = style.dp(4);
        int keyHeight = style.dp(KEY_HEIGHT);
        String[] letters = {"QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"};
        for (int r = 0; r < letters.length; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setBaselineAligned(false);
            LayoutParams rowParams = new LayoutParams(-1, keyHeight);
            if (r > 0) rowParams.topMargin = rowGap;
            if (r == 1) addSpacer(row, 0.5f);
            else if (r == 2) addSpacer(row, 1.25f);

            String line = letters[r];
            for (int i = 0; i < line.length(); i++) {
                String ch = String.valueOf(line.charAt(i));
                TextView key = keyView(ch, 17, style.title, style.surface, style.stroke, 9);
                key.setPadding(0, 0, 0, 0);
                key.setIncludeFontPadding(false);
                key.setOnClickListener(v -> insertLetter(ch));
                LayoutParams params = new LayoutParams(0, -1, 1f);
                if (i > 0 || r > 0) params.setMarginStart(keyGap);
                row.addView(key, params);
            }

            if (r == 1) {
                LayoutParams rightSpacer = new LayoutParams(0, -1, 0.5f);
                rightSpacer.setMarginStart(keyGap);
                View spacer = new View(getContext());
                row.addView(spacer, rightSpacer);
            } else if (r == 2) {
                TextView del = keyView("⌫", 18, style.primary, style.soft, style.stroke, 9);
                del.setPadding(0, 0, 0, 0);
                del.setContentDescription("退格，长按清空");
                del.setOnClickListener(v -> backspace());
                del.setOnLongClickListener(v -> clearField());
                LayoutParams delParams = new LayoutParams(0, -1, 1.75f);
                delParams.setMarginStart(keyGap);
                row.addView(del, delParams);
            }
            englishPad.addView(row, rowParams);
        }

        LinearLayout bottomRow = new LinearLayout(getContext());
        bottomRow.setOrientation(HORIZONTAL);
        bottomRow.setBaselineAligned(false);
        LayoutParams bottomParams = new LayoutParams(-1, keyHeight);
        bottomParams.topMargin = rowGap;

        TextView collapse = keyView("⌄", 18, style.primary, style.soft, style.stroke, 10);
        collapse.setContentDescription("收起键盘");
        collapse.setOnClickListener(v -> {
            LedgerStyle.hapticTick(v);
            dismiss();
        });
        bottomRow.addView(collapse, new LayoutParams(0, -1, 1f));

        TextView clear = keyView("清空", 15, style.title, style.soft, style.stroke, 10);
        clear.setContentDescription("清空货币代码");
        clear.setOnClickListener(v -> clearField());
        LayoutParams clearParams = new LayoutParams(0, -1, 1.2f);
        clearParams.setMarginStart(rowGap);
        bottomRow.addView(clear, clearParams);

        LayoutParams actionParams = new LayoutParams(0, -1, 1.8f);
        actionParams.setMarginStart(rowGap);
        bottomRow.addView(englishAction, actionParams);
        englishPad.addView(bottomRow, bottomParams);
    }

    private void addSpacer(LinearLayout row, float weight) {
        row.addView(new View(getContext()), new LayoutParams(0, -1, weight));
    }

    private TextView buildActionKey() {
        TextView action = keyView("完成", 15, style.surface, style.primary, 0, 10);
        action.setOnClickListener(v -> performAction());
        return action;
    }

    private TextView keyView(String label, int sp, int textColor, int fill, int stroke, int radius) {
        TextView view = style.text(label, sp, textColor);
        view.setTypeface(LedgerStyle.MEDIUM != null ? LedgerStyle.MEDIUM : Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setBackground(style.ripple(style.rounded(fill, stroke, radius), radius));
        return view;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusChanged);
        updateVisibility();
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalFocusChangeListener(focusChanged);
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        updateVisibility();
    }

    private void updateVisibility() {
        View focused = getRootView().findFocus();
        boolean numeric = focused instanceof FormInput.AmountField && focused.isEnabled() && hasWindowFocus();
        boolean english = focused instanceof FormInput.CurrencyField && focused.isEnabled() && hasWindowFocus();
        boolean show = numeric || english;
        if (!show) {
            if (getVisibility() == VISIBLE) {
                setVisibility(GONE);
                if (onVisibilityChanged != null) onVisibilityChanged.accept(false);
            }
            return;
        }
        FormInput.hideSystemKeyboard(focused);
        numericPad.setVisibility(numeric ? VISIBLE : GONE);
        englishPad.setVisibility(english ? VISIBLE : GONE);
        EditText field = (EditText) focused;
        boolean isNext = (field.getImeOptions() & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_NEXT;
        String actionText = isNext ? "下一项" : "完成";
        numericAction.setText(actionText);
        englishAction.setText(actionText);
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            if (onVisibilityChanged != null) onVisibilityChanged.accept(true);
            if (ValueAnimator.areAnimatorsEnabled()) {
                setAlpha(0f);
                animate().alpha(1f).setDuration(110).start();
            } else {
                setAlpha(1f);
            }
            focused.post(() -> {
                if (!focused.hasFocus()) return;
                Rect bounds = new Rect();
                focused.getDrawingRect(bounds);
                focused.requestRectangleOnScreen(bounds, true);
            });
        }
    }

    private EditText activeField() {
        View focused = getRootView().findFocus();
        if ((focused instanceof FormInput.AmountField || focused instanceof FormInput.CurrencyField) && focused.isEnabled()) {
            return (EditText) focused;
        }
        return null;
    }

    private void insertNumeric(String token) {
        EditText target = activeField();
        if (!(target instanceof FormInput.AmountField)) return;
        Editable text = target.getText();
        int rawStart = target.getSelectionStart();
        int rawEnd = target.getSelectionEnd();
        int start = rawStart < 0 ? text.length() : Math.min(rawStart, rawEnd);
        int end = rawEnd < 0 ? text.length() : Math.max(rawStart, rawEnd);
        String before = text.subSequence(0, start).toString();
        String after = text.subSequence(end, text.length()).toString();

        String insert = token;
        if (".".equals(token)) {
            if ((before + after).indexOf('.') >= 0) {
                LedgerStyle.hapticReject(this);
                return;
            }
            if (before.isEmpty()) insert = "0.";
        } else {
            boolean zeros = "0".equals(token) || "00".equals(token) || "000".equals(token);
            if (before.isEmpty() && after.isEmpty() && zeros) {
                text.replace(0, text.length(), "0");
                target.setSelection(1);
                LedgerStyle.hapticTick(this);
                return;
            }
            if ("0".equals(before) && after.isEmpty()) {
                if (zeros) {
                    text.replace(0, text.length(), "0");
                    target.setSelection(1);
                } else {
                    text.replace(0, text.length(), token);
                }
                LedgerStyle.hapticTick(this);
                return;
            }
        }

        if (before.length() + insert.length() + after.length() > MAX_AMOUNT_LENGTH) {
            LedgerStyle.hapticReject(this);
            return;
        }
        text.replace(start, end, insert);
        LedgerStyle.hapticTick(this);
    }

    private void insertLetter(String letter) {
        EditText target = activeField();
        if (!(target instanceof FormInput.CurrencyField)) return;
        Editable text = target.getText();
        int rawStart = target.getSelectionStart();
        int rawEnd = target.getSelectionEnd();
        int start = rawStart < 0 ? text.length() : Math.min(rawStart, rawEnd);
        int end = rawEnd < 0 ? text.length() : Math.max(rawStart, rawEnd);
        if (start == end && text.length() >= 3) {
            LedgerStyle.hapticReject(this);
            return;
        }
        text.replace(start, end, letter);
        LedgerStyle.hapticTick(this);
    }

    private void backspace() {
        EditText target = activeField();
        if (target == null) return;
        Editable text = target.getText();
        int rawStart = target.getSelectionStart();
        int rawEnd = target.getSelectionEnd();
        int start = rawStart < 0 ? text.length() : Math.min(rawStart, rawEnd);
        int end = rawEnd < 0 ? text.length() : Math.max(rawStart, rawEnd);
        if (start != end) text.delete(start, end);
        else if (start > 0) text.delete(start - 1, start);
        LedgerStyle.hapticTick(this);
    }

    private boolean clearField() {
        EditText target = activeField();
        if (target == null) return false;
        target.getText().clear();
        LedgerStyle.hapticTick(this);
        return true;
    }

    private void performAction() {
        EditText target = activeField();
        if (target == null) {
            dismiss();
            return;
        }
        LedgerStyle.hapticConfirm(this);
        int action = target.getImeOptions() & EditorInfo.IME_MASK_ACTION;
        if (action == EditorInfo.IME_ACTION_NEXT) target.onEditorAction(EditorInfo.IME_ACTION_NEXT);
        else dismiss();
    }

    boolean dismiss() {
        if (getVisibility() != VISIBLE) return false;
        setVisibility(GONE);
        if (onVisibilityChanged != null) onVisibilityChanged.accept(false);
        View focused = getRootView().findFocus();
        if (focusSink != null) focusSink.requestFocus();
        if (focused instanceof EditText) focused.clearFocus();
        return true;
    }
}
