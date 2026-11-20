package com.rideledger;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;
import java.util.function.IntConsumer;

/** Appearance shared by the app's platform views. Sizes are in dp or sp as appropriate. */
final class LedgerStyle {
    static final int PAGE_INSET = 16, CARD_INSET = 14, SECTION_GAP = 12, CONTROL_GAP = 8, ITEM_GAP = 4;
    static final int CONTROL_HEIGHT = 48, COMPACT_HEIGHT = 44;
    static final int CARD_RADIUS = 14, CONTROL_RADIUS = 10, SHEET_RADIUS = 20;
    static final int PAGE_TITLE = 24, SECTION_TITLE = 18, LABEL = 14, BODY = 15, CAPTION = 13, QUICK_AMOUNT = 32;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    enum Action { PRIMARY, OUTLINE, QUIET, DANGER, TINT }

    final int page, surface, ink, title, muted, hint, primary, selected, soft, stroke, fieldLine, divider;
    final int danger, dangerStroke, warning, pressed, pendingRecord, confirmedRecord;
    private final Context context;

    LedgerStyle(Context context) {
        this.context = context;
        page = context.getColor(R.color.ledger_page);
        surface = context.getColor(R.color.ledger_surface);
        ink = context.getColor(R.color.ledger_ink);
        title = context.getColor(R.color.ledger_title);
        muted = context.getColor(R.color.ledger_muted);
        hint = context.getColor(R.color.ledger_hint);
        primary = context.getColor(R.color.ledger_primary);
        selected = context.getColor(R.color.ledger_selected);
        soft = context.getColor(R.color.ledger_soft);
        stroke = context.getColor(R.color.ledger_stroke);
        fieldLine = context.getColor(R.color.ledger_field_line);
        divider = context.getColor(R.color.ledger_divider);
        danger = context.getColor(R.color.ledger_danger);
        dangerStroke = context.getColor(R.color.ledger_danger_stroke);
        warning = context.getColor(R.color.ledger_warning);
        pressed = context.getColor(R.color.ledger_pressed);
        pendingRecord = context.getColor(R.color.ledger_pending_record);
        confirmedRecord = context.getColor(R.color.ledger_confirmed_record);
    }

    int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    GradientDrawable rounded(int fill, int outline, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        if (outline != Color.TRANSPARENT) background.setStroke(dp(1), outline);
        return background;
    }

    GradientDrawable card() { return rounded(surface, stroke, CARD_RADIUS); }

    GradientDrawable sheet() {
        GradientDrawable background = rounded(surface, Color.TRANSPARENT, 0);
        float radius = dp(SHEET_RADIUS);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return background;
    }

    RippleDrawable ripple(Drawable background, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(pressed), background, rounded(Color.WHITE, 0, radius));
    }

    Drawable menu() { return context.getDrawable(R.drawable.ledger_menu_background); }

    TextView text(CharSequence value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    TextView heading(CharSequence value, int sp) {
        TextView view = text(value, sp, title);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }

    LinearLayout tripTitleRow(String name, View trailing) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);
        row.setPadding(0, dp(18), 0, 0);
        TextView heading = heading(name, PAGE_TITLE);
        heading.setPadding(0, dp(CONTROL_GAP), 0, dp(CONTROL_GAP));
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleWidth = new LinearLayout.LayoutParams(0, -2, 1);
        titleWidth.gravity = Gravity.TOP;
        row.addView(heading, titleWidth);
        if (trailing != null) {
            LinearLayout.LayoutParams trailingSize = new LinearLayout.LayoutParams(-2, -2);
            trailingSize.setMarginStart(dp(CONTROL_GAP));
            row.addView(trailing, trailingSize);
        }
        return row;
    }

    Button button(CharSequence value, Action action, Runnable click) {
        Button button = new Button(context);
        button.setText(value);
        button.setAllCaps(false);
        styleAction(button, action);
        if (click != null) button.setOnClickListener(v -> click.run());
        return button;
    }

    void styleAction(TextView view, Action action) {
        boolean solid = action == Action.PRIMARY;
        int fill = solid ? primary : action == Action.TINT ? selected : action == Action.QUIET ? Color.TRANSPARENT : surface;
        int border = action == Action.OUTLINE ? stroke : action == Action.DANGER ? dangerStroke : Color.TRANSPARENT;
        int foreground = solid ? surface : action == Action.DANGER ? danger : primary;
        int disabled = -android.R.attr.state_enabled;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{disabled}, rounded(solid ? selected : fill, border, CONTROL_RADIUS));
        states.addState(new int[0], rounded(fill, border, CONTROL_RADIUS));
        view.setBackgroundTintList(null);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(solid ? 0x2bffffff : pressed), states,
                rounded(Color.WHITE, 0, CONTROL_RADIUS)));
        view.setStateListAnimator(null);
        view.setTextColor(new ColorStateList(new int[][]{{disabled}, {}}, new int[]{hint, foreground}));
        view.setTextSize(BODY);
        view.setTypeface(MEDIUM);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        int height = dp(action == Action.QUIET ? COMPACT_HEIGHT : CONTROL_HEIGHT);
        view.setMinHeight(height);
        view.setMinimumHeight(height);
        view.setPadding(dp(CONTROL_GAP + ITEM_GAP), dp(CONTROL_GAP), dp(CONTROL_GAP + ITEM_GAP), dp(CONTROL_GAP));
    }

    void alertActions(AlertDialog dialog, Action positive) {
        Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (confirm != null) styleAction(confirm, positive);
        if (cancel != null) styleAction(cancel, Action.QUIET);
    }

    void input(EditText input) {
        input.setTextColor(ink);
        input.setHintTextColor(hint);
        input.setTextSize(16);
        input.setMinimumHeight(dp(COMPACT_HEIGHT));
        inputUnderline(input, fieldLine);
        input.setPadding(dp(3), dp(6), dp(3), dp(7));
    }

    void inputUnderline(EditText input, int idleColor) {
        input.setBackgroundTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {-android.R.attr.state_enabled}, {}},
                new int[]{primary, divider, idleColor}));
    }

    LinearLayout topBar(String title, Runnable back) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(PAGE_INSET), dp(ITEM_GAP), dp(PAGE_INSET), dp(ITEM_GAP));
        row.addView(button("返回", Action.OUTLINE, back), new LinearLayout.LayoutParams(-2, -2));
        TextView heading = heading(title, 22);
        LinearLayout.LayoutParams width = new LinearLayout.LayoutParams(0, -2, 1);
        width.setMarginStart(dp(SECTION_GAP));
        row.addView(heading, width);
        return row;
    }

    LinearLayout option(CharSequence label, String mark, boolean current, boolean dropdown, boolean alignEnd) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(COMPACT_HEIGHT));
        row.setPadding(dp(dropdown ? 10 : ITEM_GAP), dp(ITEM_GAP), dp(CONTROL_GAP), dp(ITEM_GAP));
        TextView name = text(label, BODY, current ? primary : title);
        name.setMaxLines(dropdown ? 2 : 1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        if (current) name.setTypeface(MEDIUM);
        if (alignEnd) name.setGravity(Gravity.END);
        LinearLayout.LayoutParams width = new LinearLayout.LayoutParams(0, -2, 1);
        if (!mark.isEmpty()) width.setMarginEnd(dp(ITEM_GAP));
        row.addView(name, width);
        if (!mark.isEmpty()) {
            TextView indicator = text(mark, BODY, current ? primary : muted);
            indicator.setGravity(Gravity.CENTER);
            indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(indicator, new LinearLayout.LayoutParams(-2, -2));
        }
        if (dropdown) row.setBackground(ripple(rounded(current ? selected : surface, 0, CONTROL_RADIUS), CONTROL_RADIUS));
        return row;
    }

    void spinner(Spinner spinner) {
        spinner.setBackgroundTintList(null);
        spinner.setBackground(ripple(rounded(surface, stroke, CONTROL_RADIUS), CONTROL_RADIUS));
        spinner.setPadding(dp(CONTROL_GAP), 0, dp(ITEM_GAP), 0);
        spinner.setMinimumHeight(dp(COMPACT_HEIGHT));
        spinner.setPopupBackgroundDrawable(menu());
        spinner.setDropDownVerticalOffset(dp(ITEM_GAP));
        spinner.setDropDownWidth(ViewGroup.LayoutParams.MATCH_PARENT);
    }

    ArrayAdapter<String> options(List<String> choices, Spinner spinner, int selected) {
        return new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, choices) {
            @Override public View getView(int position, View convert, ViewGroup parent) {
                if (spinner == null) return getDropDownView(position, convert, parent);
                return option(getItem(position), "⌄", false, false, false);
            }

            @Override public View getDropDownView(int position, View convert, ViewGroup parent) {
                int current = spinner == null ? selected : spinner.getSelectedItemPosition();
                boolean checked = position == current;
                return option(getItem(position), checked ? "✓" : "", checked, true, false);
            }
        };
    }

    void showChoices(View anchor, List<String> items, int selected, IntConsumer choose) {
        ListPopupWindow popup = new ListPopupWindow(context);
        popup.setAnchorView(anchor);
        popup.setModal(true);
        popup.setBackgroundDrawable(menu());
        popup.setDropDownGravity(Gravity.END);
        popup.setVerticalOffset(dp(ITEM_GAP));
        int safe = context.getResources().getConfiguration().screenWidthDp - 2 * PAGE_INSET;
        popup.setContentWidth(Math.min(dp(safe), Math.max(dp(156), anchor.getWidth())));
        popup.setAdapter(options(items, null, selected));
        popup.setOnItemClickListener((parent, row, position, id) -> {
            popup.dismiss();
            choose.accept(position);
        });
        popup.show();
    }
}
