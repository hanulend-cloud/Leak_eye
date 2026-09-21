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
