package io.soartv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "Continue watching" cards' pictures, decoded in the background once, at the card's size on screen (16:9,
 * centre-cropped), and kept in memory. Supports content://, android.resource://, and http/https:// artwork URIs,
 * plus automatic TMDB / Cinemeta movie & series backdrop lookup by title when an app omits artwork. Never falls back
 * to the streaming app's icon/banner as the card image.
 */
final class WatchArt {
    /** Exact 2:3 portrait card size (500×750 high-res portrait) matching Backdrop.TALL_W × TALL_H. */
    static final int W = 500, H = 750;
    private static final int MAX_BYTES = 12 << 20;
    /** At most this many pixels decoded (~16 MB): supports native 4K card bitmaps. */
    private static final long MAX_PIXELS = 4_000_000;

    private final Context app;
    private final Runnable onLoaded;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Dedicated 2-thread daemon pool so Continue Watching card art loads concurrently without leaving stray threads. */
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {
            }
            r.run();
        }, "soartv-watch-art");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;
    private volatile int pw = W, ph = H;   // the pictures' size in pixels
    private static final long RETRY_MS = 10 * 60_000L;
    private final Set<String> pending = new HashSet<>();
    private final Map<String, Long> failed = new HashMap<>();
    private final Map<String, Integer> colors = new HashMap<>();   // each picture's colour, for the background
    private final Map<Bitmap, BitmapShader> shaders = new HashMap<>();   // for the rounded corners, made with the picture
    private final Map<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(24, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            if (size() <= 24) return false;
            Bitmap evicted = eldest.getValue();
            shaders.remove(evicted);
            Backdrop.scheduleSafeBitmapRecycle(evicted);
            return true;
        }
    };

    WatchArt(Context c, Runnable onLoaded) {
        app = c.getApplicationContext();
        this.onLoaded = onLoaded;
        IO.execute(() -> {
            try {
                java.io.File dir = new java.io.File(app.getCacheDir(), "watch-art");
                java.io.File[] stale = dir.listFiles((d, name) -> name.startsWith("wa16_"));
                for (int i = 0; stale != null && i < stale.length; i++) {
                    //noinspection ResultOfMethodCallIgnored
                    stale[i].delete();
                }
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * The card's movie/series picture, looking up TMDB/Cinemeta by title if the app provided no URI or the URI failed.
     */
    Bitmap get(Uri uri, String key, String bannerKey, String pkg) {
        return get(uri, key, bannerKey, pkg, null, false);
    }

    Bitmap get(Uri uri, String key, String bannerKey, String pkg, String title, boolean isSeries) {
        if (uri != null && key != null) {
            Bitmap b = fetch(key, uri, pkg, title, isSeries);
            if (b != null || !failedRecently(key)) return b;
        }
        String fallbackKey = title != null && !title.isEmpty() ? ("tmdb:" + (isSeries ? "tv:" : "movie:") + title) : bannerKey;
        return fetch(fallbackKey, null, pkg, title, isSeries);
    }

    /** The UI's scale (width / 1920): pictures already made at another size are made again. */
    void setScale(float k) {
        int width = Math.max(1, Math.round(W * k)), height = Math.max(1, Math.round(H * k));
        if (width == pw && height == ph) return;
        pw = width;
        ph = height;
        synchronized (cache) {
            for (Bitmap bmp : cache.values()) {
                Backdrop.scheduleSafeBitmapRecycle(bmp);
            }
            cache.clear();
            shaders.clear();
        }
    }

    /** The picture's shader (get's pictures only). */
    BitmapShader shader(Bitmap b) {
        synchronized (cache) {
            return shaders.get(b);
        }
    }

    /** The colour of the card's picture (or of the banner in its place), or null until it's decoded. */
    Integer color(String key, String bannerKey) {
        synchronized (cache) {
            if (key != null) {
                Integer c = colors.get(key);
                Long at = failed.get(key);
                if (c != null || at == null || System.currentTimeMillis() - at >= RETRY_MS) return c;
            }
            return colors.get(bannerKey);
        }
    }

    /** The home screen went away: nothing more is posted to it, and the pictures go. */
    void close() {
        closed = true;
        synchronized (cache) {
            for (Bitmap bmp : cache.values()) {
                Backdrop.scheduleSafeBitmapRecycle(bmp);
            }
            cache.clear();
            shaders.clear();
            colors.clear();
        }
    }

    private boolean failedRecently(String uri) {
        synchronized (cache) {
            Long at = failed.get(uri);
            return at != null && System.currentTimeMillis() - at < RETRY_MS;
        }
    }

    private Bitmap fetch(String key, Uri uri, String pkg, String title, boolean isSeries) {
        if (closed) return null;
        synchronized (cache) {
            Bitmap b = cache.get(key);
            if (b != null || pending.contains(key)) return b;
            Long at = failed.get(key);
            if (at != null && System.currentTimeMillis() - at < RETRY_MS) return null;
            pending.add(key);
        }
        IO.execute(() -> {
            if (closed) return;
            Bitmap b = null;
            try {
                if (uri != null) {
                    b = load(uri, title, isSeries, pkg);
                } else if (title != null && !title.isEmpty()) {
                    b = loadMediaArtByTitle(title, isSeries, pkg);
                } else {
                    b = titleCard(title != null ? title : "", pkg);
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("continue watching picture: " + e.getMessage());
                if (uri != null && title != null && !title.isEmpty()) {
                    try {
                        b = loadMediaArtByTitle(title, isSeries, pkg);
                    } catch (Exception ignored) {
                    }
                }
            }
            Integer color = b == null ? null : colorOf(b);
            BitmapShader shader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (shader != null && android.os.Build.VERSION.SDK_INT >= 33) {
                shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            }
            synchronized (cache) {
                pending.remove(key);
                if (closed) return;
                if (b != null) {
                    shaders.put(b, shader);
                    cache.put(key, b);
                    colors.put(key, color);
                } else {
                    failed.put(key, System.currentTimeMillis());
                }
            }
            main.post(() -> { if (!closed) onLoaded.run(); });
        });
        return null;
    }

    private Bitmap load(Uri uri) throws IOException {
        return load(uri, null, false, null);
    }

    private Bitmap load(Uri uri, String title, boolean isSeries, String pkg) throws IOException {
        String rawUriStr = uri.toString();
        boolean strict = WatchNext.isStremioPackage(pkg);
        if (!WatchNext.isCleanPosterUrl(rawUriStr, strict)) {
            String clean = WatchNext.sanitizePosterUrl(rawUriStr, null, strict);
            if (clean == null || clean.isEmpty()) {
                if (title != null && !title.isEmpty()) return loadMediaArtByTitle(title, isSeries, pkg);
                throw new IOException("rejected rating-badged poster");
            }
            uri = Uri.parse(clean);
            rawUriStr = clean;
        }
        // Prefer crisp TMDB w780 studio poster over Metahub fallback when an IMDb ID is embedded in the Metahub URL
        if (rawUriStr.contains("metahub.space/poster/")) {
            String tt = WatchNext.extractImdbIdFromStrings(rawUriStr);
            if (!tt.isEmpty()) {
                String tmdbByTt = findTmdbPosterByImdbId(tt, isSeries);
                if (tmdbByTt != null && !tmdbByTt.isEmpty()) {
                    try {
                        return load(Uri.parse(tmdbByTt), null, isSeries, pkg);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        byte[] data = read(uri);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("unreadable picture");
        // If an external provider supplied a wide 16:9 thumbnail for a 2:3 tall card, prefer a true 2:3 portrait poster by title
        if (bounds.outWidth > bounds.outHeight * 1.12f && title != null && title.trim().length() >= 3) {
            try {
                String cleanTitle = title.replaceAll("\\s*\\(\\d{4}\\)\\s*$", "").replaceAll("\\s*-\\s*S\\d+.*$", "").trim();
                String portraitUrl = findTmdbBackdropUrl(cleanTitle, isSeries);
                if (portraitUrl == null) portraitUrl = findCinemetaBackdropUrl(cleanTitle, isSeries);
                if (portraitUrl != null && !portraitUrl.isEmpty() && !portraitUrl.equals(uri.toString())) {
                    return load(Uri.parse(portraitUrl), null, isSeries, pkg);
                }
            } catch (Exception ignored) {
            }
        }
        int cw = pw, ch = ph, bw = bounds.outWidth, bh = bounds.outHeight;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        // Halved while both sides stay at least the card's, or while it's still too many pixels (either side too big).
        for (int s = 1; bw / (s * 2) >= cw && bh / (s * 2) >= ch || (long) (bw / s) * (bh / s) > MAX_PIXELS; s *= 2) {
            opts.inSampleSize = s * 2;
        }
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (src == null) throw new IOException("unreadable picture");
        // Anchor at top (top = 0) when cropping vertically so top-placed poster titles are never cut off.
        int sw = src.getWidth(), sh = src.getHeight();
        Rect from = (long) sw * ch > (long) sh * cw ? new Rect((sw - sh * cw / ch) / 2, 0, (sw + sh * cw / ch) / 2, sh)
                : new Rect(0, 0, sw, Math.min(sh, sw * ch / cw));
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, from, new Rect(0, 0, cw, ch), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        src.recycle();
        return out;
    }

    /**
     * One colour for the whole picture, worked out once here in the background: the average of a tiny copy, with its
     * saturation lifted (a plain average comes out greyish) and its brightness kept in the middle.
     */
    private static int colorOf(Bitmap b) {
        Bitmap tiny = Bitmap.createScaledBitmap(b, 8, 5, true);
        int[] px = new int[40];
        tiny.getPixels(px, 0, 8, 0, 0, 8, 5);
        tiny.recycle();
        long r = 0, g = 0, bl = 0;
        for (int p : px) {
            r += (p >> 16) & 255;
            g += (p >> 8) & 255;
            bl += p & 255;
        }
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV((int) (r / 40), (int) (g / 40), (int) (bl / 40), hsv);
        hsv[1] = Math.min(1, hsv[1] * 1.5f + 0.08f);
        hsv[2] = Math.max(0.45f, Math.min(0.85f, hsv[2]));
        return android.graphics.Color.HSVToColor(hsv);
    }

    /**
     * Looks up the movie or series 16:9 backdrop by title via TMDB (if key is present) or Cinemeta,
     * falling back to a cinema title card (never the streaming app's icon/banner).
     */
    private Bitmap loadMediaArtByTitle(String title, boolean isSeries, String pkg) throws IOException {
        String cleanTitle = title.replaceAll("\\s*\\(\\d{4}\\)\\s*$", "").replaceAll("\\s*-\\s*S\\d+.*$", "").trim();
        String imageUrl = findTmdbBackdropUrl(cleanTitle, isSeries);
        if (imageUrl == null) {
            imageUrl = findCinemetaBackdropUrl(cleanTitle, isSeries);
        }
        if (imageUrl != null && !imageUrl.isEmpty()) {
            return load(Uri.parse(imageUrl));
        }
        return titleCard(title, pkg);
    }

    private String findTmdbPosterByImdbId(String imdbId, boolean isSeries) {
        String key = Recs.key();
        if (key == null || key.isEmpty() || imdbId == null || !imdbId.startsWith("tt")) return null;
        try {
            boolean bearer = key.startsWith("eyJ");
            String url = "https://api.tmdb.org/3/find/" + java.net.URLEncoder.encode(imdbId, "UTF-8")
                    + "?external_source=imdb_id"
                    + (bearer ? "" : "&api_key=" + java.net.URLEncoder.encode(key, "UTF-8"));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Accept", "application/json");
            if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
            try {
                if (c.getResponseCode() != 200) return null;
                byte[] bytes = readAll(c.getInputStream());
                org.json.JSONObject root = new org.json.JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                String[] buckets = isSeries ? new String[] {"tv_results", "movie_results"} : new String[] {"movie_results", "tv_results"};
                for (String bucket : buckets) {
                    org.json.JSONArray arr = root.optJSONArray(bucket);
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        String poster = arr.getJSONObject(i).optString("poster_path", "");
                        if (!poster.isEmpty() && !"null".equals(poster)) {
                            return "https://image.tmdb.org/t/p/w780" + poster;
                        }
                    }
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String findTmdbBackdropUrl(String query, boolean isSeries) {
        String key = Recs.key();
        if (key == null || key.isEmpty() || query.isEmpty()) return null;
        try {
            boolean bearer = key.startsWith("eyJ");
            String endpoint = isSeries ? "/search/tv" : "/search/multi";
            String url = "https://api.tmdb.org/3" + endpoint + "?query="
                    + java.net.URLEncoder.encode(query, "UTF-8")
                    + "&include_adult=false"
                    + (bearer ? "" : "&api_key=" + java.net.URLEncoder.encode(key, "UTF-8"));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("Accept", "application/json");
            if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
            try {
                if (c.getResponseCode() != 200) return null;
                byte[] bytes = readAll(c.getInputStream());
                org.json.JSONObject root = new org.json.JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                org.json.JSONArray results = root.optJSONArray("results");
                // Check poster_path first for portrait 2:3 composition
                for (int i = 0; i < results.length(); i++) {
                    org.json.JSONObject item = results.getJSONObject(i);
                    String poster = item.optString("poster_path", "");
                    if (!poster.isEmpty() && !"null".equals(poster)) {
                        return "https://image.tmdb.org/t/p/w780" + poster;
                    }
                }
                // Fallback to backdrop_path
                for (int i = 0; i < results.length(); i++) {
                    org.json.JSONObject item = results.getJSONObject(i);
                    String bd = item.optString("backdrop_path", "");
                    if (!bd.isEmpty() && !"null".equals(bd)) {
                        return "https://image.tmdb.org/t/p/original" + bd;
                    }
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String findCinemetaBackdropUrl(String query, boolean isSeries) {
        try {
            List<CinemetaClient.MediaItem> items = CinemetaClient.get(app).searchSync(query, isSeries ? "series" : "movie");
            if (items.isEmpty()) items = CinemetaClient.get(app).searchSync(query, isSeries ? "movie" : "series");
            for (CinemetaClient.MediaItem item : items) {
                if (item.posterUrl != null && !item.posterUrl.isEmpty()) return item.posterUrl;
                if (item.backdropUrl != null && !item.backdropUrl.isEmpty()) return item.backdropUrl;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Clean cinema title card when offline and no artwork URL is reachable (never uses the app's icon/banner). */
    private Bitmap titleCard(String title, String pkg) {
        int cw = pw, ch = ph;
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        int hash = (title == null ? 0 : title.hashCode());
        float hue = Math.abs(hash % 360);
        int c1 = android.graphics.Color.HSVToColor(new float[] {hue, 0.55f, 0.28f});
        int c2 = android.graphics.Color.HSVToColor(new float[] {(hue + 40) % 360, 0.65f, 0.14f});
        bg.setShader(new android.graphics.LinearGradient(0, 0, cw, ch, c1, c2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, cw, ch, bg);
        if (title != null && !title.isEmpty()) {
            Paint tp = Ui.text(Ui.displaySemibold, Math.max(20f, ch * 0.11f), 0xF2FFFFFF);
            tp.setTextAlign(Paint.Align.CENTER);
            String shown = android.text.TextUtils.ellipsize(title, new android.text.TextPaint(tp), cw * 0.84f,
                    android.text.TextUtils.TruncateAt.END).toString();
            c.drawText(shown, cw / 2f, ch / 2f + Ui.halfCap(tp), tp);
        }
        return out;
    }

    static java.io.File resolveCachedWatchArtFile(java.io.File dir, String basePrefix) {
        java.io.File webp = new java.io.File(dir, basePrefix + ".webp");
        if (webp.exists() && webp.length() > 128) return webp;
        java.io.File jpg = new java.io.File(dir, basePrefix + ".jpg");
        if (jpg.exists() && jpg.length() > 128) return jpg;
        java.io.File legacyImg = new java.io.File(dir, basePrefix + ".img");
        if (legacyImg.exists() && legacyImg.length() > 256) return legacyImg;
        return null;
    }

    /** Reads content://, android.resource://, and http/https:// (with Native WebP Q86 disk cache in getCacheDir()/watch-art). */
    private byte[] read(Uri uri) throws IOException {
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            java.io.File dir = new java.io.File(app.getCacheDir(), "watch-art");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String basePrefix = "wa17_" + Integer.toHexString(uri.toString().hashCode());
            java.io.File cached = resolveCachedWatchArtFile(dir, basePrefix);
            if (cached != null) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(cached)) {
                    return readAll(fis);
                }
            }
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(uri.toString()).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "SoarTV/1.4");
            try {
                int code = conn.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                byte[] data = readAll(conn.getInputStream());
                writeWatchArtWebpCache(dir, basePrefix, data);
                return data;
            } finally {
                conn.disconnect();
            }
        }
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("no picture there");
            return readAll(in);
        }
    }

    private static void writeWatchArtWebpCache(java.io.File dir, String basePrefix, byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) return;
        Bitmap decoded = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while ((long) (bounds.outWidth / opts.inSampleSize) * (bounds.outHeight / opts.inSampleSize) > MAX_PIXELS) {
                opts.inSampleSize *= 2;
            }
            decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length, opts);
            if (decoded == null) return;
            java.io.File webpTarget = new java.io.File(dir, basePrefix + ".webp");
            java.io.File tmp = new java.io.File(dir, basePrefix + ".webp.tmp");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                decoded.compress(GamingZone.webpLossyFormat(), GamingZone.WEBP_CACHE_QUALITY, fos);
                fos.getFD().sync();
            }
            if (!tmp.renameTo(webpTarget)) {
                //noinspection ResultOfMethodCallIgnored
                webpTarget.delete();
                if (!tmp.renameTo(webpTarget)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        } catch (Throwable t) {
            AppLog.w("WatchArt WebP cache write failed", t);
        } finally {
            if (decoded != null && !decoded.isRecycled()) {
                decoded.recycle();
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_BYTES) throw new IOException("picture too large");
        }
        return out.toByteArray();
    }
}
