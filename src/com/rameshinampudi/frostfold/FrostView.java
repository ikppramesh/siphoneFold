package com.rameshinampudi.frostfold;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Draws the glass that sits over the (window-blurred) screen. The blur itself is
 * done by the window manager; this view only adds what a pane of tinted glass
 * would: an even dim, extra shade on each half as it tilts away from flat, and a
 * soft shadow in the crease. No highlights or sweeps; the look is quiet.
 * {@code progress}: 1 = fully frosted, 0 = clear.
 */
public class FrostView extends View {

    private final Paint dim = new Paint();
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crease = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF half = new RectF();
    private final float dp;

    private float progress;
    private float tilt;
    private boolean showHinge;

    public FrostView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
    }

    /**
     * @param tilt how far each half leans away from flat, 0 (flat) to 1 (closed).
     * @param showHinge false on the cover screen, which has no crease.
     */
    public void setState(float progress, float tilt, boolean showHinge) {
        this.progress = progress;
        this.tilt = tilt;
        this.showHinge = showHinge;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float f = progress;
        if (f <= 0f) return;
        int w = getWidth(), h = getHeight();

        // Glass dims what is behind it a little, more as it fogs up.
        dim.setColor(argb(0.30f * f, 12, 12, 18));
        canvas.drawRect(0, 0, w, h, dim);
        if (!showHinge) return;

        // Book-style fold: the hinge runs across the shorter axis' middle.
        boolean vertical = w <= h;
        float len = vertical ? w : h;
        float mid = len / 2f;

        // Each half falls into shadow towards the hinge the more it is tilted.
        float depth = 0.38f * tilt * f;
        int dark = argb(depth, 0, 0, 6);
        int clear = argb(0f, 0, 0, 6);
        setHalf(vertical, 0, mid, w, h);
        shade.setShader(gradient(vertical, 0, mid, clear, dark));
        canvas.drawRect(half, shade);
        setHalf(vertical, mid, len, w, h);
        shade.setShader(gradient(vertical, mid, len, dark, clear));
        canvas.drawRect(half, shade);

        // Soft crease shadow, narrowing as the panels come flat.
        float band = (8f + 40f * tilt) * dp;
        int creaseDark = argb(0.32f * f, 0, 0, 0);
        setHalf(vertical, mid - band, mid + band, w, h);
        crease.setShader(new LinearGradient(
                vertical ? mid - band : 0, vertical ? 0 : mid - band,
                vertical ? mid + band : 0, vertical ? 0 : mid + band,
                new int[] {clear, creaseDark, clear}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(half, crease);
    }

    private void setHalf(boolean vertical, float from, float to, int w, int h) {
        if (vertical) half.set(from, 0, to, h);
        else half.set(0, from, w, to);
    }

    private static LinearGradient gradient(boolean vertical, float from, float to, int c0, int c1) {
        return vertical
                ? new LinearGradient(from, 0, to, 0, c0, c1, Shader.TileMode.CLAMP)
                : new LinearGradient(0, from, 0, to, c0, c1, Shader.TileMode.CLAMP);
    }

    private static int argb(float alpha, int r, int g, int b) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
