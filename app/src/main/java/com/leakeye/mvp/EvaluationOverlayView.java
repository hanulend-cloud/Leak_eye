package com.leakeye.mvp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Locale;

/**
 * 라이브 프리뷰 위에 선택된 비율(10/20/30%)의 평가영역 정사각형 1개(테두리만)와 그 바깥쪽 십자선,
 * 평균 밝기(0-255)·면적(px)을 그린다. preview(TextureView)와 정확히 같은 크기로 겹쳐서 배치된다는
 * 전제 하에 자신의 getWidth()/getHeight()를 좌표계로 쓴다.
 */
public class EvaluationOverlayView extends View {
    private float centerX = -1f;
    private float centerY = -1f;
    private float percent = 0.10f;
    private int luma;
    private int area;
    private boolean gated = true;

    private final Paint boxPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final Paint textPaint = new Paint();

    public EvaluationOverlayView(Context context) {
        super(context);
        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);
        linePaint.setColor(Color.RED);
        linePaint.setStrokeWidth(2f);
        textPaint.setColor(Color.RED);
        textPaint.setTextSize(28f);
        textPaint.setAntiAlias(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (centerX < 0) {
            centerX = w / 2f;
            centerY = h / 2f;
        } else if (oldw > 0 && oldh > 0 && (oldw != w || oldh != h)) {
            // 폴드/언폴드 등으로 뷰 크기가 바뀌면 이전 탭 위치를 비율 그대로 새 크기에 맞춰 옮긴다.
            centerX = centerX / oldw * w;
            centerY = centerY / oldh * h;
        }
    }

    /** 터치 지점(뷰 좌표)으로 평가영역 중심을 옮긴다. */
    public void updateCenter(float x, float y) {
        centerX = x;
        centerY = y;
        invalidate();
    }

    public float getCenterX() { return centerX; }
    public float getCenterY() { return centerY; }

    /** 표시할 평가영역 비율(예: 0.10f = 10%)을 바꾼다. */
    public void setPercent(float percent) {
        this.percent = percent;
        invalidate();
    }

    /** true면 아직 포커스가 확정되지 않아 수치를 신뢰할 수 없음을 표시한다. */
    public void setGated(boolean value) {
        if (gated != value) {
            gated = value;
            invalidate();
        }
    }

    public void updateMetrics(int luma, int area) {
        this.luma = luma;
        this.area = area;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || centerX < 0) return;

        int side = SquareGeometry.squareSide(w, h, percent);
        float cx = SquareGeometry.clampCenter(centerX, side / 2f, w);
        float cy = SquareGeometry.clampCenter(centerY, side / 2f, h);

        drawSquare(canvas, cx, cy, side);

        float half = side / 2f;
        float left = cx - half;
        float top = cy - half;
        float right = cx + half;
        float bottom = cy + half;
        canvas.drawLine(cx, 0, cx, top, linePaint);
        canvas.drawLine(cx, bottom, cx, h, linePaint);
        canvas.drawLine(0, cy, left, cy, linePaint);
        canvas.drawLine(right, cy, w, cy, linePaint);

        String suffix = gated ? " (측정대기)" : "";
        int percentInt = Math.round(percent * 100);
        canvas.drawText(String.format(Locale.US, "%d%%: 밝기 %d / 면적 %dpx%s", percentInt, luma, area, suffix),
                cx + half + 8, cy - half, textPaint);
    }

    /** (x, y)가 현재 그려진 평가영역 사각형 내부인지 판단한다. onDraw()와 동일한 계산을 재사용한다. */
    public boolean containsPoint(float x, float y) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || centerX < 0) return false;
        int side = SquareGeometry.squareSide(w, h, percent);
        float cx = SquareGeometry.clampCenter(centerX, side / 2f, w);
        float cy = SquareGeometry.clampCenter(centerY, side / 2f, h);
        float half = side / 2f;
        return x >= cx - half && x <= cx + half && y >= cy - half && y <= cy + half;
    }

    private void drawSquare(Canvas canvas, float cx, float cy, int side) {
        float half = side / 2f;
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, boxPaint);
    }
}
