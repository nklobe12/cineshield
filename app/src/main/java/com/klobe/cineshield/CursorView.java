package com.klobe.cineshield;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** On-screen pointer driven by the remote. It draws only and never takes input. */
final class CursorView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    float px, py;

    CursorView(Context context) {
        super(context);
        float s = getResources().getDisplayMetrics().density * 1.6f;
        fill.setColor(Color.WHITE);
        edge.setColor(Color.BLACK);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2 * s / 1.6f);
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 17 * s);
        arrow.lineTo(4.5f * s, 13 * s);
        arrow.lineTo(7.5f * s, 20 * s);
        arrow.lineTo(10.5f * s, 18.7f * s);
        arrow.lineTo(7.5f * s, 12 * s);
        arrow.lineTo(13 * s, 12 * s);
        arrow.close();
    }

    /** Moving a small view is far cheaper than redrawing the whole screen. */
    void moveTo(float x, float y) {
        px = x;
        py = y;
        setTranslationX(x);
        setTranslationY(y);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float s = getResources().getDisplayMetrics().density * 1.6f;
        setMeasuredDimension((int) Math.ceil(16 * s), (int) Math.ceil(23 * s));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.translate(2, 2);
        c.drawPath(arrow, fill);
        c.drawPath(arrow, edge);
        c.restore();
    }
}
