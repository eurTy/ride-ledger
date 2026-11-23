package com.rideledger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
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
    private static final int ROOMY_HEIGHT = 590;
    private static final int MAX_CONTROL_GROWTH = 64;
    private final LedgerStyle style;
    private final QuickEntryDrafts drafts;
    private LedgerStore.Trip trip;
    private final FrameLayout amountArea;
    private final LinearLayout amountRow, amountBox, currencyBox;
    private final LinearLayout paymentControl, cashActions;
    private final View cashDivider;
    private final TextView amountCaption, paymentCaption, noteCaption, cashCaption;
    private final PaymentSummary paymentSummary;
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
    private ExpensePayments payments;
    private String paymentCurrency = "";
    private String payerId;
    private PaymentSplitSheet paymentSheet;
    private int verticalGrowth = -1, viewportWidth;
    private float preferredAmountSize;
    private final TextPaint measurePaint = new TextPaint();

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
        amountRow = new LinearLayout(activity);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        amountRow.setBaselineAligned(false);
        amountBox = new LinearLayout(activity);
        amountBox.setOrientation(LinearLayout.VERTICAL);
        amount = forms.compactAmount(amountBox, "支付金额", "0.00", LedgerStyle.CAPTION);
        amountCaption = captionFor(amountBox, amount);
        amount.setTextColor(style.title);
        amount.setTypeface(LedgerStyle.MEDIUM);
        amount.setPaddingRelative(0, amount.getPaddingTop(), amount.getPaddingEnd(), amount.getPaddingBottom());
        style.inputUnderline(amount, style.stroke);
        currencyBox = forms.addAmountColumns(amountRow, amountBox);
        defaultCurrency = currencyHistory.quickDefault(trip.id);
        currencyInput = forms.quickCurrencyField(currencyBox, trip.id, defaultCurrency);
        currency = currencyInput.field;
        if (state != null) {
            amount.setText(state.getString("amount"));
            currency.setText(state.getString("currency"));
        }
        amountArea.addView(amountRow, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        card.addView(amountArea, fullWidth(LedgerStyle.CONTROL_GAP));
        defaultPayer = uiPrefs.getString("payer:" + trip.id, trip.localPerson);
        payerId = trip.member(Math.max(0, trip.indexOf(state == null ? defaultPayer : state.getString("payer"))));
        defaultPersonal = "personal".equals(uiPrefs.getString("mode:" + trip.id, "shared"));
        personal = state == null ? defaultPersonal : state.getBoolean("personal");
        if (state != null && state.getStringArray("shares") != null) {
            try { custom = LedgerActions.customSplit(trip, state.getString("shareCurrency"), state.getStringArray("shares")); }
            catch (Exception invalid) { showError.accept(invalid); }
        }
        if (state != null && state.getStringArray("payments") != null) {
            try {
                paymentCurrency = Money.code(state.getString("paymentCurrency"));
                payments = LedgerActions.payments(trip, paymentCurrency, state.getStringArray("payments")).requireMultiple();
            } catch (Exception invalid) { showError.accept(invalid); }
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
        paymentSummary = new PaymentSummary();
        paymentSummary.setTextColor(style.title);
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
        card.addView(paymentControl, fullWidth(LedgerStyle.CONTROL_GAP));
        updatePaymentSummary();
        paymentControl.setOnClickListener(view -> {
            try {
                String code = currency.getText().toString();
                paymentSheet = new PaymentSplitSheet(activity, forms, this.trip, payerId, personal, custom, code,
                        payments, paymentCurrency, selected -> {
                    payerId = selected.payerId;
                    personal = selected.personal;
                    custom = selected.custom;
                    payments = selected.payments;
                    paymentCurrency = selected.currency;
                    if (payments != null) amount.setText(Money.amountText(payments.total, paymentCurrency));
                    else if (custom != null) amount.setText(Money.amountText(custom.total, custom.currency));
                    updatePaymentSummary();
                }, showError);
                paymentSheet.show();
            } catch (IllegalArgumentException invalid) { showError.accept(invalid); }
        });
        note = forms.expenseNote(card, trip.id, state == null ? "" : state.getString("note", ""), false);
        noteCaption = captionFor(card, note.field);
        style.inputUnderline(note.field, style.stroke);
        attachment = receipts.besideNote(note.input, trip, null, null);
        FormInput.next(amount, currency);
        FormInput.next(currency, note.field);
        FormInput.done(note.field);
        save = style.button("保存这笔支出", LedgerStyle.Action.PRIMARY, null);
        showSubmitting = submitting -> {
            amount.setEnabled(!submitting && custom == null && payments == null);
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
                ExpensePayments actualPayments = payments;
                String rawAmount = amount.getText().toString(), rawNote = note.value();
                if (receipts.picking()) throw new IllegalStateException("正在读取小票，请稍候");
                if (split != null && !code.equals(split.currency))
                    throw new IllegalArgumentException("货币已更改，请重新确认自定义分摊");
                if (actualPayments != null && !code.equals(paymentCurrency))
                    throw new IllegalArgumentException("货币已更改，请重新确认多人付款");
                String draft = receipts.draft(current.id);
                drafts.beginSave(current.id, snapshot());
                showSubmitting.accept(true);
                ledgerActions.submit(() -> {
                    ReceiptImage image = receipts.draftImage(draft);
                    ledgerActions.expense(current, who, code, rawAmount, personalMode, split, actualPayments, rawNote, image);
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
        LayoutParams saveSpacing = fullWidth(LedgerStyle.ITEM_GAP);
        saveSpacing.topMargin = dp(LedgerStyle.ITEM_GAP);
        card.addView(save, saveSpacing);

        cashDivider = new View(activity);
        cashDivider.setBackgroundColor(style.divider);
        card.addView(cashDivider, new LayoutParams(-1, dp(1)));
        cashCaption = style.heading("现金管理", LedgerStyle.CAPTION);
        cashCaption.setTextColor(style.muted);
        cashCaption.setIncludeFontPadding(false);
        card.addView(cashCaption, fullWidth(LedgerStyle.ITEM_GAP));
        cashActions = new LinearLayout(activity);
        cashActions.setBaselineAligned(false);
        card.addView(cashActions, fullWidth(0));
        addCashAction(LedgerReview.CASH_LABEL, R.drawable.ic_ledger_cash_in, latestTrip, forms::moneyIn);
        if (trip.people() > 1)
            addCashAction("成员还款", R.drawable.ic_ledger_cash_repayment, latestTrip, forms::transfer);
        addCashAction("换汇", R.drawable.ic_ledger_cash_exchange, latestTrip, forms::exchange);
        applyPresentation();
        amount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (viewportWidth > 0) fitAmountColumns(viewportWidth - getPaddingLeft() - getPaddingRight());
            }
            @Override public void afterTextChanged(Editable value) { }
        });
    }

    /** Resizing changes the presentation of the same inputs, including while the keyboard is open. */
    void fitViewport(int width, int height, boolean keyboardVisible) {
        int growth = !keyboardVisible && height >= dp(ROOMY_HEIGHT)
                ? Math.min(MAX_CONTROL_GROWTH, (height - dp(ROOMY_HEIGHT)) / dp(4)) : -1;
        if (growth >= 0 && cashActionsWidth(17 + growth / 14) > width - getPaddingLeft() - getPaddingRight())
            growth = -1;
        if (viewportWidth == width && verticalGrowth == growth) return;
        viewportWidth = width;
        verticalGrowth = growth;
        applyPresentation();
    }

    private void applyPresentation() {
        boolean tall = verticalGrowth >= 0;
        int growth = Math.max(verticalGrowth, 0);
        int sectionGap = LedgerStyle.CONTROL_GAP + Math.min(8, growth / 4);
        int itemGap = LedgerStyle.ITEM_GAP + Math.min(4, growth / 8);
        space(amountArea, 0, sectionGap);
        space(paymentControl, 0, sectionGap);
        space(noteCaption, 0, itemGap);
        space(save, itemGap, LedgerStyle.ITEM_GAP);
        space(cashDivider, sectionGap, itemGap);
        space(cashCaption, 0, itemGap);

        int label = tall ? 15 + growth / 24 : LedgerStyle.CAPTION;
        amountCaption.setTextSize(label);
        currencyInput.caption.setTextSize(label);
        paymentCaption.setTextSize(label);
        noteCaption.setTextSize(label);
        cashCaption.setTextSize(label);
        amountArea.setPaddingRelative(dp(12), dp(tall ? 12 : 6), dp(12), dp(tall ? 8 : 2));
        amount.setTextSize(tall ? 42 + growth / 8 : LedgerStyle.QUICK_AMOUNT);
        preferredAmountSize = amount.getTextSize();
        int amountHeight = tall ? 70 + growth / 2 : LedgerStyle.COMPACT_HEIGHT;
        amount.setMinimumHeight(dp(amountHeight));
        currency.setTextSize(tall ? 20 + growth / 16 : 16);
        int currencyHeight = tall ? 54 + growth / 3 : LedgerStyle.COMPACT_HEIGHT;
        currency.setMinimumHeight(dp(currencyHeight));
        currencyInput.historyButton.setTextSize(tall ? 20 + growth / 16 : 16);
        LayoutParams historySize = (LayoutParams) currencyInput.historyButton.getLayoutParams();
        historySize.height = dp(currencyHeight);
        currencyInput.historyButton.setLayoutParams(historySize);

        paymentControl.setMinimumHeight(dp(tall ? 80 + 2 * growth / 3 : 64));
        paymentSummary.setTextSize(tall ? 18 + growth / 13 : LedgerStyle.BODY);
        resizeIcon(paymentIcon, tall ? 44 + growth / 7 : 34);
        note.field.setTextSize(tall ? 17 + growth / 16 : LedgerStyle.BODY);
        note.field.setMinimumHeight(dp(tall ? 56 + growth / 3 : LedgerStyle.COMPACT_HEIGHT));
        int attachmentSize = tall ? 56 + growth / 7 : LedgerStyle.CONTROL_HEIGHT;
        resizeIcon(attachment, attachmentSize);
        save.setTextSize(tall ? 18 + growth / 12 : LedgerStyle.BODY);
        buttonHeight(save, tall ? 60 + growth / 3 : LedgerStyle.CONTROL_HEIGHT);

        for (int i = 0; i < cashActions.getChildCount(); i++) ((CashAction) cashActions.getChildAt(i)).fit(tall, growth);
        if (viewportWidth > 0) {
            int contentWidth = viewportWidth - getPaddingLeft() - getPaddingRight();
            fitAmountColumns(contentWidth);
            fitCashActions(contentWidth);
        }
    }

    private void fitAmountColumns(int contentWidth) {
        int gap = dp(LedgerStyle.CONTROL_GAP);
        int available = contentWidth - amountArea.getPaddingLeft() - amountArea.getPaddingRight();
        int codeMinimum = LedgerStyle.textWidth(currency, "WWW")
                + currencyInput.historyButton.getLayoutParams().width + dp(2);
        measurePaint.set(amount.getPaint());
        measurePaint.setTextSize(preferredAmountSize);
        CharSequence entered = amount.getText();
        float amountTextWidth = Math.max(measurePaint.measureText("000.00"),
                measurePaint.measureText(entered, 0, Math.min(64, entered.length())));
        int amountPadding = amount.getCompoundPaddingLeft() + amount.getCompoundPaddingRight() + dp(3);
        int amountMinimum = (int) Math.ceil(amountTextWidth) + amountPadding;
        boolean stack = available - gap < codeMinimum + amountMinimum;
        int orientation = stack ? VERTICAL : HORIZONTAL;
        boolean changedOrientation = amountRow.getOrientation() != orientation;
        if (changedOrientation) {
            amountRow.setOrientation(orientation);
            amountBox.setLayoutParams(new LayoutParams(stack ? -1 : 0, -2, stack ? 0 : 1));
        }
        int codeWidth = stack ? -1
                : Math.min(available - gap - amountMinimum, Math.max(codeMinimum, (available - gap) * 2 / 5));
        if (changedOrientation || currencyBox.getLayoutParams().width != codeWidth) {
            LayoutParams code = new LayoutParams(codeWidth, -2);
            if (stack) code.topMargin = gap;
            else code.setMarginStart(gap);
            currencyBox.setLayoutParams(code);
        }

        // Keep shortcuts and pasted long amounts legible after using the full row for the amount.
        float scale = stack && amountMinimum > available
                ? Math.max(.74f, (available - amountPadding) / amountTextWidth) : 1f;
        float fontSize = preferredAmountSize * scale;
        if (Math.abs(fontSize - amount.getTextSize()) >= .5f)
            amount.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
    }

    private int cashActionsWidth(int proposedSp) {
        int children = cashActions.getChildCount();
        int minimum = 0;
        for (int i = 0; i < children; i++) {
            CashAction action = (CashAction) cashActions.getChildAt(i);
            minimum = Math.max(minimum, action.minimumColumnWidth(proposedSp));
        }
        return minimum * children + dp(LedgerStyle.CONTROL_GAP) * (children - 1);
    }

    private void fitCashActions(int contentWidth) {
        int children = cashActions.getChildCount();
        int gap = dp(LedgerStyle.CONTROL_GAP);
        boolean stack = cashActionsWidth(0) > contentWidth;
        cashActions.setOrientation(stack ? VERTICAL : HORIZONTAL);
        for (int i = 0; i < children; i++) {
            CashAction action = (CashAction) cashActions.getChildAt(i);
            action.layoutLabel(stack);
            LayoutParams size = new LayoutParams(stack ? -1 : 0, stack ? -2 : -1, stack ? 0 : 1);
            if (i > 0) {
                if (stack) size.topMargin = dp(LedgerStyle.ITEM_GAP);
                else size.setMarginStart(gap);
            }
            action.setLayoutParams(size);
        }
    }

    private void space(View control, int topGap, int bottomGap) {
        LayoutParams params = (LayoutParams) control.getLayoutParams();
        params.topMargin = dp(topGap);
        params.bottomMargin = dp(bottomGap);
        control.setLayoutParams(params);
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

        void fit(boolean tall, int growth) {
            setMinimumHeight(dp(tall ? 76 + 3 * growth / 4 : LedgerStyle.CONTROL_HEIGHT));
            resizeIcon(icon, tall ? 34 + growth / 4 : 24);
            label.setTextSize(tall ? 17 + growth / 14 : LedgerStyle.CAPTION);
            label.setPadding(0, dp(tall ? 5 + growth / 12 : 1), 0, 0);
        }

        int minimumColumnWidth(int proposedSp) {
            int width = LedgerStyle.textWidth(label);
            if (proposedSp > 0) {
                measurePaint.set(label.getPaint());
                measurePaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, proposedSp,
                        getResources().getDisplayMetrics()));
                width = (int) Math.ceil(measurePaint.measureText(label.getText().toString()));
            }
            return width + getPaddingLeft() + getPaddingRight() + dp(2);
        }

        void layoutLabel(boolean stack) {
            setOrientation(stack ? HORIZONTAL : VERTICAL);
            setGravity(stack ? Gravity.CENTER_VERTICAL | Gravity.START : Gravity.CENTER);
            label.setGravity(stack ? Gravity.START : Gravity.CENTER);
            label.setLayoutParams(new LayoutParams(-1, -2));
            if (stack) label.setPaddingRelative(dp(LedgerStyle.CONTROL_GAP), 0, 0, 0);
        }

        @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
    }

    private final class PaymentSummary extends TextView {
        private String person = "", rule = "";

        PaymentSummary() { super(QuickEntryPanel.this.getContext()); }

        void setPayment(String person, String rule) {
            this.person = person;
            this.rule = rule;
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec) - getCompoundPaddingLeft() - getCompoundPaddingRight();
            String summary = person + " · " + rule;
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && width > 0
                    && LedgerStyle.textWidth(this, summary) > MeasureSpec.getSize(widthSpec)) {
                summary = TextUtils.ellipsize(person, getPaint(), width, TextUtils.TruncateAt.END) + "\n" + rule;
            }
            if (!summary.contentEquals(getText())) setText(summary);
            super.onMeasure(widthSpec, heightSpec);
        }
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
                member.equals(defaultPayer) && personal == defaultPersonal && custom == null && payments == null) return null;
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
        if (payments != null) {
            String[] values = new String[trip.people()];
            for (int i = 0; i < values.length; i++)
                values[i] = Money.amountText(payments.minor(trip.member(i)), paymentCurrency);
            state.putString("paymentCurrency", paymentCurrency);
            state.putStringArray("payments", values);
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
        if (payments != null) person = payments.amounts().size() + " 人付款";
        String rule = custom != null ? "自定义分摊" : personal ? (payments == null ? "仅付款人" : "各自承担") : "全员平摊";
        paymentSummary.setPayment(person, rule);
        paymentControl.setContentDescription("付款与分摊，" + person + "，" + rule);
        amount.setEnabled(!drafts.isSubmitting(trip.id) && custom == null && payments == null);
    }

    @Override protected void onDetachedFromWindow() {
        if (paymentSheet != null) paymentSheet.dismiss();
        super.onDetachedFromWindow();
    }

    private LayoutParams fullWidth(int bottomGap) {
        LayoutParams params = new LayoutParams(-1, -2);
        params.bottomMargin = dp(bottomGap);
        return params;
    }

    private int dp(int value) { return style.dp(value); }
}
