package com.leakeye.mvp;

import android.content.Context;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** 수동 노출 스위치 + ISO/셔터/초점 슬라이더. 값 읽기와 변경 알림만 담당한다. */
final class ExposureControls extends LinearLayout {
    private static final int STEPS = 1000;

    private final Switch manualSwitch;
    private final TextView isoLabel;
    private final TextView exposureLabel;
    private final TextView focusLabel;
    private final SeekBar isoBar;
    private final SeekBar exposureBar;
    private final SeekBar focusBar;

    private int isoLo = 50, isoHi = 3200;
    private long expLo = 32_544L, expHi = 150_000_000L;
    private float focusMax = 10f;
    private Runnable onChanged;

    ExposureControls(Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);
        setPadding(24, 0, 24, 0);

        manualSwitch = new Switch(ctx);
        manualSwitch.setText("수동 노출 (ISO / 셔터 / 초점 고정)");
        manualSwitch.setTextColor(Color.LTGRAY);
        addView(manualSwitch, new LayoutParams(-1, -2));

        isoLabel = label(ctx);
        isoBar = bar(ctx);
        exposureLabel = label(ctx);
        exposureBar = bar(ctx);
        focusLabel = label(ctx);
        focusBar = bar(ctx);

        isoBar.setProgress(ExposureScale.toPosition(400, STEPS, isoLo, isoHi));
        exposureBar.setProgress(ExposureScale.toPosition(16_000_000L, STEPS, expLo, expHi));
        focusBar.setProgress(0);

        manualSwitch.setOnCheckedChangeListener((button, checked) -> {
            setSlidersEnabled(checked);
            refreshLabels();
            notifyChanged();
        });
        setSlidersEnabled(false);
        refreshLabels();
    }

    private TextView label(Context ctx) {
        TextView t = new TextView(ctx);
        t.setTextColor(Color.LTGRAY);
        t.setPadding(0, 8, 0, 0);
        addView(t, new LayoutParams(-1, -2));
        return t;
    }

    private SeekBar bar(Context ctx) {
        SeekBar b = new SeekBar(ctx);
        b.setMax(STEPS);
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                refreshLabels();
                if (fromUser) notifyChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        addView(b, new LayoutParams(-1, -2));
        return b;
    }

    /** 카메라 특성이 확인된 뒤 실제 범위를 설정한다. 현재 값은 새 범위 안에서 유지한다. */
    void configure(int isoLo, int isoHi, long expLo, long expHi, float focusMax) {
        int iso = iso();
        long exp = exposureNs();
        float focus = focusDiopters();
        this.isoLo = isoLo; this.isoHi = isoHi; this.expLo = expLo; this.expHi = expHi;
        this.focusMax = focusMax > 0f ? focusMax : 10f;
        isoBar.setProgress(ExposureScale.toPosition(iso, STEPS, isoLo, isoHi));
        exposureBar.setProgress(ExposureScale.toPosition(exp, STEPS, expLo, expHi));
        focusBar.setProgress(Math.round(Math.min(focus, this.focusMax) / this.focusMax * STEPS));
        refreshLabels();
    }

    int iso() { return (int) ExposureScale.toValue(isoBar.getProgress(), STEPS, isoLo, isoHi); }
    long exposureNs() { return ExposureScale.toValue(exposureBar.getProgress(), STEPS, expLo, expHi); }
    float focusDiopters() { return focusMax * focusBar.getProgress() / STEPS; }
    boolean isManual() { return manualSwitch.isChecked(); }
    int isoLo() { return isoLo; }
    int isoHi() { return isoHi; }
    long expLo() { return expLo; }
    long expHi() { return expHi; }

    ExposureSettings current() {
        return isManual() ? ExposureSettings.manual(iso(), exposureNs(), focusDiopters()) : ExposureSettings.auto();
    }

    void setManual(boolean manual) { manualSwitch.setChecked(manual); }

    void setOnChanged(Runnable r) { onChanged = r; }

    private void setSlidersEnabled(boolean enabled) {
        isoBar.setEnabled(enabled);
        exposureBar.setEnabled(enabled);
        focusBar.setEnabled(enabled);
    }

    private void refreshLabels() {
        isoLabel.setText("ISO " + iso());
        exposureLabel.setText("셔터 " + ExposureScale.formatExposure(exposureNs()));
        focusLabel.setText("초점 " + ExposureScale.formatFocus(focusDiopters()));
    }

    private void notifyChanged() {
        if (onChanged != null) onChanged.run();
    }
}
