package com.leakeye.mvp;

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
        String requestMode; Integer requestIso; Long requestExposureNs; Float requestFocusDiopters;
        String sweepId; Integer sweepIndex; Integer sweepTotal;
        Float distanceM; String distanceSource; Float tiltPitchDeg; Float tiltRollDeg;
        Float illuminanceLux;
    }

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

    static Values collect(CameraCharacteristics c, TotalCaptureResult r, Frame f, ExposureSettings s,
                          String cameraId, String versionName, int versionCode, float pitchDeg, float rollDeg,
                          float illuminanceLux) {
        Values v = new Values();
        v.requestMode = s.manual ? "manual" : "auto";
        if (s.manual) { v.requestIso = s.iso; v.requestExposureNs = s.exposureNs; v.requestFocusDiopters = s.focusDiopters; }
        if (s.sweepId != null) { v.sweepId = s.sweepId; v.sweepIndex = s.sweepIndex; v.sweepTotal = s.sweepTotal; }
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

        Float focusDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (focusDiopters != null) {
            v.distanceM = ExposureScale.distanceMeters(focusDiopters);
            if (v.distanceM != null) v.distanceSource = "af_diopters";
        }
        if (!Float.isNaN(pitchDeg)) v.tiltPitchDeg = pitchDeg;
        if (!Float.isNaN(rollDeg)) v.tiltRollDeg = rollDeg;
        if (!Float.isNaN(illuminanceLux)) v.illuminanceLux = illuminanceLux;
        return v;
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

        JSONObject request = new JSONObject();
        put(request, "mode", v.requestMode);
        put(request, "iso", v.requestIso);
        put(request, "exposure_time_ns", v.requestExposureNs);
        put(request, "focus_distance_diopters", v.requestFocusDiopters);
        put(request, "sweep_id", v.sweepId);
        put(request, "sweep_index", v.sweepIndex);
        put(request, "sweep_total", v.sweepTotal);
        root.put("request", request);

        JSONObject pose = new JSONObject();
        put(pose, "distance_m", v.distanceM);
        put(pose, "distance_source", v.distanceSource);
        put(pose, "tilt_pitch_deg", v.tiltPitchDeg);
        put(pose, "tilt_roll_deg", v.tiltRollDeg);
        root.put("pose", pose);

        JSONObject environment = new JSONObject();
        put(environment, "illuminance_lx", v.illuminanceLux);
        root.put("environment", environment);
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
