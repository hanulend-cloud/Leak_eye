package com.leakeye.mvp;

import java.util.Locale;

/** 로그 스케일 슬라이더 매핑과 표시 문자열. 순수 함수. */
final class ExposureScale {
    private ExposureScale() {}

    /** pos/max 비율을 [lo, hi] 로그 스케일 값으로. */
    static long toValue(int pos, int max, long lo, long hi) {
        if (pos <= 0) return lo;
        if (pos >= max) return hi;
        return Math.round(lo * Math.pow((double) hi / lo, (double) pos / max));
    }

    static int toPosition(long value, int max, long lo, long hi) {
        if (value <= lo) return 0;
        if (value >= hi) return max;
        return (int) Math.round(max * Math.log((double) value / lo) / Math.log((double) hi / lo));
    }

    static String formatExposure(long ns) {
        double ms = ns / 1e6;
        if (ns < 1_000_000_000L) return String.format(Locale.US, "1/%d s (%.1f ms)", Math.round(1e9 / ns), ms);
        return String.format(Locale.US, "%.1f ms", ms);
    }

    static String formatFocus(float diopters) {
        if (diopters <= 0f) return "∞";
        return String.format(Locale.US, "%.2f D (%.1f m)", diopters, 1f / diopters);
    }

    /** 초점거리(diopters)를 미터로 역산한다. 0 이하(무한대)면 null. */
    static Float distanceMeters(float diopters) {
        if (diopters <= 0f) return null;
        return 1f / diopters;
    }
}
