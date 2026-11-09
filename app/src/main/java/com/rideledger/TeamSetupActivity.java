package com.rideledger;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TeamSetupActivity extends Activity {
    static final String MODE = "mode", TRIP = "trip";
    static final String CREATE = "create", JOIN = "join", INVITE = "invite";
    private static final int SCAN = 1, IMAGE = 2, CAMERA_PERMISSION = 3;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    // Transport callbacks refresh membership immediately. Only user-visible deadlines
    // need a timer; an idle invitation page must not read SQLite four times per second.
    private final Runnable tick = this::refresh;
    private LedgerStore store;
    private TeamCreator creator;
    private TeamJoiner joiner;
    private TeamTransportAdapter transport;
    private String mode, tripId, invitation;
    private String joinFailure = "";
    private long startedAt, joinedAt;
    private boolean started;
    private LinearLayout body, identity;
    private TextView status, count, roster, history, nameConflict;
    private EditText title, name, manual;
    private Button tokenToggle, readToken;
    private boolean tokenExpanded;
    private SeekBar expected;
    private RadioGroup seat;
    private ImageView qr;
    private AlertDialog textInviteDialog;
    private Button ready, retry, changeName;
    private LedgerStore.Trip preview;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = LedgerStore.application(this);
        creator = new TeamCreator(store);
        joiner = new TeamJoiner(store);
        transport = new TeamTransportAdapter(this, store, added -> refresh(), true);
        mode = saved == null ? getIntent().getStringExtra(MODE) : saved.getString(MODE);
        tripId = saved == null ? getIntent().getStringExtra(TRIP) : saved.getString(TRIP);
        invitation = saved == null ? null : saved.getString("invite");
        tokenExpanded = saved != null && saved.getBoolean("tokenExpanded");
        long now = SystemClock.elapsedRealtime();
        startedAt = saved == null ? now : Math.min(now, saved.getLong("startedAt", now));
        joinedAt = saved == null ? 0 : saved.getLong("joinedAt");
        joinFailure = saved == null ? "" : saved.getString("joinFailure", "");
        show();
        if (CREATE.equals(mode) && tripId != null) selectResult();
        if (saved != null) {
            if (title != null && saved.containsKey("title")) title.setText(saved.getString("title"));
            if (name != null && saved.containsKey("name")) name.setText(saved.getString("name"));
            if (manual != null && saved.containsKey("manual")) manual.setText(saved.getString("manual"));
            if (expected != null) expected.setProgress(saved.getInt("expected") + 1);
            if (seat != null) check(seat, saved.getInt("seat", -1));
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(MODE, mode);
        out.putString(TRIP, tripId);
        out.putString("invite", invitation);
        out.putBoolean("tokenExpanded", tokenExpanded);
        out.putLong("startedAt", startedAt);
        out.putLong("joinedAt", joinedAt);
        out.putString("joinFailure", joinFailure);
        if (title != null) out.putString("title", value(title));
        if (name != null) out.putString("name", value(name));
        if (manual != null) out.putString("manual", value(manual));
        if (expected != null) out.putInt("expected", expected.getProgress() - 1);
        if (seat != null) out.putInt("seat", selected(seat));
    }

    @Override protected void onStart() {
        super.onStart();
        SyncBackground.foregroundEntered(this);
        started = true;
        LedgerStore.Trip current = trip();
        if (!JOIN.equals(mode) || current == null || store.teamReady(current) ||
                (joinFailure.isEmpty() && SystemClock.elapsedRealtime() - startedAt < TeamJoiner.WAIT_MILLIS))
            transport.select(current);
        main.post(tick);
    }

    @Override protected void onStop() {
        started = false;
        main.removeCallbacks(tick);
        transport.stop();
        SyncBackground.foregroundLeft();
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(tick);
        transport.stop();
        if (textInviteDialog != null) textInviteDialog.dismiss();
        decoder.shutdownNow();
        super.onDestroy();
    }

    private LedgerStore.Trip trip() { return tripId == null ? null : store.getTrip(tripId); }

    private void show() {
        title = name = manual = null;
        expected = null;
        seat = null;
        identity = null;
        count = roster = history = nameConflict = null;
        ready = retry = changeName = null;
        qr = null;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Color.rgb(248, 249, 247));
        body.setPadding(dp(20), dp(16), dp(20), dp(28));
        scroll.addView(body);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(248, 249, 247));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        FormInput.fitActivityWindow(getWindow(), root);
        setContentView(root);
        button("返回", this::finish);
        LedgerStore.Trip current = trip();
        if (JOIN.equals(mode)) joining(current);
        else organizing(current);
        refresh();
    }

    private void organizing(LedgerStore.Trip current) {
        text(body, current == null ? "创建小队旅行" : "邀请同伴", 24, Color.BLACK);
        title = input(body, "旅行名称", 80);
        name = input(body, "本人名称", 30);
        if (current == null) {
            FormInput.next(title, name);
            showTeamSize();
            status = text(body, "", 14, Color.GRAY);
            button("生成邀请", () -> {
                try {
                    LedgerStore.Trip created = creator.create(value(title), value(name), expected.getProgress());
                    tripId = created.id;
                    startedAt = SystemClock.elapsedRealtime();
                    selectResult();
                    show();
                    transport.select(created);
                } catch (Exception error) { inline(error); }
            });
            return;
        }
        title.setText(current.name);
        name.setText(current.nameOf(current.localPerson));
        title.setEnabled(false);
        name.setEnabled(false);
        count = text(body, "", 18, Color.BLACK);
        status = text(body, "", 14, Color.GRAY);
        roster = text(body, "", 15, Color.DKGRAY);
        if (current.people() > 1) {
            qr = new ImageView(this);
            qr.setContentDescription("小队邀请二维码");
            body.addView(qr, new LinearLayout.LayoutParams(-1, dp(280)));
            showInvitation(current);
            button("令牌", () -> showTextInvite(current));
        }
        ready = button("开始记账", () -> { selectResult(); finish(); });
    }

    private void showInvitation(LedgerStore.Trip current) {
        try {
            String code = creator.invite(current);
            qr.setImageBitmap(InviteQr.image(code, dp(280)));
        } catch (Exception error) { text(body, "无法生成二维码，请检查旅行信息。", 14, Color.GRAY); }
    }

    private void showTextInvite(LedgerStore.Trip current) {
        String code;
        try { code = creator.invite(current); }
        catch (Exception error) { inline(error); return; }

        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        boolean copied = false;
        if (clipboard != null) try {
            ClipData clip = ClipData.newPlainText("旅行账本令牌", code);
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            clipboard.setPrimaryClip(clip);
            copied = true;
        } catch (RuntimeException ignored) { }

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), 0, dp(24), 0);
        text(content, copied ? "已复制到剪贴板" : "可长按复制令牌", 14, 0xff176344);
        text(content, code, 14, Color.DKGRAY).setTextIsSelectable(true);
        scroll.addView(content);
        textInviteDialog = new AlertDialog.Builder(this).setTitle("令牌").setView(scroll)
                .setPositiveButton("关闭", null).show();
    }

    private void joining(LedgerStore.Trip current) {
        text(body, "加入小队旅行", 24, Color.BLACK);
        if (current == null) {
            Button scan = button("扫码加入", this::openScanner);
            GradientDrawable surface = new GradientDrawable();
            surface.setCornerRadius(dp(12));
            surface.setColor(0xff14775f);
            scan.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), surface, null));
            scan.setTextColor(Color.WHITE);
            scan.setTypeface(scan.getTypeface(), Typeface.BOLD);
            scan.setTextSize(16);
            LinearLayout.LayoutParams scanSize = new LinearLayout.LayoutParams(-1, dp(52));
            scanSize.setMargins(0, dp(4), 0, dp(8));
            scan.setLayoutParams(scanSize);
            button("从相册选二维码", () -> {
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                try { startActivityForResult(pick, IMAGE); }
                catch (ActivityNotFoundException ignored) { status.setText("此设备没有可用的图片选择器，可使用令牌。"); }
            });
            tokenToggle = button("令牌（备用）", () -> setTokenExpanded(!tokenExpanded));
            manual = new EditText(this);
            manual.setContentDescription("备用令牌");
            manual.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            manual.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            manual.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
            manual.setMaxLines(4);
            body.addView(manual, new LinearLayout.LayoutParams(-1, -2));
            readToken = button("读取令牌", () -> readInvite(value(manual)));
            setTokenExpanded(tokenExpanded);
            identity = new LinearLayout(this);
            identity.setOrientation(LinearLayout.VERTICAL);
            body.addView(identity);
            status = text(body, "", 14, Color.GRAY);
            if (invitation != null) readInvite(invitation);
        } else {
            tripLabels(body, current);
            text(body, "本人名称 · " + current.nameOf(current.localPerson), 15, Color.DKGRAY);
            status = text(body, "正在加入小队...", 14, Color.GRAY);
            history = text(body, "", 14, Color.GRAY);
            retry = button("重试连接", () -> {
                startedAt = SystemClock.elapsedRealtime();
                joinFailure = "";
                transport.stop();
                transport.select(trip());
                refresh();
            });
            if (current.team.openSeats) changeName = button("修改本人名称", () -> {
                try {
                    invitation = store.inviteCode(trip());
                    transport.stop();
                    joinFailure = "";
                    tripId = null;
                    show();
                    name.setText(current.joinName);
                } catch (Exception error) { inline(error); }
            });
        }
    }

    private void readInvite(String code) {
        try {
            preview = joiner.preview(code);
            invitation = code;
            identity.removeAllViews();
            tripLabels(identity, preview);
            name = null;
            seat = null;
            if (preview.team.openSeats) {
                name = input(identity, "本人名称", 30);
                nameConflict = text(identity, "", 14, Color.GRAY);
                nameConflict.setVisibility(View.GONE);
                name.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
                    @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                        String conflict = joiner.nameConflict(preview, value.toString());
                        updateText(nameConflict, conflict);
                        nameConflict.setVisibility(conflict.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                    @Override public void afterTextChanged(Editable value) { }
                });
            } else {
                text(identity, "请选择名单里的本人名称", 14, Color.DKGRAY);
                String[] names = new String[preview.people() - 1];
                for (int i = 0; i < names.length; i++) names[i] = preview.nameOf(preview.member(i + 1));
                seat = choices(identity, names);
            }
            Button submit = new Button(this);
            submit.setText("确认并加入");
            submit.setOnClickListener(view -> submitJoin());
            identity.addView(submit);
            status.setText("");
        } catch (Exception error) {
            invitation = null;
            preview = null;
            identity.removeAllViews();
            inline(error);
        }
    }

    private void setTokenExpanded(boolean expanded) {
        tokenExpanded = expanded;
        tokenToggle.setText(expanded ? "令牌（备用）  ▴" : "令牌（备用）  ▾");
        tokenToggle.setContentDescription(expanded ? "收起备用令牌" : "展开备用令牌");
        manual.setVisibility(expanded ? View.VISIBLE : View.GONE);
        readToken.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private void submitJoin() {
        try {
            String member;
            if (name != null) {
                member = value(name);
                if (!joiner.nameConflict(preview, member).isEmpty()) return;
            } else {
                int index = selected(seat);
                if (index < 0) throw new IllegalArgumentException("请选择本人名称");
                member = preview.member(index + 1);
            }
            LedgerStore.Trip current = joiner.join(invitation, member);
            tripId = current.id;
            startedAt = SystemClock.elapsedRealtime();
            joinedAt = 0;
            joinFailure = "";
            show();
            transport.select(current);
        } catch (Exception error) { inline(error); }
    }

    private void tripLabels(LinearLayout parent, LedgerStore.Trip current) {
        text(parent, "旅行名称 · " + current.name, 16, Color.BLACK);
        text(parent, "组织者 · " + current.nameOf(current.member(0)), 14, Color.DKGRAY);
    }

    private void refresh() {
        main.removeCallbacks(tick);
        if (status == null || isFinishing()) return;
        LedgerStore.Trip current = trip();
        if (current == null) return;
        long now = SystemClock.elapsedRealtime();
        if (JOIN.equals(mode)) {
            boolean enrolled = store.teamReady(current);
            String failure = transport.error();
            if (!failure.isEmpty()) joinFailure = failure;
            boolean expired = now - startedAt >= TeamJoiner.WAIT_MILLIS;
            if (!enrolled && transport.nearby != null && (expired || !joinFailure.isEmpty())) transport.stop();
            updateText(status, joiner.status(current, joinFailure, now - startedAt));
            retry.setVisibility(!enrolled && (expired || !joinFailure.isEmpty()) ? View.VISIBLE : View.GONE);
            if (changeName != null) changeName.setVisibility(!enrolled && !joinFailure.isEmpty() ? View.VISIBLE : View.GONE);
            if (enrolled && joinedAt == 0) joinedAt = now;
            boolean receiving = enrolled && !transport.initialHistoryReceived() && transport.receivingInitialHistory() &&
                    now - joinedAt < TeamJoiner.HISTORY_WAIT_MILLIS;
            String historyLabel = !enrolled || transport.initialHistoryReceived() ? "" : receiving ?
                    "正在接收历史账目…" : "与队友连通后会自动补齐历史账目";
            updateText(history, historyLabel);
            history.setVisibility(historyLabel.isEmpty() ? View.GONE : View.VISIBLE);
            if (enrolled && started && !receiving && now - joinedAt >= TeamJoiner.RETURN_MILLIS) {
                selectResult();
                finish();
            } else if (!enrolled && !expired && joinFailure.isEmpty()) {
                refreshAt(startedAt + TeamJoiner.WAIT_MILLIS);
            } else if (enrolled) {
                refreshAt(joinedAt + (receiving ? TeamJoiner.HISTORY_WAIT_MILLIS : TeamJoiner.RETURN_MILLIS));
            }
            return;
        }
        try {
            List<String> members = creator.roster(current);
            updateText(count, "已加入 " + members.size() + " / " + current.people() + " 人");
            updateText(roster, String.join("\n", members.stream().map(member -> member + " · 已加入")
                    .toArray(String[]::new)));
            boolean complete = members.size() == current.people();
            ready.setVisibility(complete ? View.VISIBLE : View.GONE);
            updateText(status, complete ? "小队已就绪，可开始记账" : now - startedAt >= TeamJoiner.WAIT_MILLIS ?
                    "等待队友加入中，可分享二维码或令牌" : "正在等待队友登记");
            if (!complete && now - startedAt < TeamJoiner.WAIT_MILLIS)
                refreshAt(startedAt + TeamJoiner.WAIT_MILLIS);
        } catch (Exception error) { inline(error); }
    }

    private void refreshAt(long deadline) {
        if (started && !isFinishing()) main.postDelayed(tick, Math.max(1, deadline - SystemClock.elapsedRealtime()));
    }

    private void openScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startActivityForResult(new Intent(this, InviteScannerActivity.class), SCAN);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != CAMERA_PERMISSION || isFinishing() || tripId != null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openScanner();
        else status.setText("相机权限未开启，可以从相册选择二维码。");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || identity == null) return;
        if (request == SCAN) {
            String code = data.getStringExtra(InviteScannerActivity.EXTRA_INVITE);
            if (code != null) readInvite(code);
        } else if (request == IMAGE && data.getData() != null) {
            Uri image = data.getData();
            status.setText("正在识别二维码…");
            decoder.execute(() -> {
                String code = null;
                try { code = InviteQr.readImage(getContentResolver(), image); } catch (Exception ignored) { }
                String decoded = code;
                main.post(() -> {
                    if (isDestroyed() || isFinishing() || identity == null) return;
                    if (InviteQr.isTeamCode(decoded)) readInvite(decoded);
                    else status.setText("图片里没有读到有效的小队二维码。");
                });
            });
        }
    }

    private void selectResult() { setResult(RESULT_OK, new Intent().putExtra(TRIP, tripId)); }
    private void inline(Exception error) { status.setText(error.getMessage() == null ? "暂时无法完成，请重试。" : error.getMessage()); }
    private String value(EditText field) { return field.getText().toString().trim(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        body.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private TextView text(LinearLayout parent, String content, int size, int color) {
        TextView label = new TextView(this);
        label.setText(content);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setPadding(dp(2), dp(7), dp(2), dp(7));
        parent.addView(label);
        return label;
    }

    private EditText input(LinearLayout parent, String label, int length) {
        text(parent, label, 14, Color.DKGRAY);
        EditText field = new EditText(this);
        field.setHint(label);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(length)});
        field.setSingleLine(true);
        FormInput.done(field);
        parent.addView(field);
        return field;
    }

    private void updateText(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private RadioGroup choices(LinearLayout parent, String[] labels) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(LinearLayout.VERTICAL);
        for (String label : labels) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(label);
            group.addView(button, new RadioGroup.LayoutParams(-1, dp(48)));
        }
        parent.addView(group, new LinearLayout.LayoutParams(-1, -2));
        return group;
    }

    private void showTeamSize() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(header, new LinearLayout.LayoutParams(-1, -2));
        TextView label = text(header, "小队人数", 14, Color.DKGRAY);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(header, "1 人", 16, 0xff14775f);
        value.setTypeface(value.getTypeface(), Typeface.BOLD);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        expected = new SeekBar(this);
        expected.setMin(1);
        expected.setMax(Team.MAX_DEVICES);
        expected.setProgress(1);
        expected.setContentDescription("小队人数，1 人，包括你");
        expected.setThumbTintList(ColorStateList.valueOf(0xff14775f));
        expected.setProgressTintList(ColorStateList.valueOf(0xff14775f));
        GradientDrawable tick = new GradientDrawable();
        tick.setShape(GradientDrawable.OVAL);
        tick.setSize(dp(5), dp(5));
        tick.setColor(0xff7c9e92);
        expected.setTickMark(tick);
        expected.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int people, boolean fromUser) {
                value.setText(people + " 人");
                slider.setContentDescription("小队人数，" + people + " 人，包括你");
            }
            @Override public void onStartTrackingTouch(SeekBar slider) { }
            @Override public void onStopTrackingTouch(SeekBar slider) { }
        });
        body.addView(expected, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private int selected(RadioGroup group) { return group.indexOfChild(group.findViewById(group.getCheckedRadioButtonId())); }
    private void check(RadioGroup group, int index) {
        if (index >= 0 && index < group.getChildCount()) group.check(group.getChildAt(index).getId());
    }
}
