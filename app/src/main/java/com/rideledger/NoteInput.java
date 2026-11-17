package com.rideledger;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListPopupWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Keeps the category control and note text within one underlined field. */
final class NoteInput {
    final EditText field;
    final FrameLayout input;
    private final LedgerStyle style;
    private final Button categoryMenu;
    private final int fieldEndPadding;
    private ExpenseCategory category = ExpenseCategory.OTHER;

    NoteInput(EditText field, LedgerStyle style, String rawNote, Supplier<List<String>> history) {
        this.field = field;
        this.style = style;
        fieldEndPadding = field.getPaddingEnd();
        categoryMenu = style.button("", LedgerStyle.Action.QUIET, this::showCategories);
        categoryMenu.setTextSize(LedgerStyle.CAPTION);
        categoryMenu.setTypeface(Typeface.DEFAULT);
        categoryMenu.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{style.hint, style.muted}));
        int touch = style.dp(LedgerStyle.COMPACT_HEIGHT);
        categoryMenu.setMinWidth(touch);
        categoryMenu.setMinimumWidth(touch);
        int inset = style.dp(LedgerStyle.ITEM_GAP);
        categoryMenu.setPadding(inset, 0, inset, 0);
        categoryMenu.setSingleLine(true);
        input = new FrameLayout(field.getContext());
        input.addView(field, new FrameLayout.LayoutParams(-1, -2));
        input.addView(categoryMenu, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        restore(rawNote);
        attachHistory(history);
    }

    String value() {
        String text = field.getText().toString();
        return category == ExpenseCategory.OTHER ? text : category.applyTo(text);
    }

    void setEnabled(boolean enabled) {
        field.setEnabled(enabled);
        categoryMenu.setEnabled(enabled);
    }

    private void restore(String note) {
        category = ExpenseCategory.explicit(note);
        field.setText(category == ExpenseCategory.OTHER ? note : ExpenseCategory.details(note));
        field.setSelection(field.getText().length());
        updateCategory();
    }

    private void updateCategory() {
        boolean selected = category != ExpenseCategory.OTHER;
        categoryMenu.setText(selected ? category.label + "  ⌄" : "⌄");
        categoryMenu.setContentDescription(selected ? "备注分类，" + category.label : "选择备注分类");
        categoryMenu.setSelected(selected);
        categoryMenu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        field.setPaddingRelative(field.getPaddingStart(), field.getPaddingTop(),
                fieldEndPadding + categoryMenu.getMeasuredWidth(), field.getPaddingBottom());
    }

    private void showCategories() {
        List<String> choices = new ArrayList<>();
        for (ExpenseCategory choice : ExpenseCategory.SHORTCUTS) choices.add(choice.label);
        int selected = ExpenseCategory.SHORTCUTS.indexOf(category);
        style.showChoices(categoryMenu, choices, selected, index -> {
            category = index == selected ? ExpenseCategory.OTHER : ExpenseCategory.SHORTCUTS.get(index);
            updateCategory();
        });
    }

    private void attachHistory(Supplier<List<String>> history) {
        ListPopupWindow menu = new ListPopupWindow(field.getContext());
        menu.setAnchorView(field);
        menu.setModal(false);
        menu.setInputMethodMode(ListPopupWindow.INPUT_METHOD_NEEDED);
        menu.setBackgroundDrawable(style.menu());
        menu.setVerticalOffset(style.dp(LedgerStyle.ITEM_GAP));
        Runnable show = () -> {
            if (!field.isEnabled() || !field.hasFocus() || !field.isAttachedToWindow() || menu.isShowing() ||
                    field.length() != 0) return;
            List<String> recent = new ArrayList<>(), labels = new ArrayList<>();
            for (String note : history.get()) {
                if (ExpenseCategory.details(note).isEmpty()) continue;
                recent.add(note);
                labels.add(ExpenseCategory.visibleNote(note));
            }
            if (recent.isEmpty()) return;
            menu.setAdapter(style.options(labels, null, -1));
            menu.setWidth(field.getWidth());
            menu.setOnItemClickListener((parent, row, position, id) -> {
                restore(recent.get(position));
                menu.dismiss();
            });
            menu.show();
        };
        field.setOnFocusChangeListener((view, focused) -> {
            if (focused) field.post(show);
            else menu.dismiss();
        });
        field.setOnClickListener(view -> show.run());
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { menu.dismiss(); }
        });
        field.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                field.removeCallbacks(show);
                menu.dismiss();
            }
        });
    }
}
