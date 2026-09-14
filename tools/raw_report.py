#!/usr/bin/env python3
"""RAW 측정 폴더를 PNG 미리보기 + report.csv 로 정리한다.

사용: python tools/raw_report.py <폴더>
폴더에는 앱이 저장한 RAW_*.raw 와 같은 이름의 RAW_*.json 이 있어야 한다.
"""
import csv
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

CSV_COLUMNS = [
    "file", "captured_at", "mode", "req_iso", "req_exposure_ms", "iso", "exposure_ms",
    "focus_diopters", "distance_m", "tilt_pitch_deg", "tilt_roll_deg",
    "sweep_id", "sweep_index", "mean_dn", "p99_dn", "sat_frac",
]


def stats(arr, black, white):
    """블랙 차감 평균, 상위 1% 값, 포화(>= 0.98*white) 비율."""
    a = arr.astype(np.float64) - black
    return {
        "mean_dn": float(a.mean()),
        "p99_dn": float(np.percentile(a, 99)),
        "sat_frac": float((arr >= 0.98 * white).mean()),
    }


def preview(arr, black, white):
    """2x2 비닝 후 (x-black)/(white-black) 을 감마 1/2.2 로 8비트 변환한다."""
    h, w = arr.shape
    h2, w2 = h // 2 * 2, w // 2 * 2
    binned = arr[:h2, :w2].astype(np.float64).reshape(h2 // 2, 2, w2 // 2, 2).mean(axis=(1, 3))
    norm = np.clip((binned - black) / float(white - black), 0.0, 1.0)
    return (np.power(norm, 1 / 2.2) * 255 + 0.5).astype(np.uint8)


def load_raw(path, width, height):
    a = np.fromfile(str(path), dtype="<u2")
    if a.size != width * height:
        raise ValueError(f"{path.name}: size {a.size} != {width}x{height}")
    return a.reshape(height, width)


def process(folder):
    folder = Path(folder)
    rows = []
    for jp in sorted(folder.glob("*.json")):
        meta = json.load(open(jp, encoding="utf-8"))
        rp = jp.with_suffix(".raw")
        if not rp.exists():
            print(f"warn: {rp.name} missing, skipped", file=sys.stderr)
            continue
        frame, cam = meta["frame"], meta["camera"]
        cap, req, pose = meta.get("capture", {}), meta.get("request", {}), meta.get("pose", {})
        try:
            arr = load_raw(rp, frame["width"], frame["height"])
        except ValueError as e:
            print(f"warn: {e}, skipped", file=sys.stderr)
            continue
        black = int(round(float(np.mean(cam.get("black_level_pattern", [0])))))
        white = int(cam.get("white_level", 65535))
        s = stats(arr, black, white)
        img = preview(arr, black, white)
        # 센서는 가로로 읽히지만 폰은 항상 세로로 들고 촬영하므로(가로 촬영이 어려움),
        # 저장되는 JPEG과 같은 방향(세로)으로 보이도록 90도 시계 방향 회전한다.
        # (실기기에서 텍스트가 있는 장면으로 4방향을 비교해 확정한 방향 — MainActivity의
        # JPEG_ROTATE_DEGREES와 반드시 같은 방향으로 맞춘다.)
        img = np.rot90(img, k=-1)
        Image.fromarray(img).resize((img.shape[1] // 2, img.shape[0] // 2), Image.BILINEAR).save(jp.with_suffix(".png"))
        rows.append({
            "file": rp.name,
            "captured_at": meta.get("captured_at", ""),
            "mode": req.get("mode", ""),
            "req_iso": req.get("iso", ""),
            "req_exposure_ms": _ms(req.get("exposure_time_ns")),
            "iso": cap.get("iso", ""),
            "exposure_ms": _ms(cap.get("exposure_time_ns")),
            "focus_diopters": cap.get("focus_distance_diopters", ""),
            "distance_m": pose.get("distance_m", ""),
            "tilt_pitch_deg": pose.get("tilt_pitch_deg", ""),
            "tilt_roll_deg": pose.get("tilt_roll_deg", ""),
            "sweep_id": req.get("sweep_id", ""),
            "sweep_index": req.get("sweep_index", ""),
            "mean_dn": round(s["mean_dn"], 2),
            "p99_dn": round(s["p99_dn"], 2),
            "sat_frac": round(s["sat_frac"], 5),
        })
    out = folder / "report.csv"
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=CSV_COLUMNS)
        w.writeheader()
        w.writerows(rows)
    return rows


def _ms(ns):
    return "" if ns is None else round(ns / 1e6, 3)


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    rows = process(argv[1])
    print(f"{len(rows)} frames -> {Path(argv[1]) / 'report.csv'}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
