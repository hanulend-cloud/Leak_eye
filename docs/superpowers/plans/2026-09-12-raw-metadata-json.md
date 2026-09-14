# RAW 메타데이터 JSON 저장 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** RAW 프레임 저장 시 같은 촬영의 카메라 메타데이터를 같은 기본 이름의 `.json`으로 함께 저장한다.

**Architecture:** 새 클래스 `CaptureMetadata`가 원시값 홀더 `Values`를 `JSONObject`로 바꾸는 순수 함수(`toJson`)와 Android 객체를 읽는 `collect`를 제공한다. `MainActivity`는 `CaptureCallback`으로 `TotalCaptureResult`를 받고, Image 저장 시 떠 둔 `Frame` 스냅샷과 센서 타임스탬프로 짝지어 JSON을 쓴다. 두 콜백 모두 camera-thread에서 실행되므로 단일 대기 슬롯으로 잠금 없이 처리한다.

**Tech Stack:** Java, Camera2 API, `org.json`(Android 내장), JUnit 4 + `org.json:json`(로컬 단위 테스트용), Gradle 9.6, JAVA_HOME=`E:/Program install/Android studio/jbr`.

**참고:** 이 프로젝트는 git 저장소가 아니다. 커밋 단계는 생략한다. 스펙: `docs/superpowers/specs/2026-09-12-raw-metadata-json-design.md`.

---

## File Structure

- Modify `app/build.gradle` — 단위 테스트 의존성, `buildConfig true`.
- Create `app/src/main/java/com/leakeye/mvp/CaptureMetadata.java` — `Values`, `Frame`, `frameOf`, `collect`, `toJson`. 파일 I/O·UI 없음.
- Create `app/src/test/java/com/leakeye/mvp/CaptureMetadataTest.java` — `toJson` 검증.
- Modify `app/src/main/java/com/leakeye/mvp/MainActivity.java` — characteristics 보관, CaptureCallback, pending 슬롯, `tryWriteMetadata`.
- Modify `README.md` — 현재 기능/다음 단계 갱신.

---

### Task 1: 빌드 설정 (테스트 의존성 + BuildConfig)

**Files:**
- Modify: `app/build.gradle`

- [ ] **Step 1: `android {}` 안에 buildFeatures 추가, 파일 끝에 dependencies 추가**

```groovy
android {
    // ...기존 내용...
    buildFeatures {
        buildConfig true
    }
}

dependencies {
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.json:json:20240303'
}
```

- [ ] **Step 2: 테스트 태스크 동작 확인 (테스트 없음 상태)**

Run:
```bash
cd "E:/Vibe/Leak_eye" && JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --console=plain -q; echo EXIT=$?
```
Expected: `EXIT=0` (테스트 소스가 없어 NO-SOURCE로 통과).

---

### Task 2: `CaptureMetadata.Values` + `toJson` (TDD)

**Files:**
- Create: `app/src/test/java/com/leakeye/mvp/CaptureMetadataTest.java`
- Create: `app/src/main/java/com/leakeye/mvp/CaptureMetadata.java`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.leakeye.mvp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class CaptureMetadataTest {

    private static CaptureMetadata.Values sample() {
        CaptureMetadata.Values v = new CaptureMetadata.Values();
        v.rawFile = "RAW_20260912_161519.raw";
        v.capturedAt = "2026-09-12T16:15:19+09:00";
        v.manufacturer = "samsung"; v.model = "SM-F946N"; v.sdkInt = 35;
        v.versionName = "0.1.0"; v.versionCode = 1;
        v.cameraId = "0";
        v.pixelArraySize = new int[]{4080, 3060};
        v.activeArray = new int[]{0, 0, 4080, 3060};
        v.physicalSizeMm = new float[]{7.3f, 5.5f};
        v.focalLengthsMm = new float[]{6.3f};
        v.apertures = new float[]{1.8f};
        v.cfaPattern = 0;
        v.blackLevelPattern = new int[]{64, 64, 64, 64};
        v.whiteLevel = 4095;
        v.isoRange = new int[]{50, 3200};
        v.exposureTimeRangeNs = new long[]{10000L, 1000000000L};
        v.width = 4080; v.height = 3060; v.rowStride = 8160; v.pixelStride = 2;
        v.bytes = 24969600L; v.sensorTimestampNs = 123456789L;
        v.exposureTimeNs = 33333333L; v.iso = 100; v.aperture = 1.8f;
        v.focalLengthMm = 6.3f; v.focusDistanceDiopters = 0.5f;
        v.colorGains = new float[]{2.1f, 1.0f, 1.0f, 1.7f};
        v.colorTransform = new float[]{1.2f, -0.3f, 0.1f, -0.1f, 1.1f, 0.0f, 0.0f, -0.2f, 1.2f};
        v.neutralColorPoint = new float[]{0.47f, 1.0f, 0.61f};
        v.aeMode = 1; v.awbMode = 1;
        return v;
    }

    @Test
    public void writesSchemaAndSections() throws Exception {
        JSONObject j = CaptureMetadata.toJson(sample());
        assertEquals(1, j.getInt("schema_version"));
        assertEquals("RAW_20260912_161519.raw", j.getString("raw_file"));
        assertEquals("2026-09-12T16:15:19+09:00", j.getString("captured_at"));
        assertEquals("SM-F946N", j.getJSONObject("device").getString("model"));
        assertEquals(35, j.getJSONObject("device").getInt("sdk_int"));
        assertEquals("0.1.0", j.getJSONObject("app").getString("version_name"));
        JSONObject camera = j.getJSONObject("camera");
        assertEquals("0", camera.getString("id"));
        assertEquals(4080, camera.getJSONArray("pixel_array_size").getInt(0));
        assertEquals(4, camera.getJSONArray("active_array").length());
        assertEquals(4095, camera.getInt("white_level"));
        assertEquals(1000000000L, camera.getJSONArray("exposure_time_range_ns").getLong(1));
        JSONObject frame = j.getJSONObject("frame");
        assertEquals("RAW_SENSOR", frame.getString("format"));
        assertEquals(4080, frame.getInt("width"));
        assertEquals(3060, frame.getInt("height"));
        assertEquals(24969600L, frame.getLong("bytes"));
        assertEquals(123456789L, frame.getLong("sensor_timestamp_ns"));
        JSONObject capture = j.getJSONObject("capture");
        assertEquals(33333333L, capture.getLong("exposure_time_ns"));
        assertEquals(100, capture.getInt("iso"));
        assertEquals(1.8, capture.getDouble("aperture"), 1e-6);
        assertEquals(4, capture.getJSONArray("color_gains").length());
        assertEquals(9, capture.getJSONArray("color_transform").length());
        assertEquals(3, capture.getJSONArray("neutral_color_point").length());
        assertEquals(1, capture.getInt("awb_mode"));
    }

    @Test
    public void omitsNullFields() throws Exception {
        CaptureMetadata.Values v = sample();
        v.aperture = null;
        v.blackLevelPattern = null;
        v.focusDistanceDiopters = null;
        JSONObject j = CaptureMetadata.toJson(v);
        assertFalse(j.getJSONObject("capture").has("aperture"));
        assertFalse(j.getJSONObject("capture").has("focus_distance_diopters"));
        assertFalse(j.getJSONObject("camera").has("black_level_pattern"));
        assertTrue(j.getJSONObject("capture").has("iso"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
cd "E:/Vibe/Leak_eye" && JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --console=plain -q 2>&1 | tail -5; echo EXIT=${PIPESTATUS[0]}
```
Expected: 컴파일 오류 `cannot find symbol ... CaptureMetadata`, EXIT≠0.

- [ ] **Step 3: 최소 구현 (`Values` + `toJson`)**

`app/src/main/java/com/leakeye/mvp/CaptureMetadata.java`:

```java
package com.leakeye.mvp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 촬영 메타데이터를 JSON으로 만든다. 파일 I/O와 UI는 다루지 않는다. */
final class CaptureMetadata {
    static final int SCHEMA_VERSION = 1;

    private CaptureMetadata() {}

    /** 원시값만 담는 홀더. null 필드는 JSON에서 생략된다. */
    static final class Values {
        String rawFile; String capturedAt;
        String manufacturer; String model; Integer sdkInt;
        String versionName; Integer versionCode;
        String cameraId; int[] pixelArraySize; int[] activeArray; float[] physicalSizeMm;
        float[] focalLengthsMm; float[] apertures; Integer cfaPattern; int[] blackLevelPattern;
        Integer whiteLevel; int[] isoRange; long[] exposureTimeRangeNs;
        Integer width; Integer height; Integer rowStride; Integer pixelStride; Long bytes; Long sensorTimestampNs;
        Long exposureTimeNs; Integer iso; Float aperture; Float focalLengthMm; Float focusDistanceDiopters;
        float[] colorGains; float[] colorTransform; float[] neutralColorPoint; Integer aeMode; Integer awbMode;
    }

    static JSONObject toJson(Values v) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schema_version", SCHEMA_VERSION);
        put(root, "raw_file", v.rawFile);
        put(root, "captured_at", v.capturedAt);

        JSONObject device = new JSONObject();
        put(device, "manufacturer", v.manufacturer);
        put(device, "model", v.model);
        put(device, "sdk_int", v.sdkInt);
        root.put("device", device);

        JSONObject app = new JSONObject();
        put(app, "version_name", v.versionName);
        put(app, "version_code", v.versionCode);
        root.put("app", app);

        JSONObject camera = new JSONObject();
        put(camera, "id", v.cameraId);
        put(camera, "pixel_array_size", arr(v.pixelArraySize));
        put(camera, "active_array", arr(v.activeArray));
        put(camera, "physical_size_mm", arr(v.physicalSizeMm));
        put(camera, "focal_lengths_mm", arr(v.focalLengthsMm));
        put(camera, "apertures", arr(v.apertures));
        put(camera, "cfa_pattern", v.cfaPattern);
        put(camera, "black_level_pattern", arr(v.blackLevelPattern));
        put(camera, "white_level", v.whiteLevel);
        put(camera, "iso_range", arr(v.isoRange));
        put(camera, "exposure_time_range_ns", arr(v.exposureTimeRangeNs));
        root.put("camera", camera);

        JSONObject frame = new JSONObject();
        frame.put("format", "RAW_SENSOR");
        put(frame, "width", v.width);
        put(frame, "height", v.height);
        put(frame, "row_stride", v.rowStride);
        put(frame, "pixel_stride", v.pixelStride);
        put(frame, "bytes", v.bytes);
        put(frame, "sensor_timestamp_ns", v.sensorTimestampNs);
        root.put("frame", frame);

        JSONObject capture = new JSONObject();
        put(capture, "exposure_time_ns", v.exposureTimeNs);
        put(capture, "iso", v.iso);
        put(capture, "aperture", v.aperture);
        put(capture, "focal_length_mm", v.focalLengthMm);
        put(capture, "focus_distance_diopters", v.focusDistanceDiopters);
        put(capture, "color_gains", arr(v.colorGains));
        put(capture, "color_transform", arr(v.colorTransform));
        put(capture, "neutral_color_point", arr(v.neutralColorPoint));
        put(capture, "ae_mode", v.aeMode);
        put(capture, "awb_mode", v.awbMode);
        root.put("capture", capture);
        return root;
    }

    private static void put(JSONObject o, String key, Object value) throws JSONException {
        if (value != null) o.put(key, value);
    }

    private static JSONArray arr(int[] a) {
        if (a == null) return null;
        JSONArray j = new JSONArray();
        for (int x : a) j.put(x);
        return j;
    }

    private static JSONArray arr(long[] a) {
        if (a == null) return null;
        JSONArray j = new JSONArray();
        for (long x : a) j.put(x);
        return j;
    }

    private static JSONArray arr(float[] a) throws JSONException {
        if (a == null) return null;
        JSONArray j = new JSONArray();
        for (float x : a) j.put((double) x);
        return j;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run:
```bash
cd "E:/Vibe/Leak_eye" && JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --console=plain -q 2>&1 | tail -5; echo EXIT=${PIPESTATUS[0]}
```
Expected: `EXIT=0`. 결과 XML `app/build/test-results/testDebugUnitTest/TEST-com.leakeye.mvp.CaptureMetadataTest.xml`에 `tests="2" failures="0"`.

---

### Task 3: `Frame`, `frameOf`, `collect` (Android 객체 읽기)

단위 테스트 불가(Android 스텁). Task 5 기기 검증으로 확인한다.

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/CaptureMetadata.java`

- [ ] **Step 1: import 추가**

파일 상단 `import org.json.JSONArray;` 위에 추가:

```java
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.media.Image;
import android.os.Build;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
```

- [ ] **Step 2: `Frame`, `frameOf`, `collect` 추가**

`toJson` 메서드 바로 위에 추가:

```java
    /** Image가 닫히기 전에 떠 두는 프레임 스냅샷. */
    static final class Frame {
        final File rawFile; final int width; final int height; final int rowStride; final int pixelStride;
        final long bytes; final long timestampNs;

        Frame(File rawFile, int width, int height, int rowStride, int pixelStride, long bytes, long timestampNs) {
            this.rawFile = rawFile; this.width = width; this.height = height;
            this.rowStride = rowStride; this.pixelStride = pixelStride;
            this.bytes = bytes; this.timestampNs = timestampNs;
        }
    }

    static Frame frameOf(Image image, File rawFile, long bytes) {
        Image.Plane plane = image.getPlanes()[0];
        return new Frame(rawFile, image.getWidth(), image.getHeight(),
                plane.getRowStride(), plane.getPixelStride(), bytes, image.getTimestamp());
    }

    static Values collect(CameraCharacteristics c, TotalCaptureResult r, Frame f,
                          String cameraId, String versionName, int versionCode) {
        Values v = new Values();
        v.rawFile = f.rawFile.getName();
        v.capturedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());
        v.manufacturer = Build.MANUFACTURER;
        v.model = Build.MODEL;
        v.sdkInt = Build.VERSION.SDK_INT;
        v.versionName = versionName;
        v.versionCode = versionCode;

        v.cameraId = cameraId;
        Size pas = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (pas != null) v.pixelArraySize = new int[]{pas.getWidth(), pas.getHeight()};
        Rect aa = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (aa != null) v.activeArray = new int[]{aa.left, aa.top, aa.width(), aa.height()};
        SizeF ps = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (ps != null) v.physicalSizeMm = new float[]{ps.getWidth(), ps.getHeight()};
        v.focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        v.apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        v.cfaPattern = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        BlackLevelPattern blp = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        if (blp != null) { int[] bl = new int[4]; blp.copyTo(bl, 0); v.blackLevelPattern = bl; }
        v.whiteLevel = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        Range<Integer> isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (isoRange != null) v.isoRange = new int[]{isoRange.getLower(), isoRange.getUpper()};
        Range<Long> expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (expRange != null) v.exposureTimeRangeNs = new long[]{expRange.getLower(), expRange.getUpper()};

        v.width = f.width; v.height = f.height; v.rowStride = f.rowStride; v.pixelStride = f.pixelStride;
        v.bytes = f.bytes; v.sensorTimestampNs = f.timestampNs;

        v.exposureTimeNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        v.iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
        v.aperture = r.get(CaptureResult.LENS_APERTURE);
        v.focalLengthMm = r.get(CaptureResult.LENS_FOCAL_LENGTH);
        v.focusDistanceDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE);
        RggbChannelVector gains = r.get(CaptureResult.COLOR_CORRECTION_GAINS);
        if (gains != null) { float[] g = new float[4]; gains.copyTo(g, 0); v.colorGains = g; }
        ColorSpaceTransform cst = r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        if (cst != null) {
            float[] m = new float[9];
            for (int row = 0; row < 3; row++)
                for (int col = 0; col < 3; col++)
                    m[row * 3 + col] = cst.getElement(col, row).floatValue();
            v.colorTransform = m;
        }
        Rational[] ncp = r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        if (ncp != null && ncp.length == 3)
            v.neutralColorPoint = new float[]{ncp[0].floatValue(), ncp[1].floatValue(), ncp[2].floatValue()};
        v.aeMode = r.get(CaptureResult.CONTROL_AE_MODE);
        v.awbMode = r.get(CaptureResult.CONTROL_AWB_MODE);
        return v;
    }
```

- [ ] **Step 3: 컴파일 및 단위 테스트 유지 확인**

Run:
```bash
cd "E:/Vibe/Leak_eye" && JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew testDebugUnitTest --console=plain -q 2>&1 | grep -vE "deprecated|Xlint" | tail -5; echo EXIT=${PIPESTATUS[0]}
```
Expected: `EXIT=0`.

---

### Task 4: MainActivity 연결

**Files:**
- Modify: `app/src/main/java/com/leakeye/mvp/MainActivity.java`

- [ ] **Step 1: import 추가**

기존 `import android.hardware.camera2.CaptureRequest;` 아래에:

```java
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import org.json.JSONException;
import java.nio.charset.StandardCharsets;
```

- [ ] **Step 2: 필드 추가**

`private boolean rawSupported;` 아래에:

```java
    private CameraCharacteristics characteristics;
    private TotalCaptureResult pendingResult;
    private CaptureMetadata.Frame pendingFrame;
```

- [ ] **Step 3: openCamera에서 characteristics 보관**

```java
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
```
을
```java
            characteristics = manager.getCameraCharacteristics(cameraId);
```
로 변경.

- [ ] **Step 4: CaptureCallback 추가**

`stateCallback` 선언 블록 바로 아래에:

```java
    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            pendingResult = result;
            tryWriteMetadata();
        }
    };
```

- [ ] **Step 5: captureRaw에서 콜백 등록**

```java
            session.capture(builder.build(), null, cameraHandler);
```
을
```java
            session.capture(builder.build(), captureCallback, cameraHandler);
```
로 변경.

- [ ] **Step 6: saveRaw에서 Frame 스냅샷 + 상태 문구 변경**

```java
            output.write(bytes);
            runOnUiThread(() -> status.setText("저장 완료: " + file.getName()));
```
을
```java
            output.write(bytes);
            pendingFrame = CaptureMetadata.frameOf(image, file, bytes.length);
            runOnUiThread(() -> status.setText("저장 완료: " + file.getName() + " (메타데이터 대기)"));
            tryWriteMetadata();
```
로 변경.

- [ ] **Step 7: tryWriteMetadata 추가**

`saveRaw` 메서드 아래에:

```java
    /** camera-thread에서만 호출된다. Image와 CaptureResult가 모두 준비되면 JSON을 쓴다. */
    private void tryWriteMetadata() {
        if (pendingResult == null || pendingFrame == null) return;
        Long resultTs = pendingResult.get(CaptureResult.SENSOR_TIMESTAMP);
        if (resultTs == null || resultTs != pendingFrame.timestampNs) {
            if (resultTs != null && resultTs < pendingFrame.timestampNs) pendingResult = null; else pendingFrame = null;
            return;
        }
        CaptureMetadata.Frame frame = pendingFrame;
        TotalCaptureResult result = pendingResult;
        pendingFrame = null;
        pendingResult = null;
        String base = frame.rawFile.getName().replaceAll("\\.raw$", "");
        File json = new File(frame.rawFile.getParentFile(), base + ".json");
        try {
            CaptureMetadata.Values values = CaptureMetadata.collect(characteristics, result, frame,
                    cameraId, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
            String text = CaptureMetadata.toJson(values).toString(2);
            try (FileOutputStream output = new FileOutputStream(json)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            runOnUiThread(() -> status.setText("저장 완료: " + frame.rawFile.getName() + " + json"));
        } catch (IOException | JSONException e) {
            runOnUiThread(() -> status.setText("메타데이터 저장 실패: " + e.getMessage()));
        }
    }
```

- [ ] **Step 8: 빌드 확인**

Run:
```bash
cd "E:/Vibe/Leak_eye" && JAVA_HOME="E:/Program install/Android studio/jbr" ./gradlew assembleDebug testDebugUnitTest --console=plain -q 2>&1 | grep -vE "deprecated|Xlint" | tail -8; echo EXIT=${PIPESTATUS[0]}
```
Expected: `EXIT=0`.

---

### Task 5: 기기 검증 (Fold5)

**Files:** 없음 (adb + python)

- [ ] **Step 1: 설치·실행·촬영**

Run:
```bash
export MSYS_NO_PATHCONV=1; S="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"; cd "E:/Vibe/Leak_eye"
"$S" install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
"$S" shell am force-stop com.leakeye.mvp; "$S" shell am start -n com.leakeye.mvp/.MainActivity >/dev/null; sleep 4
"$S" shell input tap 452 2103; sleep 6
"$S" shell uiautomator dump /sdcard/ui.xml >/dev/null; "$S" shell cat /sdcard/ui.xml | tr '>' '>\n' | grep -oE 'text="[^"]+"' | sed -n 2p
"$S" shell ls -la /sdcard/Android/data/com.leakeye.mvp/files/Pictures/measurements/
```
Expected: 상태 `저장 완료: RAW_<stamp>.raw + json`, 목록에 같은 stamp의 `.raw`와 `.json`.

- [ ] **Step 2: JSON 내용 검증**

`check_meta.py` (스크래치패드에 저장):
```python
import json, sys
j = json.load(open(sys.argv[1], encoding="utf-8")); raw_size = int(sys.argv[2])
c, f, cam = j["capture"], j["frame"], j["camera"]
assert j["schema_version"] == 1
assert c["exposure_time_ns"] > 0 and c["iso"] > 0, c
assert (f["width"], f["height"]) == (4080, 3060), f
assert f["bytes"] == raw_size, (f["bytes"], raw_size)
assert cam["white_level"] > 0 and len(cam["black_level_pattern"]) == 4, cam
assert len(c["color_gains"]) == 4 and len(c["color_transform"]) == 9
print("OK exposure_ns", c["exposure_time_ns"], "iso", c["iso"], "aperture", c.get("aperture"),
      "white", cam["white_level"], "black", cam["black_level_pattern"])
```

Run:
```bash
export MSYS_NO_PATHCONV=1; S="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"; D=/sdcard/Android/data/com.leakeye.mvp/files/Pictures/measurements
J=$("$S" shell ls $D | grep json | tail -1 | tr -d '\r')
"$S" shell cat $D/$J > "$SCRATCH/meta.json"
RAWSIZE=$("$S" shell stat -c %s $D/${J%.json}.raw | tr -d '\r')
"E:/Anaconda3/python.exe" "$SCRATCH/check_meta.py" "$SCRATCH/meta.json" "$RAWSIZE"
```
Expected: `OK exposure_ns ... iso ...` 출력, assert 없음.

---

### Task 6: README 갱신

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 현재 기능에 항목 추가, 다음 단계에서 1번 제거**

"## 현재 기능" 목록의 `- 측정 프레임 저장 상태 표시` 아래에:
```
- RAW와 같은 이름의 `.json`에 카메라 메타데이터(노출·ISO·조리개·색보정·블랙/화이트 레벨·센서 정보) 저장
```
"## 다음 구현 단계"를 다음으로 교체:
```
1. 수동 ISO·셔터·초점 고정
2. 화면 마커 기반 거리·각도 가이드
3. ROI 등록 및 휘도 캘리브레이션 분석
```

- [ ] **Step 2: 확인**

Run: `grep -n "json\|^1\.\|^2\.\|^3\." "E:/Vibe/Leak_eye/README.md"`
Expected: json 항목 1줄, 다음 단계 3줄.
