package com.rideledger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Owns the edited expense so a roster update never recreates the input fields. */
final class QuickEntryPanel extends LinearLayout {
    private static final List<String> MODES = Arrays.asList("全员平摊", "仅付款人", "自定义分摊");
    private static final int FORM_SECTION_GAP = LedgerStyle.SECTION_GAP + LedgerStyle.CONTROL_GAP;
    private final LedgerStyle style;
    private final LedgerForms forms;
    private final NoteHistory noteHistory;
    private final QuickEntryDrafts drafts;
    private LedgerStore.Trip trip;
    private final PayerSpinner payer;
    private final EditText amount, currency;
    private final NoteInput note;
    private final String defaultCurrency, defaultPayer;
    private final boolean defaultPersonal;
    private final Consumer<Boolean> showSubmitting;
    private boolean personal;
    private LedgerActions.CustomSplit custom;

    QuickEntryPanel(Activity activity, LedgerStore.Trip trip, SharedPreferences uiPrefs,
                    CurrencyHistory currencyHistory, NoteHistory noteHistory, LedgerActions ledgerActions, LedgerForms forms,
                    ReceiptUi receipts, QuickEntryDrafts drafts, Consumer<Exception> showError) {
        super(activity);
        style = new LedgerStyle(activity);
        this.forms = forms;
        this.drafts = drafts;
        this.noteHistory = noteHistory;
        this.trip = trip;
        Bundle state = drafts.form(trip.id);
        LinearLayout card = this;
        card.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(LedgerStyle.CARD_INSET);
        card.setPadding(inset, dp(12), inset, dp(12));
        card.setBackground(style.card());
        // Use more of the spare height at the card edges to keep related controls together.
        addFlexGap(1.5f);

        LinearLayout amountRow = new LinearLayout(activity);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout amountBox = new LinearLayout(activity);
        amountBox.setOrientation(LinearLayout.VERTICAL);
        amount = forms.compactAmount(amountBox, "支付金额", "0.00", LedgerStyle.CAPTION);
        amount.setTextSize(LedgerStyle.QUICK_AMOUNT);
        amount.setTextColor(style.title);
        amount.setTypeface(LedgerStyle.MEDIUM);
        LinearLayout currencyBox = forms.addAmountColumns(amountRow, amountBox);
        defaultCurrency = currencyHistory.quickDefault(trip.id);
        currency = forms.currencyField(currencyBox, trip.id, "货币", defaultCurrency, true);
        if (state != null) {
            amount.setText(state.getString("amount"));
            currency.setText(state.getString("currency"));
        }
        card.addView(amountRow, fullWidth(FORM_SECTION_GAP));
        addFlexGap(1);

        LinearLayout routing = new LinearLayout(activity);
        routing.setOrientation(LinearLayout.VERTICAL);
        routing.setPadding(dp(12), dp(2), dp(12), dp(2));
        routing.setBackground(style.rounded(style.soft, 0, LedgerStyle.CONTROL_RADIUS));
        card.addView(routing, fullWidth(FORM_SECTION_GAP));

        LinearLayout payerRow = new LinearLayout(activity);
        payerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView payerLabel = style.text("谁付款", LedgerStyle.CAPTION, style.muted);
        LinearLayout.LayoutParams payerLabelWidth = new LinearLayout.LayoutParams(-2, -2);
        payerLabelWidth.setMarginEnd(dp(8));
        payerRow.addView(payerLabel, payerLabelWidth);
        defaultPayer = uiPrefs.getString("payer:" + trip.id, trip.localPerson);
        payer = new PayerSpinner(activity);
        payer.setRoster(trip, state == null ? defaultPayer : state.getString("payer"));
        payerLabel.setLabelFor(payer.getId());
        payerRow.setMinimumHeight(dp(LedgerStyle.CONTROL_HEIGHT));
        payerRow.addView(payer, new LinearLayout.LayoutParams(0, -2, 1));
        routing.addView(payerRow, fullWidth(LedgerStyle.ITEM_GAP));

        View divider = new View(activity);
        divider.setBackgroundColor(style.divider);
        routing.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout modeRow = new LinearLayout(activity);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setMinimumHeight(dp(LedgerStyle.CONTROL_HEIGHT));
        TextView modeLabel = style.text("分摊规则", LedgerStyle.CAPTION, style.muted);
        modeRow.addView(modeLabel, new LinearLayout.LayoutParams(0, -2, 1));
        defaultPersonal = "personal".equals(uiPrefs.getString("mode:" + trip.id, "shared"));
        personal = state == null ? defaultPersonal : state.getBoolean("personal");
        if (state != null && state.getStringArray("shares") != null) {
            try { custom = LedgerActions.customSplit(trip, state.getString("shareCurrency"), state.getStringArray("shares")); }
            catch (Exception invalid) { showError.accept(invalid); }
        }
        Button modeButton = style.button("", LedgerStyle.Action.OUTLINE, null);
        modeButton.setTextSize(LedgerStyle.LABEL);
        modeButton.setMinWidth(dp(112));
        modeButton.setMinimumHeight(dp(LedgerStyle.COMPACT_HEIGHT));
        modeButton.setMinHeight(dp(LedgerStyle.COMPACT_HEIGHT));
        modeRow.addView(modeButton, new LinearLayout.LayoutParams(-2, -2));
        routing.addView(modeRow, fullWidth(LedgerStyle.ITEM_GAP));

        Runnable updateMode = () -> {
            String label = MODES.get(custom != null ? 2 : personal ? 1 : 0);
            modeButton.setText(label + "  ⌄");
            modeButton.setContentDescription("分摊规则，" + label);
            amount.setEnabled(custom == null);
        };
        updateMode.run();
        modeButton.setOnClickListener(v -> {
            style.showChoices(modeButton, MODES, custom != null ? 2 : personal ? 1 : 0, choice -> {
                if (choice == 2) {
                    try {
                        String code = Money.code(currency.getText().toString());
                        forms.showCustomSplit(this.trip, code, custom, split -> {
                            custom = split;
                            amount.setText(Money.amountText(split.total, split.currency));
                            updateMode.run();
                        });
                    } catch (IllegalArgumentException invalid) { showError.accept(invalid); }
                } else {
                    personal = choice == 1;
                    custom = null;
                    updateMode.run();
                }
            });
        });
        addFlexGap(1);
        note = forms.expenseNote(card, trip.id, state == null ? "" : state.getString("note", ""), false);
        note.field.setTextSize(LedgerStyle.BODY);
        ImageButton attach = receipts.besideNote(note.input, trip, null, false);
        FormInput.next(amount, currency);
        FormInput.next(currency, note.field);
        FormInput.done(note.field);
        addFlexGap(1);
        Button save = style.button("保存这笔支出", LedgerStyle.Action.PRIMARY, null);
        showSubmitting = submitting -> {
            amount.setEnabled(!submitting && custom == null);
            currency.setEnabled(!submitting);
            note.setEnabled(!submitting);
            payer.setEnabled(!submitting && this.trip.people() > 1);
            modeButton.setEnabled(!submitting);
            attach.setEnabled(!submitting);
            save.setEnabled(!submitting);
        };
        if (drafts.isSubmitting(trip.id)) showSubmitting.accept(true);
        save.setOnClickListener(view -> {
            LedgerStore.Trip current = this.trip;
            if (drafts.isSubmitting(current.id)) return;
            try {
                String code = Money.code(currency.getText().toString());
                String who = current.member(payer.getSelectedItemPosition());
                boolean personalMode = personal;
                LedgerActions.CustomSplit split = custom;
                String rawAmount = amount.getText().toString(), rawNote = note.value();
                if (receipts.picking()) throw new IllegalStateException("正在读取小票，请稍候");
                if (split != null && !code.equals(split.currency))
                    throw new IllegalArgumentException("货币已更改，请重新确认自定义分摊");
                String draft = receipts.draft(current.id);
                drafts.beginSave(current.id, snapshot());
                showSubmitting.accept(true);
                ledgerActions.submit(() -> {
                    ReceiptImage image = receipts.draftImage(draft);
                    if (split == null) ledgerActions.expense(current, who, code, rawAmount, personalMode, rawNote, image);
                    else ledgerActions.expense(current, who, split, rawNote, image);
                }, () -> {
                    try {
                        receipts.clearDraft(current.id, draft);
                        currencyHistory.remember(current.id, code, code);
                        noteHistory.remember(current.id, rawNote);
                        uiPrefs.edit().putString("payer:" + current.id, who)
                                .putString("mode:" + current.id, personalMode ? "personal" : "shared").apply();
                    } catch (RuntimeException preferencesError) {
                        SyncLog.write(activity, SyncLog.Event.UI_REFRESH, preferencesError);
                    } finally { drafts.finishSave(current.id, null); }
                }, error -> drafts.finishSave(current.id, error));
            } catch (Exception e) { showError.accept(e); }
        });
        save.setTextSize(LedgerStyle.BODY);
        LayoutParams saveSpacing = fullWidth(LedgerStyle.ITEM_GAP);
        saveSpacing.topMargin = dp(FORM_SECTION_GAP - LedgerStyle.ITEM_GAP);
        card.addView(save, saveSpacing);
        addFlexGap(1.5f);
    }

    void rememberDraft() { drafts.remember(trip.id, snapshot()); }
    void saveFailed() { showSubmitting.accept(false); }

    private Bundle snapshot() {
        String rawAmount = amount.getText().toString(), rawNote = note.value();
        String rawCurrency = currency.getText().toString();
        String member = trip.member(Math.max(0, payer.getSelectedItemPosition()));
        if (rawAmount.isEmpty() && rawNote.isEmpty() && rawCurrency.equals(defaultCurrency) &&
                member.equals(defaultPayer) && personal == defaultPersonal && custom == null) return null;
        Bundle state = new Bundle();
        state.putString("amount", rawAmount);
        state.putString("currency", rawCurrency);
        state.putString("note", rawNote);
        state.putString("payer", member);
        state.putBoolean("personal", personal);
        if (custom != null) {
            String[] shares = new String[trip.people()];
            for (int i = 0; i < shares.length; i++)
                shares[i] = Money.amountText(custom.share(trip.member(i)), custom.currency);
            state.putString("shareCurrency", custom.currency);
            state.putStringArray("shares", shares);
        }
        return state;
    }

    void updateRoster(LedgerStore.Trip latest) {
        if (!latest.id.equals(trip.id) || latest.seatNames.equals(trip.seatNames)) return;
        String selected = trip.member(Math.max(0, payer.getSelectedItemPosition()));
        trip = latest;
        payer.setRoster(latest, selected);
        if (drafts.isSubmitting(trip.id)) payer.setEnabled(false);
    }

    private void addFlexGap(float weight) {
        addView(new Space(getContext()), new LayoutParams(-1, 0, weight));
    }

    private LayoutParams fullWidth(int bottomGap) {
        LayoutParams params = new LayoutParams(-1, -2);
        params.bottomMargin = dp(bottomGap);
        return params;
    }

    private int dp(int value) { return style.dp(value); }
}
