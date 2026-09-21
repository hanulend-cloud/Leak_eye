package com.leakeye.mvp;

/** 화면 터치 좌표를 크롭 영역 기준 활성 배열 좌표의 정사각형 AF 리전으로 변환한다. 순수 함수. */
final class TapFocusMapper {
    private TapFocusMapper() {}

    /**
     * @param tapX, tapY   뷰 픽셀 좌표의 터치 지점
     * @param viewW, viewH 뷰(프리뷰) 크기
     * @param cropLeft, cropTop, cropWidth, cropHeight 현재 SCALER_CROP_REGION
     * @param regionSize   반환할 정사각형 AF 리전의 한 변 (활성 배열 좌표계 픽셀)
     * @return {left, top, width, height} — 활성 배열 좌표계, 크롭 영역 안쪽으로 클램프됨
     */
    static int[] mapTapToAfRegion(float tapX, float tapY, int viewW, int viewH,
                                   int cropLeft, int cropTop, int cropWidth, int cropHeight,
                                   int regionSize) {
        float nx = clamp01(tapX / viewW);
        float ny = clamp01(tapY / viewH);
        float sensorX = cropLeft + nx * cropWidth;
        float sensorY = cropTop + ny * cropHeight;
        int half = regionSize / 2;
        int left = clampInt(Math.round(sensorX) - half, cropLeft, cropLeft + cropWidth - regionSize);
        int top = clampInt(Math.round(sensorY) - half, cropTop, cropTop + cropHeight - regionSize);
        return new int[]{left, top, regionSize, regionSize};
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
