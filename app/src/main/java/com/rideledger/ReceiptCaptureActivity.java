package com.rideledger;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Captures a JPEG in the app's private cache and returns to the receipt editor. */
@SuppressWarnings("deprecation")
public final class ReceiptCaptureActivity extends Activity implements SurfaceHolder.Callback {
    static final String EXTRA_CAPTURE = "com.rideledger.RECEIPT_CAPTURE";
    private static final int MAX_CAPTURE_SIDE = 2800;
    private FrameLayout viewport;
    private SurfaceView preview;
    private TextView status;
    private Button shutter;
    private File output;
    private Camera camera;
    private boolean resumed, surfaceReady, takingPhoto, manualFocus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String token = getIntent().getStringExtra(EXTRA_CAPTURE);
        if (!PeerIdentity.valid(token) || !ReceiptCamera.file(this, token).isFile()) {
            Toast.makeText(this, "无法打开这笔小票的拍照页面", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        output = ReceiptCamera.file(this, token);
        LedgerStyle style = new LedgerStyle(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(style.page);
        root.addView(style.topBar("拍摄小票", this::finish));

        viewport = new FrameLayout(this);
        viewport.setBackgroundColor(Color.BLACK);
        preview = new SurfaceView(this);
        viewport.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        preview.getHolder().addCallback(this);
        preview.setOnClickListener(view -> focus());

        status = style.text("", LedgerStyle.LABEL, style.muted);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(android.view.View.GONE);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        shutter = style.button("拍照", LedgerStyle.Action.PRIMARY, this::takePhoto);
        shutter.setEnabled(false);
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-1, -2);
        int inset = style.dp(LedgerStyle.PAGE_INSET);
        button.setMargins(inset, inset, inset, inset);
        root.addView(shutter, button);
        FormInput.fitActivityWindow(getWindow(), root);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        startPreview();
    }

    @Override protected void onPause() {
        resumed = false;
        releaseCamera();
        super.onPause();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        startPreview();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        releaseCamera();
    }

    private void startPreview() {
        if (!resumed || !surfaceReady || isFinishing() || camera != null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showFailure("请授权相机后重新拍摄");
            return;
        }
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int id = cameraId(info);
            if (id < 0) throw new IOException("No camera");
            camera = Camera.open(id);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = captureSize(parameters.getSupportedPictureSizes());
            if (size != null) parameters.setPictureSize(size.width, size.height);
            int rotation = displayDegrees();
            boolean front = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
            int jpegRotation = (info.orientation + (front ? rotation : -rotation) + 360) % 360;
            parameters.setRotation(jpegRotation);
            List<String> focusModes = parameters.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            manualFocus = Camera.Parameters.FOCUS_MODE_AUTO.equals(parameters.getFocusMode());
            camera.setParameters(parameters);
            int orientation = front ? (360 - jpegRotation) % 360 : jpegRotation;
            camera.setDisplayOrientation(orientation);
            fitPreview(orientation, camera.getParameters().getPreviewSize());
            camera.setPreviewDisplay(preview.getHolder());
            camera.setErrorCallback((error, source) -> {
                if (source == camera) showFailure("相机暂时不可用，请重试");
            });
            camera.startPreview();
            shutter.setText("拍照");
            shutter.setEnabled(true);
            status.setVisibility(android.view.View.GONE);
            focus();
        } catch (IOException | RuntimeException unavailable) {
            showFailure("无法打开相机，请重试");
        }
    }

    private void takePhoto() {
        if (takingPhoto) return;
        if (camera == null) {
            startPreview();
            return;
        }
        takingPhoto = true;
        shutter.setText("正在拍照");
        shutter.setEnabled(false);
        try {
            camera.takePicture(null, null, this::saveCapture);
        } catch (RuntimeException failure) {
            showFailure("拍照失败，请重试");
        }
    }

    private void saveCapture(byte[] jpeg, Camera source) {
        if (source != camera || !resumed || isFinishing()) return;
        try {
            if (jpeg == null || jpeg.length == 0) throw new IOException("Empty capture");
            Files.write(output.toPath(), jpeg, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            setResult(RESULT_OK);
            finish();
        } catch (IOException | RuntimeException failure) {
            showFailure("小票照片未能保存，请重试");
        }
    }

    private void focus() {
        if (camera == null || !manualFocus || takingPhoto) return;
        try { camera.autoFocus(null); } catch (RuntimeException ignored) { }
    }

    private void releaseCamera() {
        Camera previous = camera;
        camera = null;
        takingPhoto = false;
        if (shutter != null) shutter.setEnabled(false);
        if (previous == null) return;
        try { previous.stopPreview(); } catch (RuntimeException ignored) { }
        previous.release();
    }

    private void showFailure(String message) {
        releaseCamera();
        status.setText(message);
        status.setVisibility(android.view.View.VISIBLE);
        shutter.setText("重试");
        shutter.setEnabled(true);
    }

    private static int cameraId(Camera.CameraInfo info) {
        int count = Camera.getNumberOfCameras();
        if (count == 0) return -1;
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        Camera.getCameraInfo(0, info);
        return 0;
    }

    private static Camera.Size captureSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size smallest = sizes.get(0), best = null;
        for (Camera.Size size : sizes) {
            int pixels = size.width * size.height;
            if (pixels < smallest.width * smallest.height) smallest = size;
            if (Math.max(size.width, size.height) <= MAX_CAPTURE_SIDE &&
                    (best == null || pixels > best.width * best.height)) best = size;
        }
        return best != null ? best : smallest;
    }

    private int displayDegrees() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private void fitPreview(int orientation, Camera.Size size) {
        int width = orientation % 180 == 0 ? size.width : size.height;
        int height = orientation % 180 == 0 ? size.height : size.width;
        viewport.post(() -> {
            float scale = Math.min((float) viewport.getWidth() / width, (float) viewport.getHeight() / height);
            if (scale > 0) preview.setLayoutParams(new FrameLayout.LayoutParams(
                    Math.round(width * scale), Math.round(height * scale), Gravity.CENTER));
        });
    }
}
