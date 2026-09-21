# 3배 줌 + 탭 포커스 + 평가영역 오버레이 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 라이브 프리뷰(및 RAW/JPEG 촬영)에 항상 3배 디지털 줌을 적용하고, 화면 터치 지점으로 포커스를 맞추면서 그 지점 중심으로 10/20/30% 빨간 정사각형 평가영역 + 십자선을 그리고, 각 영역의 평균 밝기(0-255)·면적(pixel수)을 표시하되 포커스가 실제로 잠기기 전까지는 수치를 게이팅한다.

**Architecture:** 순수 계산 로직(크롭 영역, 좌표 매핑, 정사각형 크기/클램프, 루마 평균, AF 상태 분류)은 Android 프레임워크 의존 없는 정적 유틸 클래스 5개로 분리해 plain JUnit으로 테스트한다. 화면 렌더링은 `previewContainer` 안에 `TextureView`(preview)와 같은 크기로 겹쳐 그리는 신규 `EvaluationOverlayView`가 담당한다. `MainActivity`는 이 유틸 클래스들과 오버레이를 카메라 콜백/터치 이벤트에 연결하는 글루 코드만 담당한다 (기존 코드베이스 패턴 그대로: `ExposureScale`처럼 순수 로직은 별도 클래스, 카메라 연동은 `MainActivity`).

**Tech Stack:** Java, Android Camera2 API, `android.view.View`/`Canvas` 커스텀 드로잉, JUnit 4 (plain, no Robolectric).

**스펙 문서:** `docs/superpowers/specs/2026-09-21-zoom-focus-eval-overlay-design.md`

**빌드 환경 (모든 gradle 명령에 필요):**
```
JAVA_HOME="E:/Program install/Android studio/jbr"
```
(`E:\Android\jbr`는 불완전한 설치라 사용 금지)

---

### Task 1: ZoomCropRegion (3배 줌 크롭 영역 계산)

**Files:**
- Create: `app/src/main/java/com/leakeye/mvp/ZoomCropRegion.java`
- Test: `app/src/test/java/com/leakeye/mvp/ZoomCropRegionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.leakeye.mvp;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class ZoomCropRegionTest {
    @Test
    public void centeredCropShrinksAndCentersForFactorThree() {
        int[] crop = ZoomCropRegion.centeredCrop(0, 0, 4080, 3060, 3f);
        assertArrayEquals(new int[]{1360, 1020, 2720, 2040}, crop);
    }

    @Test
    public void centeredCropHandlesNonZeroOrigin() {
        int[] crop = ZoomCropRegion.centeredCrop(100, 200, 4180, 3260, 3f);
        // width=4080,height=3060 -> newWidth=1360,newHeight=1020
        // centerX=100+2040=2140, centerY=200+1530=1730
        // newLeft=2140-680=1460, newTop=1730-510=1220
        assertArrayEquals(new int[]{1460, 1220, 2820, 2240}, crop);
    }

    @Test
    public void factorOneReturnsSameRect() {
        int[] crop = ZoomCropRegion.centeredCrop(0, 0, 1000, 500, 1f);
        assertArrayEquals(new int[]{0, 0, 1000, 500}, crop);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --tests "com.leakeye.mvp.ZoomCropRegionTest"`
Expected: FAIL (compilation error — `ZoomCropRegion` does not exist)

- [ ] **Step 3: Write minimal implementation**

```java
package com.leakeye.mvp;

/** 활성 배열 영역을 중앙 정렬로 축소해 디지털 줌 크롭 영역을 계산한다. 순수 함수. */
final class ZoomCropRegion {
    private ZoomCropRegion() {}

    /**
     * (left, top, right, bottom)로 정의된 사각형을 가로/세로 각각 1/factor 크기로 줄이고
     * 원래 사각형과 같은 중심을 갖도록 배치한 새 사각형을 {left, top, right, bottom}으로 반환한다.
     */
    static int[] centeredCrop(int left, int top, int right, int bottom, float factor) {
        int width = right - left;
        int height = bottom - top;
        int newWidth = Math.round(width / factor);
        int newHeight = Math.round(height / factor);
        int centerX = left + width / 2;
        int centerY = top + height / 2;
        int newLeft = centerX - newWidth / 2;
        int newTop = centerY - newHeight / 2;
        return new int[]{newLeft, newTop, newLeft + newWidth, newTop + newHeight};
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --tests "com.leakeye.mvp.ZoomCropRegionTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/ZoomCropRegion.java app/src/test/java/com/leakeye/mvp/ZoomCropRegionTest.java
git commit -m "Add ZoomCropRegion for 3x digital-zoom crop calculation"
```

---

### Task 2: TapFocusMapper (터치 좌표 → AF 리전 매핑)

**Files:**
- Create: `app/src/main/java/com/leakeye/mvp/TapFocusMapper.java`
- Test: `app/src/test/java/com/leakeye/mvp/TapFocusMapperTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.leakeye.mvp;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class TapFocusMapperTest {
    @Test
    public void centerTapMapsToCenteredRegion() {
        int[] region = TapFocusMapper.mapTapToAfRegion(500, 500, 1000, 1000, 0, 0, 1000, 1000, 100);
        assertArrayEquals(new int[]{450, 450, 100, 100}, region);
    }

    @Test
    public void cornerTapClampsInsideCropBounds() {
        int[] region = TapFocusMapper.mapTapToAfRegion(0, 0, 1000, 1000, 0, 0, 1000, 1000, 100);
        assertArrayEquals(new int[]{0, 0, 100, 100}, region);

        int[] regionOpposite = TapFocusMapper.mapTapToAfRegion(1000, 1000, 1000, 1000, 0, 0, 1000, 1000, 100);
        assertArrayEquals(new int[]{900, 900, 100, 100}, regionOpposite);
    }

    @Test
    public void nonZeroCropOffsetIsRespected() {
        int[] region = TapFocusMapper.mapTapToAfRegion(500, 500, 1000, 1000, 1360, 1020, 1360, 1020, 68);
        // nx=0.5, ny=0.5 -> sensorX=1360+680=2040, sensorY=1020+510=1530
        // left=2040-34=2006, top=1530-34=1496
        assertArrayEquals(new int[]{2006, 1496, 68, 68}, region);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --tests "com.leakeye.mvp.TapFocusMapperTest"`
Expected: FAIL (compilation error — `TapFocusMapper` does not exist)

- [ ] **Step 3: Write minimal implementation**

```java
package com.leakeye.mvp;

/** 화면 터치 좌표를 크롭 영역 기준 활성 배열 좌표의 정사각형 AF 리전으로 변환한다. 순수 함수. */
final class TapFocusMapper {
    private TapFocusMapper() {}

    /**
     * @param tapX, tapY   뷰 픽셀 좌표의 터치 지점
     * @param viewW, viewH 뷰(프리뷰) 크기
     * @param cropLeft, cropTop, cropWidth, cropHeight 현재 SCALER_CROP_REGION
     * @param regionSize   반환할 정사각형 AF 리전의 한 변 (활성 배열 좌표계 픽셀)
     * @return {left, top, width, height} — 활성 배열 좌표계, 크롭 영역 안쪽으로 클램프됨
     */
    static int[] mapTapToAfRegion(float tapX, float tapY, int viewW, int viewH,
                                   int cropLeft, int cropTop, int cropWidth, int cropHeight,
                                   int regionSize) {
        float nx = clamp01(tapX / viewW);
        float ny = clamp01(tapY / viewH);
        float sensorX = cropLeft + nx * cropWidth;
        float sensorY = cropTop + ny * cropHeight;
        int half = regionSize / 2;
        int left = clampInt(Math.round(sensorX) - half, cropLeft, cropLeft + cropWidth - regionSize);
        int top = clampInt(Math.round(sensorY) - half, cropTop, cropTop + cropHeight - regionSize);
        return new int[]{left, top, regionSize, regionSize};
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --tests "com.leakeye.mvp.TapFocusMapperTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/TapFocusMapper.java app/src/test/java/com/leakeye/mvp/TapFocusMapperTest.java
git commit -m "Add TapFocusMapper for tap-to-AF-region coordinate mapping"
```

---

### Task 3: SquareGeometry (평가영역 정사각형 크기·클램프)

**Files:**
- Create: `app/src/main/java/com/leakeye/mvp/SquareGeometry.java`
- Test: `app/src/test/java/com/leakeye/mvp/SquareGeometryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.leakeye.mvp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SquareGeometryTest {
    @Test
    public void squareSideUsesShorterDimension() {
        assertEquals(90, SquareGeometry.squareSide(900, 1200, 0.10f));
        assertEquals(120, SquareGeometry.squareSide(1200, 1500, 0.10f));
    }

    @Test
    public void clampCenterKeepsSquareInsideBounds() {
        assertEquals(50f, SquareGeometry.clampCenter(10f, 50f, 1000f), 1e-6);
        assertEquals(950f, SquareGeometry.clampCenter(990f, 50f, 1000f), 1e-6);
        assertEquals(500f, SquareGeometry.clampCenter(500f, 50f, 1000f), 1e-6);
    }

    @Test
    public void clampCenterForcesMidpointWhenSquareLargerThanDimension() {
        assertEquals(500f, SquareGeometry.clampCenter(10f, 600f, 1000f), 1e-6);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --tests "com.leakeye.mvp.SquareGeometryTest"`
Expected: FAIL (compilation error — `SquareGeometry` does not exist)

- [ ] **Step 3: Write minimal implementation**

```java
package com.leakeye.mvp;

/** 평가영역 정사각형의 변 길이 계산과 화면 경계 클램프. 순수 함수. */
final class SquareGeometry {
    private SquareGeometry() {}

    /** 뷰의 짧은 변 기준 percent(예: 0.10f)만큼의 정사각형 한 변(px)을 반환한다. */
    static int squareSide(int viewWidth, int viewHeight, float percent) {
        return Math.round(Math.min(viewWidth, viewHeight) * percent);
    }

    /**
     * value를 중심으로 한 변이 halfSide*2인 구간이 [0, dimension] 안에 들어오도록 클램프한다.
     * 구간이 dimension보다 크면 dimension의 중점을 반환한다.
     */
    static float clampCenter(float value, float halfSide, float dimension) {
        if (halfSide * 2 > dimension) return dimension / 2f;
        if (value - halfSide < 0) return halfSide;
        if (value + halfSide > dimension) return dimension - halfSide;
        return value;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --tests "com.leakeye.mvp.SquareGeometryTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/SquareGeometry.java app/src/test/java/com/leakeye/mvp/SquareGeometryTest.java
git commit -m "Add SquareGeometry for evaluation-square sizing and clamping"
```

---

### Task 4: LumaMath (평균 밝기 계산)

**Files:**
- Create: `app/src/main/java/com/leakeye/mvp/LumaMath.java`
- Test: `app/src/test/java/com/leakeye/mvp/LumaMathTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.leakeye.mvp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LumaMathTest {
    @Test
    public void whiteBlackAndRedPixels() {
        assertEquals(255, LumaMath.averageLuma(new int[]{0xFFFFFFFF}));
        assertEquals(0, LumaMath.averageLuma(new int[]{0xFF000000}));
        assertEquals(76, LumaMath.averageLuma(new int[]{0xFFFF0000}));
    }

    @Test
    public void averagesMultiplePixels() {
        int[] pixels = new int[]{0xFFFFFFFF, 0xFF000000};
        assertEquals(127, LumaMath.averageLuma(pixels));
    }

    @Test
    public void emptyArrayReturnsZero() {
        assertEquals(0, LumaMath.averageLuma(new int[]{}));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --tests "com.leakeye.mvp.LumaMathTest"`
Expected: FAIL (compilation error — `LumaMath` does not exist)

- [ ] **Step 3: Write minimal implementation**

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
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --tests "com.leakeye.mvp.LumaMathTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/LumaMath.java app/src/test/java/com/leakeye/mvp/LumaMathTest.java
git commit -m "Add LumaMath for average-brightness calculation"
```

---

### Task 5: AfStateClassifier (AF 상태 → 신뢰가능 여부)

**Files:**
- Create: `app/src/main/java/com/leakeye/mvp/AfStateClassifier.java`
- Test: `app/src/test/java/com/leakeye/mvp/AfStateClassifierTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.leakeye.mvp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AfStateClassifierTest {
    @Test
    public void focusedLockedAndPassiveFocusedAreLocked() {
        assertTrue(AfStateClassifier.isLocked(4));  // CONTROL_AF_STATE_FOCUSED_LOCKED
        assertTrue(AfStateClassifier.isLocked(2));  // CONTROL_AF_STATE_PASSIVE_FOCUSED
    }

    @Test
    public void scanningAndUnfocusedAreNotLocked() {
        assertFalse(AfStateClassifier.isLocked(0)); // INACTIVE
        assertFalse(AfStateClassifier.isLocked(1)); // PASSIVE_SCAN
        assertFalse(AfStateClassifier.isLocked(3)); // ACTIVE_SCAN
        assertFalse(AfStateClassifier.isLocked(5)); // NOT_FOCUSED_LOCKED
        assertFalse(AfStateClassifier.isLocked(6)); // PASSIVE_UNFOCUSED
    }

    @Test
    public void nullStateIsNotLocked() {
        assertFalse(AfStateClassifier.isLocked(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --tests "com.leakeye.mvp.AfStateClassifierTest"`
Expected: FAIL (compilation error — `AfStateClassifier` does not exist)

- [ ] **Step 3: Write minimal implementation**

```java
package com.leakeye.mvp;

/**
 * Camera2 CONTROL_AF_STATE 정수 값을 신뢰 가능(잠김) 여부로 분류한다. 순수 함수.
 * 값은 android.hardware.camera2.CaptureResult의 CONTROL_AF_STATE_* 상수와 동일하며,
 * plain JUnit 테스트를 위해 Android 프레임워크 의존 없이 정수 리터럴로 정의한다.
 */
final class AfStateClassifier {
    private AfStateClassifier() {}

    private static final int STATE_PASSIVE_FOCUSED = 2;
    private static final int STATE_FOCUSED_LOCKED = 4;

    static boolean isLocked(Integer afState) {
        if (afState == null) return false;
        return afState == STATE_FOCUSED_LOCKED || afState == STATE_PASSIVE_FOCUSED;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --tests "com.leakeye.mvp.AfStateClassifierTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/AfStateClassifier.java app/src/test/java/com/leakeye/mvp/AfStateClassifierTest.java
git commit -m "Add AfStateClassifier to distinguish trustworthy AF lock states"
```

---

### Task 6: EvaluationOverlayView (신규 View, 오버레이 드로잉)

**Files:**
- Create: `app/src/main/java/com/leakeye/mvp/EvaluationOverlayView.java`

이 클래스는 Android `View`/`Canvas`에 직접 의존해 실기기 렌더링이 필요하므로 유닛 테스트 대상이 아니다 (스펙의 "카메라/터치/실기기 동작은 에뮬레이션 불가"). 대신 Step 2에서 컴파일 확인으로 검증한다.

- [ ] **Step 1: Write the implementation**

```java
package com.leakeye.mvp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Locale;

/**
 * 라이브 프리뷰 위에 10/20/30% 평가영역 정사각형(테두리만)과 그 바깥쪽 십자선,
 * 각 영역의 평균 밝기(0-255)·면적(px)을 그린다. preview(TextureView)와 정확히 같은
 * 크기로 겹쳐서 배치된다는 전제 하에 자신의 getWidth()/getHeight()를 좌표계로 쓴다.
 */
public class EvaluationOverlayView extends View {
    private float centerX = -1f;
    private float centerY = -1f;
    private int luma10, area10, luma20, area20, luma30, area30;
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

    /** true면 아직 포커스가 확정되지 않아 수치를 신뢰할 수 없음을 표시한다. */
    public void setGated(boolean value) {
        if (gated != value) {
            gated = value;
            invalidate();
        }
    }

    public void updateMetrics(int luma10, int area10, int luma20, int area20, int luma30, int area30) {
        this.luma10 = luma10; this.area10 = area10;
        this.luma20 = luma20; this.area20 = area20;
        this.luma30 = luma30; this.area30 = area30;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || centerX < 0) return;

        int side10 = SquareGeometry.squareSide(w, h, 0.10f);
        int side20 = SquareGeometry.squareSide(w, h, 0.20f);
        int side30 = SquareGeometry.squareSide(w, h, 0.30f);

        float cx10 = SquareGeometry.clampCenter(centerX, side10 / 2f, w);
        float cy10 = SquareGeometry.clampCenter(centerY, side10 / 2f, h);
        float cx20 = SquareGeometry.clampCenter(centerX, side20 / 2f, w);
        float cy20 = SquareGeometry.clampCenter(centerY, side20 / 2f, h);
        float cx30 = SquareGeometry.clampCenter(centerX, side30 / 2f, w);
        float cy30 = SquareGeometry.clampCenter(centerY, side30 / 2f, h);

        drawSquare(canvas, cx10, cy10, side10);
        drawSquare(canvas, cx20, cy20, side20);
        drawSquare(canvas, cx30, cy30, side30);

        float outerHalf = side30 / 2f;
        float outerLeft = cx30 - outerHalf;
        float outerTop = cy30 - outerHalf;
        float outerRight = cx30 + outerHalf;
        float outerBottom = cy30 + outerHalf;
        canvas.drawLine(cx30, 0, cx30, outerTop, linePaint);
        canvas.drawLine(cx30, outerBottom, cx30, h, linePaint);
        canvas.drawLine(0, cy30, outerLeft, cy30, linePaint);
        canvas.drawLine(outerRight, cy30, w, cy30, linePaint);

        String suffix = gated ? " (측정대기)" : "";
        canvas.drawText(String.format(Locale.US, "10%%: 밝기 %d / 면적 %dpx%s", luma10, area10, suffix),
                cx10 + side10 / 2f + 8, cy10 - side10 / 2f, textPaint);
        canvas.drawText(String.format(Locale.US, "20%%: 밝기 %d / 면적 %dpx%s", luma20, area20, suffix),
                cx20 + side20 / 2f + 8, cy20 - side20 / 2f + 32, textPaint);
        canvas.drawText(String.format(Locale.US, "30%%: 밝기 %d / 면적 %dpx%s", luma30, area30, suffix),
                cx30 + side30 / 2f + 8, cy30 - side30 / 2f + 64, textPaint);
    }

    private void drawSquare(Canvas canvas, float cx, float cy, int side) {
        float half = side / 2f;
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, boxPaint);
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/EvaluationOverlayView.java
git commit -m "Add EvaluationOverlayView drawing evaluation squares, crosshair, and metrics"
```

---

### Task 7: MainActivity — imports 및 필드 추가

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java`

이 작업부터는 `MainActivity.java`에 이후 작업(8~11)에서 쓸 필드/임포트를 미리 추가한다. 아직 동작은 바뀌지 않으므로 컴파일만 확인한다.

- [ ] **Step 1: 임포트 추가**

`import android.graphics.RectF;` 줄 바로 앞에 추가:

```java
import android.graphics.Rect;
```

`import android.hardware.camera2.params.StreamConfigurationMap;` 줄 바로 앞에 추가:

```java
import android.hardware.camera2.params.MeteringRectangle;
```

`import android.os.HandlerThread;` 줄 바로 다음에 추가:

```java
import android.os.Looper;
```

`import android.view.Surface;` 줄 바로 앞에 추가:

```java
import android.view.MotionEvent;
```

- [ ] **Step 2: 필드 추가 (1) — UI/오버레이 관련**

찾을 코드 (`MainActivity.java` 약 69-74행):

```java
    private TextureView preview;
    private FrameLayout previewContainer;
    private TextView status;
    private TextView poseStatus;
    private TextView distanceStatus;
    private ExposureControls controls;
```

교체:

```java
    private TextureView preview;
    private FrameLayout previewContainer;
    private TextView status;
    private TextView poseStatus;
    private TextView distanceStatus;
    private TextView focusStatus;
    private ExposureControls controls;
    private EvaluationOverlayView overlay;
```

- [ ] **Step 3: 필드 추가 (2) — 줌/AF/샘플러 관련**

찾을 코드 (약 86-87행, Step 2 반영 후에도 그대로 유지되는 부분):

```java
    private boolean rawSupported;
    private CameraCharacteristics characteristics;
```

교체:

```java
    private boolean rawSupported;
    private CameraCharacteristics characteristics;
    private Rect cropRegion;
    private MeteringRectangle afRegion;
    private final Handler brightnessHandler = new Handler(Looper.getMainLooper());
    // Task 11에서 sampleBrightness()를 정의하면서 this::sampleBrightness로 교체한다.
    private Runnable brightnessTick = () -> {};
    private static final long BRIGHTNESS_INTERVAL_MS = 300L;
```

- [ ] **Step 4: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Add fields and imports for zoom/focus/overlay feature"
```

---

### Task 8: MainActivity — 3배 줌(크롭 영역) 적용

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java`

- [ ] **Step 1: setupCamera()에서 크롭 영역 계산**

찾을 코드:

```java
            characteristics = manager.getCameraCharacteristics(cameraId);
            configureControls();
```

교체:

```java
            characteristics = manager.getCameraCharacteristics(cameraId);
            Rect activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            int[] crop = ZoomCropRegion.centeredCrop(activeArray.left, activeArray.top, activeArray.right, activeArray.bottom, 3f);
            cropRegion = new Rect(crop[0], crop[1], crop[2], crop[3]);
            configureControls();
```

- [ ] **Step 2: updatePreview()에 크롭 적용**

찾을 코드:

```java
                CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(previewSurface);
                applySettings(builder, settings);
                session.setRepeatingRequest(builder.build(), previewCallback, cameraHandler);
```

교체:

```java
                CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(previewSurface);
                applySettings(builder, settings);
                if (cropRegion != null) builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
                if (afRegion != null && !settings.manual) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{afRegion});
                }
                session.setRepeatingRequest(builder.build(), previewCallback, cameraHandler);
```

- [ ] **Step 3: startNext()의 스틸 캡처에도 크롭 적용**

찾을 코드:

```java
            CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(rawReader.getSurface());
            builder.addTarget(jpegReader.getSurface());
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            applySettings(builder, next);
```

교체:

```java
            CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(rawReader.getSurface());
            builder.addTarget(jpegReader.getSurface());
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            applySettings(builder, next);
            if (cropRegion != null) builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
```

- [ ] **Step 4: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Apply 3x SCALER_CROP_REGION to preview and still-capture requests"
```

**실기기 확인 필요 (Task 12에서 수행):** RAW 파일에도 크롭이 반영되는지 — 스펙 "알려진 리스크" 참고. 반영 안 되면 이 Step 3의 크롭 적용 줄을 제거하고 RAW는 풀센서로 되돌린다.

---

### Task 9: MainActivity — 오버레이 뷰 + 탭 포커스 연결

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java`

- [ ] **Step 1: buildUi()에 focusStatus 표시줄 추가**

찾을 코드:

```java
        distanceStatus = new TextView(this);
        distanceStatus.setText("거리(추정): -");
        distanceStatus.setTextColor(Color.rgb(150, 190, 210));
        distanceStatus.setPadding(24, 0, 24, 12);
        root.addView(distanceStatus, new LinearLayout.LayoutParams(-1, -2));

        previewContainer = new FrameLayout(this);
```

교체:

```java
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

        previewContainer = new FrameLayout(this);
```

- [ ] **Step 2: preview에 터치 리스너 추가, overlay 뷰 추가**

찾을 코드:

```java
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(surfaceListener);
        previewContainer.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        root.addView(previewContainer, new LinearLayout.LayoutParams(-1, 0, 1));
```

교체:

```java
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(surfaceListener);
        preview.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) focusAt(event.getX(), event.getY());
            return true;
        });
        previewContainer.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        overlay = new EvaluationOverlayView(this);
        previewContainer.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(previewContainer, new LinearLayout.LayoutParams(-1, 0, 1));
```

- [ ] **Step 3: layoutPreview()에서 overlay도 preview와 동일한 크기로 배치**

찾을 코드:

```java
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(targetW, targetH);
        lp.gravity = Gravity.CENTER;
        preview.setLayoutParams(lp);
        configureTransform(targetW, targetH);
    }
```

교체:

```java
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(targetW, targetH);
        lp.gravity = Gravity.CENTER;
        preview.setLayoutParams(lp);
        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(targetW, targetH);
        overlayLp.gravity = Gravity.CENTER;
        overlay.setLayoutParams(overlayLp);
        configureTransform(targetW, targetH);
    }
```

- [ ] **Step 4: focusAt() 메서드 추가**

`updatePreview()` 메서드가 끝나는 다음 지점(바로 뒤에 `captureRaw()`가 오는 자리) 앞에 추가:

찾을 코드:

```java
    private void captureRaw() {
        if (!ready()) return;
        enqueue(Collections.singletonList(controls.current()));
    }
```

교체 (새 메서드를 `captureRaw()` 앞에 삽입):

```java
    /** 화면 터치 지점으로 포커스를 맞추고 평가영역 중심을 갱신한다. */
    private void focusAt(float viewX, float viewY) {
        if (overlay != null) overlay.updateCenter(viewX, viewY);
        if (camera == null || session == null || cropRegion == null || previewSurface == null) return;
        ExposureSettings settings = controls.current();
        if (settings.manual) return;
        int viewW = preview.getWidth();
        int viewH = preview.getHeight();
        if (viewW == 0 || viewH == 0) return;
        int regionSize = Math.max(1, Math.round(cropRegion.width() * 0.05f));
        int[] region = TapFocusMapper.mapTapToAfRegion(viewX, viewY, viewW, viewH,
                cropRegion.left, cropRegion.top, cropRegion.width(), cropRegion.height(), regionSize);
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
                // AF 트리거 실패는 무시한다 (오버레이 이동은 이미 반영됨).
            }
        });
        updatePreview();
    }

    private void captureRaw() {
        if (!ready()) return;
        enqueue(Collections.singletonList(controls.current()));
    }
```

- [ ] **Step 5: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Wire evaluation overlay and tap-to-focus into preview"
```

---

### Task 10: MainActivity — AF 상태 게이팅

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java`

- [ ] **Step 1: previewCallback에서 AF_STATE를 읽어 게이팅**

찾을 코드:

```java
    private final CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            Float diopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (diopters == null) return;
            Float meters = ExposureScale.distanceMeters(diopters);
            String text = meters != null ? String.format(Locale.US, "거리(추정): %.2f m (AF)", meters) : "거리(추정): ∞";
            runOnUiThread(() -> distanceStatus.setText(text));
        }
    };
```

교체:

```java
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
```

(원래 `if (diopters == null) return;`으로 인해 diopters가 없으면 거리뿐 아니라 이후 로직도 전부 건너뛰었다. AF 상태 게이팅은 diopters 유무와 무관하게 항상 동작해야 하므로 早期 return을 제거하고 `if (diopters != null) { ... }`로 감쌌다.)

- [ ] **Step 2: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Gate focus status and overlay metrics on AF lock state"
```

---

### Task 11: MainActivity — 밝기 샘플러 연결

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java`

- [ ] **Step 1: brightnessTick을 실제 메서드 참조로 되돌리기**

찾을 코드 (Task 7 Step 3에서 임시로 바꿔둔 줄):

```java
    private Runnable brightnessTick = () -> {};
```

교체:

```java
    private final Runnable brightnessTick = this::sampleBrightness;
```

- [ ] **Step 2: 샘플러 메서드 추가**

`focusAt()` 메서드(Task 9에서 추가됨) 바로 다음, `captureRaw()` 앞에 추가:

찾을 코드:

```java
    private void captureRaw() {
        if (!ready()) return;
        enqueue(Collections.singletonList(controls.current()));
    }
```

교체 (새 메서드들을 `captureRaw()` 앞에 삽입):

```java
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
```

- [ ] **Step 3: 프리뷰 세션 시작/종료에 샘플러 시작/정지 연결**

찾을 코드:

```java
                @Override public void onConfigured(CameraCaptureSession configured) {
                    session = configured;
                    runOnUiThread(() -> {
                        status.setText("측정 대기 / RAW " + (rawSupported ? "지원" : "미지원"));
                        layoutPreview();
                        updatePreview();
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
                        startBrightnessSampler();
                    });
                }
```

찾을 코드:

```java
    protected void onPause() {
        sensorManager.unregisterListener(sensorListener);
        closeCameraDevice();
        super.onPause();
    }
```

교체:

```java
    protected void onPause() {
        sensorManager.unregisterListener(sensorListener);
        stopBrightnessSampler();
        closeCameraDevice();
        super.onPause();
    }
```

- [ ] **Step 4: 컴파일 확인**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/leakeye/mvp/MainActivity.java
git commit -m "Sample preview brightness every 300ms and feed evaluation overlay"
```

---

### Task 12: 전체 빌드 + 실기기(Fold5) 수동 검증 + APK 산출

**Files:** 없음 (빌드/검증만)

- [ ] **Step 1: 유닛 테스트 전체 실행**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 기존 테스트 포함 전부 PASS

- [ ] **Step 2: 디버그 APK 빌드**

Run: `JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

Run: `find app/build/outputs/apk -name "*.apk"`
Expected: `app/build/outputs/apk/debug/app-debug.apk` 존재

- [ ] **Step 3: 실기기(Fold5) 설치 및 수동 확인**

```
MSYS_NO_PATHCONV=1 "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

수동 확인 체크리스트 (화면을 직접 보며 확인):

- [ ] 앱 실행 시 프리뷰가 기존보다 3배 확대되어 보이는가 (화각이 좁아짐)
- [ ] 앱 실행 직후(터치 전) 평가영역 3개 사각형이 화면 중심에 표시되는가
- [ ] 화면을 터치하면 그 지점으로 사각형/십자선 중심이 이동하는가
- [ ] 터치 직후 "포커스: 확인중" → 잠시 후 "포커스: 완료"로 바뀌는가
- [ ] "포커스: 확인중"인 동안 밝기/면적 수치에 "(측정대기)"가 붙어 표시되는가
- [ ] "포커스: 완료" 후 밝기(0-255)·면적(px) 수치가 정상 범위로 표시되는가
- [ ] 십자선이 사각형 내부를 지나지 않고 바깥쪽에서만 보이는가
- [ ] **RAW 촬영** 버튼으로 찍은 RAW 파일이 3배 줌 화각을 반영하는지 확인 (파일을 열어 확인하거나 JPEG 미리보기 비교) — 반영 안 되면 Task 8 Step 3의 크롭 적용을 되돌려야 함 (스펙의 "알려진 리스크" 참고)
- [ ] Manual 모드로 전환 시 탭해도 AF 트리거는 없이 오버레이만 이동하고, 수치는 게이팅 없이 항상 표시되는가

- [ ] **Step 4: 문제 발견 시 조정**

체크리스트에서 실패한 항목이 있으면 해당 Task로 돌아가 수정 후 다시 Step 1부터 반복한다. RAW 크롭 미반영처럼 스펙에 이미 예견된 리스크라면, Task 8 Step 3에서 RAW 캡처 빌더에 크롭을 적용하지 않도록 되돌리고 해당 사실을 커밋 메시지에 남긴다.

- [ ] **Step 5: 최종 커밋 (필요 시)**

```bash
git add -A
git commit -m "Verify 3x zoom, tap-focus, and evaluation overlay on Fold5"
```

(Step 4에서 변경이 없었다면 이 커밋은 생략한다.)
