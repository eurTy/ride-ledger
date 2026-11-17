package com.rideledger;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Path;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A camera-only screen. It returns the QR contents to the normal invitation flow. */
@SuppressWarnings("deprecation")
public final class InviteScannerActivity extends Activity implements SurfaceHolder.Callback {
    static final String EXTRA_INVITE = "com.rideledger.QR_INVITE";
    private static final String AIM = "将队友的小队二维码放入取景框";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Runnable nextFrame = this::requestFrame;
    private FrameLayout previewFrame;
    private SurfaceView preview;
    private View finder;
    private TextView hint;
    private Camera camera;
    private Camera.Size frameSize;
    private boolean resumed, surfaceReady;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LedgerStyle style = new LedgerStyle(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(style.page);
        root.addView(style.topBar("扫码加入", this::finish));

        previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        preview = new SurfaceView(this);
        previewFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        finder = new Finder(this, style);
        finder.setVisibility(View.INVISIBLE);
        previewFrame.addView(finder, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        LinearLayout.LayoutParams area = new LinearLayout.LayoutParams(-1, 0, 1);
        int inset = style.dp(LedgerStyle.PAGE_INSET);
        area.setMargins(inset, style.dp(LedgerStyle.CONTROL_GAP), inset, 0);
        root.addView(previewFrame, area);
        preview.getHolder().addCallback(this);
        preview.setOnClickListener(view -> {
            if (camera != null) focusIfManual(camera);
        });
        hint = style.text(AIM, LedgerStyle.LABEL, style.muted);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(inset, style.dp(LedgerStyle.SECTION_GAP), inset, style.dp(LedgerStyle.SECTION_GAP));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        FormInput.fitActivityWindow(getWindow(), root);
        setContentView(root);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            hint.setText("请返回加入页授权相机，或从相册选择二维码。");
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        openPreview();
    }

    @Override protected void onPause() {
        resumed = false;
        closePreview();
        super.onPause();
    }

    @Override protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        openPreview();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        closePreview();
    }

    private void openPreview() {
        if (!resumed || !surfaceReady || camera != null ||
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int id = preferredCamera(info);
            if (id < 0) {
                hint.setText("此设备没有可用相机，可以返回粘贴邀请码。");
                return;
            }
            camera = Camera.open(id);
            Camera.Parameters parameters = camera.getParameters();
            parameters.setPreviewFormat(ImageFormat.NV21);
            Camera.Size size = suitableSize(parameters.getSupportedPreviewSizes());
            if (size != null) parameters.setPreviewSize(size.width, size.height);
            List<String> focus = parameters.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            else if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_AUTO))
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            camera.setParameters(parameters);
            frameSize = camera.getParameters().getPreviewSize();
            int orientation = displayOrientation(info);
            camera.setDisplayOrientation(orientation);
            fitPreview(orientation, frameSize);
            camera.setPreviewDisplay(preview.getHolder());
            camera.setErrorCallback((error, source) -> { if (source == camera) showCameraFailure(); });
            camera.startPreview();
            finder.setVisibility(View.VISIBLE);
            hint.setText(AIM);
            focusIfManual(camera);
            requestFrame();
        } catch (Exception error) { showCameraFailure(); }
    }

    private int preferredCamera(Camera.CameraInfo info) {
        int count = Camera.getNumberOfCameras();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        if (count == 0) return -1;
        Camera.getCameraInfo(0, info);
        return 0;
    }

    private Camera.Size suitableSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = sizes.get(0);
        for (Camera.Size size : sizes)
            if (Math.abs(size.width * size.height - 921600) < Math.abs(best.width * best.height - 921600)) best = size;
        return best;
    }

    private int displayOrientation(Camera.CameraInfo info) {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 :
                rotation == Surface.ROTATION_270 ? 270 : 0;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
            return (360 - (info.orientation + degrees) % 360) % 360;
        return (info.orientation - degrees + 360) % 360;
    }

    private void fitPreview(int orientation, Camera.Size size) {
        int width = orientation % 180 == 0 ? size.width : size.height;
        int height = orientation % 180 == 0 ? size.height : size.width;
        previewFrame.post(() -> {
            float scale = Math.min((float) previewFrame.getWidth() / width, (float) previewFrame.getHeight() / height);
            if (scale <= 0) return;
            int fittedWidth = Math.round(width * scale), fittedHeight = Math.round(height * scale);
            preview.setLayoutParams(new FrameLayout.LayoutParams(fittedWidth, fittedHeight, Gravity.CENTER));
            finder.setLayoutParams(new FrameLayout.LayoutParams(fittedWidth, fittedHeight, Gravity.CENTER));
        });
    }

    private void focusIfManual(Camera source) {
        try {
            if (Camera.Parameters.FOCUS_MODE_AUTO.equals(source.getParameters().getFocusMode())) source.autoFocus(null);
        } catch (RuntimeException ignored) { }
    }

    private void requestFrame() {
        if (camera == null) return;
        try { camera.setOneShotPreviewCallback(this::decodeFrame); }
        catch (RuntimeException error) { showCameraFailure(); }
    }

    private void decodeFrame(byte[] frame, Camera source) {
        if (source != camera) return;
        Camera.Size size = frameSize;
        decoder.execute(() -> {
            String invite = InviteQr.readFrame(frame, size.width, size.height);
            main.post(() -> {
                if (source != camera || isFinishing()) return;
                if (InviteQr.isTeamCode(invite)) {
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_INVITE, invite));
                    finish();
                    return;
                }
                if (invite != null) hint.setText("请对准旅行账本的小队邀请二维码。");
                main.postDelayed(nextFrame, 90);
            });
        });
    }

    private void showCameraFailure() {
        closePreview();
        hint.setText("相机暂时无法打开，可以返回粘贴邀请码。");
    }

    private void closePreview() {
        main.removeCallbacks(nextFrame);
        if (finder != null) finder.setVisibility(View.INVISIBLE);
        Camera previous = camera;
        camera = null;
        if (previous == null) return;
        try { previous.stopPreview(); } catch (RuntimeException ignored) { }
        previous.release();
    }

    /** Open corners leave the preview and tap-to-focus area visible. */
    private static final class Finder extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path corners = new Path();
        private final LedgerStyle style;

        Finder(Context context, LedgerStyle style) {
            super(context);
            this.style = style;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            float side = Math.min(width, height) * .69f;
            float left = (width - side) / 2f, top = (height - side) / 2f;
            float right = left + side, bottom = top + side;
            float length = Math.min(side / 3, style.dp(24));
            corners.reset();
            corner(left, top, length, length);
            corner(right, top, -length, length);
            corner(right, bottom, -length, -length);
            corner(left, bottom, length, -length);
        }

        private void corner(float x, float y, float horizontal, float vertical) {
            corners.moveTo(x, y + vertical);
            corners.lineTo(x, y);
            corners.lineTo(x + horizontal, y);
        }

        @Override protected void onDraw(Canvas canvas) {
            line.setColor(0x90000000);
            line.setStrokeWidth(style.dp(5));
            canvas.drawPath(corners, line);
            line.setColor(style.selected);
            line.setStrokeWidth(style.dp(2));
            canvas.drawPath(corners, line);
        }
    }
}
