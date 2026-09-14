# 수동 노출 · 노출 스윕 · PC 리포트 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 휴대폰에서 ISO·셔터·초점을 직접 정해 RAW를 찍고, 16개 노출 조합을 자동 연속 촬영하며, PC에서 조건·통계를 한 표로 비교한다.

**Architecture:** 순수 로직(`ExposureSettings`, `ExposureScale`, `ExposureSweep`)은 Android 의존 없이 단위 테스트한다. `ExposureControls`는 View만 담당한다. `MainActivity`는 촬영 큐를 `cameraHandler`에서만 다루고, 기존 `tryWriteMetadata` 완료 지점에서 다음 촬영을 시작한다. PC 리포트는 numpy/Pillow 순수 함수 + CLI.

**Tech Stack:** Java, Camera2, JUnit 4, Python 3 (numpy, Pillow, pytest, Anaconda `E:/Anaconda3/python.exe`), Gradle(JAVA_HOME=`E:/Program install/Android studio/jbr`).

**참고:** git 저장소가 아니므로 커밋 단계 없음. 스펙: `docs/superpowers/specs/2026-09-12-manual-exposure-sweep-design.md`.

---

## File Structure

- Create `app/src/main/java/com/leakeye/mvp/ExposureSettings.java` — 불변 촬영 설정.
- Create `app/src/main/java/com/leakeye/mvp/ExposureScale.java` — 로그 슬라이더 매핑·표시 문자열.
- Create `app/src/main/java/com/leakeye/mvp/ExposureSweep.java` — 스윕 조합 계획.
- Create `app/src/main/java/com/leakeye/mvp/ExposureControls.java` — 스위치+슬라이더 View.
- Modify `app/src/main/java/com/leakeye/mvp/CaptureMetadata.java` — `request` 섹션.
- Modify `app/src/main/java/com/leakeye/mvp/MainActivity.java` — 컨트롤, 큐, 스윕, 파일명.
- Create `app/src/test/java/com/leakeye/mvp/ExposureScaleTest.java`, `ExposureSweepTest.java`; Modify `CaptureMetadataTest.java`.
- Create `tools/raw_report.py`, `tools/test_raw_report.py`.
- Modify `README.md`.

---

### Task 1: 순수 로직 3종 (TDD)

**Files:** 위 `ExposureSettings/Scale/Sweep` + 테스트 2개

- [ ] **Step 1: 실패 테스트 작성** — `ExposureScaleTest`

```java
package com.leakeye.mvp;
import static org.junit.Assert.*;
import org.junit.Test;
public class ExposureScaleTest {
    @Test public void logMappingRoundTrips() {
        long lo = 32_544L, hi = 150_002_608L;
        assertEquals(lo, ExposureScale.toValue(0, 1000, lo, hi));
        assertEquals(hi, ExposureScale.toValue(1000, 1000, lo, hi));
        long mid = ExposureScale.toValue(500, 1000, lo, hi);
        assertEquals(500, ExposureScale.toPosition(mid, 1000, lo, hi));
        assertEquals(1000, ExposureScale.toPosition(hi, 1000, lo, hi));
        assertEquals(0, ExposureScale.toPosition(lo, 1000, lo, hi));
    }
    @Test public void isoMappingCoversRange() {
        assertEquals(50, ExposureScale.toValue(0, 100, 50, 3200));
        assertEquals(3200, ExposureScale.toValue(100, 100, 50, 3200));
        assertEquals(400, ExposureScale.toValue(50, 100, 50, 3200));
    }
    @Test public void formatsExposure() {
        assertEquals("1/60 s (16.7 ms)", ExposureScale.formatExposure(16_666_667L));
        assertEquals("1/500 s (2.0 ms)", ExposureScale.formatExposure(2_000_000L));
        assertEquals("150.0 ms", ExposureScale.formatExposure(150_000_000L));
    }
    @Test public void formatsFocus() {
        assertEquals("∞", ExposureScale.formatFocus(0f));
        assertEquals("0.50 D (2.0 m)", ExposureScale.formatFocus(0.5f));
        assertEquals("10.00 D (0.1 m)", ExposureScale.formatFocus(10f));
    }
}
```

`ExposureSweepTest`:

```java
package com.leakeye.mvp;
import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
public class ExposureSweepTest {
    @Test public void fold5RangeGivesSixteenCombos() {
        List<ExposureSettings> plan = ExposureSweep.plan(50, 3200, 32_544L, 150_002_608L, 0.5f);
        assertEquals(16, plan.size());
        assertEquals(50, plan.get(0).iso); assertEquals(2_000_000L, plan.get(0).exposureNs);
        assertEquals(50, plan.get(3).iso); assertEquals(150_000_000L, plan.get(3).exposureNs);
        assertEquals(3200, plan.get(15).iso);
        assertTrue(plan.get(0).manual);
        assertEquals(0.5f, plan.get(0).focusDiopters, 1e-6);
    }
    @Test public void clampsAndDedupesNarrowRange() {
        List<ExposureSettings> plan = ExposureSweep.plan(100, 800, 10_000_000L, 20_000_000L, 0f);
        // ISO {100,200,800} x exposure {10ms,16ms,20ms} = 9
        assertEquals(9, plan.size());
        assertEquals(100, plan.get(0).iso); assertEquals(10_000_000L, plan.get(0).exposureNs);
        assertEquals(800, plan.get(8).iso); assertEquals(20_000_000L, plan.get(8).exposureNs);
    }
    @Test public void inSweepCopiesIndex() {
        ExposureSettings s = ExposureSettings.manual(100, 1_000L, 0f).inSweep("S1", 3, 16);
        assertEquals("S1", s.sweepId); assertEquals(3, s.sweepIndex); assertEquals(16, s.sweepTotal);
        assertNull(ExposureSettings.auto().sweepId);
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew testDebugUnitTest -q` → 컴파일 오류.

- [ ] **Step 3: 구현**

`ExposureSettings.java`:
```java
package com.leakeye.mvp;
final class ExposureSettings {
    final boolean manual; final int iso; final long exposureNs; final float focusDiopters;
    final String sweepId; final int sweepIndex; final int sweepTotal;
    private ExposureSettings(boolean manual, int iso, long exposureNs, float focusDiopters, String sweepId, int sweepIndex, int sweepTotal) {
        this.manual = manual; this.iso = iso; this.exposureNs = exposureNs; this.focusDiopters = focusDiopters;
        this.sweepId = sweepId; this.sweepIndex = sweepIndex; this.sweepTotal = sweepTotal;
    }
    static ExposureSettings auto() { return new ExposureSettings(false, 0, 0L, 0f, null, 0, 0); }
    static ExposureSettings manual(int iso, long exposureNs, float focusDiopters) { return new ExposureSettings(true, iso, exposureNs, focusDiopters, null, 0, 0); }
    ExposureSettings inSweep(String id, int index, int total) { return new ExposureSettings(manual, iso, exposureNs, focusDiopters, id, index, total); }
}
```

`ExposureScale.java`:
```java
package com.leakeye.mvp;
import java.util.Locale;
final class ExposureScale {
    private ExposureScale() {}
    static long toValue(int pos, int max, long lo, long hi) {
        if (pos <= 0) return lo; if (pos >= max) return hi;
        return Math.round(lo * Math.pow((double) hi / lo, (double) pos / max));
    }
    static int toPosition(long value, int max, long lo, long hi) {
        if (value <= lo) return 0; if (value >= hi) return max;
        return (int) Math.round(max * Math.log((double) value / lo) / Math.log((double) hi / lo));
    }
    static String formatExposure(long ns) {
        double ms = ns / 1e6;
        if (ns < 1_000_000_000L) return String.format(Locale.US, "1/%d s (%.1f ms)", Math.round(1e9 / ns), ms);
        return String.format(Locale.US, "%.1f ms", ms);
    }
    static String formatFocus(float diopters) {
        if (diopters <= 0f) return "∞";
        return String.format(Locale.US, "%.2f D (%.1f m)", diopters, 1f / diopters);
    }
}
```
주의: 테스트 `formatsExposure`의 150 ms 케이스는 `ns < 1s`라 `1/7 s (150.0 ms)`가 된다. 스펙대로 "1초 미만은 1/N 형식"이므로 테스트 기대값을 `"1/7 s (150.0 ms)"`로 맞춘다.

`ExposureSweep.java`:
```java
package com.leakeye.mvp;
import java.util.ArrayList; import java.util.LinkedHashSet; import java.util.List; import java.util.Set;
final class ExposureSweep {
    static final int[] ISO_STEPS = {50, 200, 800, 3200};
    static final long[] EXPOSURE_STEPS_NS = {2_000_000L, 16_000_000L, 60_000_000L, 150_000_000L};
    private ExposureSweep() {}
    static List<ExposureSettings> plan(int isoLo, int isoHi, long expLo, long expHi, float focusDiopters) {
        Set<Integer> isos = new LinkedHashSet<>();
        for (int iso : ISO_STEPS) isos.add(Math.max(isoLo, Math.min(isoHi, iso)));
        Set<Long> exps = new LinkedHashSet<>();
        for (long e : EXPOSURE_STEPS_NS) exps.add(Math.max(expLo, Math.min(expHi, e)));
        List<ExposureSettings> out = new ArrayList<>();
        for (int iso : isos) for (long e : exps) out.add(ExposureSettings.manual(iso, e, focusDiopters));
        return out;
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew testDebugUnitTest -q` → EXIT=0, 결과 XML 3개 모두 failures=0.

---

### Task 2: `CaptureMetadata` request 섹션 (TDD)

- [ ] **Step 1: 테스트 추가** (`CaptureMetadataTest`)

```java
    @Test
    public void writesRequestSection() throws Exception {
        CaptureMetadata.Values v = sample();
        v.requestMode = "manual"; v.requestIso = 800; v.requestExposureNs = 16_000_000L; v.requestFocusDiopters = 0.5f;
        v.sweepId = "20260912_170000"; v.sweepIndex = 3; v.sweepTotal = 16;
        JSONObject r = CaptureMetadata.toJson(v).getJSONObject("request");
        assertEquals("manual", r.getString("mode"));
        assertEquals(800, r.getInt("iso"));
        assertEquals(16_000_000L, r.getLong("exposure_time_ns"));
        assertEquals(0.5, r.getDouble("focus_distance_diopters"), 1e-6);
        assertEquals("20260912_170000", r.getString("sweep_id"));
        assertEquals(3, r.getInt("sweep_index"));
        assertEquals(16, r.getInt("sweep_total"));
    }

    @Test
    public void autoRequestOmitsValues() throws Exception {
        CaptureMetadata.Values v = sample();
        v.requestMode = "auto";
        JSONObject r = CaptureMetadata.toJson(v).getJSONObject("request");
        assertEquals("auto", r.getString("mode"));
        assertFalse(r.has("iso")); assertFalse(r.has("sweep_id"));
    }
```

- [ ] **Step 2: 실패 확인** → 컴파일 오류(requestMode 없음).
- [ ] **Step 3: 구현** — `Values`에 `String requestMode; Integer requestIso; Long requestExposureNs; Float requestFocusDiopters; String sweepId; Integer sweepIndex; Integer sweepTotal;` 추가. `toJson`에 `capture` 섹션 뒤:

```java
        JSONObject request = new JSONObject();
        put(request, "mode", v.requestMode);
        put(request, "iso", v.requestIso);
        put(request, "exposure_time_ns", v.requestExposureNs);
        put(request, "focus_distance_diopters", v.requestFocusDiopters);
        put(request, "sweep_id", v.sweepId);
        put(request, "sweep_index", v.sweepIndex);
        put(request, "sweep_total", v.sweepTotal);
        root.put("request", request);
```
`collect`에 `ExposureSettings s` 파라미터 추가:
```java
        v.requestMode = s.manual ? "manual" : "auto";
        if (s.manual) { v.requestIso = s.iso; v.requestExposureNs = s.exposureNs; v.requestFocusDiopters = s.focusDiopters; }
        if (s.sweepId != null) { v.sweepId = s.sweepId; v.sweepIndex = s.sweepIndex; v.sweepTotal = s.sweepTotal; }
```
- [ ] **Step 4: 통과 확인**.

---

### Task 3: `ExposureControls` View + MainActivity 연결

- [ ] **Step 1: `ExposureControls.java`** (코드는 구현 파일 참조: Switch + 3×(TextView, SeekBar), `configure`, `current`, `setOnChanged`, `focusDiopters()`).
- [ ] **Step 2: MainActivity**
  - 필드: `ExposureControls controls; final ArrayDeque<ExposureSettings> queue = new ArrayDeque<>(); ExposureSettings inFlight; int sweepFailures;`
  - `buildUi`: status 아래 `controls` 추가, 버튼 2개("RAW 촬영", "노출 스윕 (16장)").
  - `openCamera`: characteristics 확인 후 `controls.configure(...)` (ISO 범위, 노출 범위, `LENS_INFO_MINIMUM_FOCUS_DISTANCE`).
  - `applySettings(builder, s)`; `startPreview`는 `applySettings(builder, controls.current())`; `controls.setOnChanged(this::updatePreview)`; `updatePreview`는 `cameraHandler.post`로 repeating request 재설정.
  - `captureRaw()` → `enqueue(List.of(controls.current()))`; `captureSweep()` → `ExposureSweep.plan(...)`을 `inSweep(stamp, i+1, n)`으로 enqueue.
  - `enqueue`는 `cameraHandler.post`로 큐에 넣고 `startNext()`; `startNext`는 pop → `applySettings` → `session.capture`.
  - `saveRaw` 파일명 `RAW_yyyyMMdd_HHmmss_SSS`; `collect(..., inFlight)`.
  - `finishCapture()`: `inFlight = null; startNext();` — `tryWriteMetadata` 성공/실패 뒤, RAW 저장 실패 뒤, `onCaptureFailed` 뒤 호출.
- [ ] **Step 3: 빌드** — `./gradlew assembleDebug testDebugUnitTest -q` → EXIT=0.

---

### Task 4: `tools/raw_report.py` (TDD, pytest)

- [ ] **Step 1: `tools/test_raw_report.py`**

```python
import numpy as np
from raw_report import stats, preview

def test_stats_subtracts_black_and_counts_saturation():
    arr = np.full((4, 4), 356, dtype=np.uint16)
    arr[0, 0] = 4095; arr[0, 1] = 4090
    s = stats(arr, black=256, white=4095)
    assert abs(s["mean_dn"] - ((356-256)*14 + (4095-256) + (4090-256)) / 16) < 1e-6
    assert s["sat_frac"] == 2 / 16
    assert s["p99_dn"] >= 100

def test_preview_bins_and_scales():
    arr = np.full((4, 4), 256, dtype=np.uint16)
    arr[2:, 2:] = 4095
    img = preview(arr, black=256, white=4095)
    assert img.shape == (2, 2) and img.dtype == np.uint8
    assert img[0, 0] == 0 and img[1, 1] == 255
```

- [ ] **Step 2: 실패 확인** — `cd tools && python -m pytest -q` → ImportError.
- [ ] **Step 3: 구현** — `stats`, `preview`, `main(folder)`: json 순회, raw 로드(`np.fromfile('<u2').reshape(h, w)`), 크기 불일치 시 경고·건너뜀, PNG 저장(Pillow, 1/2 비닝 후 추가 1/2 축소 = 1020×765), CSV 기록.
- [ ] **Step 4: 통과 확인**.

---

### Task 5: 기기 검증

- [ ] 수동 ISO 800 / 16 ms 1장: UI 덤프로 슬라이더 위치 설정 대신 스윕으로 대체 가능. 스윕 버튼 탭 → 약 20초 대기 → 16쌍 확인, JSON `request.iso`와 `capture.iso` 비교.
- [ ] 폴더 pull → `python tools/raw_report.py <폴더>` → `report.csv` 16행, PNG 16장.

### Task 6: README 갱신
- 현재 기능에 수동 노출·스윕·리포트 추가, 다음 단계에서 1번 제거, 사용법(adb pull + 스크립트) 추가.
