# RAW 메타데이터 JSON 저장 설계

날짜: 2026-09-12 / 대상: README "다음 구현 단계 1. RAW 메타데이터와 촬영조건 JSON 저장"

## 목표

RAW 프레임을 저장할 때, 같은 촬영의 카메라 메타데이터를 같은 기본 이름의 `.json` 파일로 함께 저장한다.
사용자 입력은 없다. 값은 CameraCharacteristics(정적), TotalCaptureResult(동적), Image(프레임), 기기 정보에서만 가져온다.

## 접근

타임스탬프 매칭. `session.capture`에 `CaptureCallback`을 등록해 `TotalCaptureResult`를 받고,
`Image.getTimestamp()`와 `SENSOR_TIMESTAMP`가 같은 것끼리 묶는다.
Image 콜백과 CaptureCallback은 모두 `cameraHandler`(camera-thread)에서 실행되므로 잠금 없이 단일 대기 슬롯으로 처리한다.

## 구성

### `CaptureMetadata` (신규, `com.leakeye.mvp`)

- 역할: 메타데이터를 `JSONObject`로 만드는 것만 담당. 파일 I/O, UI 없음.
- 입력을 두 단계로 나눈다.
  - `CaptureMetadata.Values`: 원시값만 담는 데이터 홀더(문자열·정수·실수·배열). 단위 테스트가 이 타입을 직접 만든다.
  - `CaptureMetadata.Frame`: Image가 닫히기 전에 `frameOf(Image, File, bytes)`로 떠 둔 프레임 스냅샷(파일, 크기, stride, 바이트 수, 타임스탬프). CaptureResult가 Image보다 늦게 와도 안전하다.
  - `CaptureMetadata.collect(CameraCharacteristics, TotalCaptureResult, Frame, cameraId, versionName, versionCode)` → `Values`. Android 객체를 읽는 유일한 지점.
  - `CaptureMetadata.toJson(Values)` → `JSONObject`. 순수 함수.
- null 값은 JSON에 키를 넣지 않는다(`JSONObject.NULL` 대신 생략).

### MainActivity 변경

- `captureRaw`: `session.capture(request, captureCallback, cameraHandler)`. `onCaptureCompleted`에서 `pendingResult = result` 후 `tryWriteMetadata()`.
- `saveRaw`: RAW 저장 후 `pendingRaw = {file, width, height, rowStride, pixelStride, bytes, timestamp}` 를 기록하고 `tryWriteMetadata()`. `image.close()`는 기존과 같이 finally에서.
- `tryWriteMetadata()`: `pendingResult`와 `pendingRaw`가 모두 있고 타임스탬프가 같으면 JSON을 쓰고 두 슬롯을 비운다. 타임스탬프가 다르면 더 최근 것만 남긴다.
- `characteristics`는 `openCamera`에서 필드로 보관한다.

## JSON 스키마

```json
{
  "schema_version": 1,
  "raw_file": "RAW_20260912_161519.raw",
  "captured_at": "2026-09-12T16:15:19+09:00",
  "device": {"manufacturer": "samsung", "model": "SM-F946N", "sdk_int": 35},
  "app": {"version_name": "0.1.0", "version_code": 1},
  "camera": {
    "id": "0",
    "pixel_array_size": [4080, 3060],
    "active_array": [0, 0, 4080, 3060],
    "physical_size_mm": [7.3, 5.5],
    "focal_lengths_mm": [6.3],
    "apertures": [1.8],
    "cfa_pattern": 0,
    "black_level_pattern": [64, 64, 64, 64],
    "white_level": 4095,
    "iso_range": [50, 3200],
    "exposure_time_range_ns": [10000, 1000000000]
  },
  "frame": {
    "format": "RAW_SENSOR", "width": 4080, "height": 3060,
    "row_stride": 8160, "pixel_stride": 2, "bytes": 24969600,
    "sensor_timestamp_ns": 123456789
  },
  "capture": {
    "exposure_time_ns": 33333333, "iso": 100, "aperture": 1.8,
    "focal_length_mm": 6.3, "focus_distance_diopters": 0.5,
    "color_gains": [2.1, 1.0, 1.0, 1.7],
    "color_transform": [1.2, -0.3, 0.1, -0.1, 1.1, 0.0, 0.0, -0.2, 1.2],
    "neutral_color_point": [0.47, 1.0, 0.61],
    "ae_mode": 1, "awb_mode": 1
  }
}
```

- 숫자 배열의 예시 값은 형식 설명용이며 실제 값은 기기에서 읽는다.
- `color_transform`은 3x3 유리수 행렬을 실수 9개(행 우선)로 기록한다.
- `captured_at`은 저장 시각(ISO 8601, 기기 로컬 오프셋).

## 파일

- 위치: 기존 RAW와 같은 폴더 `getExternalFilesDir(Pictures)/measurements/`
- 이름: RAW와 같은 기본 이름 + `.json`
- 인코딩: UTF-8, `JSONObject.toString(2)`

## 오류 처리

- CaptureResult가 오지 않아도 RAW는 이미 저장되어 있다. 상태 표시는 "저장 완료: RAW_x.raw (메타데이터 대기)"에서 JSON 완료 시 "저장 완료: RAW_x.raw + json"으로 갱신한다.
- JSON 쓰기 실패: "메타데이터 저장 실패: <메시지>" 표시, RAW는 유지.
- 개별 키가 null이면 그 키만 생략한다. 예외로 전체 저장을 막지 않는다.

## 테스트

- 로컬 단위 테스트(`app/src/test`): `CaptureMetadata.toJson(Values)`가 스키마의 키·값을 내는지, null 필드가 생략되는지 검증. `testImplementation 'org.json:json:20240303'`과 JUnit 4를 추가한다. Android 스텁 `org.json`은 테스트 클래스패스에서 실제 구현으로 대체된다.
- 기기 검증(adb): 촬영 후 `.raw`와 `.json` 쌍이 생기고, `exposure_time_ns > 0`, `iso > 0`, `frame.width/height == 4080/3060`, `frame.bytes == 파일 크기`인지 확인.

## 범위 밖

- DNG 컨테이너, nit 환산, 사용자 입력 측정조건(모델명·거리·각도), 수동 노출 고정(README 2번).
