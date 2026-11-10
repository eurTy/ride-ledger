package com.rideledger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;

/** Owns the edited expense so a roster update never recreates the input fields. */
final class QuickEntryPanel extends LinearLayout {
    private final LedgerForms forms;
    private final NoteHistory noteHistory;
    private LedgerStore.Trip trip;
    private final PayerSpinner payer;
    private boolean committed;

    QuickEntryPanel(Activity activity, LedgerStore.Trip trip, SharedPreferences uiPrefs,
                    CurrencyHistory currencyHistory, LedgerActions ledgerActions, LedgerForms forms,
                    Consumer<LedgerStore.Trip> saved, Consumer<Exception> showError) {
        super(activity);
        this.forms = forms;
        noteHistory = new NoteHistory(uiPrefs);
        this.trip = trip;
        LinearLayout card = this;
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(8), dp(14), dp(10));
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(Color.WHITE);
        cardBackground.setCornerRadius(dp(18));
        cardBackground.setStroke(dp(1), Color.rgb(222, 233, 224));
        card.setBackground(cardBackground);

        LinearLayout amountRow = new LinearLayout(activity);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout amountBox = new LinearLayout(activity);
        amountBox.setOrientation(LinearLayout.VERTICAL);
        EditText amount = forms.quickField(amountBox, "支付金额", "0.00", true);
        amount.setTextSize(26);
        LinearLayout currencyBox = forms.addAmountColumns(amountRow, amountBox);
        EditText currency = forms.currencyField(currencyBox, trip.id, "货币", currencyHistory.quickDefault(trip.id), true);
        card.addView(amountRow, fullMargin());
        card.addView(new View(activity), new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout routing = new LinearLayout(activity);
        routing.setOrientation(LinearLayout.VERTICAL);
        routing.setPadding(dp(12), dp(2), dp(12), dp(2));
        GradientDrawable routingBackground = new GradientDrawable();
        routingBackground.setColor(Color.rgb(249, 251, 249));
        routingBackground.setCornerRadius(dp(15));
        routingBackground.setStroke(dp(1), Color.rgb(220, 232, 223));
        routing.setBackground(routingBackground);
        card.addView(routing, fullMargin());

        LinearLayout payerRow = new LinearLayout(activity);
        payerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView payerLabel = new TextView(activity);
        payerLabel.setText("谁付款");
        payerLabel.setTextSize(14);
        payerLabel.setTextColor(Color.rgb(78, 113, 96));
        LinearLayout.LayoutParams payerLabelWidth = new LinearLayout.LayoutParams(-2, -2);
        payerLabelWidth.setMarginEnd(dp(8));
        payerRow.addView(payerLabel, payerLabelWidth);
        String savedPayer = uiPrefs.getString("payer:" + trip.id, trip.localPerson);
        payer = new PayerSpinner(activity);
        payer.setRoster(trip, savedPayer);
        payerLabel.setLabelFor(payer.getId());
        payerRow.setMinimumHeight(dp(48));
        payerRow.addView(payer, new LinearLayout.LayoutParams(0, dp(44), 1));
        routing.addView(payerRow, fullMargin());

        View divider = new View(activity);
        divider.setBackgroundColor(Color.rgb(228, 237, 229));
        routing.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout modeRow = new LinearLayout(activity);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setMinimumHeight(dp(48));
        TextView modeLabel = new TextView(activity);
        modeLabel.setText("怎么分摊");
        modeLabel.setTextSize(14);
        modeLabel.setTextColor(Color.rgb(78, 113, 96));
        modeRow.addView(modeLabel, new LinearLayout.LayoutParams(0, -2, 1));
        String savedMode = uiPrefs.getString("mode:" + trip.id, "shared");
        String sharedLabel = "全员平摊";
        boolean[] personalMode = {"personal".equals(savedMode)};
        LedgerActions.CustomSplit[] custom = {null};
        TextView modeButton = new TextView(activity);
        modeButton.setText((personalMode[0] ? "仅付款人" : sharedLabel) + "  ⌄");
        modeButton.setTextSize(14);
        modeButton.setTextColor(Color.rgb(25, 93, 61));
        modeButton.setGravity(Gravity.CENTER);
        modeButton.setMinWidth(dp(122));
        modeButton.setMinHeight(dp(44));
        modeButton.setPadding(dp(10), 0, dp(10), 0);
        modeButton.setContentDescription("怎么分摊，" + (personalMode[0] ? "仅付款人" : sharedLabel));
        modeButton.setBackground(forms.chip(Color.WHITE, Color.rgb(220, 232, 223), false));
        modeRow.addView(modeButton, new LinearLayout.LayoutParams(-2, dp(44)));
        routing.addView(modeRow, fullMargin());

        Runnable updateMode = () -> {
            String label = custom[0] != null ? "自定义分摊" : personalMode[0] ? "仅付款人" : sharedLabel;
            modeButton.setText(label + "  ⌄");
            modeButton.setContentDescription("怎么分摊，" + label);
            amount.setEnabled(custom[0] == null);
        };
        modeButton.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(activity, modeButton);
            menu.getMenu().add(0, 0, 0, sharedLabel);
            menu.getMenu().add(0, 1, 1, "仅付款人");
            menu.getMenu().add(0, 2, 2, "自定义分摊");
            menu.getMenu().setGroupCheckable(0, true, true);
            menu.getMenu().findItem(custom[0] != null ? 2 : personalMode[0] ? 1 : 0).setChecked(true);
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 2) {
                    try {
                        String code = Money.code(currency.getText().toString());
                        forms.showCustomSplit(this.trip, code, custom[0], split -> {
                            custom[0] = split;
                            amount.setText(Money.amountText(split.total, split.currency));
                            updateMode.run();
                        });
                    } catch (IllegalArgumentException invalid) { showError.accept(invalid); }
                } else {
                    personalMode[0] = item.getItemId() == 1;
                    custom[0] = null;
                    updateMode.run();
                }
                return true;
            });
            menu.show();
        });
        card.addView(new View(activity), new LinearLayout.LayoutParams(-1, 0, 1));
        EditText note = forms.quickField(card, "备注（可选）", "例如：房费、饭费", false);
        ListPopupWindow noteSuggestions = new ListPopupWindow(activity);
        noteSuggestions.setAnchorView(note);
        noteSuggestions.setModal(false);
        Runnable showNoteHistory = () -> {
            if (!note.hasFocus() || !note.isAttachedToWindow() || noteSuggestions.isShowing()) return;
            List<String> notes = noteHistory.options(this.trip.id);
            if (notes.isEmpty()) return;
            noteSuggestions.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, notes));
            noteSuggestions.setWidth(note.getWidth());
            noteSuggestions.setOnItemClickListener((parent, view, position, id) -> {
                String selected = notes.get(position);
                note.setText(selected);
                note.setSelection(selected.length());
                noteSuggestions.dismiss();
            });
            noteSuggestions.show();
        };
        note.setOnFocusChangeListener((view, focused) -> {
            if (focused) note.post(showNoteHistory);
            else noteSuggestions.dismiss();
        });
        note.setOnClickListener(view -> showNoteHistory.run());
        note.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                note.removeCallbacks(showNoteHistory);
                noteSuggestions.dismiss();
            }
        });
        FormInput.next(amount, currency);
        FormInput.next(currency, note);
        FormInput.done(note);
        card.addView(new View(activity), new LinearLayout.LayoutParams(-1, 0, 1));

        Button save = new Button(activity);
        save.setText("保存这笔支出");
        save.setAllCaps(false);
        save.setOnClickListener(view -> {
            if (committed) return;
            LedgerStore.Trip current = this.trip;
            try {
                String code = Money.code(currency.getText().toString());
                String who = current.member(payer.getSelectedItemPosition());
                boolean personal = personalMode[0];
                LedgerActions.CustomSplit split = custom[0];
                String rawAmount = amount.getText().toString(), rawNote = note.getText().toString();
                if (split != null && !code.equals(split.currency))
                    throw new IllegalArgumentException("货币已更改，请重新确认自定义分摊");
                committed = true;
                save.setEnabled(false);
                ledgerActions.submit(() -> {
                    if (split == null) ledgerActions.expense(current, who, code, rawAmount, personal, rawNote);
                    else ledgerActions.expense(current, who, split, rawNote);
                }, () -> {
                    currencyHistory.remember(current.id, code, code);
                    noteHistory.remember(current.id, rawNote);
                    uiPrefs.edit().putString("payer:" + current.id, who)
                            .putString("mode:" + current.id, personal ? "personal" : "shared").apply();
                    if (!activity.isDestroyed()) saved.accept(current);
                }, error -> {
                    committed = false;
                    save.setEnabled(true);
                    if (!activity.isDestroyed()) showError.accept(error);
                });
            } catch (Exception e) { showError.accept(e); }
        });
        save.setTextSize(17);
        int green = 0xff176344;
        int[] pressed = {android.R.attr.state_pressed};
        int[] normal = {};
        save.setTextColor(new ColorStateList(new int[][]{pressed, normal}, new int[]{Color.WHITE, green}));
        save.setMinHeight(dp(48));
        StateListDrawable saveSurface = new StateListDrawable();
        saveSurface.addState(pressed, forms.chip(green, green, false));
        saveSurface.addState(normal, forms.chip(Color.WHITE, green, false));
        save.setBackground(saveSurface);
        save.setStateListAnimator(null);
        save.setElevation(0);
        card.addView(save, fullMargin());
    }

    void updateRoster(LedgerStore.Trip latest) {
        if (!latest.id.equals(trip.id) || latest.seatNames.equals(trip.seatNames)) return;
        String selected = trip.member(Math.max(0, payer.getSelectedItemPosition()));
        trip = latest;
        payer.setRoster(latest, selected);
    }

    private LayoutParams fullMargin() {
        LayoutParams params = new LayoutParams(-1, -2);
        params.bottomMargin = dp(4);
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
