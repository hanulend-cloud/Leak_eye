package com.leakeye.mvp;

/** ARGB 픽셀 배열의 평균 루마(0-255)를 계산한다. 순수 함수, Android 의존 없음. */
final class LumaMath {
    private LumaMath() {}

    static int averageLuma(int[] pixels) {
        if (pixels == null || pixels.length == 0) return 0;
        long sum = 0;
        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            sum += Math.round(0.299 * r + 0.587 * g + 0.114 * b);
        }
        return (int) (sum / pixels.length);
    }
}
