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

    @Test
    public void mapToBitmapScalesCenterAndSideByFraction() {
        // 뷰 1000x1000 중심(500,500) -> 비트맵 4000x3000(다른 해상도/비율)
        float[] mapped = SquareGeometry.mapToBitmap(500f, 500f, 1000, 1000, 4000, 3000, 0.10f);
        assertEquals(2000f, mapped[0], 1e-3); // centerX
        assertEquals(1500f, mapped[1], 1e-3); // centerY
        assertEquals(300f, mapped[2], 1e-3);  // side = min(4000,3000)*0.10
    }

    @Test
    public void mapToBitmapHandlesOffCenterPoint() {
        float[] mapped = SquareGeometry.mapToBitmap(250f, 750f, 1000, 1000, 2000, 2000, 0.20f);
        assertEquals(500f, mapped[0], 1e-3);
        assertEquals(1500f, mapped[1], 1e-3);
        assertEquals(400f, mapped[2], 1e-3);
    }
}
