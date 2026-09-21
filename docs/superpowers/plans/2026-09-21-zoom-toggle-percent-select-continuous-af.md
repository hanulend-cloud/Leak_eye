# 줌 토글 + 측정영역 비율 선택(단일 사각형) + 자동 연속 AF Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배율(1배/3배)과 측정영역 비율(10/20/30%)을 버튼으로 선택 가능하게 하고, 화면에는 선택된 사각형 1개만 표시하며, 앱 시작 직후(탭 전)와 줌 전환 시에도 사각형 위치에 AF 리전을 걸어두어 연속 오토포커스가 항상 그 위치를 기준으로 동작하게 한다.

**Architecture:** 기존 5개 순수 로직 클래스(`ZoomCropRegion`, `TapFocusMapper`, `SquareGeometry`, `LumaMath`, `AfStateClassifier`)는 변경 없이 재사용한다. `EvaluationOverlayView`는 3개 동시 표시 → 1개로 단순화한다. `MainActivity`는 줌/비율 상태 필드와 버튼을 추가하고, 기존 `focusAt()`을 탭 외에도 카메라 세션 시작 시·줌 전환 시 재사용해 AF 리전을 항상 사각형 위치에 고정한다.

**Tech Stack:** Java, Android Camera2 API, `android.view.View`/`Canvas` 커스텀 드로잉, JUnit 4 (plain, no Robolectric).

**스펙 문서:** `docs/superpowers/specs/2026-09-21-zoom-toggle-percent-select-continuous-af-design.md`
**선행 스펙(참고):** `docs/superpowers/specs/2026-09-21-zoom-focus-eval-overlay-design.md`

**빌드 환경 (모든 gradle 명령에 필요):**
```
JAVA_HOME="E:/Program install/Android studio/jbr"
```

**태스크는 반드시 이 순서(1→2→3→4→5)대로 실행한다.** 뒤 태스크가 앞 태스크에서 추가된 필드/메서드를 참조하기 때문에, 순서를 바꾸면 중간에 컴파일이 깨진다.

---

### Task 1: `MainActivity` — 줌/비율 상태 필드 + `setupCamera()` 배선

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java`

- [ ] **Step 1: 필드 추가**

찾을 코드:

```java
    private Rect cropRegion;
    private MeteringRectangle afRegion;
    private final Handler brightnessHandler = new Handler(Looper.getMainLooper());
    private final Runnable brightnessTick = this::sampleBrightness;
    private static final long BRIGHTNESS_INTERVAL_MS = 300L;
```

교체:

```java
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
```

- [ ] **Step 2: `setupCamera()`에서 `activeArraySize` 저장 + `zoomFactor` 사용**

찾을 코드:

```java
            characteristics = manager.getCameraCharacteristics(cameraId);
            Rect activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            int[] crop = ZoomCropRegion.centeredCrop(activeArray.left, activeArray.top, activeArray.right, activeArray.bottom, 3f);
            cropRegion = new Rect(crop[0], crop[1], crop[2], crop[3]);
            configureControls();
```

교체:

```java
            characteristics = manager.getCameraCharacteristics(cameraId);
            activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            int[] crop = ZoomCropRegion.centeredCrop(activeArraySize.left, activeArraySize.top, activeArraySize.right, activeArraySize.bottom, zoomFactor);
            cropRegion = new Rect(crop[0], crop[1], crop[2], crop[3]);
            configureControls();
```

- [ ] **Step 3: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL (`zoomButton` 등 새 필드는 아직 어디서도 안 쓰이므로 unused-field 경고는 나되 컴파일 자체는 성공. `Button`은 이미 이 파일에서 import되어 있음 — 별도 import 불필요.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Add zoom/measurement-percent state fields and wire zoomFactor into crop calculation"
```

---

### Task 2: `EvaluationOverlayView` 단일 사각형으로 축소 + 샘플러 단순화

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/EvaluationOverlayView.java` (전체 교체)
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java` (`sampleBrightness()` 메서드만)

**선행 조건:** Task 1이 먼저 적용되어 있어야 한다 (Step 2가 Task 1에서 추가한 `measurementPercent` 필드를 참조함).

이 태스크는 오버레이가 그리는 대상(사각형 1개)과 그걸 채우는 샘플러를 같은 태스크에서 함께 바꿔서, 태스크가 끝난 시점에 항상 컴파일이 되도록 한다 (둘 다 `updateMetrics()` 시그니처를 공유하기 때문).

- [ ] **Step 1: `EvaluationOverlayView.java` 전체 교체**

전체 파일 내용을 다음으로 교체한다:

```java
package com.leakeye.mvp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Locale;

/**
 * 라이브 프리뷰 위에 선택된 비율(10/20/30%)의 평가영역 정사각형 1개(테두리만)와 그 바깥쪽 십자선,
 * 평균 밝기(0-255)·면적(px)을 그린다. preview(TextureView)와 정확히 같은 크기로 겹쳐서 배치된다는
 * 전제 하에 자신의 getWidth()/getHeight()를 좌표계로 쓴다.
 */
public class EvaluationOverlayView extends View {
    private float centerX = -1f;
    private float centerY = -1f;
    private float percent = 0.10f;
    private int luma;
    private int area;
    private boolean gated = true;

    private final Paint boxPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final Paint textPaint = new Paint();

    public EvaluationOverlayView(Context context) {
        super(context);
        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);
        linePaint.setColor(Color.RED);
        linePaint.setStrokeWidth(2f);
        textPaint.setColor(Color.RED);
        textPaint.setTextSize(28f);
        textPaint.setAntiAlias(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (centerX < 0) {
            centerX = w / 2f;
            centerY = h / 2f;
        } else if (oldw > 0 && oldh > 0 && (oldw != w || oldh != h)) {
            // 폴드/언폴드 등으로 뷰 크기가 바뀌면 이전 탭 위치를 비율 그대로 새 크기에 맞춰 옮긴다.
            centerX = centerX / oldw * w;
            centerY = centerY / oldh * h;
        }
    }

    /** 터치 지점(뷰 좌표)으로 평가영역 중심을 옮긴다. */
    public void updateCenter(float x, float y) {
        centerX = x;
        centerY = y;
        invalidate();
    }

    public float getCenterX() { return centerX; }
    public float getCenterY() { return centerY; }

    /** 표시할 평가영역 비율(예: 0.10f = 10%)을 바꾼다. */
    public void setPercent(float percent) {
        this.percent = percent;
        invalidate();
    }

    /** true면 아직 포커스가 확정되지 않아 수치를 신뢰할 수 없음을 표시한다. */
    public void setGated(boolean value) {
        if (gated != value) {
            gated = value;
            invalidate();
        }
    }

    public void updateMetrics(int luma, int area) {
        this.luma = luma;
        this.area = area;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || centerX < 0) return;

        int side = SquareGeometry.squareSide(w, h, percent);
        float cx = SquareGeometry.clampCenter(centerX, side / 2f, w);
        float cy = SquareGeometry.clampCenter(centerY, side / 2f, h);

        drawSquare(canvas, cx, cy, side);

        float half = side / 2f;
        float left = cx - half;
        float top = cy - half;
        float right = cx + half;
        float bottom = cy + half;
        canvas.drawLine(cx, 0, cx, top, linePaint);
        canvas.drawLine(cx, bottom, cx, h, linePaint);
        canvas.drawLine(0, cy, left, cy, linePaint);
        canvas.drawLine(right, cy, w, cy, linePaint);

        String suffix = gated ? " (측정대기)" : "";
        int percentInt = Math.round(percent * 100);
        canvas.drawText(String.format(Locale.US, "%d%%: 밝기 %d / 면적 %dpx%s", percentInt, luma, area, suffix),
                cx + half + 8, cy - half, textPaint);
    }

    private void drawSquare(Canvas canvas, float cx, float cy, int side) {
        float half = side / 2f;
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, boxPaint);
    }
}
```

- [ ] **Step 2: `MainActivity.sampleBrightness()` 단순화**

찾을 코드:

```java
    private void sampleBrightness() {
        if (preview != null && preview.isAvailable() && overlay != null) {
            Bitmap bitmap = preview.getBitmap();
            if (bitmap != null) {
                int w = bitmap.getWidth();
                int h = bitmap.getHeight();
                float cx = overlay.getCenterX();
                float cy = overlay.getCenterY();
                int side10 = SquareGeometry.squareSide(w, h, 0.10f);
                int side20 = SquareGeometry.squareSide(w, h, 0.20f);
                int side30 = SquareGeometry.squareSide(w, h, 0.30f);
                int luma10 = sampleSquare(bitmap, cx, cy, side10);
                int luma20 = sampleSquare(bitmap, cx, cy, side20);
                int luma30 = sampleSquare(bitmap, cx, cy, side30);
                overlay.updateMetrics(luma10, side10 * side10, luma20, side20 * side20, luma30, side30 * side30);
                bitmap.recycle();
            }
        }
        brightnessHandler.postDelayed(brightnessTick, BRIGHTNESS_INTERVAL_MS);
    }
```

교체:

```java
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
```

- [ ] **Step 3: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/EvaluationOverlayView.java app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Simplify evaluation overlay to a single measurement square"
```

---

### Task 3: `MainActivity` — 줌 토글 + 비율 선택 버튼 UI

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java`

**선행 조건:** Task 1, Task 2가 먼저 적용되어 있어야 한다 (`measurementPercent`, `zoomButton` 등 필드와 `overlay.setPercent()` 메서드를 사용함).

- [ ] **Step 1: `buildUi()`에 줌/비율 버튼 줄 추가**

찾을 코드:

```java
        focusStatus = new TextView(this);
        focusStatus.setText("포커스: -");
        focusStatus.setTextColor(Color.rgb(150, 190, 210));
        focusStatus.setPadding(24, 0, 24, 12);
        root.addView(focusStatus, new LinearLayout.LayoutParams(-1, -2));

        previewContainer = new FrameLayout(this);
```

교체:

```java
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
```

- [ ] **Step 2: 줌/비율 핸들러 메서드 추가**

`updatePreview()` 메서드 바로 다음, `focusAt()` 메서드 앞에 추가:

찾을 코드:

```java
    /** 화면 터치 지점으로 포커스를 맞추고 평가영역 중심을 갱신한다. */
    private void focusAt(float viewX, float viewY) {
```

교체 (새 메서드들을 `focusAt()` 앞에 삽입 — `focusAt` 선언 줄 자체는 그대로 둔다):

```java
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

    /** 화면 터치 지점으로 포커스를 맞추고 평가영역 중심을 갱신한다. */
    private void focusAt(float viewX, float viewY) {
```

- [ ] **Step 3: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Add zoom toggle and measurement-percent selection buttons"
```

---

### Task 4: `MainActivity` — 카메라 세션 시작 시 기본 AF 리전 적용 (탭 없이도 연속 AF)

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java`

**선행 조건:** Task 1~3이 먼저 적용되어 있어야 한다.

- [ ] **Step 1: `onConfigured`에서 초기 AF 리전 적용**

찾을 코드:

```java
                @Override public void onConfigured(CameraCaptureSession configured) {
                    session = configured;
                    runOnUiThread(() -> {
                        status.setText("측정 대기 / RAW " + (rawSupported ? "지원" : "미지원"));
                        layoutPreview();
                        updatePreview();
                        startBrightnessSampler();
                    });
                }
```

교체:

```java
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
```

(`layoutPreview()`가 이 시점에 이미 `previewContainer`의 실제 크기를 알고 있어서 `overlay`의 `onSizeChanged`가 호출되어 중심이 설정된 경우 `getCenterX() >= 0`이 성립한다. 아직 레이아웃 패스가 안 끝난 극단적 타이밍이면 이 조건이 거짓이 되어 건너뛰고, 이후 실제 레이아웃이 잡히면서 사용자가 탭할 때 정상 적용된다 — 스펙의 "에러 처리" 참고.)

- [ ] **Step 2: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Apply default AF region at camera-session start so continuous AF targets the square before any tap"
```

---

### Task 5: 전체 빌드 + 유닛 테스트 + (연결되면) 실기기 확인

**Files:** 없음 (빌드/검증만)

- [ ] **Step 1: 유닛 테스트 전체 실행**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 기존 5개 순수 로직 클래스 테스트 전부 PASS (이번 변경으로 추가/수정된 유닛 테스트는 없음 — 전부 `MainActivity`/`EvaluationOverlayView` 배선 변경이라 기존 테스트 스위트가 그대로 회귀 검증 역할을 함)

- [ ] **Step 2: 디버그 APK 빌드**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: (Fold5가 연결되어 있으면) 수동 확인**

```
MSYS_NO_PATHCONV=1 "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

체크리스트:

- [ ] "확대: 3배" 버튼을 누르면 "확대: 1배"로 바뀌고 실제 화각이 넓어지는가 (다시 누르면 원복)
- [ ] 10%/20%/30% 버튼 중 하나만 강조 표시되고, 누르면 화면의 사각형이 그 비율로 즉시 바뀌는가 (사각형은 항상 1개만 보이는가)
- [ ] 앱 실행 직후(탭 전)에도 잠시 후 "포커스: 완료"로 바뀌는가 (탭 없이도 초기 AF 리전이 걸리는지 확인)
- [ ] 줌 버튼을 누른 직후에도 같은 화면 위치 기준으로 다시 초점을 맞추는지 (포커스 상태가 일시적으로 "확인중"으로 바뀌었다가 다시 "완료"로 돌아오는가)
- [ ] (이전 계획의 미해결 리스크) 탭 위치와 실제 초점 위치가 일치하는지, RAW 파일에도 줌이 반영되는지 — 아직 확인 안 됐다면 계속 열려있는 항목으로 남겨둠

체크리스트에서 문제가 발견되면 해당 Task로 돌아가 수정 후 다시 Step 1부터 반복한다.

- [ ] **Step 4: 최종 커밋 (필요 시)**

```bash
git add -A
git commit -m "Verify zoom toggle, percent selection, and default AF on Fold5"
```

(Step 3을 기기 미연결로 건너뛰었다면 이 커밋은 생략한다.)
