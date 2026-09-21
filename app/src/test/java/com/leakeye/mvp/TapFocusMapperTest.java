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
