package com.rideledger;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.LocaleList;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import java.util.Locale;

final class FormInput {
    private FormInput() { }

    static final class AmountField extends EditText {
        AmountField(Context context) {
            super(context);
            setSingleLine(true);
            setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            setShowSoftInputOnFocus(false);
            setFontFeatureSettings("tnum");
        }
    }

    static final class CurrencyField extends EditText {
        CurrencyField(Context context) {
            super(context);
            setSingleLine(true);
            setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            setImeHintLocales(new LocaleList(Locale.US));
            setShowSoftInputOnFocus(false);
            setFilters(new InputFilter[]{asciiLettersFilter(), new InputFilter.AllCaps(), new InputFilter.LengthFilter(3)});
        }
    }

    private static InputFilter asciiLettersFilter() {
        return (CharSequence source, int start, int end, Spanned dest, int dstart, int dend) -> {
            boolean allAsciiLetters = true;
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                    allAsciiLetters = false;
                    break;
                }
            }
            if (allAsciiLetters) return null;
            StringBuilder clean = new StringBuilder(end - start);
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) clean.append(c);
            }
            return clean;
        };
    }

    static void hideSystemKeyboard(View view) {
        if (view == null) return;
        InputMethodManager manager = view.getContext().getSystemService(InputMethodManager.class);
        if (manager != null) manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    static void showSystemKeyboard(View view) {
        if (view == null) return;
        InputMethodManager manager = view.getContext().getSystemService(InputMethodManager.class);
        if (manager != null) manager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }

    static void next(EditText field, EditText target) {
        if (target.getId() == View.NO_ID) target.setId(View.generateViewId());
        field.setNextFocusForwardId(target.getId());
        field.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        field.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_NEXT || !target.requestFocus(View.FOCUS_FORWARD)) return false;
            target.post(() -> {
                if (!target.hasFocus()) return;
                Rect bounds = new Rect();
                target.getDrawingRect(bounds);
                target.requestRectangleOnScreen(bounds, true);
                if (target.getShowSoftInputOnFocus()) showSystemKeyboard(target);
            });
            return true;
        });
    }

    static void done(EditText field) {
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
    }

    static boolean keyboardVisible(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        return insets != null && (Build.VERSION.SDK_INT >= 30
                ? insets.isVisible(WindowInsets.Type.ime())
                : insets.getSystemWindowInsetBottom() > insets.getStableInsetBottom());
    }

    /** Give an activity's scroll viewport the space left above the keyboard and system bars. */
    static void fitActivityWindow(Window window, View root) {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT < 35) return;
        window.setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED;
        });
    }
}
