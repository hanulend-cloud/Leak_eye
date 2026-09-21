package com.leakeye.mvp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SquareGeometryTest {
    @Test
    public void squareSideUsesShorterDimension() {
        assertEquals(90, SquareGeometry.squareSide(900, 1200, 0.10f));
        assertEquals(120, SquareGeometry.squareSide(1200, 1500, 0.10f));
    }

    @Test
    public void clampCenterKeepsSquareInsideBounds() {
        assertEquals(50f, SquareGeometry.clampCenter(10f, 50f, 1000f), 1e-6);
        assertEquals(950f, SquareGeometry.clampCenter(990f, 50f, 1000f), 1e-6);
        assertEquals(500f, SquareGeometry.clampCenter(500f, 50f, 1000f), 1e-6);
    }

    @Test
    public void clampCenterForcesMidpointWhenSquareLargerThanDimension() {
        assertEquals(500f, SquareGeometry.clampCenter(10f, 600f, 1000f), 1e-6);
    }
}
