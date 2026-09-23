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
}
