# ROI 평균/피크 밝기 및 면적(초과영역) 계산 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 측정영역(10/20/30% 사각형) 내부 픽셀만으로 평균/피크 밝기와 "평균 초과 픽셀 면적"을 계산해, 라이브 화면과 저장 이미지 양쪽에 표시한다.

**Architecture:** 순수 함수(`LumaMath.compute`, `SquareGeometry.mapToBitmap`)로 계산 로직을 분리해 유닛 테스트하고, `MainActivity`/`EvaluationOverlayView`는 그 결과를 표시/전달만 한다. 라이브 측정은 기존 300ms 샘플러가 프리뷰 비트맵으로, 저장 시점은 촬영 요청 시각에 스냅샷한 ROI를 실제 JPEG 비트맵 해상도로 비율 매핑해 재계산한다.

**Tech Stack:** Java (Android, camera2 API), JUnit4 (순수 함수 유닛 테스트), Gradle.

참고 설계 문서: `docs/superpowers/specs/2026-09-23-roi-brightness-area-metrics-design.md`

---

### Task 1: `LumaMath.compute()` — 평균/피크/면적 통계 (히스토그램 기반)

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/LumaMath.java`
- Test: `app/src/test/java/com/leakeye/mvp/LumaMathTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`LumaMathTest.java` 끝(마지막 `}` 앞)에 추가:

```java
    @Test
    public void computeUniformPixelsHaveNoBrightArea() {
        int[] pixels = new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
        LumaMath.Stats stats = LumaMath.compute(pixels);
        assertEquals(255, stats.average);
        assertEquals(255, stats.peak);
        assertEquals(0, stats.brightAreaPx);
    }

    @Test
    public void computeHalfBrightHalfDarkSplitsArea() {
        int[] pixels = new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000, 0xFF000000};
        LumaMath.Stats stats = LumaMath.compute(pixels);
        assertEquals(127, stats.average);
        assertEquals(255, stats.peak);
        assertEquals(2, stats.brightAreaPx);
    }

    @Test
    public void computePeakMatchesSingleTopPixel() {
        int[] pixels = new int[100];
        for (int i = 0; i < 99; i++) pixels[i] = 0xFF323232; // luma 50
        pixels[99] = 0xFFFFFFFF; // luma 255, 상위 1%(=1개)에 정확히 해당
        LumaMath.Stats stats = LumaMath.compute(pixels);
        assertEquals(255, stats.peak);
        assertEquals(1, stats.brightAreaPx);
    }

    @Test
    public void computeEmptyArrayReturnsZeros() {
        LumaMath.Stats stats = LumaMath.compute(new int[]{});
        assertEquals(0, stats.average);
        assertEquals(0, stats.peak);
        assertEquals(0, stats.brightAreaPx);
    }
```

파일 상단 import는 이미 `assertEquals`만 쓰므로 추가 import 불필요.

- [ ] **Step 2: 테스트 실패 확인**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.leakeye.mvp.LumaMathTest"`
Expected: FAIL (컴파일 에러 — `LumaMath.compute`, `LumaMath.Stats`가 아직 없음)

- [ ] **Step 3: `LumaMath.compute()` 구현**

`LumaMath.java` 전체를 다음으로 교체:

```java
package com.leakeye.mvp;

/** ARGB 픽셀 배열의 평균 루마(0-255)를 계산한다. 순수 함수, Android 의존 없음. */
final class LumaMath {
    private LumaMath() {}

    static int averageLuma(int[] pixels) {
        if (pixels == null || pixels.length == 0) return 0;
        long sum = 0;
        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            sum += Math.round(0.299 * r + 0.587 * g + 0.114 * b);
        }
        return (int) (sum / pixels.length);
    }

    /** 평균/상위 1% 평균(peak)/평균 초과 픽셀 수(brightAreaPx)를 한 번의 순회로 계산한다. */
    static final class Stats {
        final int average;
        final int peak;
        final int brightAreaPx;

        Stats(int average, int peak, int brightAreaPx) {
            this.average = average;
            this.peak = peak;
            this.brightAreaPx = brightAreaPx;
        }
    }

    static Stats compute(int[] pixels) {
        if (pixels == null || pixels.length == 0) return new Stats(0, 0, 0);
        int[] histogram = new int[256];
        long sum = 0;
        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            int luma = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
            histogram[luma]++;
            sum += luma;
        }
        int count = pixels.length;
        int average = (int) (sum / count);

        int topCount = Math.max(1, (int) Math.ceil(count * 0.01));
        long peakSum = 0;
        int collected = 0;
        for (int luma = 255; luma >= 0 && collected < topCount; luma--) {
            int take = Math.min(histogram[luma], topCount - collected);
            peakSum += (long) luma * take;
            collected += take;
        }
        int peak = collected > 0 ? (int) (peakSum / collected) : 0;

        int brightAreaPx = 0;
        for (int luma = average + 1; luma <= 255; luma++) brightAreaPx += histogram[luma];

        return new Stats(average, peak, brightAreaPx);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.leakeye.mvp.LumaMathTest"`
Expected: PASS (7개 테스트 모두 — 기존 3개 + 신규 4개)

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/leakeye/mvp/LumaMath.java app/src/test/java/com/leakeye/mvp/LumaMathTest.java
git commit -m "Add LumaMath.compute() for average/peak/bright-area stats"
```

---

### Task 2: `SquareGeometry.mapToBitmap()` — 뷰 좌표를 비트맵 좌표로 비율 매핑

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/SquareGeometry.java`
- Test: `app/src/test/java/com/leakeye/mvp/SquareGeometryTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`SquareGeometryTest.java` 끝(마지막 `}` 앞)에 추가:

```java
    @Test
    public void mapToBitmapScalesCenterAndSideByFraction() {
        // 뷰 1000x1000 중심(500,500) -> 비트맵 4000x3000(다른 해상도/비율)
        float[] mapped = SquareGeometry.mapToBitmap(500f, 500f, 1000, 1000, 4000, 3000, 0.10f);
        assertEquals(2000f, mapped[0], 1e-3); // centerX
        assertEquals(1500f, mapped[1], 1e-3); // centerY
        assertEquals(300f, mapped[2], 1e-3);  // side = min(4000,3000)*0.10
    }

    @Test
    public void mapToBitmapHandlesOffCenterPoint() {
        float[] mapped = SquareGeometry.mapToBitmap(250f, 750f, 1000, 1000, 2000, 2000, 0.20f);
        assertEquals(500f, mapped[0], 1e-3);
        assertEquals(1500f, mapped[1], 1e-3);
        assertEquals(400f, mapped[2], 1e-3);
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.leakeye.mvp.SquareGeometryTest"`
Expected: FAIL (컴파일 에러 — `mapToBitmap`이 아직 없음)

- [ ] **Step 3: `mapToBitmap()` 구현**

`SquareGeometry.java`의 `clampCenter` 메서드 뒤, 마지막 `}` 앞에 추가:

```java
    /**
     * 뷰 좌표계의 중심(viewCenterX, viewCenterY)과 percent를, 다른 크기의 비트맵(예: 저장된 JPEG)
     * 좌표계로 비율(fraction) 매핑한다. TapFocusMapper와 동일한 정규화 방식.
     * @return {bitmapCenterX, bitmapCenterY, side} (비트맵 좌표계, side는 정수값을 담은 float)
     */
    static float[] mapToBitmap(float viewCenterX, float viewCenterY, int viewW, int viewH,
                                int bitmapW, int bitmapH, float percent) {
        float fx = viewCenterX / viewW;
        float fy = viewCenterY / viewH;
        float bitmapCenterX = fx * bitmapW;
        float bitmapCenterY = fy * bitmapH;
        int side = squareSide(bitmapW, bitmapH, percent);
        return new float[]{bitmapCenterX, bitmapCenterY, side};
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.leakeye.mvp.SquareGeometryTest"`
Expected: PASS (기존 3개 + 신규 2개)

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/leakeye/mvp/SquareGeometry.java app/src/test/java/com/leakeye/mvp/SquareGeometryTest.java
git commit -m "Add SquareGeometry.mapToBitmap() for view-to-bitmap ROI mapping"
```

---

### Task 3: `EvaluationOverlayView` — 평균/피크/면적 2줄 표시

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/EvaluationOverlayView.java`

Android `View`라 순수 JUnit으로 테스트 불가 (기존 코드베이스도 이 클래스에 테스트 파일 없음). 이 태스크는 코드 변경 후 Task 6의 수동 확인으로 검증한다.

- [ ] **Step 1: 필드/메서드 교체**

`EvaluationOverlayView.java`에서 필드 선언부(6-22행 부근)를 교체:

```java
    private float centerX = -1f;
    private float centerY = -1f;
    private float percent = 0.10f;
    private int average;
    private int peak;
    private int areaPx;
    private boolean gated = true;
```

- [ ] **Step 2: `updateMetrics` 시그니처 변경**

기존:
```java
    public void updateMetrics(int luma, int area) {
        this.luma = luma;
        this.area = area;
        invalidate();
    }
```

교체:
```java
    public void updateMetrics(int average, int peak, int areaPx) {
        this.average = average;
        this.peak = peak;
        this.areaPx = areaPx;
        invalidate();
    }
```

- [ ] **Step 3: `onDraw`의 텍스트 출력을 2줄로 분리**

기존:
```java
        String suffix = gated ? " (측정대기)" : "";
        int percentInt = Math.round(percent * 100);
        canvas.drawText(String.format(Locale.US, "%d%%: 밝기 %d / 면적 %dpx%s", percentInt, luma, area, suffix),
                cx + half + 8, cy - half, textPaint);
```

교체:
```java
        String suffix = gated ? " (측정대기)" : "";
        int percentInt = Math.round(percent * 100);
        canvas.drawText(String.format(Locale.US, "%d%%: 평균 %d / 피크 %d%s", percentInt, average, peak, suffix),
                cx + half + 8, cy - half, textPaint);
        canvas.drawText(String.format(Locale.US, "면적 %dpx", areaPx),
                cx + half + 8, cy + half, textPaint);
```

- [ ] **Step 4: 컴파일 확인**

Run: `.\gradlew.bat :app:compileDebugJavaSource`
Expected: `EvaluationOverlayView`는 컴파일 성공. (이 시점엔 `MainActivity`의 `updateMetrics(luma, side*side)` 호출부가 옛 2-arg 시그니처라 컴파일 에러가 나는 것이 정상 — Task 4에서 고친다.)

- [ ] **Step 5: 커밋**

Task 4와 함께 커밋한다 (MainActivity 호출부를 고쳐야 빌드가 통과하므로 분리 커밋하지 않음).

---

### Task 4: 라이브 측정 배선 — `MainActivity.sampleSquareStats` / `sampleBrightness`

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java:693-703` (`sampleSquare`)
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java:676-691` (`sampleBrightness`)

- [ ] **Step 1: `sampleSquare`를 `sampleSquareStats`로 교체**

기존 (693-703행):
```java
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
```

교체:
```java
    private LumaMath.Stats sampleSquareStats(Bitmap bitmap, float cx, float cy, int side) {
        if (side <= 0) return new LumaMath.Stats(0, 0, 0);
        float half = side / 2f;
        float clampedCx = SquareGeometry.clampCenter(cx, half, bitmap.getWidth());
        float clampedCy = SquareGeometry.clampCenter(cy, half, bitmap.getHeight());
        int left = Math.round(clampedCx - half);
        int top = Math.round(clampedCy - half);
        int[] pixels = new int[side * side];
        bitmap.getPixels(pixels, 0, side, left, top, side, side);
        return LumaMath.compute(pixels);
    }
```

- [ ] **Step 2: `sampleBrightness` 갱신**

기존 (676-691행):
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
                LumaMath.Stats stats = sampleSquareStats(bitmap, cx, cy, side);
                overlay.updateMetrics(stats.average, stats.peak, stats.brightAreaPx);
                bitmap.recycle();
            }
        }
        brightnessHandler.postDelayed(brightnessTick, BRIGHTNESS_INTERVAL_MS);
    }
```

- [ ] **Step 3: 빌드 확인**

Run: `.\gradlew.bat :app:compileDebugJavaSource`
Expected: BUILD SUCCESSFUL (Task 3의 `EvaluationOverlayView` 변경과 시그니처가 맞아떨어짐)

- [ ] **Step 4: 유닛 테스트 전체 재확인**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS (전체 스위트, 기존 테스트 회귀 없음)

- [ ] **Step 5: 커밋 (Task 3 + Task 4 함께)**

```bash
git add app/src/main/java/com/leakeye/mvp/EvaluationOverlayView.java app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Wire live ROI sampling to average/peak/bright-area stats"
```

---

### Task 5: 저장 이미지 ROI 재계산 및 오버레이 텍스트 추가

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java` (필드 선언부 ~125-135행, `startNext()` ~740-766행, `drawOverlay()` ~847-887행, `tryFinishCapture()` ~893-936행)

- [ ] **Step 1: ROI 스냅샷 필드 추가**

기존 125-135행(camera-thread 전용 필드 블록) 끝에 추가:

```java
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
    private float roiCenterX;
    private float roiCenterY;
    private float roiPercent;
    private int roiViewWidth;
    private int roiViewHeight;
```

(기존 `cropRegion`과 동일하게, 진행 중인 촬영이 하나뿐이라는 `inFlight` 불변식에 기대어 단순 필드로 스냅샷한다 — 매 `startNext()`는 이전 촬영이 `finishCapture()`로 끝난 뒤에만 실행되므로 프레임 간 값 섞임이 없다.)

- [ ] **Step 2: `startNext()`에서 ROI 스냅샷**

`startNext()` 안, `pendingFrame = null;` 다음 줄(753행 부근)에 추가:

```java
            inFlight = next;
            currentStamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            pendingResult = null;
            pendingFrame = null;
            roiCenterX = overlay.getCenterX();
            roiCenterY = overlay.getCenterY();
            roiPercent = measurementPercent;
            roiViewWidth = preview.getWidth();
            roiViewHeight = preview.getHeight();
            if (pendingJpegBitmap != null) { pendingJpegBitmap.recycle(); pendingJpegBitmap = null; }
```

- [ ] **Step 3: `drawOverlay()`에 통계 파라미터 추가**

기존 시그니처/본문 시작(847-868행):
```java
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
        if (v.illuminanceLux != null) {
            if (line3.length() > 0) line3.append("   ");
            line3.append(String.format(Locale.US, "%.2f lx", v.illuminanceLux));
        }
        if (line3.length() > 0) lines.add(line3.toString());
```

교체 (시그니처와, 끝에 한 줄 추가):
```java
    private void drawOverlay(Bitmap bitmap, CaptureMetadata.Values v, LumaMath.Stats roiStats) {
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
        if (v.illuminanceLux != null) {
            if (line3.length() > 0) line3.append("   ");
            line3.append(String.format(Locale.US, "%.2f lx", v.illuminanceLux));
        }
        if (line3.length() > 0) lines.add(line3.toString());
        lines.add(String.format(Locale.US, "평균 %d / 피크 %d / 면적 %dpx",
                roiStats.average, roiStats.peak, roiStats.brightAreaPx));
```

(나머지 `drawOverlay` 본문 — `Canvas canvas = new Canvas(bitmap);` 이후 — 은 변경 없음, `lines.size()`가 자동으로 바 높이에 반영됨.)

- [ ] **Step 4: `tryFinishCapture()`에서 재계산 후 `drawOverlay` 호출**

기존 (918-925행 부근):
```java
            String label = "저장 완료: " + frame.rawFile.getName();
            if (jpeg != null) {
                drawOverlay(jpeg, values);
                saveJpegToGallery(jpeg, base + ".jpg");
                label += " + jpg/json";
            } else {
                label += " + json (jpg 실패)";
            }
```

교체:
```java
            String label = "저장 완료: " + frame.rawFile.getName();
            if (jpeg != null) {
                float[] mapped = SquareGeometry.mapToBitmap(roiCenterX, roiCenterY, roiViewWidth, roiViewHeight,
                        jpeg.getWidth(), jpeg.getHeight(), roiPercent);
                LumaMath.Stats roiStats = sampleSquareStats(jpeg, mapped[0], mapped[1], Math.round(mapped[2]));
                drawOverlay(jpeg, values, roiStats);
                saveJpegToGallery(jpeg, base + ".jpg");
                label += " + jpg/json";
            } else {
                label += " + json (jpg 실패)";
            }
```

- [ ] **Step 5: 빌드 및 전체 테스트 확인**

Run: `.\gradlew.bat :app:compileDebugJavaSource`
Expected: BUILD SUCCESSFUL

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS (전체 스위트, 회귀 없음)

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Recompute ROI stats from captured JPEG and overlay them on saved image"
```

---

### Task 6: 실기기 수동 확인

**Files:** 없음 (빌드 산출물 설치 및 육안 확인)

- [ ] **Step 1: 디버그 APK 빌드**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 설치 및 라이브 화면 확인**

기기에 설치 후 실행 — 10/20/30% 사각형 우측 상단에 "평균 X / 피크 Y", 우측 하단에 "면적 Zpx"가 표시되는지, 밝은 물체를 비췄을 때 면적/피크 값이 반응하는지 확인.

- [ ] **Step 3: 촬영 후 저장 이미지 확인**

RAW/JPEG 촬영 1회 실행 → 갤러리(`Pictures/LeakEye`)에서 저장된 사진을 열어, 하단 정보 바에 평균/피크/면적/조도가 함께 표시되는지 확인. 저장된 면적(px) 값이 라이브 화면의 면적(px)보다 훨씬 큰 것(해상도 차이 때문)도 정상임을 확인.

- [ ] **Step 4: 문제 없으면 완료 보고**

이상 없으면 이 태스크는 커밋 없이 종료(코드 변경 없음).
