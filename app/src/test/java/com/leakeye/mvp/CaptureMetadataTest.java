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
        assertFalse(r.has("iso"));
        assertFalse(r.has("sweep_id"));
    }

    @Test
    public void writesPoseSection() throws Exception {
        CaptureMetadata.Values v = sample();
        v.distanceM = 0.51f; v.distanceSource = "af_diopters"; v.tiltPitchDeg = -2.3f; v.tiltRollDeg = 0.8f;
        JSONObject p = CaptureMetadata.toJson(v).getJSONObject("pose");
        assertEquals(0.51, p.getDouble("distance_m"), 1e-6);
        assertEquals("af_diopters", p.getString("distance_source"));
        assertEquals(-2.3, p.getDouble("tilt_pitch_deg"), 1e-6);
        assertEquals(0.8, p.getDouble("tilt_roll_deg"), 1e-6);
    }

    @Test
    public void omitsPoseFieldsWhenUnavailable() throws Exception {
        JSONObject p = CaptureMetadata.toJson(sample()).getJSONObject("pose");
        assertFalse(p.has("distance_m"));
        assertFalse(p.has("tilt_pitch_deg"));
    }

    @Test
    public void writesEnvironmentSection() throws Exception {
        CaptureMetadata.Values v = sample();
        v.illuminanceLux = 12.34f;
        JSONObject e = CaptureMetadata.toJson(v).getJSONObject("environment");
        assertEquals(12.34, e.getDouble("illuminance_lx"), 1e-6);
    }

    @Test
    public void omitsIlluminanceWhenUnavailable() throws Exception {
        JSONObject e = CaptureMetadata.toJson(sample()).getJSONObject("environment");
        assertFalse(e.has("illuminance_lx"));
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
