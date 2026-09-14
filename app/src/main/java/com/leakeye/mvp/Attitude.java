package com.leakeye.mvp;

import android.hardware.SensorManager;

/**
 * 회전벡터 센서값을 "세로로 들고 촬영" 기준 pitch/roll(도)로 변환한다.
 * SensorManager의 정적 메서드에 의존해 로컬 JUnit에서는 실행할 수 없다(안드로이드 스텁) — 기기에서 검증한다.
 */
final class Attitude {
    private Attitude() {}

    /** rotationVector: TYPE_GAME_ROTATION_VECTOR 또는 TYPE_ROTATION_VECTOR의 SensorEvent.values. 반환: {pitchDeg, rollDeg}. */
    static float[] fromRotationVector(float[] rotationVector) {
        float[] rotationMatrix = new float[9];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);
        float[] remapped = new float[9];
        SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped);
        float[] orientation = new float[3];
        SensorManager.getOrientation(remapped, orientation);
        float pitchDeg = (float) Math.toDegrees(orientation[1]);
        float rollDeg = (float) Math.toDegrees(orientation[2]);
        return new float[]{pitchDeg, rollDeg};
    }
}
