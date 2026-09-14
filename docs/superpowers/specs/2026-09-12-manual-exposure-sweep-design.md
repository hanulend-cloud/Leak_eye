# 수동 노출 · 노출 스윕 촬영 · PC 비교 리포트 설계

날짜: 2026-09-12 / 대상: README "다음 구현 단계 1. 수동 ISO·셔터·초점 고정" + 최적 촬영조건 탐색 워크플로

## 목표

휴대폰에서 ISO·셔터·초점을 직접 정해 RAW를 찍고, 한 상황에서 여러 노출 조합을 자동으로 연속 촬영한 뒤,
PC에서 모든 촬영의 조건과 밝기 통계를 한 표로 비교해 최적 촬영조건을 찾는다.

## 범위

- 앱: 수동 노출 UI, 미리보기 반영, 단일 촬영, 노출 스윕 촬영, JSON에 요청값·스윕 정보 추가, 밀리초 파일명.
- PC: `tools/raw_report.py` — `.raw`→PNG 미리보기, `report.csv` 생성.
- 범위 밖: 거리·각도 가이드, ROI, nit 환산, DNG, 사용자 입력 측정조건(모델명 등).

## 앱 구성

### `ExposureSettings` (신규, 불변 데이터)

`manual`, `iso`, `exposureNs`, `focusDiopters`, `sweepId`(null이면 단일 촬영), `sweepIndex`, `sweepTotal`.
`auto()`와 `manual(iso, exposureNs, focus)` 팩토리, `inSweep(id, index, total)` 복사 메서드.

### `ExposureScale` (신규, 순수 함수)

- 로그 스케일 슬라이더 매핑: `toValue(pos, max, lo, hi) = lo * (hi/lo)^(pos/max)`, 역함수 `toPosition`.
- 표시 문자열: `formatExposure(ns)` → 1초 미만은 `1/60 s (16.7 ms)`, 그 외 `150.0 ms`; `formatFocus(d)` → 0이면 `∞`, 아니면 `0.50 D (2.0 m)`.

### `ExposureSweep` (신규, 순수 함수)

`plan(isoLo, isoHi, expLoNs, expHiNs)` → ISO 후보 {50, 200, 800, 3200} × 셔터 후보 {2 ms, 16 ms, 60 ms, 150 ms}.
각 후보를 범위로 클램프하고 중복을 제거한 뒤 ISO 오름차순 → 셔터 오름차순으로 나열한다. Fold5 범위(ISO 50–3200, 32 µs–150 ms)에서는 16개.

### `ExposureControls` (신규, View)

세로 LinearLayout. 스위치 "수동 노출", 그 아래 ISO·셔터·초점 각각 라벨 + SeekBar.
- `configure(isoLo, isoHi, expLo, expHi, focusMaxDiopters)`: 카메라 특성 확인 후 범위 설정.
- `current()` → `ExposureSettings`(스위치 꺼짐이면 `auto()`).
- `setOnChanged(Runnable)`: 값이 바뀔 때마다 호출(UI 스레드).
- 스위치가 꺼져 있으면 슬라이더 비활성.

### MainActivity 변경

- `buildUi`: status 아래에 `ExposureControls`, 버튼 행에 "RAW 촬영"과 "노출 스윕(16장)" 두 버튼.
- `applySettings(CaptureRequest.Builder, ExposureSettings)`:
  - manual: `CONTROL_AE_MODE_OFF`, `SENSOR_SENSITIVITY`, `SENSOR_EXPOSURE_TIME`, `CONTROL_AF_MODE_OFF`, `LENS_FOCUS_DISTANCE`.
  - auto: `CONTROL_AE_MODE_ON`, `CONTROL_AF_MODE_CONTINUOUS_PICTURE`.
- 미리보기: 컨트롤 변경 시 `cameraHandler`에서 repeating request를 현재 설정으로 다시 세팅.
- 촬영 큐: `ArrayDeque<ExposureSettings>`와 `inFlight`. 모든 큐 조작은 `cameraHandler`에서만 한다.
  - 단일 촬영: `controls.current()` 1개 enqueue.
  - 스윕: `ExposureSweep.plan(...)` 각각을 `manual(iso, exp, 현재 초점)`으로 만들어 `inSweep(stamp, i, n)`으로 enqueue.
  - `startNext()`: `inFlight == null`이면 pop → 요청 생성 → `session.capture`. 상태 "스윕 3/16 촬영 중".
  - 완료 지점: `tryWriteMetadata`가 JSON을 쓰거나 실패 처리한 뒤, 그리고 RAW 저장 실패 시 → `inFlight = null; startNext()`.
- 파일명: `RAW_yyyyMMdd_HHmmss_SSS`.
- JSON: `CaptureMetadata.Values`에 `request` 섹션 추가 —
  `{"mode": "manual"|"auto", "iso", "exposure_time_ns", "focus_distance_diopters", "sweep_id", "sweep_index", "sweep_total"}`
  (auto면 iso/exposure/focus 생략, 단일 촬영이면 sweep_* 생략).

## PC 스크립트 `tools/raw_report.py`

- 사용: `python tools/raw_report.py <폴더>` (폴더에 `.raw`+`.json` 쌍). 출력: 각 `<base>.png`, `<폴더>/report.csv`.
- 순수 함수(테스트 대상):
  - `stats(arr, black, white)` → `mean_dn`(블랙 차감 평균), `p99_dn`, `sat_frac`(`>= 0.98*white` 비율).
  - `preview(arr, black, white)` → 2×2 비닝 후 `(x-black)/(white-black)`을 감마 1/2.2로 8비트 변환한 uint8 2D 배열.
- CSV 열: `file, captured_at, mode, req_iso, req_exposure_ms, iso, exposure_ms, focus_diopters, sweep_id, sweep_index, mean_dn, p99_dn, sat_frac`.
- 의존: numpy, Pillow (Anaconda 기본 포함).

## 오류 처리

- 카메라가 수동 값을 거부(예외)하면 상태에 "수동 노출 미지원: <값>" 표시하고 auto로 되돌린다.
- 스윕 도중 한 장이 실패해도 다음 장으로 진행하고, 끝에 "스윕 완료 16/16 (실패 1)" 표시.
- 스크립트: JSON은 있는데 `.raw`가 없거나 크기가 다르면 그 행을 건너뛰고 stderr에 경고.

## 테스트

- 단위(JUnit): `ExposureScale`(매핑 왕복, 표시 문자열), `ExposureSweep`(Fold5 범위 16개, 좁은 범위 클램프·중복 제거), `CaptureMetadata`(request 섹션).
- 단위(pytest): `stats`, `preview`를 합성 배열로 검증.
- 기기: 수동 ISO 800·셔터 16 ms로 1장 → JSON `capture.iso≈800`, `exposure_time_ns≈16e6`; 스윕 → 16쌍 생성, ISO/셔터가 계획과 일치. 폴더를 PC로 받아 `raw_report.py` 실행 → PNG 16장 + `report.csv` 16행.
