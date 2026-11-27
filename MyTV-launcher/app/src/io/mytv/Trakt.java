package io.mytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trakt (trakt.tv), only for someone who signs in with their own account: the picks start with what they're in the
 * middle of (continue watching, the next episode), bring in their watchlist and Trakt's recommendations, and leave out
 * what they've watched. It only reads. Needs the person's own API app (config.properties "trakt_client_id"); without
 * it, or until they sign in, nothing here runs and the picks stay TMDB's.
 *
 * Signing in is Trakt's device code with PKCE: the TV shows a short code, they type it at trakt.tv/activate on their
 * phone. Only the Client ID is needed, so no secret is ever built into the APK (Trakt made it optional, trakt-api#974).
 * The tokens stay in this process's SharedPreferences (:home, where Settings and the home screen both run), and
 * only :home uses them: on Android 11 to 12L the screensaver runs in the main process (ScreensaverAlone), where a
 * renewal from its own copy would leave :home's refresh token spent, and so signed out.
 */
final class Trakt {
    private Trakt() {}

    private static final String API = "https://api.trakt.tv";
    /** Watchlist titles read, per kind: candidates (Recs looks them up on TMDB only until it has enough). */
    static final int WATCHLIST_MAX = 250;
    /** Trakt's recommendations taken, per kind. */
    static final int RECOMMENDED_MAX = 40;
    /** Shows watched most recently whose next episode is looked up (one Trakt request each). */
    private static final int UP_NEXT_SHOWS = 8;

    /** Something they're in the middle of: a paused movie or episode, or a show's next episode. */
    static final class Progress {
        final String key;
        final int season, episode;     // 0 for a movie
        final boolean paused;          // stopped partway (Trakt's playback), not the next one up
        final long engaged;            // epoch millis of paused_at or last_watched_at (for strict last-watched sorting)
        final float progressPct;       // 0..100 for paused items, 0 for up-next

        Progress(String key, int season, int episode, boolean paused) {
            this(key, season, episode, paused, System.currentTimeMillis(), paused ? 50f : 0f);
        }

        Progress(String key, int season, int episode, boolean paused, long engaged, float progressPct) {
            this.key = key;
            this.season = season;
            this.episode = episode;
            this.paused = paused;
            this.engaged = engaged;
            this.progressPct = progressPct;
        }
    }

    /** What the picks need, as Recs keys ("movie:603", "tv:1396"). */
    static final class Lists {
        final Set<String> watched = new HashSet<>();
        final Map<String, Integer> watchedShowSeasons = new HashMap<>();
        final Set<String> disliked = new HashSet<>();
        /** Trakt's last change to the account (marked watched, added to the watchlist…) when these were read. */
        String activity = "";
        final List<Progress> progress = new ArrayList<>();
        final List<String> watchlistMovies = new ArrayList<>(), watchlistShows = new ArrayList<>();
        final List<String> recommendedMovies = new ArrayList<>(), recommendedShows = new ArrayList<>();
        /** Positive taste seeds (favorites, ratings >= 8, and most recently watched titles) for TMDB /recommendations. */
        final List<String> likedSeeds = new ArrayList<>();
    }

    // Sign-in in progress (Settings shows it in the Trakt row); null when none.
    private static volatile String code, url, message = "";
    private static volatile boolean signingIn;
    /** Its own lock: a token renewal (network, on the picks' thread) never holds up OK on the Trakt row. */
    private static final Object SIGN_IN = new Object();

    /** The person put their own Trakt API app in config.properties (and this is :home: see above). */
    static boolean available() {
        return !Config.TRAKT_CLIENT_ID.trim().isEmpty() && inHome();
    }

    private static volatile Boolean home;

    private static boolean inHome() {
        if (home == null) {
            String name = "";
            try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
                byte[] b = new byte[128];
                int n = Math.max(0, in.read(b)), end = 0;
                while (end < n && b[end] != 0) end++;
                name = new String(b, 0, end, StandardCharsets.US_ASCII);
            } catch (IOException ignored) {
            }
            home = name.isEmpty() || name.endsWith(":home") || (!name.endsWith(":sleep") && !name.endsWith(":keys"));
        }
        return home;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("trakt", Context.MODE_PRIVATE);
    }

    /** Returns the active user profile's Trakt username, falling back to Config.TRAKT_USERNAME. */
    static String configuredUser(Context c) {
        try {
            UserProfileManager.Profile p = UserProfileManager.get(c).getActiveProfile();
            if (p != null && p.traktUsername != null && !p.traktUsername.isEmpty()) {
                return p.traktUsername;
            }
        } catch (RuntimeException ignored) {
        }
        return Config.TRAKT_USERNAME == null ? "" : Config.TRAKT_USERNAME.trim();
    }

    static boolean hasOAuth(Context c) {
        return available() && !sp(c).getString("refresh", "").isEmpty();
    }

    static boolean signedIn(Context c) {
        return hasOAuth(c);
    }

    /** The account's name ("" if not signed in). */
    static String user(Context c) {
        if (!signedIn(c)) return "";
        String oauthUser = sp(c).getString("user", "");
        return !oauthUser.isEmpty() ? oauthUser : configuredUser(c);
    }

    /** Changes when the account does: the picks are fetched again. */
    static long stamp(Context c) {
        return signedIn(c) ? 31L * user(c).hashCode() + 2L : 0L;
    }

    /** The code to type at the address, while signing in; else null. */
    static String code() { return signingIn ? code : null; }
    static String url() { return url; }

    /** Asking Trakt for a code, or waiting for it to be typed. */
    static boolean busy() { return signingIn; }

    /** How the last sign-in went ("" when there's nothing to say). */
    static String message() { return message; }

    // MARK: - Sign in and out

    /** Starts Trakt's device sign-in in the background; `changed` runs (on that thread) at every step. */
    static void signIn(Context c, Runnable changed) {
        synchronized (SIGN_IN) {
            if (signingIn || !available()) return;
            signingIn = true;
        }
        code = null;
        message = "";
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                // PKCE: a one-time secret for this sign-in; Trakt gets its hash now and the secret itself with the code.
                byte[] random = new byte[48];
                new SecureRandom().nextBytes(random);
                String verifier = base64Url(random);
                String challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                JSONObject start = new JSONObject(request("POST", "/oauth/device/code", new JSONObject().put("client_id", clientId())
                        .put("code_challenge", challenge).put("code_challenge_method", "S256"), null, 200));
                code = start.getString("user_code");
                url = start.optString("verification_url", "https://trakt.tv/activate").replaceFirst("^https?://", "");
                changed.run();
                String deviceCode = start.getString("device_code");
                long interval = Math.max(5, start.optLong("interval", 5)) * 1000L;
                long until = System.currentTimeMillis() + start.optLong("expires_in", 600) * 1000L;
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(interval);
                    Response r = send("POST", "/oauth/device/token", new JSONObject().put("code", deviceCode)
                            .put("client_id", clientId()).put("code_verifier", verifier), null);
                    if (r.code == 200) {
                        save(app, new JSONObject(r.body));
                        String name = "";
                        try {
                            name = new JSONObject(get(app, "/users/settings")).getJSONObject("user").optString("username");
                        } catch (Exception e) {
                            AppLog.w("trakt user: " + e.getMessage());
                        }
                        sp(app).edit().putString("user", name).apply();
                        Prefs.version++;
                        AppLog.w("trakt: signed in");
                        return;
                    }
                    if (r.code == 400) continue;                     // not typed yet
                    if (r.code == 429) { interval += 1000; continue; }  // too fast
                    if (r.code == 418) { message = Ui.tr("Sign-in declined on Trakt."); return; }
                    if (r.code == 410) break;
                    throw new IOException("HTTP " + r.code);
                }
                message = Ui.tr("The code expired. Press OK for a new one.");
            } catch (Exception e) {
                message = Ui.trf("Couldn't sign in: {error}", "error", e.getMessage());
                AppLog.w("trakt sign-in: " + e.getMessage());
            } finally {
                signingIn = false;
                changed.run();
            }
        }, "trakt").start();
    }

    /** Forgets the account here and tells Trakt to drop the token (in the background, best effort). */
    static void signOut(Context c) {
        SharedPreferences sp = sp(c);
        String token = sp.getString("access", "");
        sp.edit().clear().apply();
        message = "";
        Prefs.version++;
        if (token.isEmpty()) return;
        new Thread(() -> {
            try {
                send("POST", "/oauth/revoke", new JSONObject().put("token", token).put("client_id", clientId()), null);
            } catch (Exception ignored) {
            }
        }, "trakt").start();
    }

    private static void save(Context c, JSONObject t) {
        long created = t.optLong("created_at", System.currentTimeMillis() / 1000);
        sp(c).edit().putString("access", t.optString("access_token")).putString("refresh", t.optString("refresh_token"))
                .putLong("expires", (created + t.optLong("expires_in", 86400)) * 1000L).apply();
    }

    /** A valid access token, renewed when it's about to expire (Trakt's last a day). Off the UI thread. */
    private static synchronized String token(Context c) throws Exception {
        SharedPreferences sp = sp(c);
        String refresh = sp.getString("refresh", "");
        if (refresh.isEmpty()) throw new IOException("not signed in");
        if (System.currentTimeMillis() < sp.getLong("expires", 0) - 3600_000L) return sp.getString("access", "");
        Response r = send("POST", "/oauth/token", new JSONObject().put("refresh_token", refresh).put("client_id", clientId())
                .put("redirect_uri", "http://localhost").put("grant_type", "refresh_token"), null);
        if (r.code == 400 || r.code == 401) {
            // The person disconnected the app on trakt.tv (or the token is gone): signed out here too.
            sp.edit().clear().apply();
            Prefs.version++;
            throw new IOException("Trakt signed this TV out");
        }
        if (r.code != 200) throw new IOException("Trakt returned HTTP " + r.code);
        // Signed out (or in again) while this was on its way: the new token isn't theirs anymore.
        if (!refresh.equals(sp.getString("refresh", ""))) throw new IOException("not signed in");
        save(c, new JSONObject(r.body));
        return sp.getString("access", "");
    }

    // MARK: - Lists

    /** Progress, watched titles, the watchlist (newest first) and recommendations, from TMDB ids. Off the UI thread. */
    static Lists lists(Context c) throws Exception {
        Lists out = new Lists();
        if (!hasOAuth(c)) return out;
        try {
            out.activity = activity(c);
        } catch (Exception e) {
            AppLog.w("trakt activity: " + e.getMessage());
        }
        Map<String, String> watchedAt = new HashMap<>();
        List<Seen> movies = new ArrayList<>();
        try {
            movies = watched(c, "/sync/watched/movies", "movie", out.watched, watchedAt, null);
        } catch (Exception e) {
            AppLog.w("trakt watched movies: " + e.getMessage());
        }
        List<Seen> shows = new ArrayList<>();
        try {
            shows = watched(c, "/sync/watched/shows", "show", out.watched, watchedAt, out.watchedShowSeasons);
        } catch (Exception e) {
            AppLog.w("trakt watched shows: " + e.getMessage());
        }
        try {
            watchlist(c, "/sync/watchlist/movies", "movie", out.watchlistMovies);
        } catch (Exception e) {
            AppLog.w("trakt watchlist movies: " + e.getMessage());
        }
        try {
            watchlist(c, "/sync/watchlist/shows", "show", out.watchlistShows);
        } catch (Exception e) {
            AppLog.w("trakt watchlist shows: " + e.getMessage());
        }
        try {
            playback(c, out, watchedAt);
        } catch (Exception e) {
            AppLog.w("trakt playback: " + e.getMessage());
        }
        try {
            upNext(c, shows, out);
        } catch (Exception e) {
            AppLog.w("trakt up next: " + e.getMessage());
        }
        Collections.sort(out.progress, (a, b) -> Long.compare(b.engaged, a.engaged));
        try {
            favorites(c, "/sync/favorites/movies", "movie", out.likedSeeds);
        } catch (Exception e) {
            AppLog.w("trakt favorites movies: " + e.getMessage());
        }
        try {
            favorites(c, "/sync/favorites/shows", "show", out.likedSeeds);
        } catch (Exception e) {
            AppLog.w("trakt favorites shows: " + e.getMessage());
        }
        try {
            ratings(c, "/sync/ratings/movies", "movie", out.likedSeeds, out.disliked);
        } catch (Exception e) {
            AppLog.w("trakt ratings movies: " + e.getMessage());
        }
        try {
            ratings(c, "/sync/ratings/shows", "show", out.likedSeeds, out.disliked);
        } catch (Exception e) {
            AppLog.w("trakt ratings shows: " + e.getMessage());
        }
        // Add up to 6 most recently watched shows and 6 most recently watched movies (not disliked) as taste seeds
        List<Seen> recentShows = new ArrayList<>(shows);
        Collections.sort(recentShows, (x, y) -> y.at.compareTo(x.at));
        for (int i = 0, added = 0; i < recentShows.size() && added < 6; i++) {
            String k = recentShows.get(i).key;
            if (!out.disliked.contains(k) && !out.likedSeeds.contains(k)) {
                out.likedSeeds.add(k);
                added++;
            }
        }
        List<Seen> recentMovies = new ArrayList<>(movies);
        Collections.sort(recentMovies, (x, y) -> y.at.compareTo(x.at));
        for (int i = 0, added = 0; i < recentMovies.size() && added < 6; i++) {
            String k = recentMovies.get(i).key;
            if (!out.disliked.contains(k) && !out.likedSeeds.contains(k)) {
                out.likedSeeds.add(k);
                added++;
            }
        }
        try {
            recommended(c, "/recommendations/movies?ignore_collected=true&limit=" + RECOMMENDED_MAX * 2, "movie", out.recommendedMovies);
            recommended(c, "/recommendations/shows?ignore_collected=true&limit=" + RECOMMENDED_MAX * 2, "show", out.recommendedShows);
        } catch (Exception e) {
            AppLog.w("trakt recommendations: " + e.getMessage());
        }
        return out;
    }

    /** A show from /sync/watched/shows: what upNext needs. */
    private static final class Seen {
        String key, at;
        int trakt;
    }

    /**
     * Also when each was last watched or marked watched (`at`): marking a show watched "on its release date" leaves
     * last_watched_at in the past, but last_updated_at is when it was marked. Read as it arrives (a long history is
     * megabytes of JSON): only the ids and the two dates are kept.
     */
    private static List<Seen> watched(Context c, String path, String type, Set<String> out,
                                      Map<String, String> at, Map<String, Integer> maxSeasonsOut) throws Exception {
        List<Seen> seen = new ArrayList<>();
        stream(c, path, r -> {
            r.beginArray();
            while (r.hasNext()) {
                String watchedAt = "", updatedAt = "";
                int tmdb = 0, trakt = 0, maxSeason = 0;
                r.beginObject();
                while (r.hasNext()) {
                    String name = r.nextName();
                    if (name.equals("last_watched_at")) watchedAt = string(r);
                    else if (name.equals("last_updated_at")) updatedAt = string(r);
                    else if (maxSeasonsOut != null && name.equals("seasons") && r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray();
                        while (r.hasNext()) {
                            int sNum = 0;
                            boolean hasEpisodes = false;
                            if (r.peek() == JsonToken.BEGIN_OBJECT) {
                                r.beginObject();
                                while (r.hasNext()) {
                                    String sn = r.nextName();
                                    if (sn.equals("number")) {
                                        sNum = number(r);
                                    } else if (sn.equals("episodes") && r.peek() == JsonToken.BEGIN_ARRAY) {
                                        r.beginArray();
                                        while (r.hasNext()) {
                                            hasEpisodes = true;
                                            r.skipValue();
                                        }
                                        r.endArray();
                                    } else {
                                        r.skipValue();
                                    }
                                }
                                r.endObject();
                            } else {
                                r.skipValue();
                            }
                            if (sNum > 0 && hasEpisodes && sNum > maxSeason) {
                                maxSeason = sNum;
                            }
                        }
                        r.endArray();
                    }
                    else if (name.equals(type) && r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject();
                        while (r.hasNext()) {
                            if (r.nextName().equals("ids") && r.peek() == JsonToken.BEGIN_OBJECT) {
                                r.beginObject();
                                while (r.hasNext()) {
                                    String id = r.nextName();
                                    if (id.equals("tmdb")) tmdb = number(r);
                                    else if (id.equals("trakt")) trakt = number(r);
                                    else r.skipValue();
                                }
                                r.endObject();
                            } else {
                                r.skipValue();
                            }
                        }
                        r.endObject();
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
                if (tmdb <= 0) continue;
                Seen item = new Seen();
                item.key = (type.equals("show") ? "tv" : "movie") + ":" + tmdb;
                item.at = watchedAt.compareTo(updatedAt) > 0 ? watchedAt : updatedAt;
                item.trakt = trakt;
                out.add(item.key);
                at.put(item.key, item.at);
                if (maxSeasonsOut != null && maxSeason > 0) {
                    maxSeasonsOut.put(item.key, maxSeason);
                }
                seen.add(item);
            }
            r.endArray();
        });
        return seen;
    }

    private static String string(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.STRING) return r.nextString();
        r.skipValue();
        return "";
    }

    private static int number(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NUMBER) return r.nextInt();
        r.skipValue();
        return 0;
    }

    /** When the account last changed on Trakt (ISO 8601, one small request): the picks are fetched again if it moves. */
    static String activity(Context c) throws Exception {
        return new JSONObject(get(c, "/sync/last_activities")).optString("all");
    }

    /**
     * Movies and episodes stopped partway, the most recent first. Trakt keeps one there after it's marked watched:
     * one watched since it was paused is left out (a show's next episode then comes from upNext).
     */
    private static void playback(Context c, Lists out, Map<String, String> watchedAt) throws Exception {
        JSONArray a = new JSONArray(get(c, "/sync/playback?limit=20"));
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) items.add(a.optJSONObject(i));
        Collections.sort(items, (x, y) -> y.optString("paused_at").compareTo(x.optString("paused_at")));
        for (JSONObject item : items) {
            boolean movie = "movie".equals(item.optString("type"));
            String key = key(item, movie ? "movie" : "show");
            JSONObject ep = movie ? null : item.optJSONObject("episode");
            if (key == null || has(out.progress, key)) continue;
            String pausedAt = item.optString("paused_at");
            String watched = watchedAt.get(key);
            if (watched != null && watched.compareTo(pausedAt) >= 0) continue;
            float pct = (float) item.optDouble("progress", 50.0);
            int s = ep == null ? 0 : ep.optInt("season");
            int e = ep == null ? 0 : ep.optInt("number");
            if (pct >= 90f && !movie && e > 0) {
                out.progress.add(new Progress(key, s, e + 1, false, parseIsoMillis(pausedAt), 0f));
            } else {
                out.progress.add(new Progress(key, s, e, true, parseIsoMillis(pausedAt), pct));
            }
        }
    }

    /** The next episode of the shows watched most recently, when one is out. */
    private static void upNext(Context c, List<Seen> shows, Lists out) throws Exception {
        List<Seen> items = new ArrayList<>(shows);
        Collections.sort(items, (x, y) -> y.at.compareTo(x.at));
        int asked = 0;
        for (Seen item : items) {
            if (asked >= UP_NEXT_SHOWS) break;
            if (item.trakt <= 0 || has(out.progress, item.key)) continue;
            asked++;
            JSONObject progress = new JSONObject(get(c, "/shows/" + item.trakt + "/progress/watched?specials=false&count_specials=false"));
            JSONObject next = progress.optJSONObject("next_episode");
            if (next == null || progress.optInt("completed") >= progress.optInt("aired")) continue;
            out.progress.add(new Progress(item.key, next.optInt("season"), next.optInt("number"), false,
                    parseIsoMillis(item.at), 0f));
        }
    }

    /** Public-profile fallback when OAuth is not active: reads recent episode history and suggests Episode N+1. */
    private static void publicUpNext(Context c, String basePrefix, Lists out) throws Exception {
        JSONArray a = new JSONArray(get(c, basePrefix + "/history/episodes?limit=20"));
        for (int i = 0; i < a.length() && out.progress.size() < UP_NEXT_SHOWS; i++) {
            JSONObject item = a.optJSONObject(i);
            if (item == null) continue;
            String key = key(item, "show");
            JSONObject ep = item.optJSONObject("episode");
            if (key == null || ep == null || has(out.progress, key)) continue;
            int season = ep.optInt("season");
            int number = ep.optInt("number");
            if (season <= 0 || number <= 0) continue;
            String watchedAt = item.optString("watched_at");
            out.progress.add(new Progress(key, season, number + 1, false, parseIsoMillis(watchedAt), 0f));
        }
    }

    private static long parseIsoMillis(String iso) {
        if (iso == null || iso.isEmpty()) return System.currentTimeMillis();
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private static boolean has(List<Progress> list, String key) {
        for (Progress p : list) if (p.key.equals(key)) return true;
        return false;
    }

    /** Trakt's recommendations for the account, in its order. */
    private static void recommended(Context c, String path, String type, List<String> out) throws Exception {
        JSONArray a = new JSONArray(get(c, path));
        for (int i = 0; i < a.length() && out.size() < RECOMMENDED_MAX; i++) {
            String key = key(a.optJSONObject(i), type);
            if (key != null && !out.contains(key)) out.add(key);
        }
    }

    /** The newest additions first (listed_at is ISO 8601 in UTC, so it sorts as text). */
    private static void watchlist(Context c, String path, String type, List<String> out) throws Exception {
        JSONArray a = new JSONArray(get(c, path));
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) items.add(a.optJSONObject(i));
        Collections.sort(items, (x, y) -> y.optString("listed_at").compareTo(x.optString("listed_at")));
        for (JSONObject item : items) {
            if (out.size() >= WATCHLIST_MAX) break;
            String key = key(item, type);
            if (key != null && !out.contains(key)) out.add(key);
        }
    }

    /** User's explicit favorite movies/shows on Trakt (newest first). */
    private static void favorites(Context c, String path, String type, List<String> likedSeeds) throws Exception {
        JSONArray a = new JSONArray(get(c, path));
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) items.add(a.optJSONObject(i));
        Collections.sort(items, (x, y) -> y.optString("listed_at").compareTo(x.optString("listed_at")));
        for (JSONObject item : items) {
            String key = key(item, type);
            if (key != null && !likedSeeds.contains(key)) likedSeeds.add(key);
        }
    }

    /** User's explicit ratings on Trakt: >= 8 becomes a positive taste seed, <= 6 is excluded as disliked. */
    private static void ratings(Context c, String path, String type, List<String> likedSeeds, Set<String> disliked) throws Exception {
        JSONArray a = new JSONArray(get(c, path));
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) items.add(a.optJSONObject(i));
        Collections.sort(items, (x, y) -> y.optString("rated_at").compareTo(x.optString("rated_at")));
        for (JSONObject item : items) {
            String key = key(item, type);
            if (key == null) continue;
            int rating = item.optInt("rating", 0);
            if (rating >= 8) {
                if (!likedSeeds.contains(key)) likedSeeds.add(key);
            } else if (rating >= 1 && rating <= 6) {
                disliked.add(key);
            }
        }
    }

    /**
     * {"movie": {"ids": {"tmdb": 603}}} → "movie:603"; Trakt's "show" is TMDB's "tv". The recommendations come without
     * the wrapper ({"ids": …}). Null without a TMDB id.
     */
    private static String key(JSONObject item, String type) {
        JSONObject o = item == null ? null : item.has(type) ? item.optJSONObject(type) : item;
        JSONObject ids = o == null ? null : o.optJSONObject("ids");
        if (ids == null) return null;
        String prefix = type.equals("show") ? "tv" : "movie";
        int tmdb = ids.optInt("tmdb");
        if (tmdb > 0) return prefix + ":" + tmdb;
        String imdb = ids.optString("imdb", "").trim();
        if (imdb.startsWith("tt")) return prefix + ":" + imdb;
        return null;
    }

    // MARK: - HTTP

    private static final class Response {
        int code;
        String body = "";
    }

    private static String clientId() {
        return Config.TRAKT_CLIENT_ID.trim();
    }

    private static String base64Url(byte[] bytes) {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    private static String get(Context c, String path) throws Exception {
        try {
            return request("GET", path, null, hasOAuth(c) ? token(c) : null, 200);
        } catch (IOException e) {
            if ("Trakt refused the key or the sign-in".equals(e.getMessage()) && c != null) {
                sp(c).edit().clear().apply();
                Prefs.version++;
            }
            throw e;
        }
    }

    private interface Parser {
        void read(JsonReader r) throws IOException;
    }

    /** A GET read as it arrives, without the whole body in memory. */
    private static void stream(Context c, String path, Parser parser) throws Exception {
        HttpURLConnection h = open("GET", path, hasOAuth(c) ? token(c) : null);
        try {
            int code = h.getResponseCode();
            if (code == 401) {
                if (c != null) {
                    sp(c).edit().clear().apply();
                    Prefs.version++;
                }
                throw new IOException("Trakt refused the key or the sign-in");
            }
            if (code != 200) throw new IOException("Trakt returned HTTP " + code);
            try (JsonReader r = new JsonReader(new InputStreamReader(new BufferedInputStream(h.getInputStream()), StandardCharsets.UTF_8))) {
                parser.read(r);
            }
        } finally {
            h.disconnect();
        }
    }

    private static String request(String method, String path, JSONObject body, String token, int want) throws IOException {
        Response r = send(method, path, body, token);
        if (r.code == 401) throw new IOException("Trakt refused the key or the sign-in");
        if (r.code != want) throw new IOException("Trakt returned HTTP " + r.code);
        return r.body;
    }

    private static HttpURLConnection open(String method, String path, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);   // HttpURLConnection keeps custom headers across hosts: the token must not follow a redirect
        c.setRequestMethod(method);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "MyTV");
        c.setRequestProperty("trakt-api-version", "2");
        c.setRequestProperty("trakt-api-key", clientId());
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static Response send(String method, String path, JSONObject body, String token) throws IOException {
        HttpURLConnection c = open(method, path, token);
        try {
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            Response r = new Response();
            r.code = c.getResponseCode();
            InputStream in = r.code < 400 ? c.getInputStream() : c.getErrorStream();
            if (in != null) {
                try (InputStream s = in) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = s.read(buffer)) > 0) bytes.write(buffer, 0, n);
                    r.body = bytes.toString(StandardCharsets.UTF_8.name());
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }
}
