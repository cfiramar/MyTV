package io.soartv;

import android.graphics.RuntimeShader;

/**
 * Aurora and glass (Liquid Glass) drawn on the GPU with AGSL. The aurora is procedural, so the glass doesn't need to
 * blur what's behind it: it uses the same aurora without detail (the frosted glass, computed in Frost) and shifts the
 * sample near the edge (the refraction). The glass never sits on top of an app or a video.
 */
final class Shaders {
    private Shaders() {}

    private static final String COMMON =
        "uniform float2 res;\n" +            // full screen size (px): the same math in every layer
        "uniform float t;\n" +               // seconds
        "uniform float speed;\n" +
        "uniform float dim;\n" +
        "uniform half3 c0;\n" +              // palette: background, middle and highlight
        "uniform half3 c1;\n" +
        "uniform half3 c2;\n" +
        "float hash(float2 p) {\n" +
        "  p = fract(p * float2(123.34, 456.21));\n" +
        "  p += dot(p, p + 45.32);\n" +
        "  return fract(p.x * p.y);\n" +
        "}\n" +
        "float noise(float2 p) {\n" +
        "  float2 i = floor(p);\n" +
        "  float2 f = fract(p);\n" +
        "  float2 u = f * f * (3.0 - 2.0 * f);\n" +
        "  float a = hash(i);\n" +
        "  float b = hash(i + float2(1.0, 0.0));\n" +
        "  float c = hash(i + float2(0.0, 1.0));\n" +
        "  float d = hash(i + float2(1.0, 1.0));\n" +
        "  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);\n" +
        "}\n" +
        "float fbm2(float2 p) {\n" +
        "  return 0.6 * noise(p) + 0.3 * noise(p * 2.03 + 17.0);\n" +
        "}\n" +
        // With detail: 4 octaves. Without detail (frosted glass): only the first 2 + the mean of the rest, the same
        // image run through a low-pass filter.
        "float fbm(float2 p, float detail) {\n" +
        "  float v = 0.5 * noise(p);\n" +
        "  p = p * 2.03 + 17.0;\n" +
        "  v += 0.25 * noise(p);\n" +
        "  if (detail < 0.5) { return v + 0.09375; }\n" +
        "  p = p * 2.03 + 17.0;\n" +
        "  v += 0.125 * noise(p);\n" +
        "  p = p * 2.03 + 17.0;\n" +
        "  return v + 0.0625 * noise(p);\n" +
        "}\n" +
        // Aurora: a slowly flowing domain warp. The light bands sit in the middle of the sky; the top (clock and
        // prices) and the bottom (dock) are darker so white text stands out.
        "half3 aurora(float2 uv, float detail) {\n" +
        "  float2 p = float2(uv.x * (res.x / res.y), uv.y) * 1.7;\n" +
        "  float tt = t * 0.035 * speed;\n" +
        "  float2 q = float2(fbm2(p + float2(0.0, tt)), fbm2(p + float2(5.2, 1.3) - tt));\n" +
        "  float n = fbm(p + 2.0 * q + float2(1.7 + tt, 9.2), detail);\n" +
        "  float band = smoothstep(0.02, 0.38, uv.y) * (1.0 - smoothstep(0.5, 0.92, uv.y));\n" +
        "  float curtain = smoothstep(0.35, 0.85, n) * (0.35 + 0.65 * band);\n" +
        "  half3 col = mix(c0, c1, smoothstep(0.15, 0.85, n));\n" +
        "  col = mix(col, c2, curtain * 0.85);\n" +
        "  float vig = 1.0 - 0.38 * smoothstep(0.35, 1.0, uv.y) - 0.2 * smoothstep(0.45, 0.95, abs(uv.x - 0.5) * 1.6);\n" +
        "  float scrim = 1.0 - 0.22 * (1.0 - smoothstep(0.0, 0.42, uv.y));\n" +
        "  return col * vig * scrim * (1.0 - dim);\n" +
        "}\n";

    /**
     * Background: drawn into a small layer (¼ scale) and scaled up; gradients lose nothing. Noise of ±½ a color level
     * breaks up the banding a dark gradient would show on a big screen.
     */
    static final String AURORA = COMMON +
        "uniform float2 layer;\n" +
        "half4 main(float2 xy) {\n" +
        "  half3 col = aurora(xy / layer, 1.0) + (hash(xy) - 0.5) / 255.0;\n" +
        "  return half4(col, 1.0);\n" +
        "}\n";

    /**
     * Glass in a rounded-corner shape. The glass background comes from `frost`: the aurora without detail in a 48×27
     * image computed on the CPU (Frost), or the blurred recommendation image (Backdrop), scaled up with linear
     * filtering. That is the frosted glass, at one texture read per pixel (a TV GPU such as the PowerVR BXE-4-32 can't
     * afford to recompute the aurora at every glass pixel).
     * tint: color and strength of the smoky tint; glow: accent color rising from the bottom; lift: extra brightness
     * (focus); body: 1 = full glass, 0 = only the rim highlight (on top of an app icon). Returns premultiplied color
     * (Skia's convention).
     */
    static final String GLASS =
        "uniform shader frost;\n" +
        "uniform float2 frostScale;\n" +     // frosted image size / screen size
        "uniform float2 offset;\n" +         // layer offset on screen (the layer moves without being redrawn)
        "uniform float2 center;\n" +
        "uniform float2 halfSize;\n" +
        "uniform float2 invHalf;\n" +        // 1 / halfSize (per-shape math done on the CPU, not per pixel)
        "uniform float radius;\n" +
        "uniform float top;\n" +
        "uniform float4 depth;\n" +          // 1/refraction band, refraction shift (px), 1/highlight band, 1/height
        "uniform half4 tint;\n" +
        "uniform half4 glow;\n" +
        "uniform float lift;\n" +
        "uniform float body;\n" +
        "float hash(float2 p) {\n" +
        "  p = fract(p * float2(123.34, 456.21));\n" +
        "  p += dot(p, p + 45.32);\n" +
        "  return fract(p.x * p.y);\n" +
        "}\n" +
        "half4 main(float2 xy) {\n" +
        "  float2 d = xy - center;\n" +
        "  float2 q = abs(d) - (halfSize - radius);\n" +
        "  float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n" +
        "  float aa = clamp(0.5 - sd, 0.0, 1.0);\n" +
        "  if (aa <= 0.0) { return half4(0.0); }\n" +
        "  float2 dir = d * invHalf;\n" +
        "  float ty = xy.y - top;\n" +
        "  float rim = 1.0 - clamp(-sd * 0.4545, 0.0, 1.0);\n" +
        "  float light = clamp(-dir.y * 0.75 - dir.x * 0.25 + 0.3, 0.0, 1.0);\n" +
        "  float sheen = (1.0 - clamp(ty * depth.z, 0.0, 1.0)) * 0.06;\n" +
        "  float hl = rim * (0.08 + 0.5 * light) + sheen * body;\n" +
        "  half3 col = half3(0.0);\n" +
        "  if (body > 0.0) {\n" +
        "    float edge = 1.0 - clamp(-sd * depth.x, 0.0, 1.0);\n" +
        "    float2 sxy = xy - dir * (edge * edge * depth.y);\n" +
        "    half3 bg = frost.eval((sxy + offset) * frostScale).rgb * 1.12 + half3(0.02);\n" +
        "    col = mix(bg, tint.rgb, tint.a);\n" +
        "    col = mix(col, glow.rgb, glow.a * smoothstep(0.1, 1.0, ty * depth.w));\n" +
        "    col += half3(lift);\n" +
        "    col *= 1.0 - 0.2 * (1.0 - clamp(-sd * 0.0625, 0.0, 1.0)) * clamp(dir.y, 0.0, 1.0);\n" +
        "    col += (hash(xy) - 0.5) * 0.0039;\n" +
        "  }\n" +
        "  float a = body + hl * (1.0 - body);\n" +
        "  return half4((col * body + half3(hl)) * aa, a * aa);\n" +
        "}\n";

    /**
     * Liquid Glass (macOS 27 style) for the shelf and the cards: the background shows through the glass, lightly
     * blurred (`imageA`: the photo at 480×270, or the frosted aurora), with a slight color boost; near the edge the
     * glass is "thick" and bends the image outward (a lens), with slight color dispersion; the rim has a thin
     * highlight, strong where it faces the light (top left), and an echo on the opposite side, plus a soft inner halo.
     * `imageB`/`mixB`: the next photo during a transition, blended here (no texture rebuild every frame). band = width
     * of the thick rim (px), refraction = how much it bends (px), specular = highlight strength. Premultiplied color.
     */
    static final String LIQUID =
        "uniform shader imageA;\n" +
        "uniform shader imageB;\n" +
        "uniform float mixB;\n" +
        "uniform float2 imageScale;\n" +
        "uniform float2 offset;\n" +
        "uniform float2 center;\n" +
        "uniform float2 halfSize;\n" +
        "uniform float radius;\n" +
        "uniform half4 tint;\n" +
        "uniform float lift;\n" +
        "uniform float band;\n" +
        "uniform float refraction;\n" +
        "uniform float specular;\n" +
        "float hash(float2 p) {\n" +
        "  p = fract(p * float2(123.34, 456.21));\n" +
        "  p += dot(p, p + 45.32);\n" +
        "  return fract(p.x * p.y);\n" +
        "}\n" +
        "half3 bgAt(float2 p) {\n" +
        "  half3 a = imageA.eval(p * imageScale).rgb;\n" +
        "  if (mixB <= 0.0) { return a; }\n" +
        "  return mix(a, imageB.eval(p * imageScale).rgb, half(mixB));\n" +
        "}\n" +
        "half4 main(float2 xy) {\n" +
        "  float2 d = xy - center;\n" +
        "  float2 q = abs(d) - (halfSize - radius);\n" +
        "  float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n" +
        "  float aa = clamp(0.5 - sd, 0.0, 1.0);\n" +
        "  if (aa <= 0.0) { return half4(0.0); }\n" +
        "  float2 sg = float2(d.x < 0.0 ? -1.0 : 1.0, d.y < 0.0 ? -1.0 : 1.0);\n" +
        "  float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) * sg : (q.x > q.y ? float2(sg.x, 0.0) : float2(0.0, sg.y));\n" +
        "  float inner = -sd;\n" +
        "  float e = clamp(inner / band, 0.0, 1.0);\n" +
        "  float bend = (1.0 - e) * (1.0 - e);\n" +
        "  float2 p = xy + offset - n * (bend * refraction);\n" +
        "  half3 bg;\n" +
        "  if (bend > 0.01) {\n" +
        "    float2 ca = n * (bend * refraction * 0.22);\n" +
        "    bg = half3(bgAt(p + ca).r, bgAt(p).g, bgAt(p - ca).b);\n" +
        "  } else {\n" +
        "    bg = bgAt(p);\n" +
        "  }\n" +
        "  half l = dot(bg, half3(0.2126, 0.7152, 0.0722));\n" +
        "  bg = mix(half3(l), bg, 1.18) * 1.06;\n" +
        "  half3 col = mix(bg, tint.rgb, tint.a) + half3(lift);\n" +
        "  float ny = d.y / halfSize.y;\n" +
        "  col *= half(1.0 - 0.12 * smoothstep(0.1, 1.0, ny));\n" +
        "  col += half3(0.045 * (1.0 - smoothstep(-1.0, -0.15, ny)));\n" +
        "  float2 L = float2(-0.4, -0.917);\n" +
        "  float facing = clamp(dot(n, L), 0.0, 1.0);\n" +
        "  float back = clamp(-dot(n, L), 0.0, 1.0);\n" +
        "  float rim = 1.0 - smoothstep(0.0, 2.4, inner);\n" +
        "  float halo = 1.0 - smoothstep(0.0, 11.0, inner);\n" +
        "  float spec = rim * (0.22 + 0.78 * facing + 0.4 * back) + halo * (0.12 * facing + 0.05 * back);\n" +
        "  col += half3(spec * specular);\n" +
        "  col += half3((hash(xy) - 0.5) * 0.0039);\n" +
        "  return half4(col * half(aa), half(aa));\n" +
        "}\n";

    /**
     * Glass clock for the screensaver (like the iOS 26 lock screen's): the digits are clear glass over the photo
     * moving behind them. `slope` (opaque): R and G = the surface slope (the height gradient, pointing into the
     * stroke; 0.5 = flat), B = the height (0.5 at the edge, 1 in the middle of the strokes); `cover`: the sharp
     * rendering of the digits (the soft edge comes from it). Near the edge the glass bends the photo (a lens), with
     * slight color dispersion; the core brightens a little; a thin highlight on the edge facing the light (top left)
     * and an echo on the opposite side. Photos: `imageA`/`imageB` with each one's on-screen position (x, y) and scale
     * (the same slow zoom as the background), and `mixB` during a transition. Premultiplied color.
     */
    static final String GLASS_TEXT =
        "uniform shader imageA;\n" +
        "uniform shader imageB;\n" +
        "uniform shader slope;\n" +
        "uniform shader cover;\n" +
        "uniform float3 placeA;\n" +
        "uniform float3 placeB;\n" +
        "uniform float mixB;\n" +
        "uniform float2 origin;\n" +        // the clock's top-left corner on screen
        "uniform float2 ink;\n" +           // top and height of the digits (in the bitmaps)
        "uniform float refraction;\n" +     // how many px the edge bends the photo
        "uniform float specular;\n" +
        "uniform float alpha;\n" +
        "half3 photo(float2 p) {\n" +
        "  half3 a = imageA.eval((p - placeA.xy) / placeA.z).rgb;\n" +
        "  if (mixB <= 0.0) { return a; }\n" +
        "  return mix(a, imageB.eval((p - placeB.xy) / placeB.z).rgb, half(mixB));\n" +
        "}\n" +
        "half4 main(float2 xy) {\n" +
        "  float2 local = xy - origin;\n" +
        "  half a = cover.eval(local).a;\n" +
        "  if (a <= 0.0) { return half4(0.0); }\n" +
        "  half3 s = slope.eval(local).rgb;\n" +
        "  float2 n = (float2(s.r, s.g) - 0.5) * 2.0;\n" +
        "  float bend = length(n);\n" +
        "  float height = float(s.b);\n" +
        "  float2 p = xy + n * refraction;\n" +
        "  half3 bg;\n" +
        "  if (bend > 0.02) {\n" +
        "    float2 ca = n * (refraction * 0.2);\n" +
        "    bg = half3(photo(p + ca).r, photo(p).g, photo(p - ca).b);\n" +
        "  } else {\n" +
        "    bg = photo(p);\n" +
        "  }\n" +
        "  half l = dot(bg, half3(0.2126, 0.7152, 0.0722));\n" +
        "  bg = mix(half3(l), bg, half(1.15));\n" +
        "  bg = max(bg, half3(0.22, 0.24, 0.28));\n" +
        "  float ty = clamp((local.y - ink.x) / ink.y, 0.0, 1.0);\n" +
        // Frosted crystal glass: transmits blurred wallpaper tints while keeping luminous pearl legibility on dark & bright scenes.
        "  float frost = 0.46 + 0.16 * (1.0 - ty);\n" +
        "  half3 glassBase = bg * half(0.86) + half3(0.06);\n" +
        "  half3 col = mix(glassBase, half3(0.96, 0.98, 1.0), half(frost));\n" +
        "  float2 dir = n / max(bend, 0.001);\n" +
        "  float2 L = float2(-0.447, -0.894);\n" +
        "  float facing = clamp(-dot(dir, L), 0.0, 1.0);\n" +
        "  float back = clamp(dot(dir, L), 0.0, 1.0);\n" +
        "  float edge = smoothstep(0.08, 0.42, bend);\n" +
        "  float rim = (1.0 - smoothstep(0.10, 0.56, height)) * edge;\n" +
        "  float glow = (1.0 - smoothstep(0.18, 0.82, height)) * edge;\n" +
        "  col += half3((rim * (0.16 + 0.42 * facing + 0.18 * back) + glow * (0.04 + 0.06 * facing)) * specular);\n" +
        "  col *= half(1.0 - 0.1 * back * bend * (1.0 - rim));\n" +
        "  half k = a * half(alpha);\n" +
        "  return half4(col * k, k);\n" +
        "}\n";

    /**
     * Gradual Blur-Vignette hero backdrop for Android 13+ (API 33+):
     * - In Light Mode (`themeColor.r > 0.5`, `nonHome == 0.0`): applies an overall frosted blur (`homeBlurMix = 1.0`)
     *   paired with a directional white gradient (`maxVig = 0.88`) and soft upper-left specular lift.
     * - In Dark Mode (`themeColor.r <= 0.5`, `nonHome == 0.0`): applies a 12% overall dim (`darkDim = 0.12`),
     *   a directional dark-gradient (`rawHomeVig`), and a directional blur-gradient (`darkBlurGrad`) behind the
     *   left metadata column and bottom card stage while keeping the open upper-right hero showcase razor-sharp.
     * - On non-Home screens (`nonHome == 1.0`): switches to the smooth blurred backdrop (`blurImg`) with a uniform
     *   50% dim toward `themeColor`.
     */
    static final String HERO_VIGNETTE =
        "uniform shader sharpImg;\n" +
        "uniform shader blurImg;\n" +
        "uniform float2 res;\n" +
        "uniform half4 themeColor;\n" +
        "uniform float alpha;\n" +
        "uniform float nonHome;\n" +
        "half4 main(float2 xy) {\n" +
        "  float2 uv = xy / res;\n" +
        "  half4 sharp = sharpImg.eval(xy);\n" +
        "  float leftCurve = 1.0 - smoothstep(0.04, 0.86, uv.x);\n" +
        "  float leftVig = leftCurve * (0.45 + 0.55 * leftCurve) * 0.88;\n" +
        "  float bottomVig = smoothstep(0.26, 1.0, uv.y) * 0.93;\n" +
        "  float topVig = (1.0 - smoothstep(0.0, 0.25, uv.y)) * 0.46;\n" +
        "  float baseVig = 1.0 - (1.0 - leftVig) * (1.0 - bottomVig);\n" +
        "  float rawHomeVig = 1.0 - (1.0 - baseVig) * (1.0 - topVig);\n" +
        "  bool isLight = (themeColor.r > 0.5);\n" +
        "  float darkDim = isLight ? 0.0 : 0.12;\n" +
        "  float maxVig = isLight ? 0.88 : 1.0;\n" +
        "  float homeVig = 1.0 - (1.0 - darkDim) * (1.0 - rawHomeVig * maxVig);\n" +
        "  float leftBlur = (1.0 - smoothstep(0.08, 0.72, uv.x)) * 0.82;\n" +
        "  float bottomBlur = smoothstep(0.38, 0.96, uv.y) * 0.88;\n" +
        "  float darkBlurGrad = 1.0 - (1.0 - leftBlur) * (1.0 - bottomBlur);\n" +
        "  float homeBlurMix = isLight ? 1.0 : darkBlurGrad;\n" +
        "  float nhTotalMix = 0.50;\n" +
        "  float blurMix = mix(homeBlurMix, 1.0, nonHome);\n" +
        "  float vig = mix(homeVig, nhTotalMix, nonHome);\n" +
        "  half4 photo = (blurMix > 0.999) ? blurImg.eval(xy) : ((blurMix > 0.001) ? mix(sharp, blurImg.eval(xy), blurMix) : sharp);\n" +
        "  if (isLight) {\n" +
        "    photo.rgb += half3((1.0 - uv.y) * leftCurve * 0.045);\n" +
        "  }\n" +
        "  half4 result = mix(photo, themeColor, vig);\n" +
        "  return result * half(alpha);\n" +
        "}\n";

    /** Aurora colors: three tones (background, middle, highlight), from 0 to 1. */
    static final class Palette {
        final float[] c = new float[9];

        Palette set(int a, int b, int d) {
            put(0, a); put(3, b); put(6, d);
            return this;
        }

        private void put(int i, int rgb) {
            c[i] = ((rgb >> 16) & 255) / 255f;
            c[i + 1] = ((rgb >> 8) & 255) / 255f;
            c[i + 2] = (rgb & 255) / 255f;
        }

        int color(int i) {
            return 0xFF000000 | (Math.round(c[i * 3] * 255) << 16) | (Math.round(c[i * 3 + 1] * 255) << 8) | Math.round(c[i * 3 + 2] * 255);
        }

        /** Luminance of the brightest tone (0 to 1): bright palettes (day, dawn) get darkened a little. */
        float brightest() {
            float max = 0;
            for (int i = 0; i < 9; i += 3) max = Math.max(max, 0.2126f * c[i] + 0.7152f * c[i + 1] + 0.0722f * c[i + 2]);
            return max;
        }

        void apply(RuntimeShader s) {
            s.setFloatUniform("c0", c[0], c[1], c[2]);
            s.setFloatUniform("c1", c[3], c[4], c[5]);
            s.setFloatUniform("c2", c[6], c[7], c[8]);
        }
    }

    // Sky phases through the day.
    private static final int[] NIGHT = {0x070B1E, 0x16225A, 0x4B2F8F};
    private static final int[] DAWN = {0x24183F, 0xE89A55, 0xF6D59A};
    private static final int[] DAY = {0x1E57C8, 0x4FB9EC, 0x9EE2EE};
    private static final int[] SUNSET = {0x2E1650, 0xE2546C, 0xF39A4F};
    private static final int[] DUSK = {0x10173A, 0x36348A, 0x8A4BB0};
    private static final int[][] SKY_KEYS = {NIGHT, DAWN, DAY, DAY, SUNSET, DUSK, NIGHT};
    private static final long DAY_MS = 86_400_000L;

    private static volatile long sTzCachedMinute = Long.MIN_VALUE;
    private static volatile int sTzOffsetMs = 0;
    private static volatile String sTzCachedId = "";

    static int localTimeZoneOffsetMs(long epochMs) {
        long minuteBucket = epochMs / 60_000L;
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        String tzId = tz != null ? tz.getID() : "";
        if (minuteBucket != sTzCachedMinute || !tzId.equals(sTzCachedId)) {
            sTzOffsetMs = tz != null ? tz.getOffset(epochMs) : 0;
            sTzCachedId = tzId;
            sTzCachedMinute = minuteBucket;
        }
        return sTzOffsetMs;
    }

    /** Zero-allocation local milliseconds since midnight [0, 86_400_000). */
    static long localMillisSinceMidnight(long epochMs) {
        return Math.floorMod(epochMs + (long) localTimeZoneOffsetMs(epochMs), DAY_MS);
    }

    /**
     * Palette for the sun's current position at your location. Uses the sunrise and sunset times from the last weather
     * fetch (they shift ~1 min a day, so yesterday's times are fine today); without data, 5:30 and 17:40.
     * Computed via fast epoch arithmetic + 1-minute cached TimeZone offset with zero per-frame allocations.
     */
    static void sky(Palette out, long now, long sunrise, long sunset) {
        long midnight = now - localMillisSinceMidnight(now);
        long rise = midnight + timeOfDay(sunrise, (5 * 60 + 30) * 60_000L);
        long set = midnight + timeOfDay(sunset, (17 * 60 + 40) * 60_000L);
        long m = 60_000L;
        long at0 = rise - 60 * m;
        long at1 = rise - 15 * m;
        long at2 = rise + 45 * m;
        long at3 = set - 45 * m;
        long at4 = set;
        long at5 = set + 45 * m;
        long at6 = set + 105 * m;
        if (now <= at0 || now >= at6) {
            out.set(NIGHT[0], NIGHT[1], NIGHT[2]);
            return;
        }
        long segStart, segEnd;
        int segIdx;
        if (now < at1) {
            segIdx = 0; segStart = at0; segEnd = at1;
        } else if (now < at2) {
            segIdx = 1; segStart = at1; segEnd = at2;
        } else if (now < at3) {
            segIdx = 2; segStart = at2; segEnd = at3;
        } else if (now < at4) {
            segIdx = 3; segStart = at3; segEnd = at4;
        } else if (now < at5) {
            segIdx = 4; segStart = at4; segEnd = at5;
        } else {
            segIdx = 5; segStart = at5; segEnd = at6;
        }
        long span = Math.max(1L, segEnd - segStart);
        float f = (float) (now - segStart) / span;
        int[] k0 = SKY_KEYS[segIdx];
        int[] k1 = SKY_KEYS[segIdx + 1];
        out.set(mix(k0[0], k1[0], f), mix(k0[1], k1[1], f), mix(k0[2], k1[2], f));
    }

    /** Time of day (ms since local midnight) of an instant; `fallback` if there is no data. */
    static long timeOfDay(long at, long fallback) {
        if (at == 0) return fallback;
        long dayMs = localMillisSinceMidnight(at);
        return (dayMs / 1000L) * 1000L;
    }

    /** Screensaver: always the night palette, by day and by night. */
    static void dream(Palette out) {
        out.set(NIGHT[0], NIGHT[1], NIGHT[2]);
    }

    /**
     * Settings: dark and nearly neutral at any hour, like the home screen's plain background at night (the day's sky
     * read as a flat bright blue there, above all without AGSL, where it's a plain gradient); a trace of violet moves in
     * the aurora.
     */
    static void settings(Palette out) {
        out.set(0x08090C, 0x141519, 0x2B2838);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return (r << 16) | (g << 8) | bl;
    }
}
