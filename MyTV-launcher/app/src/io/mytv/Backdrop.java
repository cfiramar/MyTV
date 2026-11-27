package io.mytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * The home screen background made from the recommendation's image, built once in the background:
 *  - the photo cropped to 16:9 at the TV's UI size (1920×1080; 1280×720 on TVs that draw their UI at 720p, like TCL's
 *    1 GB models, where every bitmap here is 2/3 as wide), with the dark scrims already applied (on the left,
 *    where the hero text sits; at the bottom, behind the shelf; a faint one at the top, behind the buttons); the left
 *    scrim gets stronger when the photo is bright there;
 *  - the frosted version (192×108, heavily blurred) of the same image: the background of the frosted glass and of the
 *    bottom drawer;
 *  - the Liquid Glass version (480×270, lightly blurred): the photo shows through the shelf and the cards;
 *  - with forLight: the card (the photo without scrims, cropped wide, already at the focused card size) and
 *    the frosted and glass versions blended with white (by day) and with near-black (at night).
 * Only what is needed gets built (with forLight, no full photo: the cards are lightweight, so all 9 for the three tabs
 * fit in memory, ready); the other fields stay null.
 * The final image lives only in GPU memory (HARDWARE): drawing it costs one texture, and the process doesn't hold 8 MB.
 */
final class Backdrop {
    /** Scale factor for card bitmaps (1.0f for full-resolution 4K/1080p clarity). */
    static final float UI_SCALE = 1.0f;
    static final int DISPLAY_W = detectDisplayWidth();
    static final int W = 3840;
    static final int H = 2160;
    static final int FW = 192, FH = 108, GW = 480, GH = 270;
    /** 16:9 card size and exact 2:3 narrow poster cards (500×750 high-res portrait). */
    static final int WIDE_W = 792, WIDE_H = 446, TALL_W = 500, TALL_H = 750;
    /** Safe post-crossfade bitmap recycle delay (> HomeView.FADE_MS = 450ms). */
    static final long SAFE_RECYCLE_DELAY_MS = 550L;

    private static volatile Backdrop activeDisplayedFrom;
    private static volatile Backdrop activeDisplayedTo;

    /**
     * Tracks the currently displayed / cross-fading Backdrops in HomeView so deferred LRU recycling
     * never recycles a Bitmap that is actively bound to a Canvas or RuntimeShader.
     */
    static void setDisplayedBackdrops(Backdrop from, Backdrop to) {
        activeDisplayedFrom = from;
        activeDisplayedTo = to;
    }

    static boolean isDisplayedBackdrop(Backdrop b) {
        return b != null && (b == activeDisplayedFrom || b == activeDisplayedTo);
    }

    /**
     * Schedules safe post-fade recycling of a Backdrop 550ms (> 450ms crossfade) on the main looper.
     * If still actively displayed when the timer fires, defers by another 550ms instead of recycling.
     */
    static void scheduleSafeRecycle(final Backdrop b) {
        if (b == null || b.isRecycled()) return;
        scheduleSafeRecycleInternal(b, 0);
    }

    private static void scheduleSafeRecycleInternal(final Backdrop b, final int attempt) {
        if (b == null || b.isRecycled()) return;
        try {
            android.os.Looper mainLooper = android.os.Looper.getMainLooper();
            if (mainLooper != null) {
                new android.os.Handler(mainLooper).postDelayed(() -> {
                    if (b.isRecycled()) return;
                    if (isDisplayedBackdrop(b)) {
                        if (attempt < 8) {
                            scheduleSafeRecycleInternal(b, attempt + 1);
                        }
                        return;
                    }
                    b.recycle();
                }, SAFE_RECYCLE_DELAY_MS);
                return;
            }
        } catch (Throwable ignored) {
            // Headless desktop JVM unit tests where Looper throws Stub!
        }
        if (!isDisplayedBackdrop(b)) {
            b.recycle();
        }
    }

    /** Schedules safe 550ms post-frame recycling of a standalone Bitmap on the main looper. */
    static void scheduleSafeBitmapRecycle(final Bitmap bmp) {
        if (bmp == null) return;
        try {
            if (bmp.isRecycled()) return;
        } catch (Throwable ignored) {
            return;
        }
        try {
            android.os.Looper mainLooper = android.os.Looper.getMainLooper();
            if (mainLooper != null) {
                new android.os.Handler(mainLooper).postDelayed(() -> safeRecycleBitmapNow(bmp), SAFE_RECYCLE_DELAY_MS);
                return;
            }
        } catch (Throwable ignored) {
        }
        safeRecycleBitmapNow(bmp);
    }

    private static void safeRecycleBitmapNow(Bitmap bmp) {
        if (bmp == null) return;
        try {
            if (!bmp.isRecycled()) bmp.recycle();
        } catch (Throwable ignored) {
        }
    }

    private static int detectDisplayWidth() {
        try {
            android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
            if (dm != null && dm.widthPixels > 0) {
                return Math.max(dm.widthPixels, dm.heightPixels);
            }
        } catch (Throwable ignored) {
        }
        return 1920;
    }

    private static float uiScale() {
        try {
            if (HomeApps.lowRam(null)) return 0.67f;
            return 1f;
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    private static int ui(int px) {
        return Math.round(px * UI_SCALE);
    }
    final Bitmap image;
    final Bitmap frostBitmap;
    final Bitmap softBlurBitmap;
    final BitmapShader sharpShader;
    final BitmapShader frostShader;
    final BitmapShader softBlurShader;
    final int[] frost;      // heavily blurred (frosted glass, bottom drawer)
    final int[] glass;      // lightly blurred (Liquid Glass: the photo shows through the glass)
    final Bitmap wide;                  // the photo on the cards
    final BitmapShader wideShader;      // its shader, for the rounded cards: it goes away with the backdrop
    private final Matrix sharpMatrix = createMatrixOrNull();
    private final Matrix blurMatrix = createMatrixOrNull();
    private final Matrix softBlurMatrix = createMatrixOrNull();
    private int lastScaleW, lastScaleH;

    private static Matrix createMatrixOrNull() {
        try {
            return new Matrix();
        } catch (Throwable ignored) {
            return null;
        }
    }
    final int[] light, lightGlass;     // by day: the frosted and glass versions, lightened
    final Bitmap lightImage;            // by day: the background (the lightened frosted version, scaled up on screen)
    final int[] nightFrost, nightGlass; // at night: frosted (the bottom drawer's frosted glass) and glass, darkened
    final Bitmap nightImage;            // at night: the background (the darkened frosted version)
    private volatile boolean recycled;

    /** Exposed package-private factory for unit-testing active-display recycle guards on headless JVM. */
    static Backdrop emptyForTesting() {
        return new Backdrop(null, null, null, null, null, null, null, null);
    }

    boolean isRecycled() {
        return recycled;
    }

    /** Recycles all distinct non-null Bitmaps owned by this Backdrop once safely off-screen. */
    void recycle() {
        if (recycled) return;
        recycled = true;
        safeRecycleBitmapNow(image);
        safeRecycleBitmapNow(frostBitmap);
        if (softBlurBitmap != frostBitmap) safeRecycleBitmapNow(softBlurBitmap);
        if (wide != image) safeRecycleBitmapNow(wide);
        safeRecycleBitmapNow(lightImage);
        if (nightImage != lightImage) safeRecycleBitmapNow(nightImage);
    }

    private Backdrop(Bitmap image, int[] frost, int[] glass, Bitmap wide, int[] light, int[] lightGlass,
                     int[] night, int[] nightGlass) {
        this(image, frost, glass, wide, light, lightGlass, night, nightGlass, light, night, FW, FH);
    }

    /** `lightBg` and `nightBg` (bw × bh): the background itself, when it isn't the frosted version. */
    private Backdrop(Bitmap image, int[] frost, int[] glass, Bitmap wide, int[] light, int[] lightGlass,
                     int[] night, int[] nightGlass, int[] lightBg, int[] nightBg, int bw, int bh) {
        this.image = image;
        this.frost = frost;
        this.frostBitmap = frost == null ? null : tryCreateBitmap(frost, FW, FH);
        this.glass = glass;
        this.softBlurBitmap = glass == null ? this.frostBitmap : tryCreateBitmap(glass, GW, GH);
        this.wide = wide;
        wideShader = wide == null ? null : createShader(wide);
        sharpShader = image == null ? null : createShader(image);
        frostShader = frostBitmap == null ? null : createShader(frostBitmap);
        softBlurShader = softBlurBitmap == null ? frostShader : createShader(softBlurBitmap);
        this.light = light;
        this.lightGlass = lightGlass;
        this.nightFrost = night;
        this.nightGlass = nightGlass;
        lightImage = (image == null && lightBg != null) ? tryCreateBitmap(lightBg, bw, bh) : null;
        nightImage = (image == null && nightBg != null) ? tryCreateBitmap(nightBg, bw, bh) : null;
    }

    private static BitmapShader createShader(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        }
        return s;
    }

    void prepareShaders(int w, int h) {
        if (w <= 0 || h <= 0 || (w == lastScaleW && h == lastScaleH)) return;
        lastScaleW = w;
        lastScaleH = h;
        if (sharpShader != null && image != null && sharpMatrix != null) {
            float scale = Math.max((float) w / image.getWidth(), (float) h / image.getHeight());
            sharpMatrix.setScale(scale, scale);
            sharpMatrix.postTranslate(w - image.getWidth() * scale, 0f);
            sharpShader.setLocalMatrix(sharpMatrix);
        }
        if (frostShader != null && frostBitmap != null && blurMatrix != null) {
            blurMatrix.setScale((float) w / frostBitmap.getWidth(), (float) h / frostBitmap.getHeight());
            frostShader.setLocalMatrix(blurMatrix);
        }
        if (softBlurShader != null && softBlurShader != frostShader && softBlurBitmap != null && softBlurMatrix != null) {
            softBlurMatrix.setScale((float) w / softBlurBitmap.getWidth(), (float) h / softBlurBitmap.getHeight());
            softBlurShader.setLocalMatrix(softBlurMatrix);
        }
    }

    private static Bitmap tryCreateBitmap(int[] colors, int w, int h) {
        try {
            return Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Backdrop make(Bitmap src, boolean forLight) {
        return make(src, forLight, false);
    }

    /**
     * `own`: one of your photos (Photos). Its background is less blurred than a pick's; behind the clock, date and
     * weather it is whitened (darkened at night) as much as a pick's, so the text stays readable, then less toward the
     * right, so it still reads as your photo. The glass and the drawer stay as they are.
     */
    static Backdrop make(Bitmap src, boolean forLight, boolean own) {
        // Cropped to 16:9 at the source's resolution first, then filtered down up to 4K (never upscaling smaller sources on CPU).
        Bitmap crop = center(src, 16f / 9f);
        if (crop != src) src.recycle();
        int targetW = Math.min(W, Math.max(1, crop.getWidth()));
        int targetH = Math.max(1, Math.round(targetW * 9f / 16f));
        Bitmap out = (crop.getWidth() > targetW || crop.getHeight() > targetH)
                ? Ui.downscale(crop, targetW, targetH) : crop;
        if (out != crop) crop.recycle();
        if (forLight) {
            // Fast single-stage downscale to GW × GH (480×270) before CPU box-blur passes (~50× fewer pixels than 4K/1080p).
            Bitmap baseSmall = (out.getWidth() == GW && out.getHeight() == GH)
                    ? out : Bitmap.createScaledBitmap(out, GW, GH, true);
            int[] glass = reduce(baseSmall, GW, GH, 4, 2);
            int[] frost = reduce(baseSmall, FW, FH, 4, 3);
            int[] light = whiten(frost.clone(), 0.82f);
            int[] lightGlass = whiten(glass.clone(), 0.74f);
            int[] night = darken(frost.clone(), 0.76f);
            int[] nightGlass = darken(glass.clone(), 0.56f);
            if (own) {
                int[] sharp = reduce(baseSmall, GW, GH, 1, 1);
                if (baseSmall != out) baseSmall.recycle();
                int[] lightBg = ramp(sharp.clone(), 0.82f, 0.45f, true), nightBg = ramp(sharp, 0.76f, 0.5f, false);
                Bitmap heroImg = gpu(out);
                return new Backdrop(heroImg, frost, glass, null, light, lightGlass, night, nightGlass, lightBg, nightBg, GW, GH);
            }
            if (baseSmall != out) baseSmall.recycle();
            Bitmap heroImg = gpu(out);
            return new Backdrop(heroImg, frost, glass, null, light, lightGlass, night, nightGlass);
        }

        if (!out.isMutable()) {
            Bitmap copy = out.copy(Bitmap.Config.ARGB_8888, true);
            out.recycle();
            out = copy;
        }
        int ow = out.getWidth(), oh = out.getHeight();
        Canvas c = new Canvas(out);
        float boost = Math.max(0, Math.min(0.3f, (luminance(out, 0, 0.22f, 0.5f, 0.62f) - 0.22f) * 1.1f));
        Paint scrim = new Paint(Paint.DITHER_FLAG);
        scrim.setShader(new LinearGradient(0, 0, ow * 0.68f, 0,
                new int[] {black(0.66f + boost), black(0.52f + boost * 0.8f), black(0.26f + boost * 0.4f), black(0.07f), 0},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, ow, oh, scrim);
        // At the bottom, a faint scrim: the photo must show through the glass shelf (the glass provides the contrast).
        scrim.setShader(new LinearGradient(0, oh, 0, oh * 0.4f,
                new int[] {black(0.38f), black(0.3f), black(0.18f), black(0.05f), 0},
                new float[] {0, 0.25f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, oh * 0.4f, ow, oh, scrim);
        // Behind the prices (above the shelf, on the right), a localized scrim for the small text.
        scrim.setShader(new android.graphics.RadialGradient(ow * 0.8f, oh * 0.65f, ow * 0.24f,
                new int[] {black(0.42f), black(0.18f), 0}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 0.32f, ow * 0.8f, oh * 0.65f);
        c.drawCircle(ow * 0.8f, oh * 0.65f, ow * 0.24f, scrim);
        c.restore();
        scrim.setShader(new LinearGradient(0, 0, 0, oh * 0.24f, new int[] {black(0.34f), black(0.1f), 0},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, ow, oh * 0.24f, scrim);

        int[] frost = reduce(out, FW, FH, 3, 3);
        int[] glass = reduce(out, GW, GH, 2, 2);
        return new Backdrop(gpu(out), frost, glass, null, null, null, null, null);
    }

    /** How many times smaller the screensaver photo's frosted version is (the glass math uses the same number). */
    static final int PHOTO_FROST = 6;

    /**
     * Screensaver: the whole original from disk at w × h (center-cropped to that aspect ratio), filtered down, plus the
     * frosted version (1/6, blurred) for the glass clock. `light` receives the luminance (0 to 1) where the caption
     * (bottom left) and the clock (top right) sit, for the scrims. Returns {photo (mutable), frosted}.
     */
    static Bitmap[] photo(java.io.File f, int w, int h, float[] light) throws java.io.IOException {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("unreadable image");
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = 1;
        opts.inMutable = true;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
        Bitmap src = android.graphics.BitmapFactory.decodeFile(f.getPath(), opts);
        if (src == null) throw new java.io.IOException("unreadable image");
        float ratio = (float) w / h;
        int cw = Math.min(src.getWidth(), Math.round(src.getHeight() * ratio));
        int ch = Math.min(src.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(src, (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
        if (b != src) src.recycle();
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            b.recycle();
            b = half;
        }
        Bitmap out = b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true);
        if (out != b) b.recycle();
        if (light != null && light.length >= 2) {
            light[0] = luminance(out, 0, 0.6f, 0.5f, 0.95f);
            light[1] = luminance(out, 0.62f, 0.04f, 0.96f, 0.36f);
        }
        int fw = w / PHOTO_FROST, fh = h / PHOTO_FROST;
        Bitmap frost = Bitmap.createBitmap(reduce(out, fw, fh, 3, 2), fw, fh, Bitmap.Config.ARGB_8888);
        if (!out.isMutable()) {
            Bitmap copy = out.copy(Bitmap.Config.ARGB_8888, true);
            out.recycle();
            out = copy;
        }
        return new Bitmap[] {out, frost};
    }

    /** The center of the photo at the card's aspect ratio, filtered down to the exact size. */
    static Bitmap card(Bitmap full, int w, int h) {
        return card(full, w, h, 0.5f);
    }

    /**
     * The photo at the card's aspect ratio, filtered down to the exact size. Where it's taller than the card, `top`
     * says where the cut goes: 0 keeps its top and takes the strip off the bottom, 0.5 takes half from each.
     */
    static Bitmap card(Bitmap full, int w, int h, float top) {
        float ratio = (float) w / h;
        int cw = Math.min(full.getWidth(), Math.round(full.getHeight() * ratio));
        int ch = Math.min(full.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(full, (full.getWidth() - cw) / 2, Math.round((full.getHeight() - ch) * top), cw, ch);
        Bitmap out = Ui.downscale(b, w, h);
        if (b != full && b != out) b.recycle();
        if (out == full) {
            Bitmap copy = full.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) return full;
            Bitmap hw = gpu(copy);
            if (hw != copy && !copy.isRecycled()) copy.recycle();
            return hw;
        }
        return gpu(out);
    }

    /**
     * Computes top-right anchored crop rectangle `[left, top, width, height]` so any required crop
     * removes only from the left side or bottom side, never from the top or right side.
     */
    static int[] computeTopRightCropRect(int srcW, int srcH, float ratio) {
        int cw = Math.min(srcW, Math.max(1, Math.round(srcH * ratio)));
        int ch = Math.min(srcH, Math.max(1, Math.round(cw / ratio)));
        int left = Math.max(0, srcW - cw);
        int top = 0;
        return new int[] { left, top, cw, ch };
    }

    /** Top-right anchored crop at that aspect ratio (crops bottom or left side, never top or right). */
    private static Bitmap center(Bitmap b, float ratio) {
        int[] r = computeTopRightCropRect(b.getWidth(), b.getHeight(), ratio);
        return Bitmap.createBitmap(b, r[0], r[1], r[2], r[3]);
    }

    /** Copies it to GPU memory (cheap to draw; the process doesn't keep the pixels). */
    static Bitmap gpu(Bitmap b) {
        Bitmap g = b.copy(Bitmap.Config.HARDWARE, false);
        if (g == null) return b;
        b.recycle();
        return g;
    }

    /** Blends with a near-black (the darkest gray of Apple's dark-mode backgrounds), keeping the photo's tint. */
    private static int[] darken(int[] px, float amount) {
        float keep = 1 - amount, base = 11 * amount;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * keep + base);
            int g = Math.round(((p >> 8) & 255) * keep + base);
            int b = Math.round((p & 255) * keep + base + 2 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    /** Whitens (or darkens) by `left` up to 45% of the width, easing to `right` by 70% (a GW × GH image). */
    private static int[] ramp(int[] px, float left, float right, boolean light) {
        int[] col = new int[GH];
        for (int x = 0; x < GW; x++) {
            float t = Math.max(0, Math.min(1, (x / (float) GW - 0.45f) / 0.25f));
            float a = left + (right - left) * t * t * (3 - 2 * t);
            for (int y = 0; y < GH; y++) col[y] = px[y * GW + x];
            if (light) whiten(col, a); else darken(col, a);
            for (int y = 0; y < GH; y++) px[y * GW + x] = col[y];
        }
        return px;
    }

    private static int[] whiten(int[] px, float amount) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * (1 - amount) + 255 * amount);
            int g = Math.round(((p >> 8) & 255) * (1 - amount) + 255 * amount);
            int b = Math.round((p & 255) * (1 - amount) + 255 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    /** Halved at each step (a true average, no aliasing), then blurred (box passes of radius r). */
    private static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap b = full;
        while (b.getWidth() / 2 >= w * 1.5f) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full) b.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        return px;
    }

    /** Moving average of radius r in one direction, repeating the edge pixels. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float k = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            float sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = 0xFF000000 | (Math.round(sr * k) << 16) | (Math.round(sg * k) << 8) | Math.round(sb * k);
                int add = in[base + Math.min(n - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** Average luminance (0 to 1) of a rectangle given as fractions of the image, by sampling. */
    private static float luminance(Bitmap b, float x0, float y0, float x1, float y1) {
        float sum = 0;
        int count = 0;
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 32; i++) {
                int x = Math.round((x0 + (x1 - x0) * (i + 0.5f) / 32) * (b.getWidth() - 1));
                int y = Math.round((y0 + (y1 - y0) * (j + 0.5f) / 24) * (b.getHeight() - 1));
                int p = b.getPixel(x, y);
                sum += (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
                count++;
            }
        }
        return sum / count;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }
}
