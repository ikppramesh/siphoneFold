package com.rameshinampudi.frostfold;

/**
 * The frosted-glass fold, as an AGSL shader.
 *
 * Model: the screen content stays where it is, flat (the plane z = 0). Above
 * it, each half of the display is a pane of frosted glass hinged at the crease.
 * At {@code progress} p the pane's outer edge has lifted towards the viewer by
 * {@code p * maxTilt}. The viewer's eye sits {@code eyePx} in front of the
 * screen, level with the hinge.
 *
 * For each pixel we take the point of the glass that is drawn there, cast the
 * ray from the eye through it on down to the content, and look at the content
 * where that ray lands. The further the glass is from the content (its height
 * above the screen) the more the frost scatters light, so the blur radius and
 * the darkening grow with that height: crisp at the crease, softest and darkest
 * at the lifted edge, which also magnifies slightly, as real lifted glass does.
 * Where the whole blur disc misses the screen, the glass shows black.
 */
final class GlassShader {
    private GlassShader() {}

    static final String SOURCE = """
        uniform shader image;
        uniform float2 imageSize;
        uniform float2 viewSize;
        uniform float hinge;      // hinge line position across the split axis, view px
        uniform float eyeAcross;  // eye position across the split axis, view px
        uniform float vertical;   // 1: hinge is a vertical line (split along x)
        uniform float single;     // 1: one pane hinged at an edge (cover screen)
        uniform float progress;   // 0 flat and clear ... 1 fully lifted and frosted
        uniform float maxTilt;    // radians
        uniform float eyePx;      // viewer distance from the screen, view px
        uniform float blurPerPx;  // blur radius per px of glass height
        uniform float darkPerPx;  // light lost per px of glass height

        half4 look(float2 v) {
            return image.eval(clamp(v, float2(0.5), viewSize - 0.5) * imageSize / viewSize);
        }

        half4 main(float2 pos) {
            float2 q = vertical > 0.5 ? pos : pos.yx;
            float2 size = vertical > 0.5 ? viewSize : viewSize.yx;

            float side = (single > 0.5 || q.x >= hinge) ? 1.0 : -1.0;
            float d = abs(q.x - hinge);

            // The glass point drawn here, and its height above the content.
            float theta = maxTilt * progress;
            float height = d * sin(theta);
            float2 glass = float2(hinge + side * d * cos(theta), q.y);
            float2 eye = float2(eyeAcross, size.y * 0.5);
            float depth = eyePx - height;
            if (depth <= 1.0) return half4(0.0, 0.0, 0.0, 1.0);
            float2 hitQ = eye + (glass - eye) * (eyePx / depth);

            float radius = blurPerPx * height;
            if (hitQ.x < -radius || hitQ.y < -radius
                    || hitQ.x > size.x + radius || hitQ.y > size.y + radius) {
                return half4(0.0, 0.0, 0.0, 1.0);
            }
            float2 hit = vertical > 0.5 ? hitQ : hitQ.yx;
            float light = max(1.0 - darkPerPx * height, 0.0);
            if (radius < 0.5) return half4(look(hit).rgb * light, 1.0);

            // Grainy disc blur (golden-angle spiral, rotated per pixel), with
            // fewer taps where the radius is small.
            float taps = clamp(radius * 1.5, 6.0, 20.0);
            float spin = fract(sin(dot(pos, float2(12.9898, 78.233))) * 43758.5453) * 6.2831853;
            half3 sum = half3(0.0);
            float count = 0.0;
            for (int i = 0; i < 20; i++) {
                float fi = float(i);
                float use = 1.0 - step(taps, fi);
                float r = radius * sqrt((fi + 0.5) / taps);
                float a = spin + fi * 2.3999632;
                sum += look(hit + r * float2(cos(a), sin(a))).rgb * use;
                count += use;
            }
            return half4(sum / max(count, 1.0) * light, 1.0);
        }
        """;
}
