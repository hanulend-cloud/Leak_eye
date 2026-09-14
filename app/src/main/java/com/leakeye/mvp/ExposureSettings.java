package com.leakeye.mvp;

/** 한 번의 촬영에 적용할 노출 설정. 불변. */
final class ExposureSettings {
    final boolean manual;
    final int iso;
    final long exposureNs;
    final float focusDiopters;
    /** null이면 단일 촬영. */
    final String sweepId;
    final int sweepIndex;
    final int sweepTotal;

    private ExposureSettings(boolean manual, int iso, long exposureNs, float focusDiopters,
                             String sweepId, int sweepIndex, int sweepTotal) {
        this.manual = manual; this.iso = iso; this.exposureNs = exposureNs; this.focusDiopters = focusDiopters;
        this.sweepId = sweepId; this.sweepIndex = sweepIndex; this.sweepTotal = sweepTotal;
    }

    static ExposureSettings auto() {
        return new ExposureSettings(false, 0, 0L, 0f, null, 0, 0);
    }

    static ExposureSettings manual(int iso, long exposureNs, float focusDiopters) {
        return new ExposureSettings(true, iso, exposureNs, focusDiopters, null, 0, 0);
    }

    ExposureSettings inSweep(String id, int index, int total) {
        return new ExposureSettings(manual, iso, exposureNs, focusDiopters, id, index, total);
    }

    String describe() {
        if (!manual) return "자동 노출";
        return "ISO " + iso + ", " + ExposureScale.formatExposure(exposureNs) + ", 초점 " + ExposureScale.formatFocus(focusDiopters);
    }
}
