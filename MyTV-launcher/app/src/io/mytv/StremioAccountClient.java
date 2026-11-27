package io.mytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Native TV client for Stremio Account & Cloud Library synchronization (api.strem.io).
 *
 * Why: Stremio calls its user Watch List "Library" and stores items in the Stremio
 * cloud datastore collection "libraryItem". Connecting directly to the user's Stremio
 * account (via authKey or email/password login) allows MyTV to sync the user's full
 * Stremio Library (both Watch List items and episode watch progress) directly on the TV
 * with zero PC dependency.
 */
public final class StremioAccountClient {
    private static final String TAG = "MyTV";
    private static final String API_BASE = "https://api.strem.io/api";
    private static final String PREF_NAME = "stremio_account";
    private static final int TIMEOUT_MS = 10_000;

    /**
     * Value object representing an item in the user's Stremio Library ("libraryItem").
     */
    public static final class LibraryItem {
        public final String imdbId;
        public final String name;
        public final String kind; // "tv" or "movie"
        public final String poster;
        public final long ctimeMs;
        public final long mtimeMs;
        public final long lastWatchedMs;
        public final int timesWatched;
        public final long timeOffsetMs;
        public final long durationMs;
        public final String videoId;
        public final int season;
        public final int episode;
        public final boolean watched;
        public final boolean removed;
        public final boolean temp;

        public LibraryItem(
                String imdbId,
                String name,
                String kind,
                String poster,
                long ctimeMs,
                long mtimeMs,
                long lastWatchedMs,
                int timesWatched,
                long timeOffsetMs,
                long durationMs,
                String videoId,
                int season,
                int episode,
                boolean watched,
                boolean removed,
                boolean temp) {
            this.imdbId = imdbId != null ? imdbId : "";
            this.name = name != null ? name : "";
            this.kind = "movie".equals(kind) ? "movie" : "tv";
            String cleanPoster = WatchNext.sanitizePosterUrl(poster, this.imdbId, true);
            this.poster = cleanPoster != null && !cleanPoster.isEmpty() ? cleanPoster
                    : (this.imdbId.startsWith("tt") ? "https://images.metahub.space/poster/medium/" + this.imdbId + "/img" : "");
            this.ctimeMs = ctimeMs;
            this.mtimeMs = mtimeMs;
            this.lastWatchedMs = lastWatchedMs;
            this.timesWatched = timesWatched;
            this.timeOffsetMs = timeOffsetMs;
            this.durationMs = durationMs;
            this.videoId = videoId != null ? videoId : "";
            this.season = season;
            this.episode = episode;
            this.watched = watched;
            this.removed = removed;
            this.temp = temp;
        }

        /**
         * Returns true if this item was explicitly added to the user's Stremio Library (Watch List).
         * In Stremio's datastore, items explicitly saved to Library have removed=false and temp=false.
         */
        public boolean isLibraryWatchlistItem() {
            return !removed && !temp && imdbId.startsWith("tt");
        }

        /**
         * Returns true ONLY if this item has real active playback progress for Continue Watching.
         * Excludes unwatched Stremio Library additions (where Stremio sets default video_id S1:E1 with 0 progress)
         * and excludes already-finished movies.
         */
        public boolean hasContinueWatchingState() {
            if (!imdbId.startsWith("tt") || removed) return false;
            if ("movie".equals(kind)) {
                if (watched) return false;
                if (durationMs > 0L && timeOffsetMs >= (long) (durationMs * 0.90f)) return false;
                return timeOffsetMs > 60_000L;
            }
            // For TV series: require real mid-episode playback (>60s) OR actual watched episode history (timesWatched > 0 or progressed past S1E1)
            return timeOffsetMs > 60_000L
                    || (lastWatchedMs > 0L && (timesWatched > 0 || season > 1 || episode > 1));
        }
    }

    private StremioAccountClient() {}

    private static SharedPreferences prefs(Context c) {
        if (c == null) return null;
        try {
            Context app = c.getApplicationContext();
            return (app != null ? app : c).getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Returns true if a Stremio account authKey is configured in hardware-backed {@link Secrets}
     * (or migrated from legacy preferences). Plaintext passwords are never stored in APK config.
     */
    public static boolean isConfigured(Context c) {
        String key = getAuthKey(c);
        return key != null && !key.isEmpty();
    }

    /**
     * Returns the active Stremio authKey from hardware-backed {@link Secrets}, automatically
     * migrating any legacy {@code SharedPreferences("stremio_account")} {@code auth_key} entry
     * into {@link Secrets} and purging it from plaintext XML on first read.
     */
    public static String getAuthKey(Context c) {
        String secretKey = Secrets.getSecret(c, Secrets.STREMIO_AUTH_KEY);
        if (secretKey != null && !secretKey.trim().isEmpty()) {
            SharedPreferences sp = prefs(c);
            if (sp != null && (sp.contains("auth_key") || sp.contains("password"))) {
                sp.edit().remove("auth_key").remove("password").apply();
            }
            return secretKey.trim();
        }
        SharedPreferences sp = prefs(c);
        if (sp != null) {
            String legacy = sp.getString("auth_key", "");
            if (legacy != null && !legacy.trim().isEmpty()) {
                String clean = legacy.trim();
                if (Secrets.putSecret(c, Secrets.STREMIO_AUTH_KEY, clean)) {
                    sp.edit().remove("auth_key").remove("password").apply();
                }
                return clean;
            }
        }
        if (Config.STREMIO_AUTH_KEY != null && !Config.STREMIO_AUTH_KEY.trim().isEmpty()) {
            String cfgKey = Config.STREMIO_AUTH_KEY.trim();
            Secrets.putSecret(c, Secrets.STREMIO_AUTH_KEY, cfgKey);
            return cfgKey;
        }
        return "";
    }

    private static volatile List<LibraryItem> memCachedLibrary = null;

    /**
     * Saves a Stremio authKey encrypted in {@link Secrets} (and optional non-sensitive email in SharedPreferences).
     * Plaintext passwords are never persisted anywhere.
     */
    public static void saveAuthKey(Context c, String authKey, String email) {
        String cleanKey = authKey != null ? authKey.trim() : "";
        if (cleanKey.isEmpty()) {
            Secrets.deleteSecret(c, Secrets.STREMIO_AUTH_KEY);
        } else {
            Secrets.putSecret(c, Secrets.STREMIO_AUTH_KEY, cleanKey);
        }
        SharedPreferences sp = prefs(c);
        if (sp != null) {
            SharedPreferences.Editor ed = sp.edit().remove("auth_key").remove("password");
            if (email != null && !email.trim().isEmpty() && !cleanKey.isEmpty()) {
                ed.putString("email", email.trim());
            } else if (cleanKey.isEmpty()) {
                ed.putString("email", "");
                ed.putString("cached_library_json", "");
                memCachedLibrary = Collections.emptyList();
            }
            ed.apply();
        } else if (cleanKey.isEmpty()) {
            memCachedLibrary = Collections.emptyList();
        }
        Prefs.version++;
    }

    /**
     * Returns the saved Stremio account email for display in Settings.
     */
    public static String getAccountEmail(Context c) {
        SharedPreferences sp = prefs(c);
        if (sp != null) {
            String saved = sp.getString("email", "");
            if (saved != null && !saved.isEmpty()) return saved;
        }
        return Config.STREMIO_EMAIL != null ? Config.STREMIO_EMAIL : "";
    }

    /**
     * Logs in to Stremio API using email and password, storing ONLY the resulting authKey in {@link Secrets}
     * (never persisting the password).
     */
    public static String loginWithEmail(Context c, String email, String password) {
        if (email == null || email.trim().isEmpty() || password == null || password.isEmpty()) {
            return "";
        }
        try {
            JSONObject body = new JSONObject();
            body.put("type", "Login");
            body.put("email", email.trim());
            body.put("password", password);
            body.put("facebook", false);
            String resp = postJson(API_BASE + "/login", body.toString());
            if (resp == null || resp.isEmpty()) return "";
            JSONObject root = new JSONObject(resp);
            JSONObject result = root.optJSONObject("result");
            if (result != null) {
                String authKey = result.optString("authKey", "").trim();
                JSONObject user = result.optJSONObject("user");
                String resolvedEmail = user != null ? user.optString("email", email.trim()) : email.trim();
                if (!authKey.isEmpty()) {
                    saveAuthKey(c, authKey, resolvedEmail);
                    return authKey;
                }
            }
        } catch (Exception e) {
            try {
                Log.w(TAG, "Stremio login failed: " + e.getMessage());
            } catch (RuntimeException ignored) {
            }
        }
        return "";
    }

    /**
     * Fetches the user's Stremio Library items from api.strem.io/api/datastoreGet,
     * falling back to cached library JSON if offline.
     */
    public static List<LibraryItem> fetchLibraryItems(Context c) {
        String authKey = getAuthKey(c);
        if (authKey.isEmpty()) {
            return loadCachedLibraryItems(c);
        }
        try {
            JSONObject body = new JSONObject();
            body.put("authKey", authKey);
            body.put("collection", "libraryItem");
            body.put("all", true);
            String resp = postJson(API_BASE + "/datastoreGet", body.toString());
            if (resp != null && !resp.isEmpty()) {
                List<LibraryItem> parsed = parseLibraryDatastoreResponse(resp);
                if (!parsed.isEmpty()) {
                    memCachedLibrary = Collections.unmodifiableList(parsed);
                    SharedPreferences sp = prefs(c);
                    if (sp != null) {
                        sp.edit()
                                .putString("cached_library_json", resp)
                                .putLong("cached_at", System.currentTimeMillis())
                                .apply();
                    }
                }
                if (!parsed.isEmpty()) return parsed;
            }
        } catch (Exception e) {
            try {
                Log.w(TAG, "Stremio datastoreGet failed: " + e.getMessage());
            } catch (RuntimeException ignored) {
            }
        }
        return loadCachedLibraryItems(c);
    }

    /**
     * Loads the most recently cached Stremio Library items from memory / SharedPreferences (0ms, zero network).
     */
    public static List<LibraryItem> loadCachedLibraryItems(Context c) {
        List<LibraryItem> mem = memCachedLibrary;
        if (mem != null) return mem;
        SharedPreferences sp = prefs(c);
        if (sp == null) return Collections.emptyList();
        String cached = sp.getString("cached_library_json", "");
        if (cached == null || cached.isEmpty()) {
            memCachedLibrary = Collections.emptyList();
            return memCachedLibrary;
        }
        List<LibraryItem> parsed = Collections.unmodifiableList(parseLibraryDatastoreResponse(cached));
        memCachedLibrary = parsed;
        return parsed;
    }

    /**
     * Pure parser for Stremio's datastoreGet response JSON.
     * Sorts items by most recently modified/watched descending.
     */
    public static List<LibraryItem> parseLibraryDatastoreResponse(String json) {
        List<LibraryItem> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr;
            String trimmed = json.trim();
            if (trimmed.startsWith("[")) {
                arr = new JSONArray(trimmed);
            } else {
                JSONObject root = new JSONObject(trimmed);
                arr = root.optJSONArray("result");
            }
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("_id", "").trim();
                if (!id.startsWith("tt")) continue;
                boolean removed = item.optBoolean("removed", false);
                boolean temp = item.optBoolean("temp", false);
                if (removed) continue;
                String type = item.optString("type", "series");
                if (!"movie".equals(type) && !"series".equals(type) && !"tv".equals(type)) continue;
                String kind = "movie".equals(type) ? "movie" : "tv";
                String name = item.optString("name", "").trim();
                String rawPoster = item.optString("poster", "").trim();
                String poster = WatchNext.sanitizePosterUrl(rawPoster, id, true);
                if (poster == null || poster.isEmpty()) {
                    poster = "https://images.metahub.space/poster/medium/" + id + "/img";
                }
                long ctimeMs = parseIsoTimestamp(item.optString("_ctime", ""));
                long mtimeMs = parseIsoTimestamp(item.optString("_mtime", ""));

                JSONObject state = item.optJSONObject("state");
                long lastWatchedMs = 0L;
                int timesWatched = 0;
                long timeOffsetMs = 0L;
                long durationMs = 0L;
                String videoId = "";
                if (state != null) {
                    lastWatchedMs = parseIsoTimestamp(state.optString("lastWatched", ""));
                    timesWatched = state.optInt("timesWatched", 0);
                    timeOffsetMs = state.optLong("timeOffset", 0L);
                    durationMs = state.optLong("duration", 0L);
                    videoId = state.optString("video_id", "").trim();
                }
                int[] se = parseVideoIdSeasonEpisode(videoId);
                boolean watched = "movie".equals(kind)
                        ? (timesWatched > 0 && (timeOffsetMs == 0L || (durationMs > 0L && timeOffsetMs >= durationMs * 88L / 100L)))
                        : false;

                out.add(new LibraryItem(
                        id, name, kind, poster,
                        ctimeMs, mtimeMs, lastWatchedMs,
                        timesWatched, timeOffsetMs, durationMs,
                        videoId, se[0], se[1], watched, removed, temp
                ));
            }
            out.sort((a, b) -> {
                long ta = Math.max(a.lastWatchedMs, Math.max(a.mtimeMs, a.ctimeMs));
                long tb = Math.max(b.lastWatchedMs, Math.max(b.mtimeMs, b.ctimeMs));
                return Long.compare(tb, ta);
            });
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Parses Stremio video_id strings like "tt0903747:2:5" into [season, episode].
     */
    public static int[] parseVideoIdSeasonEpisode(String videoId) {
        if (videoId == null || videoId.isEmpty()) return new int[]{0, 0};
        String[] parts = videoId.split(":");
        if (parts.length >= 3) {
            try {
                int s = Integer.parseInt(parts[parts.length - 2].trim());
                int e = Integer.parseInt(parts[parts.length - 1].trim());
                if (s > 0 && e > 0) return new int[]{s, e};
            } catch (NumberFormatException ignored) {}
        }
        return new int[]{0, 0};
    }

    private static long parseIsoTimestamp(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        try {
            String clean = iso.trim();
            if (clean.endsWith("Z")) clean = clean.substring(0, clean.length() - 1);
            int dot = clean.indexOf('.');
            if (dot > 0) clean = clean.substring(0, dot);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.parse(clean).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String postJson(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }
}
