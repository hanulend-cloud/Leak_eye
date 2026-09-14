import numpy as np

from raw_report import preview, stats


def test_stats_subtracts_black_and_counts_saturation():
    arr = np.full((4, 4), 356, dtype=np.uint16)
    arr[0, 0] = 4095
    arr[0, 1] = 4090
    s = stats(arr, black=256, white=4095)
    expected_mean = ((356 - 256) * 14 + (4095 - 256) + (4090 - 256)) / 16
    assert abs(s["mean_dn"] - expected_mean) < 1e-6
    assert s["sat_frac"] == 2 / 16
    assert s["p99_dn"] >= 100


def test_preview_bins_and_scales():
    arr = np.full((4, 4), 256, dtype=np.uint16)
    arr[2:, 2:] = 4095
    img = preview(arr, black=256, white=4095)
    assert img.shape == (2, 2)
    assert img.dtype == np.uint8
    assert img[0, 0] == 0
    assert img[1, 1] == 255
