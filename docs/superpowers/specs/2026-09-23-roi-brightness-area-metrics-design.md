# 측정영역 평균/피크 밝기 및 면적(초과영역) 계산 설계

## 배경 / 목표

현재 `EvaluationOverlayView`의 10%/20%/30% 사각형 측정영역은 평균 밝기(luma)만 계산하고, "면적"은 사각형 자체의 픽셀 크기(변²)를 그대로 표시하고 있다. 요구사항은 다음과 같다:

1. 측정영역(사각형) 내부로 들어온 픽셀만으로 밝기/면적을 계산 (기존에도 사각형 내부만 샘플링하고 있어 구조는 유지, 계산 항목만 확장)
2. 밝기는 평균(average)과 peak(상위 1% 픽셀 평균) 2종류를 표시
3. 면적은 "사각형 내부 픽셀 중 사각형 전체 평균보다 밝은 픽셀의 개수(px)"로 재정의
4. 저장 이미지 하단에 평균/피크/면적/조도(lux)를 함께 기록
5. 라이브 화면에서 사각형 우측 상단에 평균/피크, 우측 하단에 면적 표시

## 범위 밖

- 저장 이미지에 "평균보다 밝은 영역"의 외곽을 점선으로 표시하는 기능 (사용자가 이번 작업에서 제외하기로 결정, 추후 별도 작업)
- `CaptureMetadata`의 JSON 사이드카에 이 값들을 추가하는 것 (요청 범위는 이미지 위 텍스트 오버레이로 한정)
- 라이브 면적(px)과 저장 이미지 면적(px)의 수치를 서로 비교 가능하게 정규화하는 것 (두 값은 해상도가 다른 별개 컨텍스트로, 각자 유효)

## 컴포넌트 설계

### 1. `LumaMath` 확장 (신규 메서드, 기존 `averageLuma`는 그대로 유지)

- 신규: `static Stats compute(int[] pixels)` — 값 객체 `Stats(int average, int peak, int brightAreaPx)`
- 구현: 단일 패스로 256-bin 히스토그램(luma별 픽셀 수)과 합계를 동시에 계산
  - `average` = 합계 / 픽셀 수 (기존 `averageLuma`와 동일한 반올림 규칙)
  - `peak` = 히스토그램을 255→0 방향으로 누적하며 상위 1%(`ceil(count * 0.01)`, 최소 1)에 도달할 때까지의 픽셀들의 평균 — 같은 bin의 픽셀은 luma 값이 동일하므로 히스토그램만으로 정확히 계산 가능(근사 아님)
  - `brightAreaPx` = 히스토그램에서 인덱스가 `average`보다 큰 bin들의 count 합 (픽셀 배열 재순회 불필요)
- 기존 `averageLuma`를 쓰는 테스트/호출부는 변경하지 않음

### 2. 라이브 측정 흐름 (`MainActivity`)

- `sampleSquare(Bitmap, cx, cy, side)`가 `LumaMath.averageLuma` 대신(또는 추가로) `LumaMath.compute(pixels)`를 호출
- `sampleBrightness()`에서 `overlay.updateMetrics(stats.average, stats.peak, stats.brightAreaPx)` 호출 (시그니처를 2-arg에서 3-arg로 변경)
- 샘플링 주기(300ms)는 변경 없음

### 3. `EvaluationOverlayView` 오버레이 UI

- 필드 변경: `luma, area` → `average, peak, areaPx`
- `updateMetrics(int average, int peak, int areaPx)`로 시그니처 변경
- `onDraw`에서 기존 한 줄 텍스트를 두 줄로 분리:
  - 사각형 우측 상단 (기존 위치): `"{percent}%: 평균 {average} / 피크 {peak}{gatedSuffix}"`
  - 사각형 우측 하단 (신규 위치): `"면적 {areaPx}px"`
- `gated`(측정대기) 상태 표시 로직은 기존과 동일하게 유지

### 4. 저장 이미지 ROI 재계산 (`MainActivity`)

- 캡처 요청을 큐잉하는 시점(비동기 프레임 매칭 이전)에 그 프레임 전용 ROI 스냅샷을 별도로 저장: `centerX, centerY, measurementPercent, previewViewWidth, previewViewHeight` — 기존에 RAW 프레임 메타데이터/`CaptureResult`/JPEG 비트맵을 타임스탬프로 페어링하는 파이프라인에 같이 태깅하여, 스윕 캡처 중 오버레이 상태가 바뀌어도 프레임 간 값이 섞이지 않게 함
- `drawOverlay(Bitmap bitmap, CaptureMetadata.Values v)` 진입 시, 저장된 스냅샷을 `TapFocusMapper`와 동일한 비율(fraction) 매핑으로 변환:
  ```
  fx = snapshot.centerX / snapshot.previewViewWidth
  fy = snapshot.centerY / snapshot.previewViewHeight
  capturedCenterX = fx * bitmap.getWidth()
  capturedCenterY = fy * bitmap.getHeight()
  capturedSide = SquareGeometry.squareSide(bitmap.getWidth(), bitmap.getHeight(), snapshot.percent)
  ```
  (프리뷰와 JPEG 스트림은 `SCALER_CROP_REGION`이 동일하게 적용되고 둘 다 4:3을 우선 선택하므로 비율 매핑이 유효함 — 단, 두 스트림이 실제로 4:3으로 해상되는지 런타임 로그로 1회 확인 권장)
- 매핑된 영역의 픽셀을 `bitmap.getPixels()`로 추출 → `LumaMath.compute()`로 재계산 (라이브 샘플링 값 재사용하지 않음)
- 기존 하단 정보 바(`drawOverlay`의 텍스트 라인들)에 `"평균 {avg} / 피크 {peak} / 면적 {areaPx}px / 조도 {lux}lx"` 형태로 한 줄 추가

## 테스트 계획

- `LumaMathTest`에 `compute()` 케이스 추가: 균일 밝기 배열(평균=피크=해당값, area=0), 절반 밝음/절반 어두움 배열(area가 어두운 쪽 픽셀 수와 일치하는지), 단일 최댓값 픽셀(peak가 그 값에 근접하는지)
- 좌표 매핑 로직은 순수 함수로 분리해 유닛 테스트 (뷰 크기/비트맵 크기가 다른 경우 fraction 매핑 검증)
