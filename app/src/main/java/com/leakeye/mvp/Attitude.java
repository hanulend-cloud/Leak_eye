package com.leakeye.mvp;

import android.hardware.SensorManager;

/**
 * 회전벡터 센서값을 "수평(바닥에 눕혔을 때)" 기준 pitch/roll(도)로 변환한다.
 * 0도는 휴대폰이 수평면에 놓였을 때이며, 세워 들면 ±90도 근처가 된다.
 * SensorManager의 정적 메서드에 의존해 로컬 JUnit에서는 실행할 수 없다(안드로이드 스텁) — 기기에서 검증한다.
 */
final class Attitude {
    private Attitude() {}

    /** rotationVector: TYPE_GAME_ROTATION_VECTOR 또는 TYPE_ROTATION_VECTOR의 SensorEvent.values. 반환: {pitchDeg, rollDeg}. */
    static float[] fromRotationVector(float[] rotationVector) {
        float[] rotationMatrix = new float[9];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);
        float[] orientation = new float[3];
        SensorManager.getOrientation(rotationMatrix, orientation);
        float pitchDeg = (float) Math.toDegrees(orientation[1]);
        float rollDeg = (float) Math.toDegrees(orientation[2]);
        return new float[]{pitchDeg, rollDeg};
    }
}
