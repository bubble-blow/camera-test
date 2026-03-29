package com.example.camerahttptest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FrameIntervalHistogramView extends View {

    private final Map<Integer, Integer> counts = new HashMap<Integer, Integer>();

    private final Paint axisPaint = new Paint();
    private final Paint barPaint = new Paint();
    private final Paint textPaint = new Paint();

    public FrameIntervalHistogramView(Context context) {
        super(context);
        init();
    }

    public FrameIntervalHistogramView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FrameIntervalHistogramView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        axisPaint.setColor(Color.DKGRAY);
        axisPaint.setStrokeWidth(2f);

        barPaint.setColor(Color.rgb(60, 140, 250));
        barPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(24f);
        textPaint.setAntiAlias(true);
    }

    public void clearData() {
        counts.clear();
        invalidate();
    }

    public void addIntervalMs(int intervalMs) {
        if (intervalMs < 0) {
            intervalMs = 0;
        }
        Integer old = counts.get(intervalMs);
        counts.put(intervalMs, old == null ? 1 : old + 1);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        int left = 60;
        int right = width - 20;
        int top = 20;
        int bottom = height - 50;

        canvas.drawLine(left, bottom, right, bottom, axisPaint);
        canvas.drawLine(left, top, left, bottom, axisPaint);

        if (counts.isEmpty()) {
            canvas.drawText("Histogram: no data", left + 10, top + 30, textPaint);
            return;
        }

        List<Integer> keys = new ArrayList<Integer>(counts.keySet());
        Collections.sort(keys);

        int maxCount = 1;
        for (Integer key : keys) {
            int count = counts.get(key);
            if (count > maxCount) {
                maxCount = count;
            }
        }

        int chartWidth = right - left;
        int chartHeight = bottom - top;
        float barWidth = Math.max(4f, (float) chartWidth / (float) keys.size());

        for (int i = 0; i < keys.size(); i++) {
            int interval = keys.get(i);
            int count = counts.get(interval);

            float normalized = (float) count / (float) maxCount;
            float barHeight = normalized * chartHeight;

            float x1 = left + i * barWidth;
            float x2 = x1 + barWidth - 1;
            float y1 = bottom - barHeight;
            float y2 = bottom;

            canvas.drawRect(x1, y1, x2, y2, barPaint);

            if (i % Math.max(1, keys.size() / 8) == 0) {
                canvas.drawText(interval + "ms", x1, bottom + 24, textPaint);
            }
        }

        canvas.drawText("max freq=" + maxCount, left + 10, top + 24, textPaint);
    }
}
