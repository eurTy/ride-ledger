package com.rideledger;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
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

/** Coordinates the platform camera, picker and receipt preview. */
final class ReceiptUi {
    private static final int CAMERA = 81, GALLERY = 82, CAMERA_PERMISSION = 83;
    private static final int GREEN = 0xff28634e;
    private final Activity activity;
    private final LedgerStyle style;
    private final LedgerStore store;
    private final LedgerActions actions;
    private final QuickEntryDrafts drafts;
    private final Consumer<LedgerStore.Trip> saved;
    private final Consumer<Exception> error;
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbnails = new LruCache<>(48);
    private String targetTrip, targetEvent, captureToken;
    private ImageButton targetButton;
    private boolean importing;
    private Dialog preview;

    ReceiptUi(Activity activity, LedgerStore store, LedgerActions actions, QuickEntryDrafts drafts, Bundle state,
              Consumer<LedgerStore.Trip> saved, Consumer<Exception> error) {
        this.activity = activity;
        style = new LedgerStyle(activity);
        this.store = store;
        this.actions = actions;
        this.drafts = drafts;
        this.saved = saved;
        this.error = error;
        if (state != null) {
            targetTrip = state.getString("receiptTrip");
            targetEvent = state.getString("receiptEvent");
            captureToken = state.getString("receiptCapture");
        }
    }

    void saveState(Bundle state) {
        if (!importing) {
            state.putString("receiptTrip", targetTrip);
            state.putString("receiptEvent", targetEvent);
            state.putString("receiptCapture", captureToken);
        }
    }

    boolean picking() { return targetTrip != null; }

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

    ImageButton besideNote(View input, LedgerStore.Trip trip, String eventId, boolean attached) {
        LinearLayout parent = (LinearLayout) input.getParent();
        int index = parent.indexOfChild(input);
        LinearLayout.LayoutParams sizing = (LinearLayout.LayoutParams) input.getLayoutParams();
        parent.removeView(input);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, index, sizing);
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton button = new ImageButton(activity);
        button.setImageResource(R.drawable.ic_ledger_camera);
        button.setScaleType(ImageView.ScaleType.CENTER);
        paint(button, eventId == null ? draft(trip.id) != null : attached);
        row.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
        button.setOnClickListener(view -> press(button, trip.id, eventId, false));
        button.setOnLongClickListener(view -> {
            press(button, trip.id, eventId, true);
            return true;
        });
        if (trip.id.equals(targetTrip) && java.util.Objects.equals(eventId, targetEvent)) targetButton = button;
        return button;
    }

    private void paint(ImageButton button, boolean attached) {
        String hint = attached ? "查看小票，长按管理" : "拍照添加小票，长按从相册选择";
        button.setContentDescription(hint);
        button.setTooltipText(hint);
        button.setBackground(style.ripple(attached ? style.rounded(style.selected, 0, LedgerStyle.CONTROL_RADIUS) : null,
                LedgerStyle.CONTROL_RADIUS));
    }

    private void press(ImageButton button, String tripId, String eventId, boolean longPress) {
        if (picking()) return;
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

    private void menu(ImageButton button, String tripId, String eventId, String draft) {
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
                    } else changeReceipt(tripId, eventId, null, button);
                    break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }

    private void select(String tripId, String eventId, ImageButton button, boolean camera) {
        targetTrip = tripId;
        targetEvent = eventId;
        targetButton = button;
        if (camera && activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        } else if (camera) launchCamera();
        else launchGallery();
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
            captureToken = UUID.randomUUID().toString();
            File output = ReceiptCamera.file(activity, captureToken);
            ensureDirectory(output.getParentFile());
            if (!output.createNewFile()) throw new IOException("无法创建照片文件");
            Uri uri = ReceiptCamera.uri(activity, captureToken);
            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            camera.setClipData(ClipData.newRawUri("receipt", uri));
            activity.startActivityForResult(camera, CAMERA);
        } catch (IOException | RuntimeException unavailable) {
            finishSelection(new IOException("系统相机不可用，长按相机图标可从相册选择"));
        }
    }

    private void launchGallery() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    activity.startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), GALLERY);
                    return;
                } catch (android.content.ActivityNotFoundException unavailable) { /* Use the document picker. */ }
            }
            activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*"), GALLERY);
        } catch (RuntimeException unavailable) { finishSelection(new IOException("系统照片选择器不可用")); }
    }

    boolean activityResult(int request, int result, Intent data) {
        if (request != CAMERA && request != GALLERY) return false;
        if (!picking()) return true;
        if (result != Activity.RESULT_OK) { finishSelection(null); return true; }
        Uri uri = request == CAMERA && captureToken != null ? ReceiptCamera.uri(activity, captureToken)
                : data == null ? null : data.getData();
        if (uri == null) { finishSelection(new IOException("没有收到照片")); return true; }
        String tripId = targetTrip, eventId = targetEvent, capture = captureToken;
        importing = true;
        images.execute(() -> {
            try {
                byte[] photo = ReceiptImage.importPhoto(activity.getContentResolver(), uri);
                if (eventId != null) {
                    ReceiptImage image = ReceiptImage.fromJpeg(photo);
                    activity.runOnUiThread(() -> changeReceipt(tripId, eventId, image, targetButton));
                } else {
                    String token = UUID.randomUUID().toString();
                    File draft = cacheFile("receipt-draft", token);
                    ensureDirectory(draft.getParentFile());
                    Files.write(draft.toPath(), photo);
                    activity.runOnUiThread(() -> {
                        if (activity.isDestroyed()) { remove(draft); return; }
                        clearDraft(tripId, draft(tripId));
                        drafts.putPhoto(tripId, token);
                        if (targetButton != null) paint(targetButton, true);
                        finishSelection(null);
                    });
                }
            } catch (IOException | RuntimeException failure) {
                activity.runOnUiThread(() -> finishSelection(new IOException("无法保存小票，请重新选择")));
            } finally {
                if (capture != null) remove(ReceiptCamera.file(activity, capture));
            }
        });
        return true;
    }

    private void changeReceipt(String tripId, String eventId, ReceiptImage image, ImageButton button) {
        LedgerStore.Trip trip = store.getTrip(tripId);
        if (trip == null) { finishSelection(new IOException("这趟旅行已删除")); return; }
        if (button != null) button.setEnabled(false);
        actions.submit(() -> actions.receipt(trip, eventId, image), () -> {
            if (activity.isDestroyed()) return;
            if (button != null) {
                button.setEnabled(true);
                paint(button, image != null);
            }
            finishSelection(null);
            saved.accept(trip);
            Toast.makeText(activity, image == null ? "小票已移除" : "小票已保存", Toast.LENGTH_SHORT).show();
        }, failure -> {
            if (button != null) button.setEnabled(true);
            finishSelection(failure);
        });
    }

    private void finishSelection(Exception failure) {
        if (captureToken != null) {
            remove(ReceiptCamera.file(activity, captureToken));
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            activity.revokeUriPermission(ReceiptCamera.uri(activity, captureToken), flags);
        }
        targetTrip = targetEvent = captureToken = null;
        targetButton = null;
        importing = false;
        if (failure != null && !activity.isDestroyed()) error.accept(failure);
    }

    void bindThumbnail(String tripId, LedgerReceipts.Entry entry, ImageView view) {
        String key = entry.eventId + "/" + entry.digest;
        view.setTag(key);
        Bitmap cached = thumbnails.get(key);
        if (cached != null) {
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setImageBitmap(cached);
            return;
        }
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setImageResource(R.drawable.ic_ledger_camera);
        if (!entry.available || images.isShutdown()) return;
        images.execute(() -> {
            byte[] bytes = store.receipts.bytes(tripId, entry, true);
            if (bytes == null) return;
            Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (image == null) return;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                thumbnails.put(key, image);
                if (key.equals(view.getTag())) {
                    view.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    view.setImageBitmap(image);
                }
            });
        });
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
