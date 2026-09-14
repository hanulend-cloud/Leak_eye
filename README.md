# leak_eye MVP

Galaxy Z Fold5 기준 Camera2 RAW 촬영 PoC입니다.

## 현재 기능

- 후면 카메라 자동 선택
- RAW capability 확인
- 카메라 미리보기
- RAW_SENSOR 프레임 저장
- 측정 프레임 저장 상태 표시
- RAW와 같은 이름의 `.json`에 카메라 메타데이터(노출·ISO·조리개·색보정·블랙/화이트 레벨·센서 정보) 저장
- 수동 노출 스위치: ISO·셔터·초점을 직접 정해 촬영 (요청값과 실제 적용값이 모두 JSON에 기록됨)
- 노출 스윕 촬영: ISO {50,200,800,3200} × 셔터 {2,16,60,150ms} 16장을 자동 연속 촬영
- 백그라운드/포그라운드 전환 시 카메라를 안전하게 반납·재획득 (화면 잠금·앱 전환 후에도 재시작 없이 계속 촬영 가능)
- 화면에 각도(상하/좌우, 자이로 기반)와 추정 거리(카메라 초점거리 역산)를 실시간 표시, 촬영 시 JSON `pose`에 함께 저장

저장 위치는 앱 전용 Pictures/measurements 폴더이며, 현재 파일은 분석용 RAW 바이트입니다. 아직 DNG 컨테이너나 nit 환산은 포함하지 않습니다.

## 촬영 조건 비교 (PC)

폴더를 PC로 내려받아 `tools/raw_report.py`를 실행하면 각 RAW의 미리보기 PNG와, 조건별 밝기 통계를 담은 `report.csv`가 생성됩니다.

```bash
adb pull /sdcard/Android/data/com.leakeye.mvp/files/Pictures/measurements ./measurements
python tools/raw_report.py ./measurements
```

`report.csv` 열: `iso`, `exposure_ms`, `distance_m`(추정 거리), `tilt_pitch_deg`/`tilt_roll_deg`(각도), `mean_dn`(블랙 차감 평균), `p99_dn`(상위 1% 값), `sat_frac`(포화 비율) 등. 이 값들로 여러 촬영 조건 중 포화 없이 신호가 가장 큰 조건을 찾을 수 있습니다.

## 거리·각도 정확도에 대한 주의

거리는 별도 거리 센서가 아니라 카메라 자동초점 거리(diopters)를 역산한 추정값입니다. 초점이 실제로 대상에 맞았을 때만 유효하며,
수동 노출 모드로 초점을 고정한 경우에는 그 고정값을 그대로 보여줍니다. 각도는 자이로 기반 회전벡터 센서로, 폰이 "세로로 들고
카메라가 정면을 수평으로 향한" 자세일 때 0°/0°가 되도록 계산합니다.

## 빌드

Android Studio에서 이 폴더를 열고 Gradle Sync 후 Galaxy Z Fold5에 실행합니다.

- compile SDK: 36
- min SDK: 29
- application id: com.leakeye.mvp
- 필요한 권한: CAMERA

실행 전 휴대폰에서 USB 디버깅과 카메라 권한을 허용해야 합니다.

## 다음 구현 단계

1. 목표 거리·각도 범위를 정해두고 조건 만족 시 안내(화면 색상 등)와 자동 촬영
2. ROI 등록 및 휘도 캘리브레이션 분석
