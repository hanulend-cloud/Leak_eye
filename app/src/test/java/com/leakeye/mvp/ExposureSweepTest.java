package com.leakeye.mvp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class ExposureSweepTest {
    @Test
    public void fold5RangeGivesSixteenCombos() {
        List<ExposureSettings> plan = ExposureSweep.plan(50, 3200, 32_544L, 150_002_608L, 0.5f);
        assertEquals(16, plan.size());
        assertEquals(50, plan.get(0).iso);
        assertEquals(2_000_000L, plan.get(0).exposureNs);
        assertEquals(50, plan.get(3).iso);
        assertEquals(150_000_000L, plan.get(3).exposureNs);
        assertEquals(3200, plan.get(15).iso);
        assertTrue(plan.get(0).manual);
        assertEquals(0.5f, plan.get(0).focusDiopters, 1e-6);
    }

    @Test
    public void clampsAndDedupesNarrowRange() {
        List<ExposureSettings> plan = ExposureSweep.plan(100, 800, 10_000_000L, 20_000_000L, 0f);
        assertEquals(9, plan.size());
        assertEquals(100, plan.get(0).iso);
        assertEquals(10_000_000L, plan.get(0).exposureNs);
        assertEquals(800, plan.get(8).iso);
        assertEquals(20_000_000L, plan.get(8).exposureNs);
    }

    @Test
    public void inSweepCopiesIndex() {
        ExposureSettings s = ExposureSettings.manual(100, 1_000L, 0f).inSweep("S1", 3, 16);
        assertEquals("S1", s.sweepId);
        assertEquals(3, s.sweepIndex);
        assertEquals(16, s.sweepTotal);
        assertTrue(s.manual);
        assertNull(ExposureSettings.auto().sweepId);
    }
}
