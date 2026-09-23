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

    /** 평균/상위 1% 평균(peak)/평균 초과 픽셀 수(brightAreaPx)를 한 번의 순회로 계산한다. */
    static final class Stats {
        final int average;
        final int peak;
        final int brightAreaPx;

        Stats(int average, int peak, int brightAreaPx) {
            this.average = average;
            this.peak = peak;
            this.brightAreaPx = brightAreaPx;
        }
    }

    static Stats compute(int[] pixels) {
        if (pixels == null || pixels.length == 0) return new Stats(0, 0, 0);
        int[] histogram = new int[256];
        long sum = 0;
        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            int luma = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
            histogram[luma]++;
            sum += luma;
        }
        int count = pixels.length;
        int average = (int) (sum / count);

        int topCount = Math.max(1, (int) Math.ceil(count * 0.01));
        long peakSum = 0;
        int collected = 0;
        for (int luma = 255; luma >= 0 && collected < topCount; luma--) {
            int take = Math.min(histogram[luma], topCount - collected);
            peakSum += (long) luma * take;
            collected += take;
        }
        int peak = collected > 0 ? (int) (peakSum / collected) : 0;

        int brightAreaPx = 0;
        for (int luma = average + 1; luma <= 255; luma++) brightAreaPx += histogram[luma];

        return new Stats(average, peak, brightAreaPx);
    }
}
