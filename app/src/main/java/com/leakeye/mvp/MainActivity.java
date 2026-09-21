package com.leakeye.mvp;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION = 42;
    private TextureView preview;
    private FrameLayout previewContainer;
    private TextView status;
    private TextView poseStatus;
    private TextView distanceStatus;
    private TextView focusStatus;
    private ExposureControls controls;
    private EvaluationOverlayView overlay;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private volatile float pitchDeg = Float.NaN;
    private volatile float rollDeg = Float.NaN;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader rawReader;
    private ImageReader jpegReader;
    private Size previewSize;
    private Handler cameraHandler;
    private String cameraId;
    private boolean rawSupported;
    private CameraCharacteristics characteristics;
    private Rect cropRegion;
    private Rect activeArraySize;
    private MeteringRectangle afRegion;
    private float zoomFactor = 3f;
    private float measurementPercent = 0.10f;
    private Button zoomButton;
    private Button percent10Button;
    private Button percent20Button;
    private Button percent30Button;
    private final Handler brightnessHandler = new Handler(Looper.getMainLooper());
    private final Runnable brightnessTick = this::sampleBrightness;
    private static final long BRIGHTNESS_INTERVAL_MS = 300L;
    /**
     * 저장되는 JPEG 파일(saveJpeg의 Bitmap 회전)에만 쓰는 값이다. 실기기에서 텍스트가 있는 장면으로
     * 4방향을 직접 비교해 확정했다(시계방향 90도). 화면 미리보기(configureTransform)는 TextureView
     * 행렬 자체의 스케일 방식 때문에 이 값과 무관하게 별도 회전 없이 이미 올바르게 보인다 — 두 파이프라인은
     * 독립적이라 같은 값을 공유하면 안 된다.
     */
    private static final int JPEG_ROTATE_DEGREES = 90;
    private Surface previewSurface;
    private HandlerThread cameraThread;
    private boolean permissionGranted;

    // 아래 필드는 camera-thread에서만 접근한다.
    private final ArrayDeque<ExposureSettings> queue = new ArrayDeque<>();
    private ExposureSettings inFlight;
    private String currentStamp;
    private TotalCaptureResult pendingResult;
    private CaptureMetadata.Frame pendingFrame;
    private Bitmap pendingJpegBitmap;
    private boolean jpegReady;
    private int sweepDone;
    private int sweepFailures;
    private int sweepTotal;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        permissionGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!permissionGranted) requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationSensor == null) poseStatus.setText("각도 센서 없음");
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeOpenCamera();
        if (rotationSensor != null) sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(sensorListener);
        stopBrightnessSampler();
        closeCameraDevice();
        super.onPause();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 20, 22));
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("LEAK EYE  /  CAMERA2 PoC");
        title.setTextColor(Color.rgb(102, 217, 166));
        title.setTextSize(18);
        title.setPadding(24, 24, 24, 12);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("카메라 capability 확인 중...");
        status.setTextColor(Color.LTGRAY);
        status.setPadding(24, 0, 24, 12);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        poseStatus = new TextView(this);
        poseStatus.setText("각도: 측정 중...");
        poseStatus.setTextColor(Color.rgb(150, 190, 210));
        poseStatus.setPadding(24, 0, 24, 0);
        root.addView(poseStatus, new LinearLayout.LayoutParams(-1, -2));

        distanceStatus = new TextView(this);
        distanceStatus.setText("거리(추정): -");
        distanceStatus.setTextColor(Color.rgb(150, 190, 210));
        distanceStatus.setPadding(24, 0, 24, 12);
        root.addView(distanceStatus, new LinearLayout.LayoutParams(-1, -2));

        focusStatus = new TextView(this);
        focusStatus.setText("포커스: -");
        focusStatus.setTextColor(Color.rgb(150, 190, 210));
        focusStatus.setPadding(24, 0, 24, 12);
        root.addView(focusStatus, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout zoomAndPercentRow = new LinearLayout(this);
        zoomAndPercentRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomButton = new Button(this);
        zoomButton.setText("확대: 3배");
        zoomButton.setOnClickListener(view -> toggleZoom());
        percent10Button = new Button(this);
        percent10Button.setText("10%");
        percent10Button.setOnClickListener(view -> selectMeasurementPercent(0.10f));
        percent20Button = new Button(this);
        percent20Button.setText("20%");
        percent20Button.setOnClickListener(view -> selectMeasurementPercent(0.20f));
        percent30Button = new Button(this);
        percent30Button.setText("30%");
        percent30Button.setOnClickListener(view -> selectMeasurementPercent(0.30f));
        LinearLayout.LayoutParams quarter = new LinearLayout.LayoutParams(0, -2, 1);
        quarter.setMargins(4, 4, 4, 4);
        zoomAndPercentRow.addView(zoomButton, quarter);
        zoomAndPercentRow.addView(percent10Button, quarter);
        zoomAndPercentRow.addView(percent20Button, quarter);
        zoomAndPercentRow.addView(percent30Button, quarter);
        LinearLayout.LayoutParams zoomRowParams = new LinearLayout.LayoutParams(-1, -2);
        zoomRowParams.setMargins(16, 0, 16, 8);
        root.addView(zoomAndPercentRow, zoomRowParams);
        refreshPercentButtonHighlight();

        previewContainer = new FrameLayout(this);
        previewContainer.setBackgroundColor(Color.BLACK);
        previewContainer.addOnLayoutChangeListener(
                (v, l, t, r, b, oldL, oldT, oldR, oldB) -> layoutPreview());

        preview = new TextureView(this);
        preview.setSurfaceTextureListener(surfaceListener);
        preview.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN && overlay.containsPoint(event.getX(), event.getY())) {
                focusAt(overlay.getCenterX(), overlay.getCenterY());
            }
            return true;
        });
        previewContainer.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        overlay = new EvaluationOverlayView(this);
        previewContainer.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(previewContainer, new LinearLayout.LayoutParams(-1, 0, 1));

        controls = new ExposureControls(this);
        controls.setOnChanged(this::updatePreview);
        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button capture = new Button(this);
        capture.setText("RAW 촬영");
        capture.setOnClickListener(view -> captureRaw());
        Button sweep = new Button(this);
        sweep.setText("노출 스윕 (16장)");
        sweep.setOnClickListener(view -> captureSweep());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
        half.setMargins(8, 4, 8, 4);
        buttons.addView(capture, half);
        buttons.addView(sweep, half);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(16, 4, 16, 24);
        root.addView(buttons, rowParams);
        setContentView(root);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            permissionGranted = true;
            maybeOpenCamera();
        } else {
            status.setText("카메라 권한이 없어 측정을 시작할 수 없습니다.");
        }
    }

    /** 권한과 프리뷰 표면이 모두 준비되고 카메라가 닫혀 있으면 (다시) 연다. */
    private void maybeOpenCamera() {
        if (!permissionGranted || !preview.isAvailable() || camera != null) return;
        if (cameraId == null) setupCamera(); else openCameraDevice();
    }

    /** 4:3에 가장 가까운 후보를 고른다. maxWidth<=0이면 크기 제한 없음. */
    private static Size chooseSize(Size[] candidates, int maxWidth, boolean require4x3) {
        if (candidates == null) return null;
        Size best = null;
        for (Size s : candidates) {
            boolean is4x3 = (long) s.getWidth() * 3 == (long) s.getHeight() * 4
                    || (long) s.getHeight() * 3 == (long) s.getWidth() * 4;
            if (require4x3 && !is4x3) continue;
            if (maxWidth > 0 && s.getWidth() > maxWidth) continue;
            if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    /** 카메라 id·특성·RAW/JPEG 리더·전용 스레드를 한 번만 준비하고 연다. */
    private void setupCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean hasRaw = false;
                if (capabilities != null) {
                    for (int capability : capabilities) {
                        if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) hasRaw = true;
                    }
                }
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    rawSupported = hasRaw;
                    break;
                }
            }
            if (cameraId == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
            characteristics = manager.getCameraCharacteristics(cameraId);
            activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            int[] crop = ZoomCropRegion.centeredCrop(activeArraySize.left, activeArraySize.top, activeArraySize.right, activeArraySize.bottom, zoomFactor);
            cropRegion = new Rect(crop[0], crop[1], crop[2], crop[3]);
            configureControls();
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
            Size rawSize = rawSizes != null && rawSizes.length > 0 ? rawSizes[0] : new Size(1920, 1080);

            Size[] previewCandidates = map.getOutputSizes(SurfaceTexture.class);
            previewSize = chooseSize(previewCandidates, 1600, true);
            if (previewSize == null) previewSize = chooseSize(previewCandidates, 1600, false);
            if (previewSize == null) previewSize = rawSize;

            Size[] jpegCandidates = map.getOutputSizes(ImageFormat.JPEG);
            Size jpegSize = chooseSize(jpegCandidates, -1, true);
            if (jpegSize == null) jpegSize = chooseSize(jpegCandidates, -1, false);
            if (jpegSize == null) jpegSize = rawSize;

            cameraThread = new HandlerThread("camera-thread");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
            rawReader = ImageReader.newInstance(rawSize.getWidth(), rawSize.getHeight(), ImageFormat.RAW_SENSOR, 2);
            rawReader.setOnImageAvailableListener(reader -> saveRaw(reader.acquireLatestImage()), cameraHandler);
            jpegReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            jpegReader.setOnImageAvailableListener(reader -> saveJpeg(reader.acquireLatestImage()), cameraHandler);
            openCameraDevice();
        } catch (Exception e) {
            cameraId = null;
            status.setText("카메라 초기화 실패: " + e.getClass().getSimpleName());
        }
    }

    private void openCameraDevice() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        status.setText("후면 카메라 " + cameraId + " / RAW " + (rawSupported ? "지원" : "미지원") + " / 준비 중");
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                manager.openCamera(cameraId, stateCallback, cameraHandler);
            }
        } catch (CameraAccessException e) {
            status.setText("카메라 열기 실패: " + e.getClass().getSimpleName());
        }
    }

    /** 화면에서 벗어날 때 카메라 장치를 반납한다. RAW/JPEG 리더와 전용 스레드는 재사용을 위해 유지한다. */
    private void closeCameraDevice() {
        if (cameraHandler != null) {
            cameraHandler.post(() -> {
                queue.clear();
                inFlight = null;
                pendingResult = null;
                pendingFrame = null;
                if (pendingJpegBitmap != null) { pendingJpegBitmap.recycle(); pendingJpegBitmap = null; }
                jpegReady = false;
            });
        }
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        previewSurface = null;
    }

    private void configureControls() {
        Range<Integer> iso = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> exp = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Float minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        controls.configure(
                iso != null ? iso.getLower() : 50, iso != null ? iso.getUpper() : 3200,
                exp != null ? exp.getLower() : 32_544L, exp != null ? exp.getUpper() : 150_000_000L,
                minFocus != null ? minFocus : 10f);
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) { camera = device; startPreview(); }
        @Override public void onDisconnected(CameraDevice device) { device.close(); if (camera == device) camera = null; session = null; }
        @Override public void onError(CameraDevice device, int error) {
            device.close();
            if (camera == device) camera = null;
            session = null;
            runOnUiThread(() -> status.setText("카메라 오류: " + error));
        }
    };

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) { maybeOpenCamera(); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { closeCameraDevice(); return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
    };

    /** UI 스레드에서 등록되므로(onResume) 콜백도 UI 스레드에서 온다. */
    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            float[] angles = Attitude.fromRotationVector(event.values);
            pitchDeg = angles[0];
            rollDeg = angles[1];
            poseStatus.setText(String.format(Locale.US, "각도: 상하 %.1f° / 좌우 %.1f°", pitchDeg, rollDeg));
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    private final CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            Float diopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (diopters != null) {
                Float meters = ExposureScale.distanceMeters(diopters);
                String text = meters != null ? String.format(Locale.US, "거리(추정): %.2f m (AF)", meters) : "거리(추정): ∞";
                runOnUiThread(() -> distanceStatus.setText(text));
            }
            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
            boolean locked = controls.current().manual || AfStateClassifier.isLocked(afState);
            runOnUiThread(() -> {
                focusStatus.setText(locked ? "포커스: 완료" : "포커스: 확인중");
                if (overlay != null) overlay.setGated(!locked);
            });
        }
    };

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            pendingResult = result;
            tryFinishCapture();
        }
        @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) {
            runOnUiThread(() -> status.setText("촬영 실패 (reason " + failure.getReason() + ")"));
            finishCapture(false);
        }
    };

    /** 요청에 노출 설정을 적용한다. */
    private static void applySettings(CaptureRequest.Builder builder, ExposureSettings s) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        if (s.manual) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, s.iso);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNs);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters);
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
    }

    private void startPreview() {
        try {
            preview.getSurfaceTexture().setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(preview.getSurfaceTexture());
            camera.createCaptureSession(Arrays.asList(previewSurface, rawReader.getSurface(), jpegReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    session = configured;
                    runOnUiThread(() -> {
                        status.setText("측정 대기 / RAW " + (rawSupported ? "지원" : "미지원"));
                        layoutPreview();
                        updatePreview();
                        if (overlay.getCenterX() >= 0) focusAt(overlay.getCenterX(), overlay.getCenterY());
                        startBrightnessSampler();
                    });
                }
                @Override public void onConfigureFailed(CameraCaptureSession configured) { runOnUiThread(() -> status.setText("촬영 세션 구성 실패")); }
            }, cameraHandler);
        } catch (CameraAccessException e) { status.setText("미리보기 시작 실패"); }
    }

    /** UI 스레드. 미리보기 컨테이너 안에서 previewSize의 실제 비율(세로 4:3)을 유지하도록 크기를 정하고 변환행렬을 적용한다. */
    private void layoutPreview() {
        if (previewContainer == null || previewSize == null) return;
        int containerW = previewContainer.getWidth();
        int containerH = previewContainer.getHeight();
        if (containerW == 0 || containerH == 0) return;
        // 센서는 가로(previewSize.width > height) 방향으로 읽히고, 화면은 세로이므로 폭:높이 비율을 뒤집는다.
        float ratioWH = (float) previewSize.getHeight() / previewSize.getWidth();
        int targetW, targetH;
        if ((float) containerW / containerH > ratioWH) {
            targetH = containerH;
            targetW = Math.round(targetH * ratioWH);
        } else {
            targetW = containerW;
            targetH = Math.round(targetW / ratioWH);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(targetW, targetH);
        lp.gravity = Gravity.CENTER;
        preview.setLayoutParams(lp);
        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(targetW, targetH);
        overlayLp.gravity = Gravity.CENTER;
        overlay.setLayoutParams(overlayLp);
        configureTransform(targetW, targetH);
    }

    /**
     * 세로 미리보기 박스에 가로(센서) 프레임을 꽉 채운다. 실기기(Fold5 커버 화면)에서 텍스트로 확인한 결과
     * 이 스케일 방식(뷰-버퍼 rect를 서로 바꿔 매핑) 자체가 올바른 방향을 만들어 내서, 별도 회전(postRotate)은
     * 필요 없고 오히려 넣으면 방향이 틀어진다. JPEG 파일 저장(saveJpeg)은 이 메서드와 무관한 별도
     * 파이프라인이라 거기서는 JPEG_ROTATE_DEGREES를 따로 적용한다.
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null) return;
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        float scale = Math.max((float) viewHeight / previewSize.getHeight(), (float) viewWidth / previewSize.getWidth());
        matrix.postScale(scale, scale, centerX, centerY);
        preview.setTransform(matrix);
    }

    /** UI 스레드에서 호출. 현재 컨트롤 값으로 미리보기 요청을 다시 세팅한다. */
    private void updatePreview() {
        if (camera == null || session == null || previewSurface == null) return;
        ExposureSettings settings = controls.current();
        cameraHandler.post(() -> {
            try {
                CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(previewSurface);
                applySettings(builder, settings);
                if (cropRegion != null) builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
                if (afRegion != null && !settings.manual) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{afRegion});
                }
                session.setRepeatingRequest(builder.build(), previewCallback, cameraHandler);
            } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
                runOnUiThread(() -> {
                    status.setText("수동 노출 미지원: " + settings.describe());
                    controls.setManual(false);
                });
            }
        });
    }

    /** 배율을 1배/3배로 토글하고, 새 크롭 영역으로 AF 리전을 다시 맞춘다. */
    private void toggleZoom() {
        zoomFactor = (zoomFactor == 3f) ? 1f : 3f;
        zoomButton.setText(zoomFactor == 3f ? "확대: 3배" : "확대: 1배");
        if (activeArraySize != null) {
            int[] crop = ZoomCropRegion.centeredCrop(activeArraySize.left, activeArraySize.top,
                    activeArraySize.right, activeArraySize.bottom, zoomFactor);
            cropRegion = new Rect(crop[0], crop[1], crop[2], crop[3]);
        }
        if (overlay != null && overlay.getCenterX() >= 0) {
            focusAt(overlay.getCenterX(), overlay.getCenterY());
        } else {
            updatePreview();
        }
    }

    /** 측정영역 비율(10/20/30%)을 바꾸고 버튼 강조 표시를 갱신한다. */
    private void selectMeasurementPercent(float percent) {
        measurementPercent = percent;
        if (overlay != null) overlay.setPercent(percent);
        refreshPercentButtonHighlight();
    }

    private void refreshPercentButtonHighlight() {
        int selectedColor = Color.rgb(102, 217, 166);
        int normalColor = Color.rgb(60, 60, 60);
        percent10Button.setBackgroundColor(measurementPercent == 0.10f ? selectedColor : normalColor);
        percent20Button.setBackgroundColor(measurementPercent == 0.20f ? selectedColor : normalColor);
        percent30Button.setBackgroundColor(measurementPercent == 0.30f ? selectedColor : normalColor);
    }

    /**
     * (viewX, viewY) 위치를 기준으로 AF 리전을 계산해 포커스를 맞춘다. overlay.updateCenter()는
     * 유지하지만, 현재 모든 호출부(탭/줌 토글/세션 시작)가 overlay의 기존 중심 좌표를 그대로 넘기므로
     * 실질적으로는 위치 이동 없이 같은 자리에서 재초점만 시도하는 셈이다.
     */
    private void focusAt(float viewX, float viewY) {
        if (overlay != null) overlay.updateCenter(viewX, viewY);
        if (camera == null || session == null || cropRegion == null || previewSurface == null) return;
        ExposureSettings settings = controls.current();
        if (settings.manual) return;
        int viewW = preview.getWidth();
        int viewH = preview.getHeight();
        if (viewW == 0 || viewH == 0) return;
        // AF 리전 크기: 크롭 영역 폭의 20%를 정사각형 한 변으로 사용한다
        // (대비가 낮은 장면에서도 AF가 잡을 대상이 넓어지도록 5%에서 확대함).
        int regionSize = Math.max(1, Math.round(cropRegion.width() * 0.20f));
        int[] region = TapFocusMapper.mapTapToAfRegion(viewX, viewY, viewW, viewH,
                cropRegion.left, cropRegion.top, cropRegion.width(), cropRegion.height(), regionSize);
        // 사용자가 명시적으로 탭한 지점이므로 최우선순위에 가깝게 높은 가중치를 준다.
        MeteringRectangle newRegion = new MeteringRectangle(region[0], region[1], region[2], region[3],
                MeteringRectangle.METERING_WEIGHT_MAX - 1);
        afRegion = newRegion;
        cameraHandler.post(() -> {
            try {
                CaptureRequest.Builder trigger = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                trigger.addTarget(previewSurface);
                applySettings(trigger, settings);
                trigger.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
                trigger.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{newRegion});
                trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                session.capture(trigger.build(), previewCallback, cameraHandler);
            } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
                // AF 트리거 실패는 무시한다.
            }
        });
        updatePreview();
    }

    /** UI 스레드. 프리뷰가 살아있는 동안 300ms 주기로 평가영역 밝기를 다시 계산한다. */
    private void startBrightnessSampler() {
        brightnessHandler.removeCallbacks(brightnessTick);
        brightnessHandler.postDelayed(brightnessTick, BRIGHTNESS_INTERVAL_MS);
    }

    private void stopBrightnessSampler() {
        brightnessHandler.removeCallbacks(brightnessTick);
    }

    private void sampleBrightness() {
        if (preview != null && preview.isAvailable() && overlay != null) {
            Bitmap bitmap = preview.getBitmap();
            if (bitmap != null) {
                int w = bitmap.getWidth();
                int h = bitmap.getHeight();
                float cx = overlay.getCenterX();
                float cy = overlay.getCenterY();
                int side = SquareGeometry.squareSide(w, h, measurementPercent);
                int luma = sampleSquare(bitmap, cx, cy, side);
                overlay.updateMetrics(luma, side * side);
                bitmap.recycle();
            }
        }
        brightnessHandler.postDelayed(brightnessTick, BRIGHTNESS_INTERVAL_MS);
    }

    private int sampleSquare(Bitmap bitmap, float cx, float cy, int side) {
        if (side <= 0) return 0;
        float half = side / 2f;
        float clampedCx = SquareGeometry.clampCenter(cx, half, bitmap.getWidth());
        float clampedCy = SquareGeometry.clampCenter(cy, half, bitmap.getHeight());
        int left = Math.round(clampedCx - half);
        int top = Math.round(clampedCy - half);
        int[] pixels = new int[side * side];
        bitmap.getPixels(pixels, 0, side, left, top, side, side);
        return LumaMath.averageLuma(pixels);
    }

    private void captureRaw() {
        if (!ready()) return;
        enqueue(Collections.singletonList(controls.current()));
    }

    private void captureSweep() {
        if (!ready()) return;
        String id = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        List<ExposureSettings> plan = ExposureSweep.plan(controls.isoLo(), controls.isoHi(), controls.expLo(), controls.expHi(), controls.focusDiopters());
        List<ExposureSettings> tagged = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) tagged.add(plan.get(i).inSweep(id, i + 1, plan.size()));
        enqueue(tagged);
    }

    private boolean ready() {
        if (camera == null || session == null || !rawSupported) {
            Toast.makeText(this, "RAW 촬영을 지원하는 후면 카메라가 준비되지 않았습니다.", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void enqueue(List<ExposureSettings> items) {
        cameraHandler.post(() -> {
            queue.addAll(items);
            if (!items.isEmpty() && items.get(0).sweepId != null) {
                sweepTotal = items.get(0).sweepTotal;
                sweepDone = 0;
                sweepFailures = 0;
            }
            startNext();
        });
    }

    /** camera-thread. 대기 중인 촬영이 있고 진행 중인 촬영이 없으면 다음 촬영을 시작한다. */
    private void startNext() {
        if (inFlight != null || queue.isEmpty()) return;
        ExposureSettings next = queue.poll();
        try {
            CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(rawReader.getSurface());
            builder.addTarget(jpegReader.getSurface());
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            applySettings(builder, next);
            if (cropRegion != null) builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
            inFlight = next;
            currentStamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            pendingResult = null;
            pendingFrame = null;
            if (pendingJpegBitmap != null) { pendingJpegBitmap.recycle(); pendingJpegBitmap = null; }
            jpegReady = false;
            session.capture(builder.build(), captureCallback, cameraHandler);
            String label = next.sweepId != null
                    ? "스윕 " + next.sweepIndex + "/" + next.sweepTotal + " 촬영 중 (" + next.describe() + ")"
                    : "RAW 촬영 중 (" + next.describe() + ")";
            runOnUiThread(() -> status.setText(label));
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            runOnUiThread(() -> status.setText("촬영 요청 실패: " + e.getClass().getSimpleName()));
            inFlight = next;
            finishCapture(false);
        }
    }

    /** camera-thread. 한 촬영이 끝났을 때(성공/실패) 호출한다. */
    private void finishCapture(boolean ok) {
        if (pendingJpegBitmap != null) { pendingJpegBitmap.recycle(); pendingJpegBitmap = null; }
        jpegReady = false;
        ExposureSettings done = inFlight;
        inFlight = null;
        pendingResult = null;
        pendingFrame = null;
        if (done != null && done.sweepId != null) {
            sweepDone++;
            if (!ok) sweepFailures++;
            if (queue.isEmpty()) {
                String summary = "스윕 완료 " + sweepDone + "/" + sweepTotal + (sweepFailures > 0 ? " (실패 " + sweepFailures + ")" : "");
                runOnUiThread(() -> status.setText(summary));
            }
        }
        startNext();
    }

    private void saveRaw(Image image) {
        if (image == null) return;
        File folder = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "measurements");
        if (!folder.exists()) folder.mkdirs();
        File file = new File(folder, "RAW_" + currentStamp + ".raw");
        try (FileOutputStream output = new FileOutputStream(file)) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            output.write(bytes);
            pendingFrame = CaptureMetadata.frameOf(image, file, bytes.length);
            runOnUiThread(() -> status.setText("저장 완료: " + file.getName() + " (메타데이터 대기)"));
            tryFinishCapture();
        } catch (IOException e) {
            runOnUiThread(() -> status.setText("RAW 저장 실패: " + e.getMessage()));
            finishCapture(false);
        } finally { image.close(); }
    }

    /**
     * camera-thread. JPEG(ISP 처리된 실제 이미지)을 디코드해 올바른 방향으로 회전만 해 둔다. 촬영조건 글자를
     * 입히고 실제로 저장(갤러리 등록)하는 일은, 그 조건 값을 만드는 tryFinishCapture에서 메타데이터와 함께 한다.
     */
    private void saveJpeg(Image image) {
        if (image == null) return;
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap != null && JPEG_ROTATE_DEGREES % 360 != 0) {
                Matrix rotate = new Matrix();
                rotate.postRotate(JPEG_ROTATE_DEGREES);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotate, true);
                bitmap.recycle();
                bitmap = rotated;
            }
            pendingJpegBitmap = bitmap;
        } finally {
            image.close();
            jpegReady = true;
            tryFinishCapture();
        }
    }

    /** MediaStore(갤러리)의 Pictures/LeakEye에 저장한다. PC 연결 없이 휴대폰에서 바로 볼 수 있어야 하므로 공개 미디어 저장소에 쓴다. */
    private void saveJpegToGallery(Bitmap bitmap, String displayName) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LeakEye");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("갤러리 저장 위치를 만들지 못함");
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("갤러리 출력 스트림 없음");
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
        }
    }

    /** 사진 좌하단에 반투명 바 위로 촬영조건(모드·ISO·셔터·거리·각도·스윕) 글자를 입힌다. */
    private void drawOverlay(Bitmap bitmap, CaptureMetadata.Values v) {
        List<String> lines = new ArrayList<>();
        String mode = "manual".equals(v.requestMode) ? "MANUAL" : "AUTO";
        lines.add(mode + (v.sweepId != null ? "  sweep " + v.sweepIndex + "/" + v.sweepTotal : ""));
        StringBuilder line2 = new StringBuilder();
        if (v.iso != null) line2.append("ISO ").append(v.iso);
        if (v.exposureTimeNs != null) {
            if (line2.length() > 0) line2.append("   ");
            line2.append(String.format(Locale.US, "%.2f ms", v.exposureTimeNs / 1e6));
        }
        if (line2.length() > 0) lines.add(line2.toString());
        StringBuilder line3 = new StringBuilder();
        if (v.distanceM != null) line3.append(String.format(Locale.US, "%.2f m", v.distanceM));
        if (v.tiltPitchDeg != null && v.tiltRollDeg != null) {
            if (line3.length() > 0) line3.append("   ");
            line3.append(String.format(Locale.US, "tilt %.1f/%.1f°", v.tiltPitchDeg, v.tiltRollDeg));
        }
        if (line3.length() > 0) lines.add(line3.toString());

        Canvas canvas = new Canvas(bitmap);
        float textSize = bitmap.getWidth() * 0.032f;
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(textSize);
        text.setShadowLayer(4f, 1f, 1f, Color.BLACK);
        float lineHeight = textSize * 1.35f;
        float padding = textSize * 0.6f;
        float barHeight = padding * 2 + lineHeight * lines.size();
        Paint bar = new Paint();
        bar.setColor(Color.argb(150, 0, 0, 0));
        canvas.drawRect(0, bitmap.getHeight() - barHeight, bitmap.getWidth(), bitmap.getHeight(), bar);
        float y = bitmap.getHeight() - barHeight + padding + textSize;
        for (String line : lines) {
            canvas.drawText(line, padding, y, text);
            y += lineHeight;
        }
    }

    /**
     * camera-thread에서만 호출된다. RAW 프레임·CaptureResult·JPEG 비트맵 세 가지가 모두 준비되면 JSON을 쓰고,
     * 같은 값을 입힌 JPEG을 갤러리에 저장한 뒤 촬영을 마친다.
     */
    private void tryFinishCapture() {
        if (pendingResult == null || pendingFrame == null || !jpegReady) return;
        Long resultTs = pendingResult.get(CaptureResult.SENSOR_TIMESTAMP);
        if (resultTs == null || resultTs != pendingFrame.timestampNs) {
            if (resultTs != null && resultTs < pendingFrame.timestampNs) pendingResult = null; else pendingFrame = null;
            return;
        }
        CaptureMetadata.Frame frame = pendingFrame;
        TotalCaptureResult result = pendingResult;
        Bitmap jpeg = pendingJpegBitmap;
        ExposureSettings settings = inFlight != null ? inFlight : ExposureSettings.auto();
        pendingFrame = null;
        pendingResult = null;
        pendingJpegBitmap = null;
        jpegReady = false;
        String base = frame.rawFile.getName().replaceAll("\\.raw$", "");
        File json = new File(frame.rawFile.getParentFile(), base + ".json");
        boolean ok;
        try {
            CaptureMetadata.Values values = CaptureMetadata.collect(characteristics, result, frame, settings,
                    cameraId, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, pitchDeg, rollDeg);
            String text = CaptureMetadata.toJson(values).toString(2);
            try (FileOutputStream output = new FileOutputStream(json)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            String label = "저장 완료: " + frame.rawFile.getName();
            if (jpeg != null) {
                drawOverlay(jpeg, values);
                saveJpegToGallery(jpeg, base + ".jpg");
                label += " + jpg/json";
            } else {
                label += " + json (jpg 실패)";
            }
            String finalLabel = label;
            runOnUiThread(() -> status.setText(finalLabel));
            ok = true;
        } catch (IOException | JSONException e) {
            runOnUiThread(() -> status.setText("저장 실패: " + e.getMessage()));
            ok = false;
        } finally {
            if (jpeg != null) jpeg.recycle();
        }
        finishCapture(ok);
    }

    @Override protected void onDestroy() {
        closeCameraDevice();
        if (rawReader != null) rawReader.close();
        if (jpegReader != null) jpegReader.close();
        if (cameraThread != null) cameraThread.quitSafely();
        super.onDestroy();
    }
}
