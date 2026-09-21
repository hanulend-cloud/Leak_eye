package com.leakeye.mvp;

/**
 * Camera2 CONTROL_AF_STATE 정수 값을 신뢰 가능(잠김) 여부로 분류한다. 순수 함수.
 * 값은 android.hardware.camera2.CaptureResult의 CONTROL_AF_STATE_* 상수와 동일하며,
 * plain JUnit 테스트를 위해 Android 프레임워크 의존 없이 정수 리터럴로 정의한다.
 */
final class AfStateClassifier {
    private AfStateClassifier() {}

    private static final int STATE_PASSIVE_FOCUSED = 2;
    private static final int STATE_FOCUSED_LOCKED = 4;

    static boolean isLocked(Integer afState) {
        if (afState == null) return false;
        return afState == STATE_FOCUSED_LOCKED || afState == STATE_PASSIVE_FOCUSED;
    }
}
