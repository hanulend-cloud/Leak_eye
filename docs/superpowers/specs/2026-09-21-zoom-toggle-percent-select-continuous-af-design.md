# 줌 토글 + 측정영역 비율 선택(단일 사각형) + 자동 연속 AF 설계

## 배경 / 목표

기존 "3배 줌 + 탭 포커스 + 평가영역 오버레이" 기능([2026-09-21-zoom-focus-eval-overlay-design.md](2026-09-21-zoom-focus-eval-overlay-design.md))을 다음과 같이 개선한다:

1. 배율을 1배/3배 중 선택 가능하게 한다 (기존에는 항상 3배 고정).
2. 측정영역 비율(10%/20%/30%)을 선택 가능하게 하고, 화면에는 선택된 사각형 1개만 표시한다 (기존에는 10/20/30% 세 개를 동시에 표시).
3. 화면(장면)이 변하더라도 사각형 위치에 카메라가 계속 자동으로 초점을 맞추도록 한다 — 지금까지는 사용자가 탭하기 전에는 AF 리전이 설정되지 않아, 앱 시작 직후에는 카메라 기본 영역(기기 의존적) 기준으로 초점이 맞춰지고 있었다.

## 범위 밖

- 배율을 1배/3배 외 임의 값으로 조절하는 기능 (토글 방식 유지)
- 측정영역 비율을 10/20/30% 외 임의 값(슬라이더 등)으로 조절하는 기능
- 주기적(타이머 기반) AF 트리거 강제 재전송 — `CONTROL_AF_MODE_CONTINUOUS_PICTURE`가 AF 리전을 기준으로 알아서 계속 재초점을 맞추는 동작에 의존한다

## 컴포넌트 설계

기존 5개 순수 로직 클래스(`ZoomCropRegion`, `TapFocusMapper`, `SquareGeometry`, `LumaMath`, `AfStateClassifier`)는 이미 배율(factor)/비율(percent)을 매개변수로 받으므로 변경 없이 재사용한다.

### 1. 줌 토글

- `MainActivity`에 필드 추가: `zoomFactor`(기본 3f), `activeArraySize`(`Rect`, `setupCamera()`에서 1회 저장 — 지금은 크롭 계산 직후 버려지던 값을 필드로 보관)
- 버튼 1개("확대: 3배" 형태, 누르면 1↔3 전환 + 라벨 갱신)
- 토글 시: `cropRegion = new Rect(ZoomCropRegion.centeredCrop(activeArraySize.left/top/right/bottom, zoomFactor))` 재계산 → **AF 리전 재적용(컴포넌트 3 참고)**

### 2. 측정영역 비율 선택 (단일 사각형)

- `MainActivity`에 필드 추가: `measurementPercent`(기본 0.10f)
- 버튼 3개(10%/20%/30%), 선택된 것만 강조 표시(배경색)
- `EvaluationOverlayView`를 3개 동시 표시 → 1개로 단순화:
  - 필드: `luma10/area10/...` 6개 → `luma`, `area` 2개로 축소, `percent` 필드 추가(`setPercent(float)`)
  - `onDraw`: 사각형 1개 + 그 바깥쪽 십자선만 그림 (기존 "가장 바깥 사각형 밖으로만" 로직이 자연히 "이 사각형 밖으로만"이 됨)
  - `updateMetrics(int luma, int area)`로 시그니처 축소 (기존 6개 인자 → 2개)
- `MainActivity.sampleBrightness()`: 3개 영역 샘플링 → `measurementPercent` 기준 1개만 계산

### 3. 자동 연속 AF (탭 없이도 사각형 위치에 AF 리전 고정)

기존 `focusAt(viewX, viewY)`를 그대로 재사용하는 2개의 신규 호출 지점을 추가한다 (새 메서드 불필요):

- **카메라 세션 시작 시**: `onConfigured` 콜백의 `runOnUiThread` 블록에서 `layoutPreview()` 호출 후, `overlay.getCenterX() >= 0`이면(오버레이가 이미 유효한 크기를 가졌으면) `focusAt(overlay.getCenterX(), overlay.getCenterY())` 호출 — 탭 전에도 화면 중심에 AF 리전을 걸어둠
- **줌 토글 시**: `cropRegion` 재계산 직후 `focusAt(overlay.getCenterX(), overlay.getCenterY())` 호출 — 같은 화면 좌표라도 크롭이 바뀌었으므로 센서 좌표계의 AF 리전을 다시 계산해야 함. 크롭 적용(`updatePreview()` 포함)과 AF 리전 재계산이 한 번의 호출로 처리됨
- 탭 시 동작은 기존 그대로 유지

**동작 원리**: `CONTROL_AF_MODE_CONTINUOUS_PICTURE`는 AF 리전이 설정된 상태로 반복 요청이 계속되는 한, 카메라 드라이버가 그 리전을 기준으로 알아서 계속 재초점을 맞춘다 (기존 `updatePreview()`가 이미 `afRegion != null && !settings.manual`일 때마다 `CONTROL_AF_REGIONS`를 반복 요청에 실어 보내고 있음 — 이 부분은 변경 불필요). 이번 변경은 AF 리전이 "탭 전에는 비어있던" 문제만 해소한다.

## 데이터 흐름

```
카메라 세션 시작
  → layoutPreview() (오버레이 기본 중심 설정)
  → overlay 중심이 유효하면 focusAt(중심좌표) 1회 호출 → AF 리전이 화면 중심에 고정됨
  → (이후는 CONTINUOUS_PICTURE 모드가 알아서 그 리전 기준 계속 재초점)

줌 토글
  → cropRegion 재계산
  → focusAt(overlay 현재 중심좌표) 호출 → 새 크롭 좌표계 기준 AF 리전 재계산 + updatePreview()

비율 버튼 탭
  → measurementPercent 갱신, overlay.setPercent() 호출 (AF와 무관, 표시/샘플링 크기만 변경)

화면 탭 (기존과 동일)
  → focusAt(탭 좌표) 호출
```

## 에러 처리

- 기존 `focusAt()`의 에러 처리(카메라/세션 미준비 시 조용히 리턴, AF 트리거 실패 시 무시)를 그대로 재사용 — 신규 호출 지점도 동일한 가드를 통과함
- `overlay.getCenterX() < 0`(아직 레이아웃 전)인 상태에서 세션이 구성되면 초기 AF 리전 적용은 건너뛰고, 이후 실제 탭이 있을 때 정상 적용됨 (드문 극단적 타이밍 케이스, 기능 저하 없음 — 기본 카메라 AF로 동작)

## 테스트 계획

이번 변경은 대부분 기존 로직 재사용 + `MainActivity` 배선이라 신규 순수 로직 유닛 테스트는 없음. 기존 5개 클래스의 테스트는 변경 없이 계속 유효. 실기기 확인 항목(Fold5, 연결되면 수행):

- 줌 토글 버튼으로 1배/3배 전환이 실제로 화각에 반영되는지
- 비율 버튼으로 사각형 1개만 표시되고 크기가 바뀌는지 (10/20/30%)
- 앱 실행 직후(탭 전)에도 화면 중심 기준으로 포커스가 잡히는지 ("포커스: 완료" 상태가 탭 없이도 도달하는지)
- 줌 토글 후에도 같은 화면 위치에 AF가 재조정되는지
