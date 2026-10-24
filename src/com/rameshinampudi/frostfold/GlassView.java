package com.rameshinampudi.frostfold;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;

/** Draws a screenshot of the screen through {@link GlassShader}. */
final class GlassView extends View {

    /** Tuning read from the settings each time an animation starts. */
    static final class Look {
        /** How far each pane's outer edge lifts at full progress. */
        float tiltDegrees = 30f;
        /** Blur radius at the lifted edge at full progress, view px. */
        float blurPx = 70f;
        /** Light lost at the lifted edge at full progress, 0..1. */
        float darkness = 0.45f;
        /** Viewer distance from the screen. */
        float eyeCm = 30f;
    }

    private final RuntimeShader shader = new RuntimeShader(GlassShader.SOURCE);
    private final Paint paint = new Paint();
    private Bitmap image;
    private boolean singlePane;
    private float progress;
    private Look look = new Look();

    GlassView(Context context) {
        super(context);
        paint.setShader(shader);
    }

    /** @param singlePane true on the cover screen: one pane hinged at its left edge. */
    void setImage(Bitmap image, boolean singlePane) {
        this.image = image;
        this.singlePane = singlePane;
        shader.setInputShader("image", new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        invalidate();
    }

    Bitmap image() {
        return image;
    }

    void setLook(Look look) {
        this.look = look;
    }

    void setProgress(float progress) {
        this.progress = progress;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (image == null) return;
        int w = getWidth(), h = getHeight();
        boolean vertical = singlePane || w <= h;
        float across = vertical ? w : h;
        float pane = singlePane ? across : across / 2f;
        float tilt = (float) Math.toRadians(look.tiltDegrees);
        float maxHeight = Math.max(1f, pane * (float) Math.sin(tilt));
        float eyePx = look.eyeCm / 2.54f * getResources().getDisplayMetrics().xdpi;
        shader.setFloatUniform("imageSize", image.getWidth(), image.getHeight());
        shader.setFloatUniform("viewSize", w, h);
        shader.setFloatUniform("hinge", singlePane ? 0f : across / 2f);
        shader.setFloatUniform("eyeAcross", across / 2f);
        shader.setFloatUniform("vertical", vertical ? 1f : 0f);
        shader.setFloatUniform("single", singlePane ? 1f : 0f);
        shader.setFloatUniform("progress", progress);
        shader.setFloatUniform("maxTilt", tilt);
        shader.setFloatUniform("eyePx", eyePx);
        shader.setFloatUniform("blurPerPx", look.blurPx / maxHeight);
        shader.setFloatUniform("darkPerPx", look.darkness / maxHeight);
        canvas.drawPaint(paint);
    }
}
