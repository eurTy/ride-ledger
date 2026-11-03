package com.rideledger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class MainActivity extends Activity {
    private static final int QUICK = 0, REVIEW = 1, TRIPS = 2;
    private LedgerStore store;
    private LanSync lan;
    private CloudSync cloud;
    private SharedPreferences uiPrefs;
    private String currentTripId;
    private int screen = TRIPS;
    private TextView syncView;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(248, 249, 247));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        store = new LedgerStore(this);
        lan = new LanSync(this, store, (message, count) -> onSyncChanged(count));
        cloud = new CloudSync(this, store, (message, count) -> onSyncChanged(count));
        uiPrefs = getSharedPreferences("ui", MODE_PRIVATE);
        currentTripId = uiPrefs.getString("last_trip", null);
        if (currentTripId == null || store.getTrip(currentTripId) == null) {
            List<LedgerStore.Trip> trips = store.trips();
            currentTripId = trips.isEmpty() ? null : trips.get(0).id;
        }
        screen = currentTripId == null ? TRIPS : QUICK;
        render();
    }

    @Override public void onStart() {
        super.onStart();
        if (currentTripId != null && screen != QUICK) { screen = QUICK; render(); }
        lan.start();
        cloud.start();
        updateSync();
    }
    @Override public void onStop() { cloud.stop(); lan.stop(); super.onStop(); }
    @Override public void onDestroy() { cloud.close(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (currentTripId != null && screen != QUICK) { screen = QUICK; render(); }
        else super.onBackPressed();
    }

    private void onSyncChanged(int count) {
        if (count > 0 && screen != QUICK) render();
        else updateSync();
    }

    private void updateSync() {
        if (syncView != null) syncView.setText(lan.status() + "\n" + cloud.status());
    }

    private void render() {
        LedgerStore.Trip trip = currentTripId == null ? null : store.getTrip(currentTripId);
        if (trip == null) { currentTripId = null; screen = TRIPS; renderTrips(); }
        else if (screen == TRIPS) renderTrips();
        else if (screen == REVIEW) renderReview(trip);
        else renderQuick(trip);
    }

    private LinearLayout page() {
        syncView = null;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(248, 249, 247));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(18), dp(18), dp(30));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (currentTripId != null) root.addView(bottomBar());
        setContentView(root);
        return body;
    }

    private LinearLayout bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), dp(7), dp(8), dp(7));
        bar.setBackgroundColor(Color.WHITE);
        navItem(bar, "记账", QUICK);
        navItem(bar, "复盘", REVIEW);
        navItem(bar, "旅行", TRIPS);
        return bar;
    }

    private void navItem(LinearLayout bar, String title, int target) {
        TextView item = new TextView(this);
        item.setText(title);
        item.setTextSize(15);
        item.setGravity(Gravity.CENTER);
        item.setTextColor(screen == target ? Color.rgb(23, 99, 68) : Color.rgb(108, 137, 117));
        GradientDrawable background = new GradientDrawable();
        background.setColor(screen == target ? Color.rgb(231, 245, 235) : Color.WHITE);
        background.setCornerRadius(dp(11));
        item.setBackground(background);
        item.setOnClickListener(v -> { screen = target; render(); });
        item.setFocusable(true);
        bar.addView(item, new LinearLayout.LayoutParams(0, dp(52), 1));
    }

    private void selectTrip(LedgerStore.Trip trip) {
        currentTripId = trip.id;
        uiPrefs.edit().putString("last_trip", trip.id).apply();
        screen = QUICK;
        render();
    }

    private void renderTrips() {
        LinearLayout body = page();
        heading(body, "我的旅行");
        text(body, "选择旅行后，之后每次打开都会直接进入快速记账。", 14, Color.GRAY);
        row(body, button("新建旅行", this::newTrip), button("加入旅行", this::joinTrip));
        section(body, "旅行列表");
        List<LedgerStore.Trip> trips = store.trips();
        if (trips.isEmpty()) text(body, "还没有旅行。先新建一个旅行，再给同伴分享邀请码。", 15, Color.GRAY);
        for (LedgerStore.Trip trip : trips) {
            Button item = button(trip.name + (trip.id.equals(currentTripId) ? " · 当前" : "") +
                    "\n" + trip.personA + " · " + trip.personB, () -> selectTrip(trip));
            body.addView(item, fullMargin());
        }
        if (currentTripId != null) {
            LedgerStore.Trip current = store.getTrip(currentTripId);
            section(body, "同行与同步");
            row(body, button("分享邀请码", () -> share(current)), button("连接同伴地址", this::manualConnect));
            buttonInto(body, "云端地址设置", this::cloudSettings);
        }
        syncView = text(body, "", 13, Color.GRAY);
        updateSync();
    }

    private void renderQuick(LedgerStore.Trip trip) {
        LinearLayout body = page();
        text(body, "当前旅行", 13, Color.rgb(104, 146, 115));
        heading(body, trip.name);
        text(body, "快速记账", 19, Color.rgb(28, 89, 56));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(20));
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(Color.WHITE);
        cardBackground.setCornerRadius(dp(18));
        cardBackground.setStroke(dp(1), Color.rgb(222, 233, 224));
        card.setBackground(cardBackground);
        body.addView(card, fullMargin());

        LinearLayout amountRow = new LinearLayout(this);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout amountBox = new LinearLayout(this);
        amountBox.setOrientation(LinearLayout.VERTICAL);
        EditText amount = field(amountBox, "支付金额", "0.00", true);
        amount.setTextSize(30);
        LinearLayout currencyBox = new LinearLayout(this);
        currencyBox.setOrientation(LinearLayout.VERTICAL);
        text(currencyBox, "货币", 14, Color.DKGRAY);
        LinearLayout currencyRow = new LinearLayout(this);
        currencyRow.setOrientation(LinearLayout.HORIZONTAL);
        currencyRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText currency = new EditText(this);
        currency.setSingleLine(true);
        currency.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        currency.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(3)});
        currency.setTextSize(18);
        currency.setSelectAllOnFocus(true);
        currency.setText(quickCurrency(trip.id));
        currencyRow.addView(currency, new LinearLayout.LayoutParams(0, -2, 1));
        TextView historyButton = new TextView(this);
        historyButton.setText("⌄");
        historyButton.setTextSize(22);
        historyButton.setTextColor(Color.rgb(34, 108, 69));
        historyButton.setGravity(Gravity.CENTER);
        historyButton.setContentDescription("选择曾用货币");
        historyButton.setBackground(chip(Color.rgb(233, 244, 235), Color.rgb(233, 244, 235), true));
        currencyRow.addView(historyButton, new LinearLayout.LayoutParams(dp(34), dp(40)));
        currencyBox.addView(currencyRow, fullMargin());
        refreshCurrencyHistoryButton(trip.id, historyButton);
        currency.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                try {
                    rememberQuickCurrency(trip.id, value.toString());
                    refreshCurrencyHistoryButton(trip.id, historyButton);
                } catch (IllegalArgumentException ignored) { /* Wait for three letters. */ }
            }
        });
        historyButton.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, historyButton);
            for (String code : quickCurrencyHistory(trip.id, quickCurrency(trip.id)))
                menu.getMenu().add(code);
            menu.setOnMenuItemClickListener(item -> {
                String code = item.getTitle().toString();
                currency.setText(code);
                currency.setSelection(code.length());
                return true;
            });
            menu.show();
        });
        amountRow.addView(amountBox, new LinearLayout.LayoutParams(0, -2, 2));
        LinearLayout.LayoutParams currencyParams = new LinearLayout.LayoutParams(0, -2, 1);
        currencyParams.leftMargin = dp(10);
        amountRow.addView(currencyBox, currencyParams);
        card.addView(amountRow, fullMargin());

        LinearLayout routing = new LinearLayout(this);
        routing.setOrientation(LinearLayout.VERTICAL);
        routing.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable routingBackground = new GradientDrawable();
        routingBackground.setColor(Color.rgb(249, 251, 249));
        routingBackground.setCornerRadius(dp(15));
        routingBackground.setStroke(dp(1), Color.rgb(220, 232, 223));
        routing.setBackground(routingBackground);
        card.addView(routing, fullMargin());

        LinearLayout payerRow = new LinearLayout(this);
        payerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView payerLabel = new TextView(this);
        payerLabel.setText("谁付款");
        payerLabel.setTextSize(14);
        payerLabel.setTextColor(Color.rgb(78, 113, 96));
        payerRow.addView(payerLabel, new LinearLayout.LayoutParams(0, -2, 1));
        String savedPayer = uiPrefs.getString("payer:" + trip.id, trip.localPerson);
        RadioGroup payer = payerChoice(trip.personA, trip.personB, "b".equals(savedPayer));
        payerRow.addView(payer, new LinearLayout.LayoutParams(0, dp(50), 3));
        routing.addView(payerRow, fullMargin());

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(228, 237, 229));
        routing.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setMinimumHeight(dp(56));
        TextView modeLabel = new TextView(this);
        modeLabel.setText("怎么分摊");
        modeLabel.setTextSize(14);
        modeLabel.setTextColor(Color.rgb(78, 113, 96));
        modeRow.addView(modeLabel, new LinearLayout.LayoutParams(0, -2, 1));
        String savedMode = uiPrefs.getString("mode:" + trip.id, "shared");
        boolean[] personalMode = {"personal".equals(savedMode)};
        TextView modeButton = new TextView(this);
        modeButton.setText(personalMode[0] ? "仅付款人  ⌄" : "两人平摊  ⌄");
        modeButton.setTextSize(14);
        modeButton.setTextColor(Color.rgb(25, 93, 61));
        modeButton.setGravity(Gravity.CENTER);
        modeButton.setMinWidth(dp(122));
        modeButton.setMinHeight(dp(44));
        modeButton.setPadding(dp(10), 0, dp(10), 0);
        modeButton.setContentDescription("怎么分摊，" + (personalMode[0] ? "仅付款人" : "两人平摊"));
        modeButton.setBackground(chip(Color.WHITE, Color.rgb(220, 232, 223), false));
        modeRow.addView(modeButton, new LinearLayout.LayoutParams(-2, dp(44)));
        routing.addView(modeRow, fullMargin());

        TextView adjust = text(routing, "改分摊比例  ▾", 13, Color.rgb(77, 119, 88));
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.VERTICAL);
        EditText percent = field(split, trip.personA + " 分摊百分比", "50", true);
        int savedShare = uiPrefs.getInt("share:" + trip.id, 50);
        percent.setText(String.valueOf(savedShare));
        routing.addView(split, fullMargin());
        boolean[] splitExpanded = {savedShare != 50};
        split.setVisibility(splitExpanded[0] ? View.VISIBLE : View.GONE);
        adjust.setOnClickListener(v -> {
            splitExpanded[0] = !splitExpanded[0];
            split.setVisibility(splitExpanded[0] ? View.VISIBLE : View.GONE);
        });
        if (personalMode[0]) {
            adjust.setVisibility(View.GONE);
            split.setVisibility(View.GONE);
        }
        modeButton.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, modeButton);
            menu.getMenu().add(0, 0, 0, "两人平摊");
            menu.getMenu().add(0, 1, 1, "仅付款人");
            menu.getMenu().setGroupCheckable(0, true, true);
            menu.getMenu().findItem(personalMode[0] ? 1 : 0).setChecked(true);
            menu.setOnMenuItemClickListener(item -> {
                personalMode[0] = item.getItemId() == 1;
                modeButton.setText(personalMode[0] ? "仅付款人  ⌄" : "两人平摊  ⌄");
                modeButton.setContentDescription("怎么分摊，" + (personalMode[0] ? "仅付款人" : "两人平摊"));
                adjust.setVisibility(personalMode[0] ? View.GONE : View.VISIBLE);
                split.setVisibility(!personalMode[0] && splitExpanded[0] ? View.VISIBLE : View.GONE);
                return true;
            });
            menu.show();
        });
        EditText note = field(card, "备注（可选）", "例如：房费、饭费", false);

        Button save = button("保存这笔支出", () -> {
            try {
                String code = Money.code(currency.getText().toString());
                long minor = Money.parse(amount.getText().toString(), code);
                String who = ((RadioButton) payer.getChildAt(0)).isChecked() ? "a" : "b";
                boolean personal = personalMode[0];
                int share = personal ? savedShare : Integer.parseInt(percent.getText().toString().trim());
                if (share < 0 || share > 100) throw new IllegalArgumentException("分摊比例需在 0 至 100 之间");
                JSONObject data = new JSONObject().put("payer", who).put("currency", code).put("amount", minor)
                        .put("shareA", personal ? ("a".equals(who) ? 10000 : 0) : share * 100)
                        .put("mode", personal ? "personal" : "shared")
                        .put("note", note.getText().toString().trim());
                rememberQuickCurrency(trip.id, code);
                uiPrefs.edit().putString("payer:" + trip.id, who)
                        .putString("mode:" + trip.id, personal ? "personal" : "shared")
                        .putInt("share:" + trip.id, share).apply();
                add(trip, "expense", data);
                toast("已记账");
            } catch (Exception e) { error(e); }
        });
        save.setTextSize(17);
        save.setTextColor(Color.WHITE);
        save.setMinHeight(dp(56));
        GradientDrawable saveBackground = new GradientDrawable();
        saveBackground.setColor(Color.rgb(236, 107, 83));
        saveBackground.setCornerRadius(dp(12));
        save.setBackground(saveBackground);
        card.addView(save, fullMargin());
        text(body, "无网时先保存在本机；连接后自动同步。", 12, Color.GRAY);
    }

    private void renderReview(LedgerStore.Trip trip) {
        LedgerStore.Summary sum = store.summary(trip.id);
        LinearLayout body = page();
        heading(body, "旅行复盘");
        text(body, trip.name, 15, Color.GRAY);
        section(body, "总支出");
        text(body, sum.spent.isEmpty() ? "暂无支出" : formatBalances(sum.spent), 18, Color.BLACK);
        section(body, "现金余额");
        text(body, trip.personA + "：" + formatBalances(sum.cashA) + "\n" +
                trip.personB + "：" + formatBalances(sum.cashB), 17, Color.BLACK);
        section(body, "两人结算");
        text(body, formatDebts(trip, sum.debtBToA), 17, Color.rgb(25, 82, 64));
        section(body, "现金与结算");
        row(body, button("期初现金", () -> moneyIn(trip, true)), button("补充现金", () -> moneyIn(trip, false)));
        row(body, button("两人还款", () -> transfer(trip)), button("换汇", () -> exchange(trip)));
        section(body, "记录（长按可删除）");
        if (sum.visible.isEmpty()) text(body, "还没有记录。", 15, Color.GRAY);
        for (LedgerStore.Event event : sum.visible) {
            TextView item = text(body, describe(trip, event), 15, Color.BLACK);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) item.getLayoutParams();
            params.bottomMargin = dp(6);
            item.setLayoutParams(params);
            item.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle("删除这笔记录？")
                        .setMessage(describe(trip, event))
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (dialog, which) -> {
                            try { add(trip, "delete", new JSONObject().put("target", event.id)); }
                            catch (Exception e) { error(e); }
                        }).show();
                return true;
            });
        }
        syncView = text(body, "", 13, Color.GRAY);
        updateSync();
    }

    private String formatBalances(Map<String, Long> balances) {
        if (balances.isEmpty()) return "尚未填写";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> entry : new TreeMap<>(balances).entrySet())
            parts.add(Money.format(entry.getValue(), entry.getKey()));
        return String.join("、", parts);
    }

    private String formatDebts(LedgerStore.Trip trip, Map<String, Long> debts) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> entry : new TreeMap<>(debts).entrySet()) {
            long value = entry.getValue();
            if (value > 0) parts.add(trip.personB + " 应还 " + trip.personA + " " + Money.format(value, entry.getKey()));
            if (value < 0) parts.add(trip.personA + " 应还 " + trip.personB + " " + Money.format(-value, entry.getKey()));
        }
        return parts.isEmpty() ? "目前已结清" : String.join("\n", parts);
    }

    private String describe(LedgerStore.Trip trip, LedgerStore.Event event) {
        JSONObject d = event.data;
        String time = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(event.at));
        String note = d.optString("note", "");
        String title;
        try {
            switch (event.kind) {
                case "opening": title = trip.nameOf(d.getString("person")) + " 期初现金 +" +
                        Money.format(d.getLong("amount"), d.getString("currency")); break;
                case "topup": title = trip.nameOf(d.getString("person")) + " 补充现金 +" +
                        Money.format(d.getLong("amount"), d.getString("currency")); break;
                case "expense": title = trip.nameOf(d.getString("payer")) + " 支付 " +
                        Money.format(d.getLong("amount"), d.getString("currency")) +
                        ("personal".equals(d.optString("mode")) ? " · 个人" : " · 共同"); break;
                case "transfer": title = trip.nameOf(d.getString("from")) + " 还款 " +
                        Money.format(d.getLong("amount"), d.getString("currency")); break;
                case "exchange": title = trip.nameOf(d.getString("person")) + " 换汇 " +
                        Money.format(d.getLong("fromAmount"), d.getString("fromCurrency")) + " → " +
                        Money.format(d.getLong("toAmount"), d.getString("toCurrency")); break;
                default: title = "未知记录";
            }
        } catch (Exception e) { title = "记录有误"; }
        return time + "  " + title + (note.isEmpty() ? "" : "\n" + note);
    }

    private void newTrip() {
        LinearLayout form = form();
        EditText name = field(form, "旅行名称", "例如：东南亚骑行", false);
        EditText a = field(form, "我的名字", "我", false); a.setText("我");
        EditText b = field(form, "同伴名字", "同伴", false); b.setText("同伴");
        showForm("新建旅行", form, () -> {
            if (name.getText().toString().trim().isEmpty() || a.getText().toString().trim().isEmpty() ||
                    b.getText().toString().trim().isEmpty()) throw new IllegalArgumentException("请填写旅行和两个人的名字");
            LedgerStore.Trip trip = store.createTrip(name.getText().toString(), a.getText().toString(), b.getText().toString());
            selectTrip(trip);
            lan.syncNow();
            cloud.syncNow();
        });
    }

    private void joinTrip() {
        LinearLayout form = form();
        EditText code = field(form, "邀请码", "粘贴同伴分享的完整邀请码", false);
        code.setMinLines(4);
        showForm("加入旅行", form, () -> {
            LedgerStore.Trip trip = store.joinTrip(code.getText().toString());
            selectTrip(trip);
            lan.syncNow();
            cloud.syncNow();
        });
    }

    private void moneyIn(LedgerStore.Trip trip, boolean opening) {
        LinearLayout form = form();
        Spinner person = personChoice(form, trip, "现金持有人");
        EditText amount = field(form, "金额", "例如：1000", true);
        EditText currency = field(form, "货币代码", "CNY / EUR / JPY", false); currency.setText("CNY");
        showForm(opening ? "填写期初现金" : "补充现金", form, () -> {
            String code = Money.code(currency.getText().toString());
            JSONObject data = new JSONObject().put("person", person.getSelectedItemPosition() == 0 ? "a" : "b")
                    .put("currency", code).put("amount", Money.parse(amount.getText().toString(), code));
            add(trip, opening ? "opening" : "topup", data);
        });
    }

    private void transfer(LedgerStore.Trip trip) {
        LinearLayout form = form();
        Spinner from = personChoice(form, trip, "付款人");
        EditText amount = field(form, "还款金额", "例如：50", true);
        EditText currency = field(form, "货币代码", "CNY / EUR / JPY", false); currency.setText("CNY");
        showForm("两人之间还款", form, () -> {
            String code = Money.code(currency.getText().toString());
            JSONObject data = new JSONObject().put("from", from.getSelectedItemPosition() == 0 ? "a" : "b")
                    .put("currency", code).put("amount", Money.parse(amount.getText().toString(), code));
            add(trip, "transfer", data);
        });
    }

    private void exchange(LedgerStore.Trip trip) {
        LinearLayout form = form();
        Spinner person = personChoice(form, trip, "换汇人");
        EditText fromAmount = field(form, "付出金额", "例如：100", true);
        EditText fromCurrency = field(form, "付出货币", "CNY", false); fromCurrency.setText("CNY");
        EditText toAmount = field(form, "得到金额", "例如：13", true);
        EditText toCurrency = field(form, "得到货币", "EUR", false); toCurrency.setText("EUR");
        showForm("记录换汇", form, () -> {
            String from = Money.code(fromCurrency.getText().toString());
            String to = Money.code(toCurrency.getText().toString());
            if (from.equals(to)) throw new IllegalArgumentException("请选择两种不同货币");
            JSONObject data = new JSONObject().put("person", person.getSelectedItemPosition() == 0 ? "a" : "b")
                    .put("fromCurrency", from).put("fromAmount", Money.parse(fromAmount.getText().toString(), from))
                    .put("toCurrency", to).put("toAmount", Money.parse(toAmount.getText().toString(), to));
            add(trip, "exchange", data);
        });
    }

    private void add(LedgerStore.Trip trip, String kind, JSONObject data) {
        store.add(trip.id, kind, data);
        render();
        lan.syncNow();
        cloud.syncNow();
    }

    private void share(LedgerStore.Trip trip) {
        try {
            String code = store.inviteCode(trip);
            TextView content = new TextView(this);
            content.setText("把完整邀请码发给同伴。邀请码含旅行密钥，请只发给同伴。\n\n" + code);
            content.setTextIsSelectable(true);
            content.setPadding(dp(20), dp(18), dp(20), dp(18));
            new AlertDialog.Builder(this).setTitle("旅行邀请码").setView(content)
                    .setNegativeButton("关闭", null)
                    .setNeutralButton("复制", (d, w) -> {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("旅行邀请码", code));
                        toast("邀请码已复制");
                    })
                    .setPositiveButton("分享", (d, w) -> {
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("text/plain");
                        intent.putExtra(Intent.EXTRA_TEXT, code);
                        startActivity(Intent.createChooser(intent, "分享旅行邀请码"));
                    }).show();
        } catch (Exception e) { error(e); }
    }

    private void manualConnect() {
        LinearLayout form = form();
        text(form, "同伴手机的局域网地址可在“旅行”页面底部看到。", 14, Color.DKGRAY);
        EditText address = field(form, "同伴地址", "例如：192.168.43.1:12345", false);
        showForm("地址直连", form, () -> { lan.connectManual(address.getText().toString()); toast("正在尝试连接"); });
    }

    private void cloudSettings() {
        LinearLayout form = form();
        text(form, "不同 Wi-Fi 之间同步需要可访问的云端服务地址。远程地址必须使用 HTTPS。", 14, Color.DKGRAY);
        EditText endpoint = field(form, "云同步服务地址", "https://example.com", false);
        endpoint.setText(cloud.endpoint());
        showForm("云端同步", form, () -> { cloud.setEndpoint(endpoint.getText().toString()); updateSync(); });
    }

    private interface SaveAction { void run() throws Exception; }
    private void showForm(String title, View view, SaveAction save) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(view)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try { save.run(); dialog.dismiss(); }
            catch (Exception e) { error(e); }
        }));
        dialog.show();
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        return form;
    }

    private EditText field(LinearLayout form, String label, String hint, boolean decimal) {
        text(form, label, 14, Color.DKGRAY);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        if (decimal) field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(field, fullMargin());
        return field;
    }

    private Spinner personChoice(LinearLayout form, LedgerStore.Trip trip, String title) {
        Spinner spinner = choice(form, title, new String[]{trip.personA, trip.personB});
        spinner.setSelection("a".equals(trip.localPerson) ? 0 : 1);
        return spinner;
    }

    private RadioGroup payerChoice(String first, String second, boolean chooseSecond) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        group.setPadding(dp(3), dp(3), dp(3), dp(3));
        group.setBackground(chip(Color.rgb(234, 243, 236), Color.rgb(219, 233, 222), false));
        String[] options = {first, second};
        for (int i = 0; i < options.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setText(options[i]);
            option.setTextSize(14);
            option.setMinHeight(dp(44));
            option.setPadding(dp(4), 0, dp(4), 0);
            option.setSingleLine(true);
            option.setEllipsize(TextUtils.TruncateAt.END);
            option.setGravity(Gravity.CENTER);
            option.setButtonDrawable(null);
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_checked}, chip(Color.WHITE, Color.WHITE, false));
            states.addState(new int[]{}, chip(Color.TRANSPARENT, Color.TRANSPARENT, false));
            option.setBackground(states);
            option.setTextColor(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{Color.rgb(20, 91, 58), Color.rgb(85, 115, 99)}));
            option.setId(View.generateViewId());
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(0, -1, 1);
            if (i == 0) params.rightMargin = dp(2);
            group.addView(option, params);
        }
        ((RadioButton) group.getChildAt(chooseSecond ? 1 : 0)).setChecked(true);
        return group;
    }

    private String quickCurrency(String tripId) {
        try { return Money.code(uiPrefs.getString("currency:" + tripId, "CNY")); }
        catch (Exception ignored) { return "CNY"; }
    }

    private List<String> quickCurrencyHistory(String tripId, String current) {
        String saved = uiPrefs.getString("currency_history:" + tripId, null);
        boolean legacy = saved == null;
        if (legacy) saved = uiPrefs.getString("currencies:" + tripId, "");
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String raw : saved.split(",")) {
            try {
                String code = Money.code(raw);
                if (!code.equals(current)) codes.add(code);
            } catch (Exception ignored) { }
        }
        if (legacy && !"CNY".equals(current)) codes.add("CNY");
        List<String> history = new ArrayList<>(codes);
        return history.subList(0, Math.min(5, history.size()));
    }

    private void rememberQuickCurrency(String tripId, String raw) {
        String currency = Money.code(raw);
        String previous = quickCurrency(tripId);
        if (currency.equals(previous)) return;
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        codes.add(previous);
        codes.addAll(quickCurrencyHistory(tripId, previous));
        codes.remove(currency);
        List<String> history = new ArrayList<>(codes);
        String saved = String.join(",", history.subList(0, Math.min(5, history.size())));
        uiPrefs.edit().putString("currency:" + tripId, currency)
                .putString("currency_history:" + tripId, saved)
                .remove("currencies:" + tripId).apply();
    }

    private void refreshCurrencyHistoryButton(String tripId, TextView button) {
        button.setVisibility(quickCurrencyHistory(tripId, quickCurrency(tripId)).isEmpty()
                ? View.GONE : View.VISIBLE);
    }

    private GradientDrawable chip(int fill, int stroke, boolean compact) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(compact ? 24 : 12));
        background.setStroke(dp(1), stroke);
        return background;
    }

    private Spinner choice(LinearLayout form, String title, String[] options) {
        text(form, title, 14, Color.DKGRAY);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options);
        spinner.setAdapter(adapter);
        form.addView(spinner, fullMargin());
        return spinner;
    }

    private void heading(LinearLayout body, String value) { text(body, value, 26, Color.rgb(21, 75, 60)); }
    private void section(LinearLayout body, String value) { text(body, value, 19, Color.rgb(21, 75, 60)); }

    private TextView text(LinearLayout body, String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setPadding(0, dp(8), 0, dp(8));
        body.addView(view, fullMargin());
        return view;
    }

    private Button button(String value, Runnable onClick) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    private void buttonInto(LinearLayout body, String value, Runnable onClick) {
        body.addView(button(value, onClick), fullMargin());
    }

    private void row(LinearLayout body, Button left, Button right) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.leftMargin = dp(6);
        line.addView(left, first);
        line.addView(right, second);
        body.addView(line, fullMargin());
    }

    private LinearLayout.LayoutParams fullMargin() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(4);
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private void error(Exception e) { toast(e.getMessage() == null ? "操作失败" : e.getMessage()); }
}
