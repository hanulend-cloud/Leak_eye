# 사각블록 내부 터치 재초점 + AF 영역 확대 + 프리뷰 폭 맞춤 + UI 통일 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 평가영역 사각형은 화면 중심에 고정하고 그 내부를 터치했을 때만 재초점을 시도하게 하고, AF 리전을 넓혀 초점 성공률을 높이고, 프리뷰가 화면 폭을 꽉 채우게 하고, UI 전체 스타일(색상/버튼/여백)을 통일한다.

**Architecture:** `EvaluationOverlayView`에 그리기와 동일한 좌표 계산을 재사용하는 `containsPoint()` 판정 메서드를 추가한다. `MainActivity`는 터치 리스너·AF 리전 크기·레이아웃 계산·UI 스타일을 조정한다. 순수 로직 클래스(`SquareGeometry` 등)는 변경 없이 재사용한다.

**Tech Stack:** Java, Android Camera2 API, `android.view.View`/`Canvas`, JUnit 4 (plain, no Robolectric).

**스펙 문서:** `docs/superpowers/specs/2026-09-21-tap-in-square-refocus-ui-cleanup-design.md`

**빌드 환경 (모든 gradle 명령에 필요):**
```
JAVA_HOME="E:/Program install/Android studio/jbr"
```

**태스크는 반드시 이 순서(1→2→3→4→5)대로 실행한다.**

---

### Task 1: `EvaluationOverlayView` — `containsPoint()` 추가

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/EvaluationOverlayView.java`

- [ ] **Step 1: `containsPoint()` 메서드 추가**

찾을 코드:

```java
    private void drawSquare(Canvas canvas, float cx, float cy, int side) {
        float half = side / 2f;
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, boxPaint);
    }
}
```

교체:

```java
    /** (x, y)가 현재 그려진 평가영역 사각형 내부인지 판단한다. onDraw()와 동일한 계산을 재사용한다. */
    public boolean containsPoint(float x, float y) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || centerX < 0) return false;
        int side = SquareGeometry.squareSide(w, h, percent);
        float cx = SquareGeometry.clampCenter(centerX, side / 2f, w);
        float cy = SquareGeometry.clampCenter(centerY, side / 2f, h);
        float half = side / 2f;
        return x >= cx - half && x <= cx + half && y >= cy - half && y <= cy + half;
    }

    private void drawSquare(Canvas canvas, float cx, float cy, int side) {
        float half = side / 2f;
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, boxPaint);
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/EvaluationOverlayView.java
git commit -m "Add EvaluationOverlayView.containsPoint() for tap-inside-square detection"
```

---

### Task 2: `MainActivity` — 사각형 내부 터치로만 재초점 + AF 리전 확대

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java`

**선행 조건:** Task 1이 먼저 적용되어 있어야 한다 (`overlay.containsPoint()` 사용).

- [ ] **Step 1: 터치 리스너를 사각형 내부 판정으로 교체**

찾을 코드:

```java
        preview.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) focusAt(event.getX(), event.getY());
            return true;
        });
```

교체:

```java
        preview.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN && overlay.containsPoint(event.getX(), event.getY())) {
                focusAt(overlay.getCenterX(), overlay.getCenterY());
            }
            return true;
        });
```

- [ ] **Step 2: AF 리전 크기를 5%에서 20%로 확대**

찾을 코드:

```java
        // AF 리전 크기: 크롭 영역 폭의 5%를 정사각형 한 변으로 사용한다.
        int regionSize = Math.max(1, Math.round(cropRegion.width() * 0.05f));
```

교체:

```java
        // AF 리전 크기: 크롭 영역 폭의 20%를 정사각형 한 변으로 사용한다
        // (대비가 낮은 장면에서도 AF가 잡을 대상이 넓어지도록 5%에서 확대함).
        int regionSize = Math.max(1, Math.round(cropRegion.width() * 0.20f));
```

- [ ] **Step 3: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Only refocus when tapping inside the measurement square, and widen the AF region"
```

---

### Task 3: `MainActivity` — 프리뷰 폭 우선 레이아웃

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java`

- [ ] **Step 1: `layoutPreview()`를 폭 우선으로 단순화**

찾을 코드:

```java
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
```

교체:

```java
        // 센서는 가로(previewSize.width > height) 방향으로 읽히고, 화면은 세로이므로 폭:높이 비율을 뒤집는다.
        float ratioWH = (float) previewSize.getHeight() / previewSize.getWidth();
        // 화면 폭을 항상 꽉 채운다 — 높이가 컨테이너보다 커지면 FrameLayout이 위아래를 잘라서 보여준다.
        int targetW = containerW;
        int targetH = Math.round(targetW / ratioWH);
```

- [ ] **Step 2: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Always fill preview width, letting excess height clip"
```

---

### Task 4: `MainActivity` — UI 색상/스타일/여백 통일

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java`

- [ ] **Step 1: 색상 상수 추가**

찾을 코드:

```java
    private static final int CAMERA_PERMISSION = 42;
```

교체:

```java
    private static final int CAMERA_PERMISSION = 42;
    private static final int COLOR_BG = Color.rgb(16, 20, 22);
    private static final int COLOR_ACCENT = Color.rgb(102, 217, 166);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(150, 190, 210);
    private static final int COLOR_BUTTON_NORMAL = Color.rgb(60, 60, 60);
    private static final int COLOR_BUTTON_TEXT = Color.WHITE;
```

- [ ] **Step 2: `buildUi()` 전체 교체**

찾을 코드:

```java
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
```

교체:

```java
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("LEAK EYE  /  CAMERA2 PoC");
        title.setTextColor(COLOR_ACCENT);
        title.setTextSize(16);
        title.setPadding(16, 12, 16, 6);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("카메라 capability 확인 중...");
        status.setTextColor(COLOR_TEXT_SECONDARY);
        status.setTextSize(13);
        status.setPadding(16, 0, 16, 6);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        poseStatus = new TextView(this);
        poseStatus.setText("각도: 측정 중...");
        poseStatus.setTextColor(COLOR_TEXT_SECONDARY);
        poseStatus.setTextSize(13);
        poseStatus.setPadding(16, 0, 16, 0);
        root.addView(poseStatus, new LinearLayout.LayoutParams(-1, -2));

        distanceStatus = new TextView(this);
        distanceStatus.setText("거리(추정): -");
        distanceStatus.setTextColor(COLOR_TEXT_SECONDARY);
        distanceStatus.setTextSize(13);
        distanceStatus.setPadding(16, 0, 16, 6);
        root.addView(distanceStatus, new LinearLayout.LayoutParams(-1, -2));

        focusStatus = new TextView(this);
        focusStatus.setText("포커스: -");
        focusStatus.setTextColor(COLOR_TEXT_SECONDARY);
        focusStatus.setTextSize(13);
        focusStatus.setPadding(16, 0, 16, 6);
        root.addView(focusStatus, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout zoomAndPercentRow = new LinearLayout(this);
        zoomAndPercentRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomButton = new Button(this);
        zoomButton.setText("확대: 3배");
        zoomButton.setTextColor(COLOR_BUTTON_TEXT);
        zoomButton.setBackgroundColor(COLOR_BUTTON_NORMAL);
        zoomButton.setOnClickListener(view -> toggleZoom());
        percent10Button = new Button(this);
        percent10Button.setText("10%");
        percent10Button.setTextColor(COLOR_BUTTON_TEXT);
        percent10Button.setOnClickListener(view -> selectMeasurementPercent(0.10f));
        percent20Button = new Button(this);
        percent20Button.setText("20%");
        percent20Button.setTextColor(COLOR_BUTTON_TEXT);
        percent20Button.setOnClickListener(view -> selectMeasurementPercent(0.20f));
        percent30Button = new Button(this);
        percent30Button.setText("30%");
        percent30Button.setTextColor(COLOR_BUTTON_TEXT);
        percent30Button.setOnClickListener(view -> selectMeasurementPercent(0.30f));
        LinearLayout.LayoutParams quarter = new LinearLayout.LayoutParams(0, -2, 1);
        quarter.setMargins(4, 4, 4, 4);
        zoomAndPercentRow.addView(zoomButton, quarter);
        zoomAndPercentRow.addView(percent10Button, quarter);
        zoomAndPercentRow.addView(percent20Button, quarter);
        zoomAndPercentRow.addView(percent30Button, quarter);
        LinearLayout.LayoutParams zoomRowParams = new LinearLayout.LayoutParams(-1, -2);
        zoomRowParams.setMargins(16, 0, 16, 6);
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
        capture.setTextColor(COLOR_BUTTON_TEXT);
        capture.setBackgroundColor(COLOR_BUTTON_NORMAL);
        capture.setOnClickListener(view -> captureRaw());
        Button sweep = new Button(this);
        sweep.setText("노출 스윕 (16장)");
        sweep.setTextColor(COLOR_BUTTON_TEXT);
        sweep.setBackgroundColor(COLOR_BUTTON_NORMAL);
        sweep.setOnClickListener(view -> captureSweep());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
        half.setMargins(8, 4, 8, 4);
        buttons.addView(capture, half);
        buttons.addView(sweep, half);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(16, 4, 16, 12);
        root.addView(buttons, rowParams);
        setContentView(root);
    }
```

(이 Step은 Task 2에서 이미 바뀐 터치 리스너 본문을 그대로 포함하고 있다 — Task 2가 먼저 적용되어 있어야 이 찾을 코드가 정확히 일치한다.)

- [ ] **Step 3: `refreshPercentButtonHighlight()`도 상수 사용하도록 정리**

찾을 코드:

```java
    private void refreshPercentButtonHighlight() {
        int selectedColor = Color.rgb(102, 217, 166);
        int normalColor = Color.rgb(60, 60, 60);
        percent10Button.setBackgroundColor(measurementPercent == 0.10f ? selectedColor : normalColor);
        percent20Button.setBackgroundColor(measurementPercent == 0.20f ? selectedColor : normalColor);
        percent30Button.setBackgroundColor(measurementPercent == 0.30f ? selectedColor : normalColor);
    }
```

교체:

```java
    private void refreshPercentButtonHighlight() {
        percent10Button.setBackgroundColor(measurementPercent == 0.10f ? COLOR_ACCENT : COLOR_BUTTON_NORMAL);
        percent20Button.setBackgroundColor(measurementPercent == 0.20f ? COLOR_ACCENT : COLOR_BUTTON_NORMAL);
        percent30Button.setBackgroundColor(measurementPercent == 0.30f ? COLOR_ACCENT : COLOR_BUTTON_NORMAL);
    }
```

- [ ] **Step 4: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Unify UI colors, button styling, and spacing across the screen"
```

---

### Task 5: 전체 빌드 + 유닛 테스트 + (연결되면) 실기기 확인

**Files:** 없음 (빌드/검증만)

- [ ] **Step 1: 유닛 테스트 전체 실행**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 기존 5개 순수 로직 클래스 테스트 전부 PASS

- [ ] **Step 2: 디버그 APK 빌드**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: (Fold5가 연결되어 있으면) 수동 확인**

```
MSYS_NO_PATHCONV=1 "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

체크리스트:

- [ ] 사각형이 화면 중심에 고정되어 있고, 탭해도 다른 곳으로 옮겨가지 않는가
- [ ] 사각형 안쪽을 탭하면 "포커스: 확인중" → "포커스: 완료"로 바뀌는가
- [ ] 사각형 바깥을 탭하면 아무 반응이 없는가 (사각형이 움직이지도, 포커스 상태가 바뀌지도 않음)
- [ ] AF 영역을 넓힌 뒤 이전보다 초점이 더 잘 잡히는가 (여전히 안 잡히면 AF 알고리즘/장면 자체의 한계일 수 있음 — 추가 조사 필요)
- [ ] 프리뷰가 화면 폭을 꽉 채우는가 (위아래가 약간 잘려도 정상)
- [ ] 모든 버튼(RAW 촬영/노출 스윕/줌/10·20·30%)이 같은 스타일(진회색+흰 글씨)로 보이고, 선택된 비율 버튼만 강조색인가
- [ ] (이전부터 미해결) 탭 위치와 실제 초점 위치 일치 여부, RAW 크롭 반영 여부 — 여전히 열려있는 항목

체크리스트에서 문제가 발견되면 해당 Task로 돌아가 수정 후 다시 Step 1부터 반복한다.

- [ ] **Step 4: 최종 커밋 (필요 시)**

```bash
git add -A
git commit -m "Verify tap-in-square refocus, wider AF region, and unified UI on Fold5"
```

(Step 3을 기기 미연결로 건너뛰었다면 이 커밋은 생략한다.)
