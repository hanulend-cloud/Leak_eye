package com.leakeye.mvp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Locale;

/**
 * 라이브 프리뷰 위에 10/20/30% 평가영역 정사각형(테두리만)과 그 바깥쪽 십자선,
 * 각 영역의 평균 밝기(0-255)·면적(px)을 그린다. preview(TextureView)와 정확히 같은
 * 크기로 겹쳐서 배치된다는 전제 하에 자신의 getWidth()/getHeight()를 좌표계로 쓴다.
 */
public class EvaluationOverlayView extends View {
    private float centerX = -1f;
    private float centerY = -1f;
    private int luma10, area10, luma20, area20, luma30, area30;
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

    /** true면 아직 포커스가 확정되지 않아 수치를 신뢰할 수 없음을 표시한다. */
    public void setGated(boolean value) {
        if (gated != value) {
            gated = value;
            invalidate();
        }
    }

    public void updateMetrics(int luma10, int area10, int luma20, int area20, int luma30, int area30) {
        this.luma10 = luma10; this.area10 = area10;
        this.luma20 = luma20; this.area20 = area20;
        this.luma30 = luma30; this.area30 = area30;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || centerX < 0) return;

        int side10 = SquareGeometry.squareSide(w, h, 0.10f);
        int side20 = SquareGeometry.squareSide(w, h, 0.20f);
        int side30 = SquareGeometry.squareSide(w, h, 0.30f);

        float cx10 = SquareGeometry.clampCenter(centerX, side10 / 2f, w);
        float cy10 = SquareGeometry.clampCenter(centerY, side10 / 2f, h);
        float cx20 = SquareGeometry.clampCenter(centerX, side20 / 2f, w);
        float cy20 = SquareGeometry.clampCenter(centerY, side20 / 2f, h);
        float cx30 = SquareGeometry.clampCenter(centerX, side30 / 2f, w);
        float cy30 = SquareGeometry.clampCenter(centerY, side30 / 2f, h);

        drawSquare(canvas, cx10, cy10, side10);
        drawSquare(canvas, cx20, cy20, side20);
        drawSquare(canvas, cx30, cy30, side30);

        float outerHalf = side30 / 2f;
        float outerLeft = cx30 - outerHalf;
        float outerTop = cy30 - outerHalf;
        float outerRight = cx30 + outerHalf;
        float outerBottom = cy30 + outerHalf;
        canvas.drawLine(cx30, 0, cx30, outerTop, linePaint);
        canvas.drawLine(cx30, outerBottom, cx30, h, linePaint);
        canvas.drawLine(0, cy30, outerLeft, cy30, linePaint);
        canvas.drawLine(outerRight, cy30, w, cy30, linePaint);

        String suffix = gated ? " (측정대기)" : "";
        canvas.drawText(String.format(Locale.US, "10%%: 밝기 %d / 면적 %dpx%s", luma10, area10, suffix),
                cx10 + side10 / 2f + 8, cy10 - side10 / 2f, textPaint);
        canvas.drawText(String.format(Locale.US, "20%%: 밝기 %d / 면적 %dpx%s", luma20, area20, suffix),
                cx20 + side20 / 2f + 8, cy20 - side20 / 2f + 32, textPaint);
        canvas.drawText(String.format(Locale.US, "30%%: 밝기 %d / 면적 %dpx%s", luma30, area30, suffix),
                cx30 + side30 / 2f + 8, cy30 - side30 / 2f + 64, textPaint);
    }

    private void drawSquare(Canvas canvas, float cx, float cy, int side) {
        float half = side / 2f;
        canvas.drawRect(cx - half, cy - half, cx + half, cy + half, boxPaint);
    }
}
