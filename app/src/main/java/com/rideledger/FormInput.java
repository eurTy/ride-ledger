package com.rideledger;

import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

final class FormInput {
    private FormInput() { }

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
            });
            return true;
        });
    }

    static void done(EditText field) {
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
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
