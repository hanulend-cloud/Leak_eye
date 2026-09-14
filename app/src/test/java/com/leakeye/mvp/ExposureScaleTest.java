package com.leakeye.mvp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ExposureScaleTest {
    @Test
    public void logMappingRoundTrips() {
        long lo = 32_544L, hi = 150_002_608L;
        assertEquals(lo, ExposureScale.toValue(0, 1000, lo, hi));
        assertEquals(hi, ExposureScale.toValue(1000, 1000, lo, hi));
        long mid = ExposureScale.toValue(500, 1000, lo, hi);
        assertEquals(500, ExposureScale.toPosition(mid, 1000, lo, hi));
        assertEquals(1000, ExposureScale.toPosition(hi, 1000, lo, hi));
        assertEquals(0, ExposureScale.toPosition(lo, 1000, lo, hi));
    }

    @Test
    public void isoMappingCoversRange() {
        assertEquals(50, ExposureScale.toValue(0, 100, 50, 3200));
        assertEquals(3200, ExposureScale.toValue(100, 100, 50, 3200));
        assertEquals(400, ExposureScale.toValue(50, 100, 50, 3200));
    }

    @Test
    public void formatsExposure() {
        assertEquals("1/60 s (16.7 ms)", ExposureScale.formatExposure(16_666_667L));
        assertEquals("1/500 s (2.0 ms)", ExposureScale.formatExposure(2_000_000L));
        assertEquals("1/7 s (150.0 ms)", ExposureScale.formatExposure(150_000_000L));
        assertEquals("1000.0 ms", ExposureScale.formatExposure(1_000_000_000L));
    }

    @Test
    public void distanceMetersInvertsDiopters() {
        assertEquals(2.0f, ExposureScale.distanceMeters(0.5f), 1e-6);
        assertEquals(0.1f, ExposureScale.distanceMeters(10f), 1e-6);
        assertNull(ExposureScale.distanceMeters(0f));
        assertNull(ExposureScale.distanceMeters(-1f));
    }

    @Test
    public void formatsFocus() {
        assertEquals("∞", ExposureScale.formatFocus(0f));
        assertEquals("0.50 D (2.0 m)", ExposureScale.formatFocus(0.5f));
        assertEquals("10.00 D (0.1 m)", ExposureScale.formatFocus(10f));
    }
}
