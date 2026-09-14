# 거리·각도 실시간 표시 설계

날짜: 2026-09-12 / 대상: 촬영 화면에 거리·각도를 실시간 표시하고 촬영 시 JSON에 함께 기록

## 결정 사항 (사용자 확인 완료)

거리는 카메라 초점거리(diopters)에서 역산한 추정값을 쓴다. ARCore 등 별도 센서 융합은 쓰지 않는다.
이유: 추가 설치·권한 없이 지금 바로 동작하고, RAW 수동 촬영 파이프라인(Camera2 직접 제어)과 충돌하지 않는다.
단점: 초점이 실제로 대상에 맞았을 때만 유효하고, 수동 노출 모드에서 초점을 고정해 둔 경우 그 고정값을 그대로 반영한다.

## 각도

`TYPE_GAME_ROTATION_VECTOR`(자기장 영향 없음, 없으면 `TYPE_ROTATION_VECTOR`로 대체)로 회전행렬을 구하고
`SensorManager.remapCoordinateSystem(AXIS_X, AXIS_Z)`로 "세로로 들고 촬영" 기준으로 변환한 뒤 `getOrientation()`의
pitch·roll을 도(°)로 쓴다. pitch=0/roll=0은 카메라가 정확히 수평·수직으로 정렬된 상태다.

## 거리

촬영 결과(`CaptureResult.LENS_FOCUS_DISTANCE`, diopters)를 `1/diopters` (미터)로 역산한다. 0이면 무한대(표시 안 함).
미리보기 중에도 같은 값을 반복 요청의 `CaptureCallback`으로 받아 실시간으로 보여준다.

## 화면

기존 상태줄 아래에 두 줄을 추가한다.
- "각도: 상하 X.X° / 좌우 Y.Y°" — 센서 콜백에서 직접 갱신 (UI 스레드에서 리스너를 등록하므로 별도 스레드 전환 불필요).
- "거리(추정): Z.ZZ m (AF)" — 미리보기 CaptureCallback에서 갱신.

## 저장

`CaptureMetadata.Values`에 `distanceM(Float)`, `distanceSource(String, "af_diopters" 고정)`, `tiltPitchDeg(Float)`, `tiltRollDeg(Float)` 추가.
JSON에 `pose` 섹션 추가:
```json
"pose": {"distance_m": 0.51, "distance_source": "af_diopters", "tilt_pitch_deg": -2.3, "tilt_roll_deg": 0.8}
```
값이 없으면(초점 무한대, 센서 없음) 해당 키를 생략한다.

## PC 리포트

`tools/raw_report.py`의 CSV에 `distance_m`, `tilt_pitch_deg`, `tilt_roll_deg` 열을 추가해 조건 비교표에서 바로 확인할 수 있게 한다.

## 테스트

- 단위: `ExposureScale.distanceMeters` (순수 함수, 0/음수/일반값). `Attitude`(SensorManager 정적 메서드 사용)는 로컬 JUnit에서
  실행 불가 — `CaptureMetadata.collect`와 같은 이유로 기기 검증으로 대체한다.
- 기기: 화면에 각도·거리 줄이 표시되는지, 폰을 기울였을 때 각도 값이 반응하는지, 촬영 후 JSON에 `pose` 섹션이 들어가는지 확인한다.
