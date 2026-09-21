# 3배 줌 + 탭 포커스 + 평가영역 오버레이 설계

## 배경 / 목표

라이브 프리뷰 화면에서 빛샘(light leak) 평가를 돕기 위해 다음을 추가한다:

1. 프리뷰 및 촬영(RAW/JPEG) 모두에 항상 3배 디지털 줌 적용
2. 표준 상태(첫 실행/미터치)에서는 화면 중심을 포커스 기준으로 사용
3. 화면 터치 시 그 위치로 포커스를 맞추고, 그 위치를 중심으로 10%/20%/30% 빨간 정사각형 평가 영역 표시
4. 정사각형 내부는 비우고(테두리만), 가장 바깥 사각형 밖으로는 중심을 지나는 가로/세로 십자선 표시
5. 각 정사각형 내부 영역의 평균 밝기(0-255)와 면적(pixel 개수)을 화면에 수치로 표시
6. 3배 줌 상태에서는 포커스 정확도가 측정 신뢰도의 전제 조건이므로, 포커스가 실제로 잠기기 전에는 밝기/면적 수치를 신뢰 가능한 값으로 표시하지 않음

## 범위 밖

- 저장된 RAW/JPEG 파일에 대한 사후 오버레이/분석 (이번 기능은 라이브 프리뷰 화면 한정)
- 줌 배율 조절 UI (3배 고정, 토글 없음)
- 사각형 크기/비율 커스터마이징 UI

## 컴포넌트 설계

### 1. CropRegion 헬퍼 (신규 클래스, 예: `ZoomCropRegion.java`)

- 입력: `CameraCharacteristics`의 `SENSOR_INFO_ACTIVE_ARRAY_SIZE` (Rect)
- 출력: 그 Rect의 가로/세로를 각각 1/3로 줄이고 중앙 정렬한 `Rect` (3배 줌에 해당하는 크롭 영역)
- 순수 함수로 분리하여 유닛 테스트 가능 (`Rect` 입력 → `Rect` 출력)

### 2. 줌 적용

- `updatePreview()`의 `CaptureRequest.Builder`와 `captureRaw()`/`captureSweep()` 경로에서 스틸 캡처용 `CaptureRequest.Builder`(현재 라인 ~505 부근) 양쪽에 `CaptureRequest.SCALER_CROP_REGION`을 CropRegion 결과로 설정
- `applySettings()` 또는 그 호출 지점에서 공통 적용하여 두 경로가 어긋나지 않게 함

### 3. 탭 포커스

- `previewContainer`에 `OnTouchListener` 추가 (`ACTION_DOWN`에서 처리)
- 좌표 매핑 함수(신규, 유닛 테스트 가능): 뷰 픽셀 좌표(tapX, tapY) + 뷰 크기(viewW, viewH) + 현재 CropRegion(Rect) → 활성 배열 좌표계의 `MeteringRectangle` (고정 크기, 예: 크롭 영역의 5% 정도 폭)로 변환
  - 정규화: `nx = tapX / viewW`, `ny = tapY / viewH`
  - 활성 배열 좌표: `sensorX = crop.left + nx * crop.width()`, `sensorY = crop.top + ny * crop.height()`
- Auto 모드(`ExposureSettings.manual == false`)일 때만: 새 `CaptureRequest.Builder`에 `CONTROL_AF_REGIONS = {region}`, `CONTROL_AF_TRIGGER = CONTROL_AF_TRIGGER_START` 설정 후 `session.capture(...)` 1회 전송, 이후 `updatePreview()`의 반복 요청에도 동일 리전을 유지(트리거는 제거)
- Manual 모드일 때는 AF 관련 요청 없이 오버레이 중심 좌표만 갱신
- 탭 좌표를 `EvaluationOverlayView`의 중심으로 그대로 전달 (뷰 좌표계 그대로, 별도 매핑 불필요 — 오버레이도 같은 뷰 위에 그려지므로)

### 4. `EvaluationOverlayView` (신규 `View`, `com.leakeye.mvp` 패키지)

- `previewContainer`에 `preview`(TextureView) 다음 순서로 추가, `LayoutParams(-1, -1)`로 동일 영역 차지, 터치 통과를 위해 자체는 터치를 소비하지 않음(터치 리스너는 `previewContainer`가 가짐)
- 상태: `centerX`, `centerY` (뷰 좌표, 기본값은 `onSizeChanged`에서 `width/2f, height/2f`로 설정), `blockMetrics[3]` (밝기 평균, 면적 pixel 수)
- `onDraw(Canvas)`:
  - `side = min(width, height)`; 3개 사각형 변 길이 = `side * 0.10 / 0.20 / 0.30`
  - 각 사각형을 `centerX/centerY` 중심에 `Paint.Style.STROKE`, 빨간색으로 그림 (채우지 않음)
  - 가장 바깥 사각형(30%) 밖으로만 십자선: 상/하/좌/우 4개 선분 — 예: 세로선은 `(centerX, 0)-(centerX, outerTop)`과 `(centerX, outerBottom)-(centerX, height)`, 가로선도 동일한 방식으로 좌우 바깥쪽만
  - 각 사각형 근처(예: 우측 상단 모서리)에 `"10%: 밝기 xxx / 면적 xxx px"` 형태 텍스트 표시
- `public void updateCenter(float x, float y)`, `public void updateMetrics(BlockMetric[3])` 메서드로 MainActivity에서 갱신 후 `invalidate()`

### 5. 밝기 샘플러

- MainActivity에 `Handler(Looper.getMainLooper())` 기반 300ms 주기 `Runnable` 루프 (프리뷰 세션이 살아있는 동안만 동작, `onPause`에서 정지)
- 매 틱: `preview.getBitmap()` 호출 (null이면 스킵) → 오버레이의 현재 `centerX/centerY` 기준으로 3개 사각형 영역 각각 `Bitmap.getPixels()`로 픽셀 추출
- 순수 함수(유닛 테스트 가능): `int[] pixels, int count -> averageLuma` — `luma = 0.299R + 0.587G + 0.114B` 평균, 0-255 범위
- 면적(pixel 개수) = 사각형 변² (정수), 별도 계산 불필요 (결정적)
- 결과를 `EvaluationOverlayView.updateMetrics(...)`로 전달

### 6. AF 상태 감시 및 측정값 게이팅

- 기존 `previewCallback`(`CameraCaptureSession.CaptureCallback.onCaptureCompleted`)에서 이미 `TotalCaptureResult`를 받고 있으므로, 여기서 `CaptureResult.CONTROL_AF_STATE`를 함께 읽음
- 상태 분류:
  - **잠김(신뢰 가능)**: `AF_STATE_FOCUSED_LOCKED`, `AF_STATE_PASSIVE_FOCUSED`
  - **확인 중(신뢰 불가)**: `AF_STATE_ACTIVE_SCAN`, `AF_STATE_PASSIVE_SCAN`, `AF_STATE_NOT_FOCUSED_LOCKED`, 또는 값 없음(manual 모드 등 AF 미사용 시)
- `poseStatus`/`distanceStatus`와 같은 위치에 "포커스: 확인중" / "포커스: 완료" 텍스트 추가 표시
- `EvaluationOverlayView`의 밝기/면적 텍스트는 AF가 "확인 중" 상태인 동안은 마지막 신뢰값을 흐리게(alpha 낮춤) 표시하거나 "측정대기"로 대체하고, "잠김" 상태가 되는 즉시 최신 밝기 샘플러 결과로 갱신
- Manual 모드(AF_MODE_OFF, 사용자가 초점거리를 직접 고정)일 때는 AF_STATE가 항상 없으므로 별도 취급: 이 경우는 사용자가 이미 의도적으로 초점을 고정한 것이므로 게이팅 없이 항상 신뢰 가능한 값으로 표시

## 데이터 흐름

```
터치(ACTION_DOWN)
  → 좌표 매핑(뷰좌표 → 활성배열좌표)
  → [auto모드] AF 리전 캡처 요청 1회 + 리전 유지
  → 오버레이 centerX/centerY 갱신 → invalidate()
  → (병렬) 300ms 밝기 샘플러 루프가 항상 최신 center 기준으로 재계산 → invalidate()
  → (병렬) previewCallback에서 AF_STATE 관찰 → "확인중"이면 수치 게이팅, "잠김"이면 최신 샘플 값 신뢰 표시로 전환
```

## 에러 처리

- AF 미지원 기기/`IllegalArgumentException`: AF 트리거만 무시하고 로그, 오버레이 이동은 항상 수행 (기존 manual 모드 실패 처리 패턴과 동일하게 조용히 폴백)
- `preview.getBitmap()`이 `null`(서페이스 미준비/카메라 미오픈): 해당 틱 스킵, 다음 틱 재시도
- 사각형이 뷰 경계를 벗어나는 극단적 탭 위치(예: 모서리 근처): 사각형/샘플링 영역을 뷰 경계로 clamp

## 알려진 리스크 (실기기에서 확인 필요)

- `SCALER_CROP_REGION`이 RAW_SENSOR 스트림에는 적용되지 않고 풀센서로 나오는 기기가 있음 — Fold5에서 RAW 파일도 3배 줌이 반영되는지 확인 필요. 반영 안 되면 "RAW는 풀센서 유지, 프리뷰/JPEG만 줌"으로 설계 조정
- AF 리전 좌표계 해석이 기기별로 달라 탭 위치와 실제 포커스 지점이 어긋날 수 있음 — 실기기 테스트하며 매핑 보정 가능성 있음

## 테스트 계획

다음은 순수 로직으로 분리해 유닛 테스트 작성 (기존 `ExposureScaleTest` 등과 동일 패턴):

- `ZoomCropRegion`: 활성 배열 Rect → 1/3 중앙 크롭 Rect 계산
- 탭 좌표 → 활성 배열 좌표 매핑 함수
- 사각형 변 길이 계산 (10/20/30%, min(w,h) 기준)
- 평균 루마 계산 함수
- AF_STATE → 신뢰가능/확인중 분류 함수

카메라/터치/실기기 동작(AF 실제 반응, 화면 렌더링)은 에뮬레이션 불가 — Fold5 실기기에서 수동 확인.
