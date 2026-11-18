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
import java.util.function.Supplier;

/** Owns the edited expense so a roster update never recreates the input fields. */
final class QuickEntryPanel extends LinearLayout {
    private static final int ROOMY_WIDTH = 344, ROOMY_HEIGHT = 590;
    private final LedgerStyle style;
    private final QuickEntryDrafts drafts;
    private LedgerStore.Trip trip;
    private final FrameLayout amountArea;
    private final LinearLayout paymentControl, cashActions;
    private final TextView amountCaption, paymentCaption, paymentSummary, noteCaption, cashCaption;
    private final LedgerForms.CurrencyInput currencyInput;
    private final ImageView paymentIcon;
    private final ImageButton attachment;
    private final Button save;
    private final EditText amount, currency;
    private final NoteInput note;
    private final String defaultCurrency, defaultPayer;
    private final boolean defaultPersonal;
    private final Consumer<Boolean> showSubmitting;
    private boolean personal;
    private LedgerActions.CustomSplit custom;
    private String payerId;
    private PaymentSplitSheet paymentSheet;
    private boolean roomy;

    QuickEntryPanel(Activity activity, LedgerStore.Trip trip, SharedPreferences uiPrefs,
                    CurrencyHistory currencyHistory, NoteHistory noteHistory, LedgerActions ledgerActions, LedgerForms forms,
                    ReceiptUi receipts, QuickEntryDrafts drafts, Supplier<LedgerStore.Trip> latestTrip,
                    Consumer<Exception> showError) {
        super(activity);
        style = new LedgerStyle(activity);
        this.drafts = drafts;
        this.trip = trip;
        Bundle state = drafts.form(trip.id);
        LinearLayout card = this;
        card.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(LedgerStyle.CARD_INSET);
        card.setPadding(inset, dp(10), inset, dp(12));
        card.setBackground(style.card());

        amountArea = new FrameLayout(activity);
        amountArea.setBackground(style.rounded(style.soft, 0, LedgerStyle.CONTROL_RADIUS));
        LinearLayout amountRow = new LinearLayout(activity);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout amountBox = new LinearLayout(activity);
        amountBox.setOrientation(LinearLayout.VERTICAL);
        amount = forms.compactAmount(amountBox, "支付金额", "0.00", LedgerStyle.CAPTION);
        amountCaption = captionFor(amountBox, amount);
        amount.setTextColor(style.title);
        amount.setTypeface(LedgerStyle.MEDIUM);
        amount.setPaddingRelative(0, amount.getPaddingTop(), amount.getPaddingEnd(), amount.getPaddingBottom());
        style.inputUnderline(amount, style.stroke);
        LinearLayout currencyBox = forms.addAmountColumns(amountRow, amountBox);
        defaultCurrency = currencyHistory.quickDefault(trip.id);
        currencyInput = forms.quickCurrencyField(currencyBox, trip.id, defaultCurrency);
        currency = currencyInput.field;
        if (state != null) {
            amount.setText(state.getString("amount"));
            currency.setText(state.getString("currency"));
        }
        amountArea.addView(amountRow, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        card.addView(amountArea, fullWidth(LedgerStyle.CONTROL_GAP, 1));
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
        paymentControl.setBackground(style.ripple(style.rounded(style.surface, style.stroke, LedgerStyle.CONTROL_RADIUS),
                LedgerStyle.CONTROL_RADIUS));
        paymentControl.setFocusable(true);
        LinearLayout summary = new LinearLayout(activity);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        paymentControl.addView(summary, new LayoutParams(0, -2, 1));
        paymentCaption = style.text("付款与分摊", LedgerStyle.CAPTION, style.muted);
        paymentCaption.setIncludeFontPadding(false);
        summary.addView(paymentCaption, fullWidth(LedgerStyle.ITEM_GAP));
        paymentSummary = style.text("", LedgerStyle.BODY, style.title);
        paymentSummary.setTypeface(LedgerStyle.MEDIUM);
        paymentSummary.setMaxLines(2);
        paymentSummary.setEllipsize(TextUtils.TruncateAt.END);
        summary.addView(paymentSummary, fullWidth(0));
        paymentIcon = new ImageView(activity);
        paymentIcon.setImageResource(R.drawable.ic_ledger_payment_settings);
        paymentIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        paymentIcon.setPadding(dp(7), dp(7), dp(7), dp(7));
        paymentIcon.setBackground(style.rounded(style.selected, 0, LedgerStyle.CONTROL_RADIUS));
        paymentIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams settingsSize = new LayoutParams(dp(34), dp(34));
        settingsSize.setMarginStart(dp(8));
        paymentControl.addView(paymentIcon, settingsSize);
        card.addView(paymentControl, fullWidth(LedgerStyle.CONTROL_GAP, .6f));
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
        noteCaption = captionFor(card, note.field);
        style.inputUnderline(note.field, style.stroke);
        attachment = receipts.besideNote(note.input, trip, null, false);
        FormInput.next(amount, currency);
        FormInput.next(currency, note.field);
        FormInput.done(note.field);
        save = style.button("保存这笔支出", LedgerStyle.Action.PRIMARY, null);
        showSubmitting = submitting -> {
            amount.setEnabled(!submitting && custom == null);
            currency.setEnabled(!submitting);
            note.setEnabled(!submitting);
            paymentControl.setEnabled(!submitting);
            paymentControl.setAlpha(submitting ? .55f : 1);
            attachment.setEnabled(!submitting);
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
        LayoutParams saveSpacing = fullWidth(LedgerStyle.ITEM_GAP, .2f);
        saveSpacing.topMargin = dp(LedgerStyle.ITEM_GAP);
        card.addView(save, saveSpacing);

        View divider = new View(activity);
        divider.setBackgroundColor(style.divider);
        LayoutParams rule = new LayoutParams(-1, dp(1));
        rule.topMargin = dp(LedgerStyle.CONTROL_GAP);
        rule.bottomMargin = dp(LedgerStyle.ITEM_GAP);
        card.addView(divider, rule);
        cashCaption = style.heading("现金管理", LedgerStyle.CAPTION);
        cashCaption.setTextColor(style.muted);
        cashCaption.setIncludeFontPadding(false);
        card.addView(cashCaption, fullWidth(LedgerStyle.ITEM_GAP));
        cashActions = new LinearLayout(activity);
        cashActions.setBaselineAligned(false);
        card.addView(cashActions, fullWidth(0, 1.15f));
        addCashAction(LedgerReview.CASH_LABEL, R.drawable.ic_ledger_cash_in, latestTrip, forms::moneyIn);
        if (trip.people() > 1)
            addCashAction("成员还款", R.drawable.ic_ledger_cash_repayment, latestTrip, forms::transfer);
        addCashAction("换汇", R.drawable.ic_ledger_cash_exchange, latestTrip, forms::exchange);
        applyPresentation();
    }

    /** The viewport is measured above the navigation and keyboard. Keep the same inputs on resize. */
    void fitViewport(int width, int height, boolean keyboardVisible) {
        boolean useRoomy = !keyboardVisible && width >= dp(ROOMY_WIDTH) && height >= dp(ROOMY_HEIGHT)
                && getResources().getConfiguration().fontScale < 1.2f;
        if (roomy == useRoomy) return;
        roomy = useRoomy;
        applyPresentation();
    }

    private void applyPresentation() {
        int label = roomy ? 15 : LedgerStyle.CAPTION;
        amountCaption.setTextSize(label);
        currencyInput.caption.setTextSize(label);
        paymentCaption.setTextSize(label);
        noteCaption.setTextSize(label);
        cashCaption.setTextSize(label);
        amountArea.setPaddingRelative(dp(12), dp(roomy ? 12 : 6), dp(12), dp(roomy ? 8 : 2));
        amount.setTextSize(roomy ? 42 : LedgerStyle.QUICK_AMOUNT);
        currency.setTextSize(roomy ? 20 : 16);
        currency.setMinimumHeight(dp(roomy ? 54 : LedgerStyle.COMPACT_HEIGHT));
        currencyInput.historyButton.setTextSize(roomy ? 20 : 16);
        LayoutParams historySize = (LayoutParams) currencyInput.historyButton.getLayoutParams();
        historySize.height = dp(roomy ? 54 : LedgerStyle.COMPACT_HEIGHT);
        currencyInput.historyButton.setLayoutParams(historySize);

        paymentControl.setMinimumHeight(dp(roomy ? 84 : 64));
        paymentSummary.setTextSize(roomy ? 18 : LedgerStyle.BODY);
        resizeIcon(paymentIcon, roomy ? 44 : 34);
        note.field.setTextSize(roomy ? 17 : LedgerStyle.BODY);
        note.field.setMinimumHeight(dp(roomy ? 56 : LedgerStyle.COMPACT_HEIGHT));
        int attachmentSize = roomy ? 56 : LedgerStyle.CONTROL_HEIGHT;
        resizeIcon(attachment, attachmentSize);
        int iconInset = dp(roomy ? 13 : 0);
        attachment.setPadding(iconInset, iconInset, iconInset, iconInset);
        attachment.setScaleType(roomy ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER);
        save.setTextSize(roomy ? 18 : LedgerStyle.BODY);
        buttonHeight(save, roomy ? 60 : LedgerStyle.CONTROL_HEIGHT);

        for (int i = 0; i < cashActions.getChildCount(); i++) ((CashAction) cashActions.getChildAt(i)).fit(roomy);
    }

    private void addCashAction(String label, int icon, Supplier<LedgerStore.Trip> latestTrip,
                               Consumer<LedgerStore.Trip> openForm) {
        CashAction action = new CashAction(label, icon, () -> {
            LedgerStore.Trip current = latestTrip.get();
            if (current != null) openForm.accept(current);
        });
        LayoutParams width = new LayoutParams(0, -1, 1);
        if (cashActions.getChildCount() > 0) width.setMarginStart(dp(LedgerStyle.CONTROL_GAP));
        cashActions.addView(action, width);
    }

    private final class CashAction extends LinearLayout {
        private final TextView label;
        private final ImageView icon;

        CashAction(String title, int glyph, Runnable open) {
            super(QuickEntryPanel.this.getContext());
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setPadding(dp(4), dp(2), dp(4), dp(2));
            setBackground(style.ripple(null, LedgerStyle.CONTROL_RADIUS));
            setContentDescription(title);
            setFocusable(true);
            setOnClickListener(view -> open.run());

            icon = new ImageView(getContext());
            icon.setImageResource(glyph);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(icon, new LayoutParams(dp(24), dp(24)));

            label = style.text(title, LedgerStyle.CAPTION, style.primary);
            label.setTypeface(LedgerStyle.MEDIUM);
            label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false);
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(label, new LayoutParams(-1, -2));
        }

        void fit(boolean expanded) {
            setMinimumHeight(dp(expanded ? 76 : LedgerStyle.CONTROL_HEIGHT));
            resizeIcon(icon, expanded ? 34 : 24);
            label.setTextSize(expanded ? 17 : LedgerStyle.CAPTION);
            label.setPadding(0, dp(expanded ? 5 : 1), 0, 0);
        }

        @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
    }

    private void resizeIcon(View icon, int size) {
        LayoutParams square = (LayoutParams) icon.getLayoutParams();
        square.width = square.height = dp(size);
        icon.setLayoutParams(square);
    }

    private void buttonHeight(Button button, int height) {
        button.setMinHeight(dp(height));
        button.setMinimumHeight(dp(height));
    }

    private static TextView captionFor(LinearLayout group, EditText field) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && child.getLabelFor() == field.getId()) return (TextView) child;
        }
        throw new IllegalStateException("Quick entry field is missing its label");
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
