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
