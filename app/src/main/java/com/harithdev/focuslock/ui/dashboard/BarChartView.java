package com.harithdev.focuslock.ui.dashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.harithdev.focuslock.model.DailySummary;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * BarChartView — Custom Canvas-based bar chart view.
 *
 * Renders daily screen-time trend bars for 7, 14, or 30 days.
 * Includes gradient fills, time labels, day labels, and today highlight.
 */
public class BarChartView extends View {

    private List<DailySummary> data = new ArrayList<>();
    private Paint barPaint;
    private Paint highlightBarPaint;
    private Paint textPaint;
    private Paint labelPaint;
    private Paint gridPaint;

    private SimpleDateFormat inputFormat  = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private SimpleDateFormat outputFormat7 = new SimpleDateFormat("EEE", Locale.getDefault());
    private SimpleDateFormat outputFormat30 = new SimpleDateFormat("M/d", Locale.getDefault());

    public BarChartView(Context context) {
        super(context);
        init();
    }

    public BarChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BarChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private int colorHighlight;
    private int colorGradientStart;
    private int colorGradientEnd;

    private void init() {
        Context context = getContext();
        colorHighlight     = androidx.core.content.ContextCompat.getColor(context, com.harithdev.focuslock.R.color.purple_primary);
        colorGradientStart = androidx.core.content.ContextCompat.getColor(context, com.harithdev.focuslock.R.color.purple_gradient_start);
        colorGradientEnd   = androidx.core.content.ContextCompat.getColor(context, com.harithdev.focuslock.R.color.purple_gradient_end);
        int colorText      = androidx.core.content.ContextCompat.getColor(context, com.harithdev.focuslock.R.color.text_primary);
        int colorLabel     = androidx.core.content.ContextCompat.getColor(context, com.harithdev.focuslock.R.color.text_secondary);
        int colorGrid      = androidx.core.content.ContextCompat.getColor(context, com.harithdev.focuslock.R.color.surface_grid);

        barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setStyle(Paint.Style.FILL);

        highlightBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightBarPaint.setColor(colorHighlight); // Brighter purple for today
        highlightBarPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(colorText);
        textPaint.setTextSize(dpToPx(10));
        textPaint.setTextAlign(Paint.Align.CENTER);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(colorLabel);
        labelPaint.setTextSize(dpToPx(10));
        labelPaint.setTextAlign(Paint.Align.CENTER);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(colorGrid);
        gridPaint.setStrokeWidth(dpToPx(1));
    }

    public void setData(List<DailySummary> dataList) {
        this.data = dataList != null ? dataList : new ArrayList<>();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (data == null || data.isEmpty()) return;

        int width  = getWidth();
        int height = getHeight();

        float paddingLeft   = dpToPx(12);
        float paddingRight  = dpToPx(12);
        float paddingTop    = dpToPx(28);
        float paddingBottom = dpToPx(28);

        float chartWidth  = width - paddingLeft - paddingRight;
        float chartHeight = height - paddingTop - paddingBottom;

        // Draw baseline
        canvas.drawLine(paddingLeft, height - paddingBottom, width - paddingRight, height - paddingBottom, gridPaint);

        // Find max value to scale bar heights (minimum scale 60 mins = 3,600,000 ms)
        long maxMs = 60 * 60_000L;
        for (DailySummary summary : data) {
            if (summary.totalUsedMs > maxMs) {
                maxMs = summary.totalUsedMs;
            }
        }

        int count = data.size();
        float barWidth = Math.max(dpToPx(6), (chartWidth / count) * 0.55f);
        float step = chartWidth / count;

        String todayString = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        for (int i = 0; i < count; i++) {
            DailySummary summary = data.get(i);

            float cx = paddingLeft + (i * step) + (step / 2f);
            float left   = cx - (barWidth / 2f);
            float right  = cx + (barWidth / 2f);
            float bottom = height - paddingBottom;

            float ratio = (float) summary.totalUsedMs / (float) maxMs;
            float barHeight = Math.max(dpToPx(4), chartHeight * ratio);
            float top = bottom - barHeight;

            boolean isToday = summary.date != null && summary.date.equals(todayString);

            RectF rect = new RectF(left, top, right, bottom);

            if (isToday) {
                canvas.drawRoundRect(rect, dpToPx(4), dpToPx(4), highlightBarPaint);
            } else {
                LinearGradient gradient = new LinearGradient(
                        0, top, 0, bottom,
                        colorGradientStart, colorGradientEnd,
                        Shader.TileMode.CLAMP
                );
                barPaint.setShader(gradient);
                canvas.drawRoundRect(rect, dpToPx(4), dpToPx(4), barPaint);
            }

            // Draw top time label (only if count <= 14 or if bar > 0)
            if (summary.totalUsedMs > 0 && (count <= 14 || i % 3 == 0)) {
                String timeStr = formatMinutesShort(summary.totalUsedMs / 60_000L);
                canvas.drawText(timeStr, cx, top - dpToPx(4), textPaint);
            }

            // Draw bottom date label
            if (count <= 7 || (count <= 14 && i % 2 == 0) || (count > 14 && i % 5 == 0)) {
                String label = formatDateLabel(summary.date, count);
                canvas.drawText(label, cx, height - dpToPx(8), labelPaint);
            }
        }
    }

    private String formatDateLabel(String dateStr, int count) {
        if (dateStr == null) return "";
        try {
            Date date = inputFormat.parse(dateStr);
            if (date == null) return dateStr;
            return count <= 7 ? outputFormat7.format(date) : outputFormat30.format(date);
        } catch (Exception e) {
            return dateStr;
        }
    }

    private String formatMinutesShort(long minutes) {
        if (minutes < 60) return minutes + "m";
        long h = minutes / 60;
        long m = minutes % 60;
        return m > 0 ? h + "h" + m + "m" : h + "h";
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
