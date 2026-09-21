package com.leakeye.mvp;

/** 평가영역 정사각형의 변 길이 계산과 화면 경계 클램프. 순수 함수. */
final class SquareGeometry {
    private SquareGeometry() {}

    /** 뷰의 짧은 변 기준 percent(예: 0.10f)만큼의 정사각형 한 변(px)을 반환한다. */
    static int squareSide(int viewWidth, int viewHeight, float percent) {
        return Math.round(Math.min(viewWidth, viewHeight) * percent);
    }

    /**
     * value를 중심으로 한 변이 halfSide*2인 구간이 [0, dimension] 안에 들어오도록 클램프한다.
     * 구간이 dimension보다 크면 dimension의 중점을 반환한다.
     */
    static float clampCenter(float value, float halfSide, float dimension) {
        if (halfSide * 2 > dimension) return dimension / 2f;
        if (value - halfSide < 0) return halfSide;
        if (value + halfSide > dimension) return dimension - halfSide;
        return value;
    }
}
