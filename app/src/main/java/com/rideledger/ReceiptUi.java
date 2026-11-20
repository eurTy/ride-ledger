package com.rideledger;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Coordinates receipt capture, the photo picker and receipt preview. */
final class ReceiptUi {
    private static final int OLD_CAMERA = 81, OLD_GALLERY = 82, CAMERA_PERMISSION = 83;
    private static final int FIRST_PICK_REQUEST = 0x4000, LAST_PICK_REQUEST = 0x4fff;
    private static final int CAMERA_BUTTON_RADIUS = 36;
    private static final int GREEN = 0xff28634e;

    /** Retained with the form so an import can finish after recreation. */
    static final class Selection {
        String tripId, eventId, captureToken;
        int request, lastRequest = FIRST_PICK_REQUEST - 1;
        boolean importing;
        ReceiptUi ui;

        Selection(Bundle state) {
            if (state == null) return;
            tripId = state.getString("receiptTrip");
            eventId = state.getString("receiptEvent");
            captureToken = state.getString("receiptCapture");
            if (tripId != null)
                request = state.getInt("receiptRequest", captureToken == null ? OLD_GALLERY : OLD_CAMERA);
            lastRequest = state.getInt("receiptLastRequest", lastRequest);
        }

        int nextRequest() {
            lastRequest = lastRequest == LAST_PICK_REQUEST ? FIRST_PICK_REQUEST : lastRequest + 1;
            return request = lastRequest;
        }
    }

    private final Activity activity;
    private final LedgerStyle style;
    private final LedgerStore store;
    private final LedgerActions actions;
    private final QuickEntryDrafts drafts;
    private final Consumer<LedgerStore.Trip> saved;
    private final Consumer<Exception> error;
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbnails = new LruCache<>(48);
    private final Selection selection;
    private ReceiptButton targetButton;
    private Dialog preview;

    ReceiptUi(Activity activity, LedgerStore store, LedgerActions actions, QuickEntryDrafts drafts,
              Consumer<LedgerStore.Trip> saved, Consumer<Exception> error) {
        this.activity = activity;
        style = new LedgerStyle(activity);
        this.store = store;
        this.actions = actions;
        this.drafts = drafts;
        this.saved = saved;
        this.error = error;
        selection = drafts.receiptSelection;
        selection.ui = this;
    }

    void saveState(Bundle state) {
        state.putInt("receiptLastRequest", selection.lastRequest);
        if (!selection.importing) {
            state.putString("receiptTrip", selection.tripId);
            state.putString("receiptEvent", selection.eventId);
            state.putString("receiptCapture", selection.captureToken);
            state.putInt("receiptRequest", selection.request);
        }
    }

    boolean picking() { return selection.tripId != null; }

    String draft(String tripId) {
        String token = drafts.photo(tripId);
        if (token == null || PeerIdentity.valid(token) && cacheFile("receipt-draft", token).isFile()) return token;
        drafts.removePhoto(tripId);
        return null;
    }

    ReceiptImage draftImage(String token) throws IOException {
        return token == null ? null : ReceiptImage.fromJpeg(readDraft(token));
    }

    private byte[] readDraft(String token) throws IOException {
        File file = cacheFile("receipt-draft", token);
        if (file.length() <= 0 || file.length() > ReceiptImage.MAX_BYTES)
            throw new IOException("暂存小票已失效，请重新选择或移除");
        return Files.readAllBytes(file.toPath());
    }

    void clearDraft(String tripId, String token) {
        if (token == null || !token.equals(draft(tripId))) return;
        remove(cacheFile("receipt-draft", token));
        drafts.removePhoto(tripId);
    }

    ImageButton besideNote(View input, LedgerStore.Trip trip, String eventId, LedgerReceipts.Entry attached) {
        LinearLayout parent = (LinearLayout) input.getParent();
        int index = parent.indexOfChild(input);
        LinearLayout.LayoutParams sizing = (LinearLayout.LayoutParams) input.getLayoutParams();
        parent.removeView(input);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, index, sizing);
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        ReceiptButton button = new ReceiptButton(activity, style);
        if (eventId == null) paintDraft(button, draft(trip.id), null);
        else {
            paint(button, attached != null);
            if (attached != null) bindThumbnail(trip.id, attached, button);
        }
        row.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
        button.setOnClickListener(view -> press(button, trip.id, eventId, false));
        button.setOnLongClickListener(view -> {
            press(button, trip.id, eventId, true);
            return true;
        });
        if (trip.id.equals(selection.tripId) && java.util.Objects.equals(eventId, selection.eventId)) targetButton = button;
        return button;
    }

    private void paint(ReceiptButton button, boolean attached) {
        button.setTag(null);
        String hint = attached ? "查看小票，长按管理" : "拍照添加小票，长按从相册选择";
        button.setContentDescription(hint);
        button.setTooltipText(hint);
        button.setBackground(style.rounded(style.surface, 0, CAMERA_BUTTON_RADIUS));
        button.setForeground(style.ripple(style.rounded(Color.TRANSPARENT, attached ? style.primary : style.stroke,
                CAMERA_BUTTON_RADIUS), CAMERA_BUTTON_RADIUS));
        button.showCamera();
    }

    private void paintDraft(ReceiptButton button, String token, byte[] thumb) {
        paint(button, token != null);
        if (token != null) bindThumbnail("draft/" + token, button,
                () -> thumb == null ? ReceiptImage.fromJpeg(readDraft(token)).thumb : thumb);
    }

    private void press(ReceiptButton button, String tripId, String eventId, boolean longPress) {
        if (picking()) {
            if (selection.importing || selection.request == 0) {
                Toast.makeText(activity, "正在处理小票，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }
            // A new press can replace a picker that left no result. A late result keeps its own request code.
            finishSelection(null);
        }
        LedgerReceipts.Entry entry = eventId == null ? null : store.receipts.get(tripId, eventId);
        String draft = eventId == null ? draft(tripId) : null;
        if (draft == null && (entry == null || entry.digest.isEmpty())) {
            select(tripId, eventId, button, !longPress);
        } else if (longPress) {
            menu(button, tripId, eventId, draft);
        } else if (draft != null) {
            showPhoto(() -> readDraft(draft));
        } else {
            view(tripId, entry);
        }
    }

    private void menu(ReceiptButton button, String tripId, String eventId, String draft) {
        PopupMenu menu = new PopupMenu(activity, button);
        menu.getMenu().add(0, 1, 0, "重新拍照");
        menu.getMenu().add(0, 2, 1, "从相册选择");
        menu.getMenu().add(0, 3, 2, "移除小票");
        menu.setOnMenuItemClickListener(item -> {
            if (picking() || !button.isAttachedToWindow()) return false;
            switch (item.getItemId()) {
                case 1: select(tripId, eventId, button, true); break;
                case 2: select(tripId, eventId, button, false); break;
                case 3:
                    if (eventId == null) {
                        clearDraft(tripId, draft);
                        paint(button, false);
                    } else {
                        target(tripId, eventId, button);
                        selection.importing = true;
                        changeReceipt(tripId, eventId, null);
                    }
                    break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }

    private void select(String tripId, String eventId, ReceiptButton button, boolean camera) {
        target(tripId, eventId, button);
        if (camera && activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        } else if (camera) launchCamera();
        else launchGallery();
    }

    private void target(String tripId, String eventId, ReceiptButton button) {
        selection.tripId = tripId;
        selection.eventId = eventId;
        targetButton = button;
    }

    boolean permissionResult(int request) {
        if (request != CAMERA_PERMISSION) return false;
        if (!picking()) return true;
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera();
        else finishSelection(new IOException("未授权相机，长按相机图标可从相册选择"));
        return true;
    }

    private void launchCamera() {
        try {
            selection.captureToken = UUID.randomUUID().toString();
            File output = ReceiptCamera.file(activity, selection.captureToken);
            ensureDirectory(output.getParentFile());
            if (!output.createNewFile()) throw new IOException("无法创建照片文件");
            Intent camera = new Intent(activity, ReceiptCaptureActivity.class)
                    .putExtra(ReceiptCaptureActivity.EXTRA_CAPTURE, selection.captureToken);
            activity.startActivityForResult(camera, selection.nextRequest());
        } catch (IOException | RuntimeException unavailable) {
            finishSelection(new IOException("无法打开相机，长按小票入口可从相册选择", unavailable));
        }
    }

    private void launchGallery() {
        try {
            int request = selection.nextRequest();
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    activity.startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), request);
                    return;
                } catch (android.content.ActivityNotFoundException unavailable) { /* Use the document picker. */ }
            }
            activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*"), request);
        } catch (RuntimeException unavailable) { finishSelection(new IOException("系统照片选择器不可用")); }
    }

    boolean activityResult(int request, int result, Intent data) {
        if (request != OLD_CAMERA && request != OLD_GALLERY && (request < FIRST_PICK_REQUEST || request > LAST_PICK_REQUEST)) return false;
        if (!picking() || request != selection.request || selection.importing) return true;
        if (result != Activity.RESULT_OK) { finishSelection(null); return true; }
        Uri uri = data == null ? null : data.getData();
        String tripId = selection.tripId, eventId = selection.eventId, capture = selection.captureToken;
        if (capture == null && uri == null) { finishSelection(new IOException("没有收到照片")); return true; }
        selection.importing = true;
        images.execute(() -> {
            try {
                byte[] photo = capture == null ? ReceiptImage.importPhoto(activity.getContentResolver(), uri)
                        : ReceiptCamera.readResult(activity, capture, data);
                ReceiptImage image = ReceiptImage.fromJpeg(photo);
                if (eventId != null) {
                    activity.runOnUiThread(() -> changeReceipt(tripId, eventId, image));
                } else {
                    String token = UUID.randomUUID().toString();
                    File draft = cacheFile("receipt-draft", token);
                    ensureDirectory(draft.getParentFile());
                    try { Files.write(draft.toPath(), photo); }
                    catch (IOException failed) { remove(draft); throw failed; }
                    activity.runOnUiThread(() -> saveDraft(tripId, token, draft, image.thumb));
                }
            } catch (IOException | RuntimeException failure) {
                String message = capture == null ? "无法保存小票，请重新选择" : "没有保存小票，请重新拍照或从相册选择";
                activity.runOnUiThread(() -> finishSelection(new IOException(message, failure)));
            }
        });
        return true;
    }

    private void saveDraft(String tripId, String token, File file, byte[] thumb) {
        ReceiptUi ui = selection.ui;
        if (ui == null && !activity.isChangingConfigurations()) remove(file);
        else {
            clearDraft(tripId, draft(tripId));
            drafts.putPhoto(tripId, token);
            if (ui != null && ui.targetButton != null) {
                ui.paintDraft(ui.targetButton, token, thumb);
                Toast.makeText(ui.activity, "小票已附上", Toast.LENGTH_SHORT).show();
            }
        }
        finishSelection(null);
    }

    private void changeReceipt(String tripId, String eventId, ReceiptImage image) {
        LedgerStore.Trip trip = store.getTrip(tripId);
        if (trip == null) { finishSelection(new IOException("这趟旅行已删除")); return; }
        actions.submit(() -> actions.receipt(trip, eventId, image), () -> {
            ReceiptUi ui = selection.ui;
            if (ui != null && ui.targetButton != null) {
                ui.paint(ui.targetButton, image != null);
                if (image != null) ui.bindThumbnail(eventId + "/" + image.digest, ui.targetButton, () -> image.thumb);
            }
            finishSelection(null);
            if (ui != null) {
                ui.saved.accept(trip);
                Toast.makeText(ui.activity, image == null ? "小票已移除" : "小票已保存", Toast.LENGTH_SHORT).show();
            }
        }, this::finishSelection);
    }

    private void finishSelection(Exception failure) {
        String capture = selection.captureToken;
        selection.tripId = selection.eventId = selection.captureToken = null;
        selection.request = 0;
        selection.importing = false;
        ReceiptUi ui = selection.ui;
        if (ui != null) ui.targetButton = null;
        if (capture != null) {
            remove(ReceiptCamera.file(activity, capture));
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            try { activity.revokeUriPermission(ReceiptCamera.uri(activity, capture), flags); }
            catch (SecurityException alreadyRevoked) { /* The camera has released the grant. */ }
        }
        if (failure != null && ui != null) ui.error.accept(failure);
    }

    void bindThumbnail(String tripId, LedgerReceipts.Entry entry, ImageView view) {
        bindThumbnail(entry.eventId + "/" + entry.digest, view,
                entry.available ? () -> store.receipts.bytes(tripId, entry, true) : null);
    }

    private void bindThumbnail(String key, ImageView view, PhotoRead source) {
        view.setTag(key);
        Bitmap cached = thumbnails.get(key);
        if (cached != null) {
            displayThumbnail(view, cached);
            return;
        }
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setImageResource(R.drawable.ic_ledger_camera);
        if (source == null || images.isShutdown()) return;
        images.execute(() -> {
            try {
                byte[] bytes = source.load();
                if (bytes == null) return;
                Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (image == null) return;
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    thumbnails.put(key, image);
                    if (key.equals(view.getTag())) displayThumbnail(view, image);
                });
            } catch (IOException | RuntimeException ignored) { /* The placeholder stays openable. */ }
        });
    }

    private static void displayThumbnail(ImageView view, Bitmap image) {
        if (view instanceof ReceiptButton) ((ReceiptButton) view).fillWithPhoto();
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageBitmap(image);
    }

    private static final class ReceiptButton extends ImageButton {
        private final int minimumInset, iconSize;
        private boolean showingPhoto;

        ReceiptButton(Activity activity, LedgerStyle style) {
            super(activity);
            minimumInset = style.dp(12);
            iconSize = style.dp(32);
            setClipToOutline(true);
        }

        void showCamera() {
            showingPhoto = false;
            updateInset(getWidth(), getHeight());
            setScaleType(ScaleType.FIT_CENTER);
            setImageResource(R.drawable.ic_ledger_camera);
        }

        void fillWithPhoto() {
            showingPhoto = true;
            updateInset(getWidth(), getHeight());
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            updateInset(width, height);
        }

        private void updateInset(int width, int height) {
            int inset = showingPhoto ? 0 : Math.max(minimumInset, (Math.min(width, height) - iconSize + 1) / 2);
            setPadding(inset, inset, inset, inset);
        }
    }

    void view(String tripId, LedgerReceipts.Entry entry) {
        if (!entry.available) error.accept(new IOException("图片待同步，连接同一 Wi-Fi 或热点后查看"));
        else showPhoto(() -> store.receipts.bytes(tripId, entry, false));
    }

    private interface PhotoRead { byte[] load() throws IOException; }

    private void showPhoto(PhotoRead source) {
        if (preview != null) preview.dismiss();
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout frame = new LinearLayout(activity);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(0xff1b2823);
        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(8), 0);
        bar.setBackgroundColor(Color.WHITE);
        TextView title = new TextView(activity);
        title.setText("小票");
        title.setTextSize(20);
        title.setTextColor(GREEN);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = style.button("关闭", LedgerStyle.Action.OUTLINE, dialog::dismiss);
        close.setMinWidth(dp(64));
        close.setMinHeight(dp(LedgerStyle.COMPACT_HEIGHT));
        close.setMinimumHeight(dp(LedgerStyle.COMPACT_HEIGHT));
        LinearLayout.LayoutParams closeSize = new LinearLayout.LayoutParams(-2, -2);
        closeSize.topMargin = closeSize.bottomMargin = dp(4);
        bar.addView(close, closeSize);
        frame.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        ZoomImage image = new ZoomImage(activity);
        image.setContentDescription("小票照片，可双指缩放");
        frame.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        Window window = dialog.getWindow();
        FormInput.fitActivityWindow(window, frame);
        dialog.setContentView(frame);
        preview = dialog;
        dialog.setOnDismissListener(ignored -> { if (preview == dialog) preview = null; });
        dialog.show();
        if (Build.VERSION.SDK_INT >= 30) {
            window.getInsetsController().setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        } else {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        images.execute(() -> {
            Bitmap bitmap = null;
            try {
                byte[] bytes = source.load();
                if (bytes != null) bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (IOException | RuntimeException failure) { /* Report on the UI thread. */ }
            Bitmap display = bitmap;
            activity.runOnUiThread(() -> {
                if (preview != dialog || activity.isDestroyed()) {
                    if (display != null) display.recycle();
                    return;
                }
                if (display != null) image.display(display);
                else { dialog.dismiss(); error.accept(new IOException("暂时无法打开小票")); }
            });
        });
    }

    void close() {
        if (preview != null) preview.dismiss();
        if (selection.ui == this) {
            selection.ui = null;
            targetButton = null;
            if (activity.isFinishing() && !selection.importing) finishSelection(null);
        }
        images.shutdown();
    }

    private File cacheFile(String directory, String token) {
        if (!PeerIdentity.valid(token)) throw new IllegalArgumentException("Invalid receipt draft token");
        return new File(new File(activity.getCacheDir(), directory), token + ".jpg");
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法暂存小票");
    }

    private static void remove(File file) {
        try { Files.deleteIfExists(file.toPath()); }
        catch (IOException ignored) { }
    }

    private int dp(int value) { return Math.round(activity.getResources().getDisplayMetrics().density * value); }

    private static final class ZoomImage extends ImageView {
        private final Matrix matrix = new Matrix();
        private final ScaleGestureDetector gesture;
        private float fit, scale, lastX, lastY;

        ZoomImage(Activity activity) {
            super(activity);
            setScaleType(ScaleType.MATRIX);
            gesture = new ScaleGestureDetector(activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    if (fit <= 0) return false;
                    float next = Math.max(fit, Math.min(fit * 6, scale * detector.getScaleFactor()));
                    matrix.postScale(next / scale, next / scale, detector.getFocusX(), detector.getFocusY());
                    scale = next;
                    position();
                    return true;
                }
            });
        }

        void display(Bitmap bitmap) { setImageBitmap(bitmap); fit(); }

        @Override protected void onSizeChanged(int w, int h, int oldWidth, int oldHeight) {
            super.onSizeChanged(w, h, oldWidth, oldHeight);
            fit();
        }

        private void fit() {
            if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
            fit = scale = Math.min((float) getWidth() / getDrawable().getIntrinsicWidth(),
                    (float) getHeight() / getDrawable().getIntrinsicHeight());
            matrix.setScale(fit, fit);
            position();
        }

        private void position() {
            RectF photo = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
            matrix.mapRect(photo);
            matrix.postTranslate(constrain(photo.left, photo.right, getWidth()),
                    constrain(photo.top, photo.bottom, getHeight()));
            setImageMatrix(matrix);
        }

        private static float constrain(float start, float end, int space) {
            float size = end - start;
            if (size <= space) return (space - size) / 2 - start;
            if (start > 0) return -start;
            return Math.max(0, space - end);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (getDrawable() == null) return super.onTouchEvent(event);
            gesture.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1 &&
                    !gesture.isInProgress()) {
                matrix.postTranslate(event.getX() - lastX, event.getY() - lastY);
                position();
            }
            int pointer = event.getActionMasked() == MotionEvent.ACTION_POINTER_UP && event.getActionIndex() == 0 ? 1 : 0;
            lastX = event.getX(pointer);
            lastY = event.getY(pointer);
            return true;
        }
    }
}
