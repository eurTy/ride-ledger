package com.rideledger;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
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
    private static final String AIM = "将队友手机上的小队二维码放入画面";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Runnable nextFrame = this::requestFrame;
    private FrameLayout previewFrame;
    private SurfaceView preview;
    private TextView hint;
    private Camera camera;
    private Camera.Size frameSize;
    private boolean resumed, surfaceReady;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(true);
        hint = new TextView(this);
        hint.setText("扫码加入");
        hint.setTextSize(17);
        hint.setGravity(Gravity.CENTER);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        hint.setPadding(padding, padding, padding, padding);
        root.addView(hint);

        previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        preview = new SurfaceView(this);
        previewFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(previewFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        preview.getHolder().addCallback(this);
        preview.setOnClickListener(view -> {
            if (camera != null) focusIfManual(camera);
        });
        Button back = new Button(this);
        back.setText("返回");
        back.setOnClickListener(view -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, -2));
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
            if (scale > 0) preview.setLayoutParams(new FrameLayout.LayoutParams(
                    Math.round(width * scale), Math.round(height * scale), Gravity.CENTER));
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
        Camera previous = camera;
        camera = null;
        if (previous == null) return;
        try { previous.stopPreview(); } catch (RuntimeException ignored) { }
        previous.release();
    }
}
