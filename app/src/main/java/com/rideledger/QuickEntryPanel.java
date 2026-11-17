package com.rideledger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/** Owns the edited expense so a roster update never recreates the input fields. */
final class QuickEntryPanel extends LinearLayout {
    private final LedgerStyle style;
    private final LedgerForms forms;
    private final NoteHistory noteHistory;
    private final QuickEntryDrafts drafts;
    private LedgerStore.Trip trip;
    private final LinearLayout paymentControl;
    private final TextView paymentSummary;
    private final EditText amount, currency;
    private final NoteInput note;
    private final String defaultCurrency, defaultPayer;
    private final boolean defaultPersonal;
    private final Consumer<Boolean> showSubmitting;
    private boolean personal;
    private LedgerActions.CustomSplit custom;
    private String payerId;
    private PaymentSplitSheet paymentSheet;

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
        card.setPadding(inset, dp(10), inset, dp(12));
        card.setBackground(style.card());

        FrameLayout amountArea = new FrameLayout(activity);
        amountArea.setPaddingRelative(dp(12), dp(6), dp(12), dp(2));
        amountArea.setBackground(style.rounded(style.soft, 0, LedgerStyle.CONTROL_RADIUS));
        LinearLayout amountRow = new LinearLayout(activity);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout amountBox = new LinearLayout(activity);
        amountBox.setOrientation(LinearLayout.VERTICAL);
        amount = forms.compactAmount(amountBox, "支付金额", "0.00", LedgerStyle.CAPTION);
        amount.setTextSize(LedgerStyle.QUICK_AMOUNT);
        amount.setTextColor(style.title);
        amount.setTypeface(LedgerStyle.MEDIUM);
        amount.setPaddingRelative(0, amount.getPaddingTop(), amount.getPaddingEnd(), amount.getPaddingBottom());
        style.inputUnderline(amount, style.stroke);
        LinearLayout currencyBox = forms.addAmountColumns(amountRow, amountBox);
        defaultCurrency = currencyHistory.quickDefault(trip.id);
        currency = forms.currencyField(currencyBox, trip.id, "货币", defaultCurrency, true);
        if (state != null) {
            amount.setText(state.getString("amount"));
            currency.setText(state.getString("currency"));
        }
        amountArea.addView(amountRow, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        // Let the input and selection rows use the free height on a tall screen.
        card.addView(amountArea, fullWidth(2, 2));

        LinearLayout routing = new LinearLayout(activity);
        routing.setOrientation(LinearLayout.VERTICAL);
        routing.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(routing, fullWidth(LedgerStyle.CONTROL_GAP, 2));
        defaultPayer = uiPrefs.getString("payer:" + trip.id, trip.localPerson);
        payerId = trip.member(Math.max(0, trip.indexOf(state == null ? defaultPayer : state.getString("payer"))));
        defaultPersonal = "personal".equals(uiPrefs.getString("mode:" + trip.id, "shared"));
        personal = state == null ? defaultPersonal : state.getBoolean("personal");
        if (state != null && state.getStringArray("shares") != null) {
            try { custom = LedgerActions.customSplit(trip, state.getString("shareCurrency"), state.getStringArray("shares")); }
            catch (Exception invalid) { showError.accept(invalid); }
        }
        paymentControl = new LinearLayout(activity);
        paymentControl.setId(View.generateViewId());
        paymentControl.setGravity(Gravity.CENTER_VERTICAL);
        paymentControl.setPadding(dp(12), dp(10), dp(12), dp(10));
        paymentControl.setMinimumHeight(dp(64));
        paymentControl.setBackground(style.ripple(style.rounded(style.surface, style.stroke, LedgerStyle.CONTROL_RADIUS),
                LedgerStyle.CONTROL_RADIUS));
        paymentControl.setFocusable(true);
        LinearLayout summary = new LinearLayout(activity);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        paymentControl.addView(summary, new LayoutParams(0, -2, 1));
        TextView caption = style.text("付款与分摊", LedgerStyle.CAPTION, style.muted);
        caption.setIncludeFontPadding(false);
        summary.addView(caption, fullWidth(LedgerStyle.ITEM_GAP));
        paymentSummary = style.text("", LedgerStyle.BODY, style.title);
        paymentSummary.setTypeface(LedgerStyle.MEDIUM);
        paymentSummary.setMaxLines(2);
        paymentSummary.setEllipsize(TextUtils.TruncateAt.END);
        summary.addView(paymentSummary, fullWidth(0));
        ImageView settings = new ImageView(activity);
        settings.setImageResource(R.drawable.ic_ledger_payment_settings);
        settings.setPadding(dp(7), dp(7), dp(7), dp(7));
        settings.setBackground(style.rounded(style.selected, 0, LedgerStyle.CONTROL_RADIUS));
        settings.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams settingsSize = new LayoutParams(dp(34), dp(34));
        settingsSize.setMarginStart(dp(8));
        paymentControl.addView(settings, settingsSize);
        routing.addView(paymentControl, fullWidth(0));
        updatePaymentSummary();
        paymentControl.setOnClickListener(view -> {
            try {
                String code = currency.getText().toString();
                paymentSheet = new PaymentSplitSheet(activity, forms, this.trip, payerId, personal, custom, code, selected -> {
                    payerId = selected.payerId;
                    personal = selected.personal;
                    custom = selected.custom;
                    if (custom != null) amount.setText(Money.amountText(custom.total, custom.currency));
                    updatePaymentSummary();
                }, showError);
                paymentSheet.show();
            } catch (IllegalArgumentException invalid) { showError.accept(invalid); }
        });
        note = forms.expenseNote(card, trip.id, state == null ? "" : state.getString("note", ""), false);
        note.field.setTextSize(LedgerStyle.BODY);
        style.inputUnderline(note.field, style.stroke);
        ImageButton attach = receipts.besideNote(note.input, trip, null, false);
        FormInput.next(amount, currency);
        FormInput.next(currency, note.field);
        FormInput.done(note.field);
        Button save = style.button("保存这笔支出", LedgerStyle.Action.PRIMARY, null);
        showSubmitting = submitting -> {
            amount.setEnabled(!submitting && custom == null);
            currency.setEnabled(!submitting);
            note.setEnabled(!submitting);
            paymentControl.setEnabled(!submitting);
            paymentControl.setAlpha(submitting ? .55f : 1);
            attach.setEnabled(!submitting);
            save.setEnabled(!submitting);
        };
        if (drafts.isSubmitting(trip.id)) showSubmitting.accept(true);
        save.setOnClickListener(view -> {
            LedgerStore.Trip current = this.trip;
            if (drafts.isSubmitting(current.id)) return;
            try {
                String code = Money.code(currency.getText().toString());
                String who = payerId;
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
        saveSpacing.topMargin = dp(LedgerStyle.ITEM_GAP);
        card.addView(save, saveSpacing);
    }

    void rememberDraft() { drafts.remember(trip.id, snapshot()); }
    void saveFailed() { showSubmitting.accept(false); }

    private Bundle snapshot() {
        String rawAmount = amount.getText().toString(), rawNote = note.value();
        String rawCurrency = currency.getText().toString();
        String member = payerId;
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
        trip = latest;
        if (paymentSheet != null) paymentSheet.updateRoster(latest);
        updatePaymentSummary();
    }

    private void updatePaymentSummary() {
        String person = trip.nameOf(payerId) + (payerId.equals(trip.localPerson) ? "（我）" : "");
        String rule = custom != null ? "自定义分摊" : personal ? "仅付款人" : "全员平摊";
        paymentSummary.setText(person + " · " + rule);
        paymentControl.setContentDescription("付款与分摊，" + person + "，" + rule);
        amount.setEnabled(!drafts.isSubmitting(trip.id) && custom == null);
    }

    @Override protected void onDetachedFromWindow() {
        if (paymentSheet != null) paymentSheet.dismiss();
        super.onDetachedFromWindow();
    }

    private LayoutParams fullWidth(int bottomGap) {
        return fullWidth(bottomGap, 0);
    }

    private LayoutParams fullWidth(int bottomGap, float weight) {
        LayoutParams params = new LayoutParams(-1, -2, weight);
        params.bottomMargin = dp(bottomGap);
        return params;
    }

    private int dp(int value) { return style.dp(value); }
}
