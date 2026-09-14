package com.leakeye.mvp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 노출 스윕 촬영 조합 계획. 순수 함수. */
final class ExposureSweep {
    static final int[] ISO_STEPS = {50, 200, 800, 3200};
    static final long[] EXPOSURE_STEPS_NS = {2_000_000L, 16_000_000L, 60_000_000L, 150_000_000L};

    private ExposureSweep() {}

    /** ISO 후보 × 셔터 후보를 범위로 클램프하고 중복을 제거해 ISO 오름차순, 셔터 오름차순으로 나열한다. */
    static List<ExposureSettings> plan(int isoLo, int isoHi, long expLo, long expHi, float focusDiopters) {
        Set<Integer> isos = new LinkedHashSet<>();
        for (int iso : ISO_STEPS) isos.add(Math.max(isoLo, Math.min(isoHi, iso)));
        Set<Long> exps = new LinkedHashSet<>();
        for (long e : EXPOSURE_STEPS_NS) exps.add(Math.max(expLo, Math.min(expHi, e)));
        List<ExposureSettings> out = new ArrayList<>();
        for (int iso : isos) for (long e : exps) out.add(ExposureSettings.manual(iso, e, focusDiopters));
        return out;
    }
}
