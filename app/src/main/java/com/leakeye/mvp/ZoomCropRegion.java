package com.leakeye.mvp;

/** 활성 배열 영역을 중앙 정렬로 축소해 디지털 줌 크롭 영역을 계산한다. 순수 함수. */
final class ZoomCropRegion {
    private ZoomCropRegion() {}

    /**
     * (left, top, right, bottom)로 정의된 사각형을 가로/세로 각각 1/factor 크기로 줄이고
     * 원래 사각형과 같은 중심을 갖도록 배치한 새 사각형을 {left, top, right, bottom}으로 반환한다.
     */
    static int[] centeredCrop(int left, int top, int right, int bottom, float factor) {
        int width = right - left;
        int height = bottom - top;
        int newWidth = Math.round(width / factor);
        int newHeight = Math.round(height / factor);
        int centerX = left + width / 2;
        int centerY = top + height / 2;
        int newLeft = centerX - newWidth / 2;
        int newTop = centerY - newHeight / 2;
        return new int[]{newLeft, newTop, newLeft + newWidth, newTop + newHeight};
    }
}
