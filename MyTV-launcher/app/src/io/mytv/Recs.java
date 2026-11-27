package io.mytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). 3 per hour, or up to 6 (Home Settings).
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key: given on the TV (Home Settings > TMDB key, which the APK on GitHub's
 * releases needs), else config.properties' "tmdb_key", built into the app. It never goes to the log.
 */
final class Recs {
    static final String EXPLORE = null, MOVIES = "movie", SHOWS = "tv", WATCHLIST = "watchlist", TRENDING = "trending";

    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, poster, meta = "", app, pkg;   // poster: the portrait image of the narrow cards
        String link;            // opens the title directly in its app, or null: the app's home
        String servicePkg;      // open_in_stremio: the TV's own app it streams on (pkg is then Stremio's)
        String tag;             // Trakt: "Up next: S2E5" (Continue Watching only); else null
        int mine;               // Trakt: PROGRESS, LIKED, WATCHLIST_MINE or 0 (TMDB's)
        long engaged;           // Trakt progress engagement timestamp (millis)
        float progressPct;      // Trakt progress percentage (0..100)
        int progressSeason;     // In-progress / up-next season number (>0 when known)
        int progressEpisode;    // In-progress / up-next episode number (>0 when known)
        boolean watched;        // True if marked as watched on Trakt (grayed out & sorted last in Watch List)
        boolean onWatchlist;    // True if explicitly on the user's Trakt/Supabase Watch List
        double rating;          // TMDB vote_average (0..10)
        int votesCount;         // TMDB vote_count
        double popularity = 20.0; // TMDB popularity metric
        double matchScore;      // Combined personal matching-score (genre affinity, Trakt seeds, ratings)
        double trendingScore;   // Combined popularity & community voting score for Trending row
        boolean highQuality = true; // True for "good" items (before subtle separator), false for lesser-quality suggestions
        String lang = "en";     // ISO 639-1 original_language ("en", "he", "ko", "ja", etc.)
        boolean isAnime;        // True if Japanese/Asian Animation (isolated to Anime & Animation rows)
        String imdbId = "";     // IMDb ID ("tt...") when known
        String imdbScore = "";  // External IMDb rating (e.g. "8.7")
        String rtScore = "";    // External Rotten Tomatoes rating (e.g. "83%")
        String mcScore = "";    // External Metacritic Metascore (e.g. "73")
        List<String> genres = new ArrayList<>();   // Canonical genre names ("Action", "Comedy", "Thriller", etc.)
        String overview = "";   // Full synopsis for Top Details Header Row 4 (never truncated)
        int runtime;            // Duration in minutes (movie or typical episode runtime)
        String releaseDate = "";// Movie release date ("YYYY-MM-DD")
        String firstAirDate = "";// Series first air date ("YYYY-MM-DD")
        String lastAirDate = "";// Series last air date ("YYYY-MM-DD")
        int seasonsCount;       // Number of seasons (TMDB "number_of_seasons")
        int airedSeasonsCount;  // Number of regular seasons (>0) whose air_date <= today
        String status = "";     // Series status ("Returning Series", "Ended", "Canceled", etc.)
        boolean hasConfirmedRenewal; // True if next_episode_to_air exists or status is "Returning Series"
        boolean detailed;
        int lazyPage = 0;       // 0 for initial catalog load; >=2 for lazy-loaded pages (appended without reshuffling)
        java.io.File photo;     // one of your own photos (Photos): no title, no caption
        String category = "";   // screensaver category (nature, urban, macro, space, paintings, art)
        String catalogId = "";  // curated screensaver entry ID (e.g. "nat_042")
        String imageUrl = "";   // remote 4K JPEG URL for on-demand caching when not yet prefetched
        String rawTitle = "", rawArtist = "", rawLocation = "", rawBuilding = "", building = ""; // unbaked screensaver metadata for instant toggle updates
        private transient String cachedNormTitleKey;

        String normTitleKey() {
            String k = cachedNormTitleKey;
            if (k == null || k.isEmpty()) {
                k = (kind == null ? "" : kind) + ":" + plain(title == null ? "" : title);
                cachedNormTitleKey = k;
            }
            return k;
        }
    }

    interface Listener { void onRecs(); }

    interface SyncProgressListener {
        void onSyncProgress(String stepText, int progressPct, boolean done);
    }

    // TMDB's own mirror of api.themoviedb.org, on the images' domain: some ISPs block the other name (Jio, #327)
    private static final String API = "https://api.tmdb.org/3";
    /** The most cards per tab and hour (Prefs.cards() picks how many, 3 by default). */
    static final int MAX_CARDS = 24;
    /** Unlimited / very high cap per horizontal row on the Home Screen browser (lazy-loaded in advance). */
    static final int ROW_MAX_CARDS = 500;

    /**
     * Selects the optimal TMDB backdrop CDN tier for the TV's UI framebuffer width:
     * {@code w1280} on 1080p/720p UI layers (eliminating 33 MB 4K RGBA decode GC pauses) and {@code original} on native 4K UI layers.
     */
    static String backdropBaseUrlForWidth(int width) {
        return "https://image.tmdb.org/t/p/original";
    }

    private static final String IMAGES = backdropBaseUrlForWidth(Backdrop.W);
    private static final String POSTERS = "https://image.tmdb.org/t/p/w780";
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with Picks > Cartoons and anime off. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 3600_000L; // 1 hour auto-refresh interval
    /** How often Trakt is asked whether the account changed (marked watched, the watchlist): one small request. */
    private static final long TRAKT_EVERY = 10 * 60_000L;
    /** Bump when what is saved for each title changes (migrated in-place without wiping cached picks). */
    private static final int CACHE_VERSION = 18;   // 18: sanitize third-party rating-overlay poster URLs across Stremio/WatchNext/Cinemeta
    /** Minimum unwatched/non-watchlist items fetched per visible genre during initial catalog build. */
    static final int MIN_INITIAL_GENRE_ITEMS = 12;
    /** Number of initial items per row to preload and keep cached in RAM for instant scrolling. */
    static final int PRELOAD_FIRST_ITEMS = 10;
    /** TMDB's titles in the initial list (trending, new releases & all user-ordered per-genre discoveries). */
    static final int POOL_CAPACITY = 1200;
    private static final int POOL = POOL_CAPACITY;
    /** Trakt's titles in the list: in-progress, explicit watchlist, and taste-seeded recommendations. */
    private static final int TRAKT_MAX = 160;
    /** Images kept on disk: a backdrop and a poster for every title the list can hold. */
    private static final int FILES = 2 * (POOL + TRAKT_MAX);
    /** Pick.mine: what they're in the middle of, what they'd like (Trakt's recommendations), and their explicit Watchlist. */
    private static final int PROGRESS = 1, LIKED = 2, WATCHLIST_MINE = 3;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"stremio", "stremio", "com.stremio.tv"},
        {"stremio", "stremio", "com.stremio.one"},
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"prime video", "prime", "com.amazon.firebat"},
        {"prime video", "prime", "com.amazon.avod"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"hulu", "hulu", "com.hulu.plus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"paramount", "paramount", "com.cbs.ca"},      // Paramount+ outside the US (a Sony in the UK; Canada)
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"peacock", "peacock", "com.peacock.peacockfiretv"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"apple tv", "appletv", "com.apple.atve.amazon.appletv"},
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"now tv", "now", "com.bskyb.nowtv.beta"},     // UK: Sky's NOW (TMDB: "Now TV", "Now TV Cinema")
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
        // Germany (TMDB's watch providers for DE)
        {"ard mediathek", "ard", "de.swr.avp.ard.tv"},
        {"zdf", "zdf", "com.zdf.android.mediathek"},
        {"arte", "arte", "tv.arte.plus7"},
        {"joyn", "joyn", "de.prosiebensat1digital.seventv"},   // TMDB: "Joyn" and "Joyn Plus", same app
        {"rtl+", "rtl", "de.rtli.tvnow"},
        {"wow", "wow", "de.sky.online"},                       // Sky's WOW (was Sky Ticket)
        {"magenta", "magenta", "de.telekom.magentatv.tv"},     // TMDB: "MagentaTV" and "Magenta TV+"
        // Russia (TMDB's watch providers for RU)
        {"okko", "okko", "ru.more.play"},
        {"kinopoisk", "kinopoisk", "ru.kinopoisk.tv"},
        {"premier", "premier", "gpm.tnt_premier"},
        {"amediateka", "amediateka", "ru.amediateka"},
        {"more tv", "wink", "ru.rt.video.app.tv"},   // TMDB: "More TV" (more.tv is part of Rostelecom's Wink now)
    };
    /** Services matched by their whole name, where a part would catch another: "WOW Presents Plus", "Belas Artes à La Carte". */
    private static final Set<String> WHOLE_NAME = new HashSet<>(Arrays.asList("wow", "arte"));
    /**
     * With open_in_stremio=true and Stremio on the TV: a pick from one of the TV's apps opens on its page in Stremio
     * (stremio:///detail/<type>/<imdb id>) instead of in that app. Only an Open in: what is picked doesn't change.
     */
    public static final String STREMIO_TV = "com.stremio.tv";
    public static final String STREMIO_ONE = "com.stremio.one";
    private static final String STREMIO = STREMIO_ONE;

    public static String resolveStremioPackage(PackageManager pm) {
        if (pm != null) {
            if (HomeApps.launchIntent(pm, STREMIO_TV) != null) return STREMIO_TV;
            if (HomeApps.launchIntent(pm, STREMIO_ONE) != null) return STREMIO_ONE;
        }
        return STREMIO_ONE;
    }

    public String stremioPkg() {
        if (context != null) {
            return resolveStremioPackage(context.getPackageManager());
        }
        return STREMIO_ONE;
    }

    public static String buildStremioDetailUri(boolean isSeries, String imdbId, int season, int episode, String videoId) {
        if (imdbId == null || imdbId.isEmpty()) return null;
        if (!isSeries) {
            return "stremio:///detail/movie/" + imdbId;
        }
        if (season > 0 && episode > 0) {
            return "stremio:///detail/series/" + imdbId + "/" + imdbId + ":" + season + ":" + episode;
        }
        if (videoId != null && !videoId.trim().isEmpty()) {
            String v = videoId.trim();
            String fullVid = v.startsWith(imdbId) ? v : (imdbId + ":" + v);
            return "stremio:///detail/series/" + imdbId + "/" + fullVid;
        }
        return "stremio:///detail/series/" + imdbId;
    }

    public static String buildStremioDetailUri(boolean isSeries, String imdbId) {
        return buildStremioDetailUri(isSeries, imdbId, 0, 0, null);
    }

    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("stremio", "Stremio");
        SERVICE_NAME.put("com.stremio.tv", "Stremio");
        SERVICE_NAME.put("com.stremio.one", "Stremio");
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("now", "NOW");
        SERVICE_NAME.put("oneplay", "Oneplay");
        SERVICE_NAME.put("ard", "ARD Mediathek");
        SERVICE_NAME.put("zdf", "ZDF");
        SERVICE_NAME.put("arte", "ARTE");
        SERVICE_NAME.put("joyn", "Joyn");
        SERVICE_NAME.put("rtl", "RTL+");
        SERVICE_NAME.put("wow", "WOW");
        SERVICE_NAME.put("magenta", "MagentaTV");
        SERVICE_NAME.put("kinopoisk", "Кинопоиск");
        SERVICE_NAME.put("premier", "Premier");
        SERVICE_NAME.put("amediateka", "Амедиатека");
        SERVICE_NAME.put("wink", "Wink");
        SERVICE_NAME.put("plex", "Plex");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    static final String REGION = Place.region();
    /** The errors the settings say in words: TMDB lists none of the TV's apps in REGION, or the TV has none we know. */
    static final String NO_APPS = "none of the TV's streaming services are on TMDB";
    static final String NO_KNOWN_APPS = "none of the TV's streaming apps can give picks";

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    /** A narrow card's portrait and its shader: the shader holds the bitmap, so they leave together. */
    static final class Poster {
        final Bitmap bitmap;
        final BitmapShader shader;

        Poster(Bitmap bitmap) {
            this.bitmap = bitmap;
            shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            }
        }

        void recycle() {
            Backdrop.scheduleSafeBitmapRecycle(bitmap);
        }
    }

    /** Hard LRU cache caps to prevent GPU VRAM exhaustion while keeping hero backdrops & 2:3 posters snappy. */
    static final int MAX_BACKDROPS_STANDARD = Backdrop.W <= 1920 ? 24 : 12;
    static final int MAX_BACKDROPS_LOW_RAM = 8;
    static final int MAX_POSTERS_STANDARD = 240;
    static final int MAX_POSTERS_COMPACT_RAM = 144;
    static final int MAX_POSTERS_LOW_RAM = 96;
    /** Cooldown before retrying a missing/404/landscape-rejected poster URL so background threads never spin infinitely. */
    static final long POSTER_RETRY_COOLDOWN_MS = 10 * 60_000L;
    private static final long PRUNE_COOLDOWN_MS = 180_000L;
    private static volatile long lastPruneAtMs = 0L;
    static final long METADATA_SAVE_DEBOUNCE_MS = 15_000L;
    private static volatile boolean backgroundActivityPaused = false;

    static void setBackgroundActivityPaused(boolean paused) {
        backgroundActivityPaused = paused;
        if (!paused) {
            Recs inst = instance;
            if (inst != null && inst.deferredSaveWhilePaused) {
                inst.deferredSaveWhilePaused = false;
                inst.scheduleDebouncedSave();
            }
        }
    }

    static boolean isBackgroundActivityPaused() {
        return backgroundActivityPaused;
    }

    static boolean shouldSkipBackgroundWork(boolean isPaused, boolean hasExplicitUserFocusCallback) {
        return isPaused && !hasExplicitUserFocusCallback;
    }

    static boolean shouldSkipFailedPoster(Long failedAtMs, long nowMs) {
        return failedAtMs != null && (nowMs - failedAtMs) < POSTER_RETRY_COOLDOWN_MS;
    }

    static int maxBackdrops() {
        return Backdrop.UI_SCALE < 1f ? MAX_BACKDROPS_LOW_RAM : MAX_BACKDROPS_STANDARD;
    }

    private static final boolean COMPACT_RAM_DEVICE = detectCompactRamDevice();

    private static boolean detectCompactRamDevice() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line = r.readLine();
            if (line != null && line.startsWith("MemTotal:")) {
                return Long.parseLong(line.replaceAll("\\D+", "")) < 3L * 1024 * 1024;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static int maxPosters() {
        if (Backdrop.UI_SCALE < 1f) return MAX_POSTERS_LOW_RAM;
        return COMPACT_RAM_DEVICE ? MAX_POSTERS_COMPACT_RAM : MAX_POSTERS_STANDARD;
    }

    private volatile String activeSubKind = "tv";
    private volatile String activeFocusKey = null;
    private volatile String activeBackdropTaskKey = null;
    private volatile java.util.concurrent.Future<?> activeBackdropTask = null;
    private final Set<String> pinnedBackdropKeys = java.util.Collections.synchronizedSet(new HashSet<>());

    void setActiveFocusKey(String key) {
        activeFocusKey = key;
    }

    String getActiveFocusKey() {
        return activeFocusKey;
    }

    void setPinnedBackdropKeys(java.util.Collection<String> keys) {
        pinnedBackdropKeys.clear();
        if (keys == null) return;
        int n = 0;
        for (String k : keys) {
            if (k != null && !k.isEmpty() && pinnedBackdropKeys.add(k) && ++n >= 6) {
                break;
            }
        }
    }

    void setActiveSubKind(String subKind) {
        if ("movie".equalsIgnoreCase(subKind)) {
            activeSubKind = "movie";
        } else {
            activeSubKind = "tv";
        }
    }

    void onUserOrderChanged(String subKind) {
        setActiveSubKind(subKind);
        prewarmRowCacheAsync();
    }

    boolean isFetching() {
        return fetching;
    }

    /** Returns the active sub-tab's kind first (`["tv", "movie"]` or `["movie", "tv"]`) so lazy-loading prioritizes what the user sees. */
    static List<String> orderedSubKinds(String activeSubKind) {
        return "movie".equalsIgnoreCase(activeSubKind)
                ? Arrays.asList("movie", "tv")
                : Arrays.asList("tv", "movie");
    }

    /** Filters and preserves the user's custom genre sequence for lazy catalog & poster loading, skipping hidden genres. */
    static List<String> orderedGenresForLazyLoad(List<String> visibleOrder, java.util.Collection<String> hiddenGenres) {
        List<String> out = new ArrayList<>();
        if (visibleOrder == null) return out;
        Set<String> seen = new HashSet<>();
        for (String g : visibleOrder) {
            if (g == null) continue;
            String clean = g.trim();
            if (clean.isEmpty()) continue;
            if (hiddenGenres != null && hiddenGenres.contains(clean)) continue;
            if (seen.add(clean)) out.add(clean);
        }
        return out;
    }

    /**
     * Builds the strict user-order lazy-loading queue:
     * 1. User's Watch List in their exact saved/similarity item order
     * 2. Recommended
     * 3. Trending
     * 4. Each Genre Row in the user's exact custom genre order
     */
    static List<Pick> buildUserOrderedLazyQueue(
            List<Pick> watchlistInUserOrder,
            List<Pick> recommended,
            List<Pick> trending,
            List<List<Pick>> genreRowsInUserOrder,
            int leadPerPrimaryRow,
            int leadPerGenreRow
    ) {
        List<Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int pCap = Math.max(0, leadPerPrimaryRow);
        for (List<Pick> row : Arrays.asList(watchlistInUserOrder, recommended, trending)) {
            if (row == null) continue;
            for (int i = 0; i < Math.min(pCap, row.size()); i++) {
                Pick p = row.get(i);
                if (p != null && seen.add(key(p))) out.add(p);
            }
        }
        int gCap = Math.max(0, leadPerGenreRow);
        if (genreRowsInUserOrder != null) {
            for (List<Pick> gRow : genreRowsInUserOrder) {
                if (gRow == null) continue;
                for (int i = 0; i < Math.min(gCap, gRow.size()); i++) {
                    Pick p = gRow.get(i);
                    if (p != null && seen.add(key(p))) out.add(p);
                }
            }
        }
        return out;
    }

    /** Returns an already-decoded hero backdrop from RAM (or starts decoding the lead pick) so non-Home screens never stay dark. */
    Backdrop firstReadyBackdrop() {
        synchronized (images) {
            if (!images.isEmpty()) {
                for (String pk : pinnedBackdropKeys) {
                    Backdrop pb = images.get(pk);
                    if (pb != null && !pb.isRecycled()) return pb;
                }
                java.util.Iterator<Map.Entry<String, Backdrop>> it = images.entrySet().iterator();
                while (it.hasNext()) {
                    Backdrop b = it.next().getValue();
                    if (b != null && b.isRecycled()) {
                        it.remove();
                        continue;
                    }
                    if (b != null) return b;
                }
            }
        }
        List<Pick> snapshot = picks;
        for (int i = 0; i < snapshot.size(); i++) {
            Pick p = snapshot.get(i);
            if (p != null && p.backdrop != null && !p.backdrop.isEmpty()) {
                return backdrop(p);
            }
        }
        return null;
    }

    interface BackdropCallback {
        void onBackdropLoaded(String key, Backdrop b);
    }

    private BackdropCallback backdropCallback = null;
    private Runnable posterCallback = null;
    private final java.util.Set<String> pendingPosters = new java.util.HashSet<>();

    void setBackdropCallback(BackdropCallback cb) {
        this.backdropCallback = cb;
    }

    void setPosterCallback(Runnable cb) {
        this.posterCallback = cb;
    }

    private static volatile long lastUserInteractionUptimeMs = 0L;

    static void notifyUserInteraction() {
        try {
            lastUserInteractionUptimeMs = android.os.SystemClock.uptimeMillis();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isUserActivelyNavigating() {
        try {
            long last = lastUserInteractionUptimeMs;
            return last > 0L && (android.os.SystemClock.uptimeMillis() - last) < 650L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static java.util.concurrent.ThreadFactory daemonFactory(String prefix, final int androidThreadPriority) {
        return r -> {
            Thread t = new Thread(() -> {
                try {
                    android.os.Process.setThreadPriority(androidThreadPriority);
                } catch (Throwable ignored) {
                }
                r.run();
            }, prefix);
            t.setDaemon(true);
            return t;
        };
    }

    static void evictOldestBackdropsWithPins(Map<String, Backdrop> map, int max, Set<String> pinnedKeys) {
        while (map.size() > max) {
            String evictKey = null;
            for (Map.Entry<String, Backdrop> entry : map.entrySet()) {
                String k = entry.getKey();
                Backdrop val = entry.getValue();
                boolean pinned = k.startsWith("wn:")
                        || (pinnedKeys != null && pinnedKeys.contains(k))
                        || Backdrop.isDisplayedBackdrop(val);
                if (!pinned) {
                    evictKey = k;
                    break;
                }
            }
            if (evictKey == null) {
                for (Map.Entry<String, Backdrop> entry : map.entrySet()) {
                    if (!Backdrop.isDisplayedBackdrop(entry.getValue())) {
                        evictKey = entry.getKey();
                        break;
                    }
                }
            }
            if (evictKey == null) evictKey = map.keySet().iterator().next();
            Backdrop evicted = map.remove(evictKey);
            if (evicted != null) Backdrop.scheduleSafeRecycle(evicted);
        }
    }

    private void evictOldestBackdropsLocked(Map<String, Backdrop> map, int max) {
        evictOldestBackdropsWithPins(map, max, pinnedBackdropKeys);
    }

    private static void evictOldestPostersLocked(Map<String, Poster> map, int max) {
        while (map.size() > max) {
            String evictKey = map.keySet().iterator().next();
            Poster evicted = map.remove(evictKey);
            if (evicted != null) evicted.recycle();
        }
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor(
            daemonFactory("mytv-recs-io", android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE));
    private final ExecutorService heroIo = Executors.newFixedThreadPool(2,
            daemonFactory("mytv-recs-hero", android.os.Process.THREAD_PRIORITY_BACKGROUND));
    private final ExecutorService imgIo = Executors.newFixedThreadPool(2,
            daemonFactory("mytv-recs-img", android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE));
    private final ExecutorService posterIo = Executors.newFixedThreadPool(4,
            daemonFactory("mytv-recs-poster", android.os.Process.THREAD_PRIORITY_BACKGROUND));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Poster> posters = new LinkedHashMap<>(128, 0.75f, true);   // access-order LRU keyed by immutable posterCacheKey(p)
    private final Map<String, Long> failedPosters = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean posterCallbackScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean metadataSaveScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean deferredSaveWhilePaused = false;
    private final Runnable deferredMetadataSaveRunnable = () -> {
        metadataSaveScheduled.set(false);
        if (backgroundActivityPaused) {
            deferredSaveWhilePaused = true;
            return;
        }
        io.execute(this::save);
    };

    void scheduleDebouncedSave() {
        if (backgroundActivityPaused) {
            deferredSaveWhilePaused = true;
            return;
        }
        if (metadataSaveScheduled.compareAndSet(false, true)) {
            main.postDelayed(deferredMetadataSaveRunnable, METADATA_SAVE_DEBOUNCE_MS);
        }
    }

    void flushPendingSaveAsync() {
        if (backgroundActivityPaused) {
            if (metadataSaveScheduled.compareAndSet(true, false)) {
                main.removeCallbacks(deferredMetadataSaveRunnable);
                deferredSaveWhilePaused = true;
            }
            return;
        }
        if (metadataSaveScheduled.compareAndSet(true, false)) {
            main.removeCallbacks(deferredMetadataSaveRunnable);
            io.execute(this::save);
        }
    }
    private final Map<String, Backdrop> images = new LinkedHashMap<>(16, 0.75f, true);   // this hour's, built and ready to draw
    private final Map<String, Pick> watchExtraPicks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private final java.util.Set<String> decodingBackdrops = new java.util.HashSet<>();
    private final Map<String, Integer> rowPageMap = new HashMap<>();
    private final Set<String> rowFetchingSet = new HashSet<>();
    private final Map<String, List<Pick>> rowCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int rowCachePrefsVersion = -1;
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, traktStamp;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts
    private volatile String traktActivity = "";  // Trakt's last change when the picks were fetched
    private volatile long traktCheckedAt;

    private Recs(Context c) {
        context = c;
        seedVaultTmdbKeyIfAbsent(c, Config.TMDB_KEY);
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        byName = readByName(c);
        boolean versionChanged = cache.getInt("version", 0) != CACHE_VERSION;
        try {
            String saved = Prefs.readJsonCacheFile(c, "recs", "recs", "picks");
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = versionChanged ? 0L : cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
            traktStamp = cache.getLong("traktStamp", 0);
            traktActivity = cache.getString("traktActivity", "");
            if (versionChanged) {
                for (Pick sp : picks) {
                    if (!isValidBackdropCandidate(sp.backdrop, sp.poster)) {
                        String fb = resolveFallbackBackdropUrl(sp.imdbId);
                        sp.backdrop = fb != null ? fb : "";
                        sp.detailed = false;
                    }
                }
                cache.edit().putInt("version", CACHE_VERSION).putLong("listAt", 0L).apply();
            }
            if (!picks.isEmpty()) {
                posterIo.execute(this::prewarmRowCache);
            }
        } catch (Exception e) {
            AppLog.w("recommendations: failed reading cached picks", e);
        }
        // Right after the TV starts, the first fetch runs before the Wi-Fi is up and fails, which would hold the old
        // picks for the 10 minutes after a failure: as soon as the network comes, try again.
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            if (cm != null) cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    failedPosters.clear();
                    if (failedAt == 0) return;
                    failedAt = 0;   // the next screen to show fetches at once
                    main.postDelayed(() -> {
                        if (listeners.isEmpty()) return;   // nothing showing: no downloads under another app
                        AppLog.w("recommendations: network up, fetching again");
                        refreshIfStale();
                    }, 2000);   // the routes and DNS settle just after
                }
            });
        } catch (RuntimeException e) {
            AppLog.w("recommendations: no network callback: " + e.getMessage());
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Clears the in-memory computed row cache and forces the next refreshIfStale() to re-fetch. */
    void clearRowCache() {
        rowCache.clear();
        listAt = 0L;
    }

    static boolean hasKey(Context c) {
        return !key().isEmpty();
    }

    /**
     * Seeds the hardware-encrypted {@link Secrets#TMDB_KEY} vault entry from {@code Config.TMDB_KEY}
     * whenever the vault has no stored TMDB key yet, ensuring a subsequent generic or template-reset
     * APK install never drops the user's Home screen media rows.
     */
    static void seedVaultTmdbKeyIfAbsent(Context ctx, String configKey) {
        String clean = configKey == null ? "" : configKey.trim();
        if (!clean.isEmpty() && Secrets.getSecret(ctx, Secrets.TMDB_KEY).isEmpty()) {
            Secrets.putSecret(ctx, Secrets.TMDB_KEY, clean);
        }
    }

    /**
     * Resolves the active TMDB API key in strict priority order:
     * 1. Runtime user override ({@link Prefs#tmdbKey()}),
     * 2. Hardware-encrypted {@link Secrets#TMDB_KEY} vault entry,
     * 3. Compile-time {@code Config.TMDB_KEY} fallback.
     */
    static String resolveEffectiveTmdbKey(String prefsOverride, Context ctx, String configFallback) {
        return Prefs.resolveKeyWithVaultFallback(prefsOverride, ctx, Secrets.TMDB_KEY, configFallback);
    }

    private static volatile String cachedKey = "";
    private static volatile int keyVersion = -1;

    /**
     * The key given on the TV, else the one built in from config.properties ("" for neither). The home screen asks
     * while drawing: read again only when a setting changed (Prefs.version).
     */
    static String key() {
        int version = Prefs.version;
        if (version != keyVersion || cachedKey.isEmpty()) {
            Context ctx = instance != null ? instance.context : Prefs.context();
            cachedKey = resolveEffectiveTmdbKey(Prefs.tmdbKey(), ctx, Config.TMDB_KEY);


            keyVersion = version;
        }
        return cachedKey;
    }

    /**
     * A key as pasted (spaces, line breaks taken out), or "" when it can't be one: an API key (32 hex digits) or an API
     * Read Access Token (a JWT, "eyJ…").
     */
    static String cleanKey(String pasted) {
        String key = pasted == null ? "" : pasted.replaceAll("\\s+", "");
        return key.matches("[A-Za-z0-9._-]{16,1024}") ? key : "";
    }

    /** Asks TMDB whether a key works, before it's saved: null if it does, else why not, for the screen. Never logged. */
    static String checkKey(String key) {
        try {
            request("/configuration", key);
            return null;
        } catch (IOException e) {
            if (REJECTED.equals(e.getMessage())) return Ui.tr("TMDB refused this key.");
            return Ui.trf("Couldn't reach TMDB: {error}", "error", e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (org.json.JSONException e) {
            return Ui.trf("Couldn't reach TMDB: {error}", "error", e.getMessage());
        } catch (RuntimeException e) {
            return Ui.trf("Couldn't reach TMDB: {error}", "error", e.getClass().getSimpleName());
        }
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null, null, null);
    }

    /** This hour's Prefs.cards(), filtered by tab (EXPLORE/Recommended, WATCHLIST, TRENDING, MOVIES, SHOWS). */
    List<Pick> now(String kind) {
        return now(kind, null, null);
    }

    private static final class CachedRowMetadata {
        final Map<String, Integer> votes;
        final Set<String> watchlistKeys;
        final Set<String> watchlistTitles;
        final Map<String, Double> genreAffinity;
        final Map<String, Boolean> haveApp;

        CachedRowMetadata(
                Map<String, Integer> votes,
                Set<String> watchlistKeys,
                Set<String> watchlistTitles,
                Map<String, Double> genreAffinity,
                Map<String, Boolean> haveApp
        ) {
            this.votes = votes;
            this.watchlistKeys = watchlistKeys;
            this.watchlistTitles = watchlistTitles;
            this.genreAffinity = genreAffinity;
            this.haveApp = haveApp;
        }
    }

    private volatile CachedRowMetadata cachedMeta;

    void invalidateRowCache() {
        rowCache.clear();
        cachedMeta = null;
    }

    private CachedRowMetadata getOrBuildMetadata() {
        CachedRowMetadata m = cachedMeta;
        if (m != null) return m;
        String activeUserId = UserProfileManager.get(context).getActiveProfile().id;
        Map<String, Integer> votes = SupabaseClient.get(context).getCachedVotes(activeUserId);
        List<SupabaseClient.WatchlistEntry> cloudWatchlist = SupabaseClient.get(context).getCachedWatchlist(activeUserId);
        Set<String> watchlistKeys = new HashSet<>();
        Set<String> watchlistTitles = new HashSet<>();
        for (SupabaseClient.WatchlistEntry w : cloudWatchlist) {
            if (w.mediaKey != null && !w.mediaKey.isEmpty()) watchlistKeys.add(w.mediaKey);
            if (w.title != null && !w.title.isEmpty()) {
                String wKind = "movie".equals(w.mediaType) ? "movie" : "tv";
                watchlistTitles.add(wKind + ":" + plain(w.title));
            }
        }
        for (StremioAccountClient.LibraryItem li : StremioAccountClient.loadCachedLibraryItems(context)) {
            if (!li.isLibraryWatchlistItem()) continue;
            watchlistKeys.add(li.imdbId);
            watchlistKeys.add(li.kind + ":" + li.imdbId);
            if (!li.name.isEmpty()) watchlistTitles.add(li.kind + ":" + plain(li.name));
        }
        Map<String, Double> genreAffinity = new HashMap<>();
        Map<String, Boolean> haveApp = new HashMap<>();
        for (Pick wp : picks) {
            wp.watched = isPickWatched(wp);
            reconcileWatchedSeriesSeasonState(wp, 0);
            if (wp.onWatchlist || wp.mine == WATCHLIST_MINE) {
                watchlistKeys.add(key(wp));
                if (wp.imdbId != null && !wp.imdbId.isEmpty()) watchlistKeys.add(wp.imdbId);
                if (wp.title != null && !wp.title.isEmpty()) watchlistTitles.add(wp.normTitleKey());
            }
            Integer v = votes.get(key(wp));
            if (v != null && v > 0) {
                for (String g : wp.genres) genreAffinity.put(g, genreAffinity.getOrDefault(g, 0.0) + 7.5);
            } else if (v != null && v < 0) {
                for (String g : wp.genres) genreAffinity.put(g, genreAffinity.getOrDefault(g, 0.0) - 6.5);
            } else if (wp.watched || wp.mine == LIKED || wp.mine == PROGRESS) {
                for (String g : wp.genres) genreAffinity.put(g, genreAffinity.getOrDefault(g, 0.0) + 4.0);
            }
        }
        m = new CachedRowMetadata(votes, watchlistKeys, watchlistTitles, genreAffinity, haveApp);
        cachedMeta = m;
        return m;
    }

    /**
     * Pre-warms `rowCache` and pre-decodes card posters in the user's exact active sub-tab, Watch List item order,
     * and custom Genre Row order without overflowing `maxPosters()` LRU capacity.
     */
    void prewarmRowCache() {
        if (!hasKey(context) || backgroundActivityPaused) return;
        try {
            rowCachePrefsVersion = Prefs.version;
            getOrBuildMetadata();
            int queued = 0;
            int budget = Math.min(84, Math.max(48, (maxPosters() * 3) / 5));
            List<String> subOrder = orderedSubKinds(activeSubKind);
            for (int sIdx = 0; sIdx < subOrder.size(); sIdx++) {
                String sub = subOrder.get(sIdx);
                boolean isActiveSub = (sIdx == 0);
                List<Pick> wl = now(WATCHLIST, sub, "All");
                List<Pick> ex = now(EXPLORE, sub, "All");
                List<Pick> tr = now(TRENDING, sub, "All");
                if (!isActiveSub) continue;
                List<List<Pick>> genreRows = new ArrayList<>();
                List<String> genres = availableGenres(EXPLORE, sub);
                for (int gIdx = 0; gIdx < Math.min(7, genres.size()); gIdx++) {
                    genreRows.add(genreRow(sub, genres.get(gIdx)));
                }
                List<Pick> orderedQueue = buildUserOrderedLazyQueue(
                        wl, ex, tr, genreRows,
                        12,
                        9
                );
                int heroLeads = 0;
                for (Pick p : orderedQueue) {
                    if (queued >= budget) break;
                    if (p != null) {
                        poster(p);
                        if (heroLeads < 5) {
                            preloadBackdropAsync(p);
                            heroLeads++;
                        }
                        queued++;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    void prewarmRowCacheAsync() {
        if (backgroundActivityPaused) return;
        io.execute(() -> {
            if (backgroundActivityPaused) return;
            invalidateRowCache();
            prewarmRowCache();
            notifyListeners();
        });
    }

    static boolean isPickWatched(Pick p) {
        if (p == null) return false;
        if (p.watched) return true;
        if (Prefs.isWatchedItem(key(p))) return true;
        return p.imdbId != null && !p.imdbId.isEmpty() && Prefs.isWatchedItem(p.imdbId);
    }

    void markPickWatched(Pick p, boolean watched) {
        if (p == null) return;
        p.watched = watched;
        Prefs.setWatchedItem(key(p), watched);
        if (p.imdbId != null && !p.imdbId.isEmpty()) {
            Prefs.setWatchedItem(p.imdbId, watched);
        }
        if (watched && "tv".equals(p.kind)) {
            int baseline = p.airedSeasonsCount > 0 ? p.airedSeasonsCount : Math.max(1, p.seasonsCount);
            Prefs.setWatchedSeriesSeasons(key(p), baseline);
            if (p.imdbId != null && !p.imdbId.isEmpty()) {
                Prefs.setWatchedSeriesSeasons(p.imdbId, baseline);
            }
        }
        for (Pick other : picks) {
            if (key(other).equals(key(p)) || (p.imdbId != null && !p.imdbId.isEmpty() && p.imdbId.equals(other.imdbId))) {
                other.watched = watched;
            }
        }
        rowCachePrefsVersion = Prefs.version;
        io.execute(() -> {
            if (watched && "tv".equals(p.kind) && p.airedSeasonsCount <= 0 && p.id > 0 && hasKey(context)) {
                try {
                    fetchDetails(p);
                    if (p.airedSeasonsCount > 0) {
                        Prefs.setWatchedSeriesSeasons(key(p), p.airedSeasonsCount);
                        if (p.imdbId != null && !p.imdbId.isEmpty()) {
                            Prefs.setWatchedSeriesSeasons(p.imdbId, p.airedSeasonsCount);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            invalidateRowCache();
            prewarmRowCache();
            save();
            notifyListeners();
        });
    }

    /**
     * Value object representing a TMDB season summary for released-season counting.
     */
    public static final class SeasonReleaseInfo {
        public final int seasonNumber;
        public final int episodeCount;
        public final String airDate;

        public SeasonReleaseInfo(int seasonNumber, int episodeCount, String airDate) {
            this.seasonNumber = seasonNumber;
            this.episodeCount = episodeCount;
            this.airDate = airDate != null ? airDate.trim() : "";
        }
    }

    /**
     * Pure-Java core evaluator that computes how many regular seasons ({@code seasonNumber > 0})
     * have aired on or before {@code todayIso} ({@code YYYY-MM-DD}), ignoring Season 0 Specials and unaired seasons.
     */
    public static int computeReleasedSeasonsCount(
            List<SeasonReleaseInfo> seasons, int lastEpSeason, String lastEpAirDate, String todayIso) {
        if (todayIso == null || todayIso.length() < 10) return 0;
        int maxSeason = 0;
        if (seasons != null) {
            for (SeasonReleaseInfo s : seasons) {
                if (s == null || s.seasonNumber <= 0 || s.episodeCount <= 0) continue;
                if (s.airDate.length() >= 10 && !"null".equalsIgnoreCase(s.airDate)) {
                    String iso = s.airDate.substring(0, 10);
                    if (iso.compareTo(todayIso) <= 0 && s.seasonNumber > maxSeason) {
                        maxSeason = s.seasonNumber;
                    }
                }
            }
        }
        String lastAir = lastEpAirDate != null ? lastEpAirDate.trim() : "";
        if (lastEpSeason > maxSeason && (lastAir.length() < 10 || lastAir.substring(0, 10).compareTo(todayIso) <= 0)) {
            maxSeason = lastEpSeason;
        }
        return maxSeason;
    }

    /**
     * Computes how many regular seasons ({@code season_number > 0}) of a TMDB TV payload have already aired
     * on or before {@code todayIso} ({@code YYYY-MM-DD}), ignoring Season 0 Specials and future/unaired seasons.
     */
    public static int computeReleasedSeasonsCount(JSONObject tvJson, String todayIso) {
        if (tvJson == null || todayIso == null || todayIso.length() < 10) return 0;
        List<SeasonReleaseInfo> list = new ArrayList<>();
        JSONArray seasons = tvJson.optJSONArray("seasons");
        if (seasons != null) {
            for (int i = 0; i < seasons.length(); i++) {
                JSONObject s = seasons.optJSONObject(i);
                if (s == null) continue;
                list.add(new SeasonReleaseInfo(
                        s.optInt("season_number", 0),
                        s.optInt("episode_count", 1),
                        s.optString("air_date", "")));
            }
        }
        int lastS = 0;
        String lastAir = "";
        JSONObject lastEp = tvJson.optJSONObject("last_episode_to_air");
        if (lastEp != null) {
            lastS = lastEp.optInt("season_number", 0);
            lastAir = lastEp.optString("air_date", "");
        }
        return computeReleasedSeasonsCount(list, lastS, lastAir, todayIso);
    }

    /**
     * Returns {@code true} when a watched TV series has released at least one newer season beyond
     * {@code recordedWatchedSeasons}, signaling that it should be restored to unwatched in the Watch List.
     */
    public static boolean shouldAutoUnwatchForNewSeason(
            String kind, boolean currentlyWatched, int recordedWatchedSeasons, int currentAiredSeasons) {
        return "tv".equalsIgnoreCase(kind)
                && currentlyWatched
                && recordedWatchedSeasons > 0
                && currentAiredSeasons > recordedWatchedSeasons;
    }

    private void reconcileWatchedSeriesSeasonState(Pick p, int traktMaxWatchedSeason) {
        if (p == null || !"tv".equals(p.kind) || p.airedSeasonsCount <= 0) return;
        String pk = key(p);
        String imdb = (p.imdbId != null && !p.imdbId.isEmpty()) ? p.imdbId : null;
        int autoUnwatchedAt = Math.max(
                Prefs.getAutoUnwatchedSeason(pk),
                imdb != null ? Prefs.getAutoUnwatchedSeason(imdb) : 0);
        if (autoUnwatchedAt >= p.airedSeasonsCount
                && traktMaxWatchedSeason < autoUnwatchedAt
                && !Prefs.isWatchedItem(pk)
                && (imdb == null || !Prefs.isWatchedItem(imdb))) {
            p.watched = false;
            return;
        }
        int recorded = Prefs.getWatchedSeriesSeasons(pk);
        if (recorded <= 0 && imdb != null) {
            recorded = Prefs.getWatchedSeriesSeasons(imdb);
        }
        if (recorded <= 0 && traktMaxWatchedSeason > 0) {
            recorded = traktMaxWatchedSeason;
        }
        boolean wasWatched = p.watched || isPickWatched(p);
        if (shouldAutoUnwatchForNewSeason(p.kind, wasWatched, recorded, p.airedSeasonsCount)) {
            p.watched = false;
            Prefs.setWatchedItem(pk, false);
            Prefs.setAutoUnwatchedSeason(pk, p.airedSeasonsCount);
            if (imdb != null) {
                Prefs.setWatchedItem(imdb, false);
                Prefs.setAutoUnwatchedSeason(imdb, p.airedSeasonsCount);
            }
        } else if (wasWatched && recorded <= 0) {
            Prefs.setWatchedSeriesSeasons(pk, p.airedSeasonsCount);
            if (imdb != null) {
                Prefs.setWatchedSeriesSeasons(imdb, p.airedSeasonsCount);
            }
        } else if (wasWatched && traktMaxWatchedSeason > 0 && Prefs.getWatchedSeriesSeasons(pk) <= 0) {
            Prefs.setWatchedSeriesSeasons(pk, traktMaxWatchedSeason);
            if (imdb != null) {
                Prefs.setWatchedSeriesSeasons(imdb, traktMaxWatchedSeason);
            }
        }
    }

    /**
     * Immediately updates recommendations in the background after the user votes (+1 Thumbs Up or -1 Thumbs Down).
     * If `vote > 0` (Thumbs Up), also queries TMDB `/{kind}/{id}/recommendations` ("More Like This") and merges
     * fresh recommendations with zero UI lag.
     */
    void onVoteCastAsync(final Pick p, final int vote) {
        imgIo.execute(() -> {
            try {
                if (p != null && vote > 0 && p.id > 0 && hasKey(context)) {
                    String kind = "movie".equals(p.kind) ? "movie" : "tv";
                    JSONArray seedRecs = get("/" + kind + "/" + p.id + "/recommendations?language=" + lang() + "&page=1").optJSONArray("results");
                    if (seedRecs != null && seedRecs.length() > 0) {
                        List<Pick> candidates = new ArrayList<>();
                        for (int i = 0; i < seedRecs.length() && i < 6; i++) {
                            add(candidates, seedRecs.getJSONObject(i), kind);
                        }
                        List<Pick> merged = new ArrayList<>(picks);
                        Set<String> seen = new HashSet<>();
                        for (Pick ex : merged) seen.add(key(ex));
                        boolean addedAny = false;
                        for (Pick sp : candidates) {
                            if (sp.isAnime) continue;
                            sp.mine = LIKED;
                            sp.tag = null;
                            sp.highQuality = true;
                            sp.matchScore += 25.0;
                            if (seen.add(key(sp))) {
                                merged.add(sp);
                                addedAny = true;
                            }
                        }
                        if (addedAny) {
                            picks = merged;
                            scheduleDebouncedSave();
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                invalidateRowCache();
                prewarmRowCache();
                notifyListeners();
            }
        });
    }

    private static double genreAffinityBonus(Pick p, Map<String, Double> genreAffinity) {
        if (p == null || genreAffinity == null || genreAffinity.isEmpty() || p.genres == null) return 0.0;
        double bonus = 0.0;
        for (String g : p.genres) {
            Double w = genreAffinity.get(g);
            if (w != null) bonus += w;
        }
        return Math.max(-25.0, Math.min(35.0, bonus));
    }

    /**
     * Filtered by main tab (`kind`: EXPLORE/null, WATCHLIST, TRENDING), sub-tab (`subKind`: "tv" for Series,
     * "movie" for Movies, or null for both), and optional `genre` ("Action", "Comedy", etc., or null/"All").
     */
    List<Pick> now(String kind, String subKind, String genre) {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;    // no TMDB key: no recommendations
        int curVer = Prefs.version;
        if (rowCachePrefsVersion != curVer) {
            invalidateRowCache();
            rowCachePrefsVersion = curVer;
        }
        String cacheKey = (kind == null ? "EXPLORE" : kind) + "|"
                + (subKind == null ? "" : subKind) + "|"
                + (genre == null || genre.isEmpty() ? "All" : genre);
        List<Pick> cachedRow = rowCache.get(cacheKey);
        if (cachedRow != null) {
            return new ArrayList<>(cachedRow);
        }

        CachedRowMetadata meta = getOrBuildMetadata();
        Map<String, Integer> votes = meta.votes;
        Set<String> watchlistKeys = meta.watchlistKeys;
        Set<String> watchlistTitles = meta.watchlistTitles;
        Map<String, Double> genreAffinity = meta.genreAffinity;
        Map<String, Boolean> have = meta.haveApp;

        List<Pick> progress = new ArrayList<>(), watchlist = new ArrayList<>(), liked = new ArrayList<>(), list = new ArrayList<>();
        boolean wantWatchlistRow = WATCHLIST.equals(kind);
        boolean wantAnimeOrAnim = genre != null && ("Anime".equalsIgnoreCase(genre) || "Animation".equalsIgnoreCase(genre));

        for (Pick p : picks) {
            String pickKey = key(p);
            Integer vote = votes.get(pickKey);
            // Thumbs Down (-1) and Watched items are excluded from all recommendation rows (Recommended, Trending, Genres),
            // but kept in Watch List (where watched items are grayed out and sorted last).
            if (!wantWatchlistRow && vote != null && vote < 0) continue;
            if (!wantWatchlistRow && p.watched) continue;

            if (subKind != null && !subKind.isEmpty() && !subKind.equals(p.kind)) continue;
            if (genre != null && !genre.isEmpty() && !"All".equalsIgnoreCase(genre) && !matchesGenre(p, genre)) continue;

            String normTitleKey = p.normTitleKey();
            boolean isWatchlist = p.onWatchlist
                    || (p.mine == WATCHLIST_MINE)
                    || watchlistKeys.contains(pickKey)
                    || (p.imdbId != null && !p.imdbId.isEmpty() && watchlistKeys.contains(p.imdbId))
                    || watchlistTitles.contains(normTitleKey);

            // Exclude Watch List series and movies from all other rows (Recommended, Trending, and Genre rows)
            if (!wantWatchlistRow && isWatchlist) {
                continue;
            }
            if (wantWatchlistRow && !isWatchlist) {
                continue;
            }

            // Isolate Anime to the Anime & Animation rows unless explicitly up-voted by the user
            if (!wantWatchlistRow && !wantAnimeOrAnim && p.isAnime && (vote == null || vote <= 0)) {
                continue;
            }

            boolean matchesKind;
            if (wantWatchlistRow) {
                matchesKind = true;
            } else if (TRENDING.equals(kind)) {
                matchesKind = (p.mine == 0 || p.mine == LIKED);
            } else {
                matchesKind = (kind == null || kind.equals(p.kind));
            }
            if (!matchesKind) continue;

            boolean appReady = !p.detailed || Config.OPEN_IN_STREMIO || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)
                    && (p.servicePkg == null || have.computeIfAbsent(p.servicePkg, this::installed)));
            if (!appReady) continue;

            if (vote != null && vote > 0 && !p.watched) {
                p.highQuality = true;
                liked.add(0, p); // Thumbs Up (+1): boosted to top of Recommended if not marked watched
            }
            if (isWatchlist) {
                watchlist.add(p);
            }
            if (p.mine == PROGRESS) progress.add(p);
            else if (p.mine == LIKED && !p.watched) liked.add(p);
            else if (p.mine == 0 && !p.watched) list.add(p);
        }
        int cards = (subKind != null && !subKind.isEmpty()) ? ROW_MAX_CARDS : Prefs.cards();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (wantWatchlistRow) {
            take(out, seen, watchlist, 0, cards);
            final List<String> customOrder = Prefs.watchlistOrder(subKind);
            List<Pick> sorted = WatchlistSimilaritySorter.sortWatchlist(out, customOrder);
            out.clear();
            out.addAll(sorted);
            prioritizeReadyArtwork(out, true);
            rowCache.put(cacheKey, java.util.Collections.unmodifiableList(new ArrayList<>(out)));
            return out;
        }
        if (TRENDING.equals(kind)) {
            take(out, seen, list, 0, cards);
            sortByTrendingScore(out);
            prioritizeReadyArtwork(out, false);
            rowCache.put(cacheKey, java.util.Collections.unmodifiableList(new ArrayList<>(out)));
            return out;
        }
        take(out, seen, liked, 0, cards);
        if (out.size() < cards) {
            take(out, seen, list, 0, cards);
        }
        sortByMatchScore(out, genreAffinity);
        prioritizeReadyArtwork(out, false);
        rowCache.put(cacheKey, java.util.Collections.unmodifiableList(new ArrayList<>(out)));
        return out;
    }

    /**
     * Prefetches lightweight 2:3 card posters in parallel on `posterIo` without blocking behind 4K hero backdrops,
     * preserving deterministic row order without ever shuffling items on arrival.
     */
    private void prioritizeReadyArtwork(List<Pick> list, boolean isWatchlist) {
        // Intentional no-op: queuing 16 posters across all 29 rows (464 posters) during row builds
        // overflows maxPosters() LRU and evicts Row 0's visible posters in an endless thrash loop.
        // Visible posters are loaded strictly by HomeView.drawPickArtwork, HomeView.retarget, and prewarmRowCache.
    }

    /**
     * Sorts row items while keeping each lazy-loaded page (`lazyPage == 0`, `2`, `3`, ...) strictly after earlier
     * pages so background pagination never reshuffles already-visible cards under the user's focus.
     */
    static void sortRowPreservingLazyPages(List<Pick> list, Map<String, Double> genreAffinity, boolean trending) {
        if (list == null || list.size() <= 1) return;
        list.sort((a, b) -> {
            int pCmp = Integer.compare(a.lazyPage, b.lazyPage);
            if (pCmp != 0) return pCmp;
            if (trending) {
                return Double.compare(b.trendingScore, a.trendingScore);
            }
            int q = Boolean.compare(b.highQuality, a.highQuality);
            if (q != 0) return q;
            double scoreA = a.matchScore + genreAffinityBonus(a, genreAffinity);
            double scoreB = b.matchScore + genreAffinityBonus(b, genreAffinity);
            return Double.compare(scoreB, scoreA);
        });
    }

    /**
     * Orders Recommended & Genre items by `lazyPage` first, then `highQuality`, then personal `matchScore`
     * (combining watched-item genre affinity, previous up/down votes on similar content, and Bayesian rating).
     */
    private static void sortByMatchScore(List<Pick> list, Map<String, Double> genreAffinity) {
        sortRowPreservingLazyPages(list, genreAffinity, false);
    }

    /**
     * Orders Trending items by `lazyPage` first, then `trendingScore` (TMDB popularity score and community votings),
     * ensuring Trending order is distinct from personal Recommended order.
     */
    private static void sortByTrendingScore(List<Pick> list) {
        sortRowPreservingLazyPages(list, null, true);
    }

    /** Returns the user-ordered visible genres (excluding hidden genres) that have at least one matching pick in `subKind`. */
    List<String> availableGenres(String tabKind, String subKind) {
        List<String> order = Prefs.visibleGenreOrder(subKind);
        List<String> active = new ArrayList<>();
        for (String g : order) {
            for (Pick p : picks) {
                if (subKind != null && !subKind.isEmpty() && !subKind.equals(p.kind)) continue;
                if (matchesGenre(p, g)) {
                    active.add(g);
                    break;
                }
            }
        }
        return active;
    }

    /** Returns the list of picks for a specific genre row filtered by subKind ("tv" or "movie"). Never pads with non-matching genres. */
    List<Pick> genreRow(String subKind, String genre) {
        int curVer = Prefs.version;
        if (rowCachePrefsVersion != curVer) {
            rowCache.clear();
            rowCachePrefsVersion = curVer;
        }
        String gCacheKey = "GENRE_ROW|" + (subKind == null ? "" : subKind) + "|" + (genre == null ? "" : genre);
        List<Pick> cached = rowCache.get(gCacheKey);
        if (cached != null) return new ArrayList<>(cached);

        List<Pick> raw = now(EXPLORE, subKind, genre);
        if (raw.size() < ROW_MAX_CARDS) {
            List<Pick> trendingGenre = now(TRENDING, subKind, genre);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Pick p : raw) seen.add(key(p));
            for (Pick p : trendingGenre) {
                if (seen.add(key(p))) raw.add(p);
                if (raw.size() >= ROW_MAX_CARDS) break;
            }
        }
        sortByMatchScore(raw, null);
        List<Pick> matched = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (int i = 0; i < Math.min(ROW_MAX_CARDS, raw.size()); i++) {
                matched.add(raw.get(i));
            }
        }
        rowCache.put(gCacheKey, java.util.Collections.unmodifiableList(new ArrayList<>(matched)));
        return matched;
    }

    static boolean matchesGenre(Pick p, String genre) {
        if (p == null || genre == null) return false;
        String gNorm = genre.trim();
        // Isolate Anime to Anime & Animation rows so Anime does not flood live-action genres
        if (p.isAnime) {
            return "Anime".equalsIgnoreCase(gNorm) || "Animation".equalsIgnoreCase(gNorm);
        }
        if ("Rom-Coms".equalsIgnoreCase(gNorm) || "Rom-Com".equalsIgnoreCase(gNorm)) {
            String lang = p.lang == null ? "en" : p.lang.toLowerCase(Locale.ROOT);
            // Keep Korean dramas in K-Drama and Japanese/Chinese titles out of Rom-Coms
            if ("ko".equals(lang) || "ja".equals(lang) || "zh".equals(lang)) {
                return false;
            }
            boolean hasComedy = false, hasRomance = false;
            for (String pg : p.genres) {
                if ("Rom-Coms".equalsIgnoreCase(pg)) return true;
                if ("Comedy".equalsIgnoreCase(pg)) hasComedy = true;
                if ("Romance".equalsIgnoreCase(pg)) hasRomance = true;
            }
            return hasComedy && hasRomance;
        }
        for (String pg : p.genres) {
            if (pg.equalsIgnoreCase(gNorm)) return true;
        }
        return false;
    }

    /** Converts Trakt in-progress items (`p.mine == PROGRESS`) into WatchNext.Item entries with 2:3 posters + 16:9 backdrops. */
    List<WatchNext.Item> traktContinueItems(Context c) {
        List<WatchNext.Item> out = new ArrayList<>();
        for (Pick p : picks) {
            if (p.mine != PROGRESS || p.title == null || p.title.isEmpty()) continue;
            String pkg = p.pkg != null ? p.pkg : stremioPkg();
            String rawPosterUrl = p.poster != null && !p.poster.isEmpty()
                    ? (p.poster.startsWith("http") ? p.poster : (POSTERS + p.poster)) : null;
            String posterUrl = WatchNext.sanitizePosterUrl(rawPosterUrl,
                    p.imdbId != null && !p.imdbId.isEmpty() ? p.imdbId : p.link, true);
            String bdUrl = isValidBackdropCandidate(p.backdrop, p.poster)
                    ? (p.backdrop.startsWith("http") ? p.backdrop : (IMAGES + p.backdrop))
                    : resolveFallbackBackdropUrl(p.imdbId);
            WatchNext.Item it = new WatchNext.Item(pkg, posterUrl, bdUrl, p.link);
            it.title = p.title;
            it.isSeries = "tv".equals(p.kind);
            it.episode = p.tag != null ? WatchNext.compactEpisodeSpacing(p.tag) : null;
            int[] se = WatchNext.extractSeasonEpisode(p.progressSeason, p.progressEpisode, it.episode, p.link);
            it.seasonNumber = se[0];
            it.episodeNumber = se[1];
            it.app = p.app != null ? name(p.app) : "";
            it.realEngaged = p.engaged > 0 ? p.engaged : 0L;
            it.engaged = p.engaged > 0 ? p.engaged : (listAt > 0 ? listAt : 1L);
            if (p.progressPct > 0f && p.progressPct < 95f) {
                it.duration = 60 * 60_000L;
                it.position = Math.round(it.duration * (p.progressPct / 100f));
            }
            if (it.isSeries && it.seasonNumber > 0 && it.episodeNumber > 0) {
                it.episodeTitle = getCachedEpisodeTitle(it, p);
            }
            if (it.isSeries && it.seasonNumber > 0 && it.episodeNumber > 0
                    && p.link != null && p.link.startsWith("stremio:///detail/series/")
                    && p.imdbId != null && !p.imdbId.isEmpty()) {
                it.intent = buildStremioDetailUri(true, p.imdbId, it.seasonNumber, it.episodeNumber, null);
            } else {
                it.intent = p.link;
            }
            out.add(it);
        }
        return out;
    }

    /** Converts Stremio Account Library items with active watch progress into WatchNext.Item entries (2:3 poster + 16:9 backdrop). */
    List<WatchNext.Item> stremioContinueItems(Context c) {
        List<WatchNext.Item> out = new ArrayList<>();
        String stremioPackage = stremioPkg();
        for (StremioAccountClient.LibraryItem li : StremioAccountClient.loadCachedLibraryItems(c)) {
            if (!li.hasContinueWatchingState() || li.name.isEmpty()) continue;
            String posterUrl = null;
            String bdUrl = null;
            for (Pick p : picks) {
                if (li.imdbId.equals(p.imdbId) || li.name.equalsIgnoreCase(p.title)) {
                    if (p.poster != null && !p.poster.isEmpty()) {
                        String cand = p.poster.startsWith("http") ? p.poster : (POSTERS + p.poster);
                        posterUrl = WatchNext.sanitizePosterUrl(cand, li.imdbId, true);
                    }
                    if (isValidBackdropCandidate(p.backdrop, p.poster)) {
                        bdUrl = p.backdrop.startsWith("http") ? p.backdrop : (IMAGES + p.backdrop);
                    }
                    break;
                }
            }
            if (posterUrl == null && !li.poster.isEmpty()) {
                posterUrl = WatchNext.sanitizePosterUrl(li.poster, li.imdbId, true);
            }
            if (bdUrl == null) {
                bdUrl = resolveFallbackBackdropUrl(li.imdbId);
            }
            boolean isTv = "tv".equals(li.kind);
            String stremioIntent = buildStremioDetailUri(isTv, li.imdbId, li.season, li.episode, li.videoId);
            WatchNext.Item it = new WatchNext.Item(stremioPackage, posterUrl, bdUrl, stremioIntent);
            it.title = li.name;
            it.isSeries = isTv;
            if (it.isSeries && li.season > 0 && li.episode > 0) {
                boolean finished = li.durationMs > 0L && li.timeOffsetMs >= (long) (li.durationMs * 0.90f);
                int targetEp = finished ? (li.episode + 1) : li.episode;
                it.seasonNumber = li.season;
                it.episodeNumber = targetEp;
                if (finished) {
                    it.episode = WatchNext.compactEpisodeSpacing(Ui.trf("Up next: S{s} E{e}", "s", li.season, "e", targetEp));
                    it.position = 0L;
                } else if (li.timeOffsetMs > 0L) {
                    it.episode = WatchNext.compactEpisodeSpacing(Ui.trf("Continue: S{s} E{e}", "s", li.season, "e", li.episode));
                    it.duration = li.durationMs > 0L ? li.durationMs : 45 * 60_000L;
                    it.position = li.timeOffsetMs;
                } else {
                    it.episode = WatchNext.compactEpisodeSpacing(Ui.trf("Up next: S{s} E{e}", "s", li.season, "e", li.episode));
                }
            } else if (!it.isSeries && li.timeOffsetMs > 60_000L) {
                it.duration = li.durationMs > 0L ? li.durationMs : 120 * 60_000L;
                it.position = li.timeOffsetMs;
            }
            it.app = "Stremio";
            long eng = Math.max(li.lastWatchedMs, li.mtimeMs);
            it.realEngaged = eng > 0L ? eng : 0L;
            it.engaged = eng > 0L ? eng : 1L;
            it.intent = buildStremioDetailUri(it.isSeries, li.imdbId, it.seasonNumber, it.episodeNumber, li.videoId);
            if (it.isSeries && it.seasonNumber > 0 && it.episodeNumber > 0) {
                it.episodeTitle = getCachedEpisodeTitle(it, findMatchingPick(it));
            }
            out.add(it);
        }
        return out;
    }

    /** Up to `max` in `out`, starting `start` into `from` (wrapping around), one per franchise. */
    private static void take(List<Pick> out, java.util.Set<String> seen, List<Pick> from, long start, int max) {
        boolean plex = false;
        for (Pick p : out) plex |= p.id < 0;
        for (int i = 0; i < from.size() && out.size() < max; i++) {
            Pick p = from.get((int) ((start + i) % from.size()));
            if (p.id < 0 && plex) continue;   // a Plex title is the surprise: one per tab, not the movie and the show together
            if (seen.add(franchise(p.title))) {
                out.add(p);
                plex |= p.id < 0;
            }
        }
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : name(p.app);
    }

    /**
     * Services SERVICES doesn't know, found by name: a TMDB service in REGION named like a TV app ("Oldflix"). As SERVICES
     * rows {plain TMDB name, key = package, package, app name}, from the last list (recs-services.json, which the settings
     * read in another process). They start off in Picks from.
     */
    private static volatile List<String[]> byName = new ArrayList<>();

    /** Not by name: Plex's TMDB entry is its free catalogue, not the person's library (#135); YouTube would join on almost every TV. */
    private static final Set<String> NOT_BY_NAME = new HashSet<>(Arrays.asList("plex", "youtube"));

    /** hasTitles' answers for a day, by provider (only on `io`): a TV without the known services lists again every 10 minutes. */
    private final Map<Integer, Boolean> titlesKnown = new HashMap<>();
    private long titlesAt;

    private static String name(String key) {
        String known = SERVICE_NAME.get(key);
        if (known != null) return known;
        for (String[] s : byName) if (s[1].equals(key)) return s[3];
        return "";
    }

    /** "Oldflix" and "OLDFLIX", "Claro video" and "Claro Vídeo": compared without case, accents or punctuation, in any script. */
    static String plain(String name) {
        String n = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace("+", " plus").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static List<String[]> readByName(Context c) {
        List<String[]> out = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(new File(c.getFilesDir(), "recs-services.json"))) {
            JSONArray a = new JSONArray(read(in));
            for (int i = 0; i < a.length(); i++) {
                JSONArray s = a.getJSONArray(i);
                out.add(new String[] {s.getString(0), s.getString(1), s.getString(2), s.getString(3)});
            }
        } catch (Exception ignored) {   // none found yet
        }
        return out;
    }

    /** Through a temporary file per process (the screensaver has its own Recs on Android 11 to 12L): the settings read it. */
    private void writeByName(List<String[]> found) {
        JSONArray a = new JSONArray();
        for (String[] s : found) a.put(new JSONArray(Arrays.asList(s)));
        File tmp = new File(context.getFilesDir(), "recs-services." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(context.getFilesDir(), "recs-services.json"));
    }

    /**
     * Whether a title's own list has the service by subscription. /discover alone can't say: it matches the service and
     * the type separately, so a store that only rents a title also comes back when someone else streams it.
     */
    private boolean hasTitles(int provider) {
        long now = System.currentTimeMillis();
        if (now - titlesAt > 24 * 3600_000L || now < titlesAt) {
            titlesKnown.clear();
            titlesAt = now;
        }
        Boolean known = titlesKnown.get(provider);
        if (known != null) return known;
        try {
            boolean found = false;
            for (String kind : new String[] {"movie", "tv"}) {
                JSONArray titles = get("/discover/" + kind + "?language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate"
                        + "&include_adult=false&sort_by=popularity.desc&with_watch_providers=" + provider).getJSONArray("results");
                for (int i = 0; i < titles.length() && i < 5 && !found; i++) {
                    JSONObject br = get("/" + kind + "/" + titles.getJSONObject(i).getInt("id") + "/watch/providers?language=" + lang())
                            .getJSONObject("results").optJSONObject(REGION);
                    JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
                    for (int j = 0; flat != null && j < flat.length(); j++) found |= flat.getJSONObject(j).optInt("provider_id") == provider;
                }
                if (found) break;
            }
            titlesKnown.put(provider, found);
            return found;
        } catch (Exception e) {   // not kept: the next list asks again
            AppLog.w("recs: titles of service " + provider + ": " + e.getMessage());
        }
        return false;
    }

    /** Returns the already-decoded Backdrop from RAM cache in O(1), or null without queueing network/disk work. */
    Backdrop peekBackdrop(Pick p) {
        if (p == null || p.backdrop == null || p.backdrop.isEmpty()) return null;
        synchronized (images) {
            Backdrop b = images.get(p.backdrop);
            if (b != null && b.isRecycled()) {
                images.remove(p.backdrop);
                return null;
            }
            return b;
        }
    }

    /** Proactively pre-downloads and pre-decodes a neighbor Pick's Backdrop on `imgIo` without blocking `heroIo`. */
    void preloadBackdropAsync(final Pick p) {
        if (p == null || p.backdrop == null || p.backdrop.isEmpty() || backgroundActivityPaused) return;
        final String key = p.backdrop;
        synchronized (images) {
            if (images.containsKey(key) || !pending.add(key)) return;
        }
        imgIo.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) {
                    pending.remove(key);
                }
            }
        });
    }

    /** Resolves the deterministic `"wn:"` backdrop cache key URL for a Continue Watching item, or null if none. */
    private String resolveWatchItemBackdropUrl(WatchNext.Item it) {
        if (it == null) return null;
        String bdUrl = null;
        Pick matched = findMatchingPick(it);
        if (matched != null && isValidBackdropCandidate(matched.backdrop, matched.poster)) {
            bdUrl = matched.backdrop.startsWith("http") ? matched.backdrop : (IMAGES + matched.backdrop);
        }
        if (bdUrl == null || bdUrl.isEmpty()) {
            bdUrl = it.backdropUrl;
        }
        if (bdUrl == null || bdUrl.isEmpty()) {
            String imdb = WatchNext.extractImdbId(it);
            bdUrl = resolveFallbackBackdropUrl(imdb);
        }
        return (bdUrl == null || bdUrl.isEmpty()) ? null : bdUrl;
    }

    String watchBackdropKey(WatchNext.Item it) {
        String bdUrl = resolveWatchItemBackdropUrl(it);
        return bdUrl == null ? null : ("wn:" + bdUrl);
    }

    /** Returns an already-decoded Continue Watching Backdrop from RAM in O(1), or null without queueing I/O. */
    Backdrop peekWatchBackdrop(WatchNext.Item it) {
        String bdUrl = resolveWatchItemBackdropUrl(it);
        if (bdUrl == null) return null;
        String wnKey = "wn:" + bdUrl;
        synchronized (images) {
            Backdrop b = images.get(wnKey);
            if (b != null && b.isRecycled()) {
                images.remove(wnKey);
                return null;
            }
            return b;
        }
    }

    private void loadWatchBackdropInternal(final String wnKey, final String fetchUrl) {
        synchronized (images) {
            if (images.containsKey(wnKey) || !decodingBackdrops.add(wnKey)) return;
        }
        Backdrop loaded = null;
        boolean notifyHero = false;
        BackdropCallback cb = null;
        try {
            File f = new File(new File(context.getCacheDir(), "recs"), "uhd4k_wn_" + safeCacheFileName(fetchUrl));
            if (!f.exists()) {
                fetchFile(f, (HttpURLConnection) new URL(fetchUrl).openConnection());
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth > 0 && bounds.outWidth > bounds.outHeight) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
                if (b != null) {
                    loaded = Backdrop.make(b, light);
                }
            }
        } catch (Exception e) {
            AppLog.w("continue watching 4k backdrop: " + e.getMessage());
        } finally {
            synchronized (images) {
                decodingBackdrops.remove(wnKey);
                if (loaded != null) {
                    Backdrop prev = images.put(wnKey, loaded);
                    if (prev != null && prev != loaded) Backdrop.scheduleSafeRecycle(prev);
                    evictOldestBackdropsLocked(images, maxBackdrops());
                    notifyHero = true;
                    cb = backdropCallback;
                }
            }
        }
        if (notifyHero && loaded != null && cb != null) {
            final Backdrop ready = loaded;
            main.post(() -> {
                BackdropCallback currentCb = backdropCallback;
                if (currentCb != null) {
                    currentCb.onBackdropLoaded(wnKey, ready);
                }
            });
        }
    }

    /** Proactively pre-downloads and pre-decodes a neighbor Continue Watching item's Backdrop on `imgIo`. */
    void preloadWatchBackdropAsync(final WatchNext.Item it) {
        final String bdUrl = resolveWatchItemBackdropUrl(it);
        if (bdUrl == null || backgroundActivityPaused) return;
        final String wnKey = "wn:" + bdUrl;
        synchronized (images) {
            if (images.containsKey(wnKey) || decodingBackdrops.contains(wnKey) || !pending.add(wnKey)) return;
        }
        imgIo.execute(() -> {
            try {
                loadWatchBackdropInternal(wnKey, bdUrl);
            } finally {
                synchronized (images) {
                    pending.remove(wnKey);
                }
            }
        });
    }

    /**
     * The pick's ready backdrop.
     * Returns immediately on the UI thread (<0.05ms) if cached in RAM.
     * If not in RAM, coordinates atomically with in-flight preloads or dispatches decoding to `heroIo`.
     */
    Backdrop backdrop(Pick p) {
        if (p == null) return null;
        if ((p.backdrop == null || p.backdrop.isEmpty()) && p.imdbId != null && p.imdbId.startsWith("tt")) {
            String fb = resolveFallbackBackdropUrl(p.imdbId);
            if (fb != null && !fb.isEmpty()) p.backdrop = fb;
        }
        if (p.backdrop == null || p.backdrop.isEmpty()) return null;
        final String key = p.backdrop;

        // 1. Cancel any stale hero task from a previously focused item
        java.util.concurrent.Future<?> task = activeBackdropTask;
        String oldKey = activeBackdropTaskKey;
        if (task != null && !task.isDone() && !key.equals(oldKey)) {
            task.cancel(false);
            if (oldKey != null) {
                synchronized (images) {
                    pending.remove(oldKey);
                }
            }
        }

        // 2. Atomically record activeFocusKey and check RAM cache or in-flight preload
        synchronized (images) {
            activeFocusKey = key;
            Backdrop b = images.get(key);
            if (b != null && b.isRecycled()) {
                images.remove(key);
                b = null;
            }
            if (b != null) return b;
            if (decodingBackdrops.contains(key)) {
                // Already mid-decode on imgIo/heroIo; loadImageLockedKey will fire backdropCallback upon completion
                return null;
            }
            pending.add(key);
        }

        // 3. Submit asynchronous decode to dedicated high-priority hero thread pool
        activeBackdropTaskKey = key;
        activeBackdropTask = heroIo.submit(() -> {
            try {
                if (!key.equals(activeFocusKey)) return;
                loadImage(p);
            } finally {
                synchronized (images) {
                    pending.remove(key);
                    if (key.equals(activeBackdropTaskKey)) {
                        activeBackdropTaskKey = null;
                    }
                }
            }
        });

        return null;
    }

    /**
     * Resolves a crisp 16:9 full-screen Backdrop for a Continue Watching item (`WatchNext.Item`),
     * matching its `Pick` 4K backdrop or `it.backdropUrl` and never stretching a 2:3 portrait card into 16:9.
     */
    Backdrop backdropForWatchItem(WatchNext.Item it, WatchArt art) {
        String bdUrl = resolveWatchItemBackdropUrl(it);
        if (bdUrl == null || bdUrl.isEmpty()) return null;

        final String wnKey = "wn:" + bdUrl;
        final String fetchUrl = bdUrl;
        java.util.concurrent.Future<?> task = activeBackdropTask;
        String oldKey = activeBackdropTaskKey;
        if (task != null && !task.isDone() && !wnKey.equals(oldKey)) {
            task.cancel(false);
            if (oldKey != null) {
                synchronized (images) {
                    pending.remove(oldKey);
                }
            }
        }
        synchronized (images) {
            activeFocusKey = wnKey;
            Backdrop existing = images.get(wnKey);
            if (existing != null && existing.isRecycled()) {
                images.remove(wnKey);
                existing = null;
            }
            if (existing != null) return existing;
            if (decodingBackdrops.contains(wnKey)) {
                return null;
            }
            pending.add(wnKey);
        }
        activeBackdropTaskKey = wnKey;
        activeBackdropTask = heroIo.submit(() -> {
            try {
                if (!wnKey.equals(activeFocusKey)) return;
                loadWatchBackdropInternal(wnKey, fetchUrl);
            } finally {
                synchronized (images) {
                    pending.remove(wnKey);
                    if (wnKey.equals(activeBackdropTaskKey)) activeBackdropTaskKey = null;
                }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && !p.backdrop.isEmpty() && imageFile(p).exists();
    }

    private static String safeCacheFileName(String ref) {
        if (ref == null) return "";
        return ref.startsWith("http") ? ref.replaceAll("[^A-Za-z0-9._-]", "_") : ref.replace("/", "");
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), "uhd4k_" + safeCacheFileName(p.backdrop));
    }

    private volatile SyncProgressListener syncProgressListener;

    void setSyncProgressListener(SyncProgressListener listener) {
        this.syncProgressListener = listener;
    }

    private void reportSyncProgress(final String stepText, final int pct, final boolean done) {
        final SyncProgressListener l = syncProgressListener;
        if (l == null) return;
        main.post(() -> l.onSyncProgress(stepText, pct, done));
    }

    /** Forces an immediate full sync of Trakt, Stremio, and TMDB catalogs with progress reporting. */
    void forceRefreshNow() {
        listAt = 0;
        traktCheckedAt = 0;
        failedAt = 0;
        if (!fetching) {
            refreshIfStale();
        } else {
            reportSyncProgress("Syncing accounts & catalogs…", 35, false);
        }
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        // Signing in or out of Trakt fetches the list again, judged only in :home: the screensaver's process on Android
        // 11 to 12L has no Trakt, and must not take the saved list for another account's.
        long trakt = Trakt.available() ? Trakt.stamp(context) : traktStamp;
        boolean newTrakt = trakt != traktStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && !newTrakt && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick);
        boolean checkTrakt = !stale && !newTrakt && Trakt.signedIn(context) && (now - traktCheckedAt > TRAKT_EVERY || now < traktCheckedAt);
        if (!stale && !missing && !checkTrakt && !newTrakt) {
            reportSyncProgress("All accounts & catalogs are up to date", 100, true);
            return;
        }
        keyStamp = stamp;
        traktStamp = trakt;
        fetching = true;
        if (checkTrakt) traktCheckedAt = now;
        io.execute(() -> {
            try {
                if (backgroundActivityPaused) return;
                boolean list = stale || newTrakt;
                if (checkTrakt) {
                    try {
                        String activity = Trakt.activity(context);
                        if (!activity.isEmpty() && !activity.equals(traktActivity)) {
                            AppLog.w("trakt: the account changed, fetching the picks again");
                            list = true;
                        }
                    } catch (Exception e) {
                        AppLog.w("trakt activity: " + e.getMessage());
                    }
                }
                if (list) {
                    reportSyncProgress("Connecting to Trakt, Stremio & TMDB…", 10, false);
                    // Always reuse known details & backdrops so existing items never swap images or re-fetch unnecessarily
                    Map<String, Pick> known = new HashMap<>();
                    for (Pick k : picks) if (k.detailed) known.put(key(k), k);
                    boolean hadInitialPicks = !picks.isEmpty();
                    fetchList(p, known);
                    if (!hadInitialPicks) {
                        prewarmRowCache();
                        scheduleDebouncedSave();
                        notifyListeners();
                    }
                }
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                List<Pick> wantedList = wanted();
                int totalWanted = Math.max(1, wantedList.size());
                for (Pick pick : wantedList) {
                    if (backgroundActivityPaused) break;
                    if (isUserActivelyNavigating()) {
                        try {
                            Thread.sleep(90L);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    tried++;
                    try {
                        if (!pick.detailed) {
                            fetchDetails(pick);
                        }
                        if (pick.pkg != null) {
                            // Download lightweight 2:3 posters first so tiles populate fast; pre-decode 4K hero backdrops only for top items
                            downloadPosterOnly(pick);
                            if (tried <= maxBackdrops() && pick.backdrop != null && !pick.backdrop.isEmpty()) {
                                boolean alreadyDecoded;
                                synchronized (images) {
                                    alreadyDecoded = images.containsKey(pick.backdrop) || images.size() >= maxBackdrops();
                                }
                                if (!alreadyDecoded) {
                                    loadImage(pick);
                                }
                            }
                        }
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    if (tried % 8 == 0) {
                        int prog = Math.min(96, 75 + (tried * 20 / totalWanted));
                        reportSyncProgress("Loading artwork & metadata (" + tried + "/" + totalWanted + ")…", prog, false);
                    }
                }
                if (!backgroundActivityPaused) {
                    Prefs.setLastAccountSyncAt(System.currentTimeMillis());
                    invalidateRowCache();
                    prewarmRowCache();
                    save();
                } else {
                    deferredSaveWhilePaused = true;
                }
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                if (!backgroundActivityPaused) {
                    prewarmRowCache();
                    reportSyncProgress("Sync complete", 100, true);
                    notifyListeners();
                }
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            for (Backdrop b : images.values()) {
                if (b != null) Backdrop.scheduleSafeRecycle(b);
            }
            images.clear();
            for (Poster p : posters.values()) {
                if (p != null) p.recycle();
            }
            posters.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && (p.servicePkg == null || have.computeIfAbsent(p.servicePkg, this::installed))
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching || backgroundActivityPaused) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        // The list's titles only up to what prune() keeps: more would be downloaded again every minute.
        List<Pick> list = picks;
        List<Pick> todo = new ArrayList<>();
        for (int i = 0; i < list.size() && i < POOL + TRAKT_MAX; i++) {
            Pick p = list.get(i);
            if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        }
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    if (backgroundActivityPaused) break;
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                if (!backgroundActivityPaused) {
                    save();
                } else {
                    deferredSaveWhilePaused = true;
                }
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /**
     * Prioritizes preloading the first few (`PRELOAD_FIRST_ITEMS`) items of each row in the user's exact order:
     * active sub-tab first, Watch List in user/similarity item order, Recommended, Trending, and Genre Rows in user genre order.
     */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> subOrder = orderedSubKinds(activeSubKind);
        // Pass 1: Lead items in strict user order (Watch List -> Recommended -> Trending -> User-Ordered Genre Rows)
        for (String sub : subOrder) {
            List<Pick> wl = now(WATCHLIST, sub, "All");
            List<Pick> ex = now(EXPLORE, sub, "All");
            List<Pick> tr = now(TRENDING, sub, "All");
            List<List<Pick>> genreRows = new ArrayList<>();
            for (String g : availableGenres(EXPLORE, sub)) {
                genreRows.add(genreRow(sub, g));
            }
            for (Pick p : buildUserOrderedLazyQueue(wl, ex, tr, genreRows, PRELOAD_FIRST_ITEMS, 6)) {
                if (seen.add(key(p))) out.add(p);
            }
        }
        // Pass 2: Next batch (indices 10..20) of primary rows in active sub-tab order
        for (String sub : subOrder) {
            for (String kind : new String[] {WATCHLIST, EXPLORE, TRENDING}) {
                List<Pick> row = now(kind, sub, "All");
                for (int i = PRELOAD_FIRST_ITEMS; i < Math.min(PRELOAD_FIRST_ITEMS * 2, row.size()); i++) {
                    Pick p = row.get(i);
                    if (seen.add(key(p))) out.add(p);
                }
            }
        }
        for (int i = 0; i < picks.size() && out.size() < 120; i++) {
            Pick p = picks.get(i);
            if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p, Map<String, Pick> known) throws Exception {
        // Whether a service we know is on the TV (and not hidden): with none, and none found by name, the error says so.
        boolean any = false;
        for (String[] s : SERVICES) any |= installed(s[2]);
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        // The TV's other apps by name: a service SERVICES doesn't know is theirs when the names are the same.
        Map<String, String[]> apps = new HashMap<>();
        Set<String> knownPkgs = new HashSet<>();
        for (String[] s : SERVICES) knownPkgs.add(s[2]);
        for (String[] app : HomeApps.launchable(context)) {
            String label = plain(app[1]);
            if (!label.isEmpty() && !NOT_BY_NAME.contains(label) && !knownPkgs.contains(app[0])) apps.put(label, app);
        }
        List<String[]> found = new ArrayList<>();
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String name = o.optString("provider_name");
            String[] s = service(name, o.optInt("provider_id"));
            if (s == null || !SERVICE_NAME.containsKey(s[1])) {
                String[] app = apps.get(plain(name));
                // A name that only rents or buys waits until it has titles.
                s = app != null && hasTitles(o.optInt("provider_id")) ? new String[] {plain(name), app[0], app[0], app[1]} : null;
                if (s != null) found.add(s);
            }
            // serviceOff too: installed() knows only the last list's names, and one found just now starts off.
            if (s != null && installed(s[2]) && !Prefs.serviceOff(s[1])) services.put(o.getInt("provider_id"), s);
        }
        byName = found;
        writeByName(found);
        if (services.isEmpty() && !Config.OPEN_IN_STREMIO) throw new IOException(any ? NO_APPS : NO_KNOWN_APPS);
        // Signed in to Trakt: what they're in the middle of comes first, then their watchlist and Trakt's
        // recommendations; what they've watched stays out. Trakt failing leaves the picks as they'd be without it.
        // The list keeps the account it was made with (0: none, and always outside :home), so a list the screensaver's
        // process made on Android 11 to 12L is fetched again when :home starts with it.
        long account = Trakt.stamp(context);
        Trakt.Lists trakt = null;
        if (account != 0) {
            try {
                trakt = Trakt.lists(context);
            } catch (Exception e) {
                AppLog.w("trakt: " + e.getMessage());
            }
        }
        final Set<String> watched = trakt == null ? new HashSet<>() : trakt.watched;
        final Set<String> disliked = trakt == null ? new HashSet<>() : trakt.disliked;
        if (trakt != null) {
            traktActivity = trakt.activity;
            traktCheckedAt = System.currentTimeMillis();
        }
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String providerFilter = ids.length() == 0 ? "" : ("&with_watch_monetization_types=flatrate&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8"));
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&include_adult=false"
                + "&sort_by=popularity.desc" + providerFilter
                + (Prefs.hideAnimation() ? "&without_genres=" + ANIMATION : "");
        String broadCommon = "&language=" + lang() + "&include_adult=false&sort_by=popularity.desc"
                + (Prefs.hideAnimation() ? "&without_genres=" + ANIMATION : "");
        synchronized (rowPageMap) {
            rowPageMap.clear();
            rowFetchingSet.clear();
        }
        // 1. User's Watch List (Trakt + Stremio Account Library + TvContract Library + Supabase Watchlist) & Continue Watching FIRST
        reportSyncProgress("Syncing Trakt Watch List & Progress…", 20, false);
        List<Pick> progress = new ArrayList<>(), listMovies = new ArrayList<>(), listShows = new ArrayList<>();
        List<Pick> recMovies = new ArrayList<>(), recShows = new ArrayList<>();
        Set<String> allWatchlistKeys = new HashSet<>();
        if (trakt != null) {
            allWatchlistKeys.addAll(trakt.watchlistMovies);
            allWatchlistKeys.addAll(trakt.watchlistShows);
            int few = 40, movieCount = 0, showCount = 0;
            for (Trakt.Progress pr : trakt.progress) {
                boolean movie = pr.key.startsWith("movie:");
                if ((movie ? movieCount : showCount) >= few) continue;
                String tag = pr.season == 0 ? Ui.tr("Continue watching")
                        : pr.paused ? WatchNext.compactEpisodeSpacing(Ui.trf("Continue: S{s} E{e}", "s", pr.season, "e", pr.episode))
                        : WatchNext.compactEpisodeSpacing(Ui.trf("Up next: S{s} E{e}", "s", pr.season, "e", pr.episode));
                if (fromTrakt(pr.key, tag, PROGRESS, progress, known)) {
                    Pick added = progress.get(progress.size() - 1);
                    added.engaged = pr.engaged;
                    added.progressPct = pr.progressPct;
                    added.progressSeason = pr.season;
                    added.progressEpisode = pr.episode;
                    if (allWatchlistKeys.contains(pr.key) || allWatchlistKeys.contains(key(added))) {
                        added.onWatchlist = true;
                        added.watched = watched.contains(pr.key) || watched.contains(key(added));
                        int tMax = trakt.watchedShowSeasons.getOrDefault(key(added),
                                trakt.watchedShowSeasons.getOrDefault(pr.key, 0));
                        reconcileWatchedSeriesSeasonState(added, tMax);
                    }
                    if (movie) movieCount++;
                    else showCount++;
                }
            }
            for (String k : trakt.watchlistMovies) {
                if (listMovies.size() < 200 && fromTrakt(k, null, WATCHLIST_MINE, listMovies, known)) {
                    Pick added = listMovies.get(listMovies.size() - 1);
                    added.onWatchlist = true;
                    added.watched = watched.contains(k) || watched.contains(key(added));
                    allWatchlistKeys.add(key(added));
                }
            }
            for (String k : trakt.watchlistShows) {
                if (listShows.size() < 200 && fromTrakt(k, null, WATCHLIST_MINE, listShows, known)) {
                    Pick added = listShows.get(listShows.size() - 1);
                    added.onWatchlist = true;
                    added.watched = watched.contains(k) || watched.contains(key(added));
                    int tMax = trakt.watchedShowSeasons.getOrDefault(key(added),
                            trakt.watchedShowSeasons.getOrDefault(k, 0));
                    reconcileWatchedSeriesSeasonState(added, tMax);
                    allWatchlistKeys.add(key(added));
                }
            }
        }
        // Merge Stremio Account Library items ("Library" in Stremio) directly into Watch List
        reportSyncProgress("Syncing Stremio Account Library…", 35, false);
        try {
            List<StremioAccountClient.LibraryItem> stremioLib = StremioAccountClient.fetchLibraryItems(context);
            for (StremioAccountClient.LibraryItem li : stremioLib) {
                if (!li.isLibraryWatchlistItem()) continue;
                List<Pick> target = "movie".equals(li.kind) ? listMovies : listShows;
                if (target.size() >= 250) continue;
                String rawKey = li.kind + ":" + li.imdbId;
                if (fromTrakt(rawKey, null, WATCHLIST_MINE, target, known)) {
                    Pick added = target.get(target.size() - 1);
                    added.imdbId = li.imdbId;
                    added.onWatchlist = true;
                    added.watched = li.watched || watched.contains(key(added));
                    int tMax = trakt != null ? trakt.watchedShowSeasons.getOrDefault(key(added), 0) : 0;
                    reconcileWatchedSeriesSeasonState(added, tMax);
                    allWatchlistKeys.add(key(added));
                    allWatchlistKeys.add(li.imdbId);
                }
            }
        } catch (Exception e) {
            AppLog.w("stremio library watchlist: " + e.getMessage());
        }
        // Merge any TvContract Watchlist / Stremio "Library" preview channel items on the TV
        try {
            for (WatchNext.Item wi : WatchNext.loadWatchlistItems(context)) {
                String imdb = WatchNext.extractImdbId(wi);
                String wKind = wi.isSeries ? "tv" : "movie";
                List<Pick> target = wi.isSeries ? listShows : listMovies;
                if (!imdb.isEmpty() && target.size() < 250) {
                    if (fromTrakt(wKind + ":" + imdb, null, WATCHLIST_MINE, target, known)) {
                        Pick added = target.get(target.size() - 1);
                        added.imdbId = imdb;
                        added.onWatchlist = true;
                        allWatchlistKeys.add(key(added));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // Merge Supabase cloud watchlist items into Watch List
        try {
            String activeUserId = UserProfileManager.get(context).getActiveProfile().id;
            for (SupabaseClient.WatchlistEntry sw : SupabaseClient.get(context).fetchWatchlistSync(activeUserId)) {
                if (sw.mediaKey == null || sw.mediaKey.isEmpty()) continue;
                String wKind = "movie".equals(sw.mediaType) ? "movie" : "tv";
                String fullKey = sw.mediaKey.contains(":") ? sw.mediaKey : (wKind + ":" + sw.mediaKey);
                List<Pick> target = "movie".equals(wKind) ? listMovies : listShows;
                if (target.size() < 250 && fromTrakt(fullKey, null, WATCHLIST_MINE, target, known)) {
                    Pick added = target.get(target.size() - 1);
                    added.onWatchlist = true;
                    allWatchlistKeys.add(key(added));
                }
            }
        } catch (Exception ignored) {
        }

        // 2. Trending this week (TMDB goes by what people are watching and searching for right now)
        reportSyncProgress("Fetching Trending & Curated Recommendations…", 50, false);
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                if (watched.contains(key(pick)) || disliked.contains(key(pick)) || allWatchlistKeys.contains(key(pick))) continue;
                try {
                    if (!reuse(pick, known)) fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 3. Curated Recommendations (Trakt Favorites, 8-10 Ratings, Watch History via TMDB /recommendations, and Trakt /recommendations)
        if (trakt != null) {
            int movieSeeds = 0, showSeeds = 0;
            for (String seedKey : trakt.likedSeeds) {
                boolean isMovie = seedKey.startsWith("movie:");
                if (isMovie && movieSeeds >= 6) continue;
                if (!isMovie && showSeeds >= 6) continue;
                String resolvedSeed = resolveTmdbKey(seedKey);
                if (resolvedSeed == null) continue;
                int seedId = Integer.parseInt(resolvedSeed.substring(resolvedSeed.indexOf(':') + 1));
                if (isMovie) movieSeeds++; else showSeeds++;
                try {
                    String kind = isMovie ? "movie" : "tv";
                    JSONArray seedRecs = get("/" + kind + "/" + seedId + "/recommendations?language=" + lang() + "&page=1").getJSONArray("results");
                    List<Pick> candidates = new ArrayList<>();
                    for (int i = 0; i < seedRecs.length() && i < 6; i++) add(candidates, seedRecs.getJSONObject(i), kind);
                    for (Pick sp : candidates) {
                        String sk = key(sp);
                        if (sp.isAnime || watched.contains(sk) || disliked.contains(sk) || allWatchlistKeys.contains(sk)) continue;
                        sp.mine = LIKED;
                        sp.tag = null;
                        sp.highQuality = true;
                        sp.matchScore += 25.0;
                        (isMovie ? recMovies : recShows).add(sp);
                    }
                } catch (Exception ignored) {
                }
            }
            for (String k : trakt.recommendedMovies) {
                if (recMovies.size() < 36 && !watched.contains(k) && !disliked.contains(k) && !allWatchlistKeys.contains(k)) {
                    fromTrakt(k, null, LIKED, recMovies, known);
                }
            }
            for (String k : trakt.recommendedShows) {
                if (recShows.size() < 36 && !watched.contains(k) && !disliked.contains(k) && !allWatchlistKeys.contains(k)) {
                    fromTrakt(k, null, LIKED, recShows, known);
                }
            }
            AppLog.w("trakt: " + trakt.progress.size() + " in progress (" + progress.size() + " on the TV's apps), "
                    + (trakt.watchlistMovies.size() + trakt.watchlistShows.size()) + " on the watchlist (" + (listMovies.size() + listShows.size())
                    + "), " + (recMovies.size() + recShows.size()) + " curated recommendations");
        }

        // 4. Recent releases + complete per-genre discovery in the user's exact genre order (ensuring >= 12 unwatched items per genre)
        reportSyncProgress("Building Genre Catalogs…", 65, false);
        final Set<String> localDownvotes = new HashSet<>();
        try {
            String activeUserId = UserProfileManager.get(context).getActiveProfile().id;
            Map<String, Integer> vMap = SupabaseClient.get(context).getCachedVotes(activeUserId);
            for (Map.Entry<String, Integer> entry : vMap.entrySet()) {
                if (entry.getValue() != null && entry.getValue() < 0) {
                    localDownvotes.add(entry.getKey());
                }
            }
        } catch (Exception ignored) {
        }
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int page = 1; page <= 2; page++) {
            try {
                JSONArray movies = get("/discover/movie?vote_count.gte=25&without_genres=16&primary_release_date.gte=" + day(-365)
                        + "&primary_release_date.lte=" + day(0) + common + "&page=" + page).getJSONArray("results");
                for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
            } catch (Exception e) {
                if (page == 1) throw e;
            }
            try {
                JSONArray shows = get("/discover/tv?vote_count.gte=20&without_genres=16&air_date.gte=" + day(-180) + "&air_date.lte=" + day(0)
                        + common + "&page=" + page).getJSONArray("results");
                for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
            } catch (Exception e) {
                if (page == 1) throw e;
            }
        }
        newMovies.removeIf(x -> isCandidateExcluded(x, watched, disliked, allWatchlistKeys, localDownvotes));
        newShows.removeIf(x -> isCandidateExcluded(x, watched, disliked, allWatchlistKeys, localDownvotes));

        // Fetch every visible genre in the user's exact saved genre order (active sub-tab first), topping up to page 2 if < 12 items survive
        List<String> subOrder = orderedSubKinds(activeSubKind);
        for (String sub : subOrder) {
            final String subKind = sub;
            List<Pick> targetPool = "movie".equals(subKind) ? newMovies : newShows;
            List<String> userGenres = orderedGenresForLazyLoad(Prefs.visibleGenreOrder(subKind), null);
            for (int start = 0; start < userGenres.size(); start += 4) {
                if (backgroundActivityPaused) break;
                int end = Math.min(userGenres.size(), start + 4);
                List<java.util.concurrent.Future<List<Pick>>> futures = new ArrayList<>(end - start);
                for (int idx = start; idx < end; idx++) {
                    final String genreName = userGenres.get(idx);
                    futures.add(imgIo.submit(() -> fetchInitialGenreItems(
                            subKind, genreName, broadCommon, watched, disliked, allWatchlistKeys, localDownvotes)));
                }
                for (java.util.concurrent.Future<List<Pick>> fut : futures) {
                    try {
                        List<Pick> gBatch = fut.get();
                        if (gBatch != null && !gBatch.isEmpty()) {
                            targetPool.addAll(gBatch);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick x : progress) if (seen.add(key(x))) list.add(x);
        for (Pick x : listMovies) if (seen.add(key(x))) list.add(x);
        for (Pick x : listShows) if (seen.add(key(x))) list.add(x);
        boolean movieFirst = "movie".equalsIgnoreCase(activeSubKind);
        alternate(list, seen, movieFirst ? recMovies : recShows, movieFirst ? recShows : recMovies, list.size() + 96);
        int ours = list.size();
        alternate(list, seen, movieFirst ? hotMovies : hotShows, movieFirst ? hotShows : hotMovies, ours + POOL);
        alternate(list, seen, movieFirst ? newMovies : newShows, movieFirst ? newShows : newMovies, ours + POOL);
        for (Pick x : list) {
            if (allWatchlistKeys.contains(key(x)) || (!x.imdbId.isEmpty() && allWatchlistKeys.contains(x.imdbId))) {
                x.onWatchlist = true;
                x.watched = x.watched || watched.contains(key(x));
            }
        }
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        invalidateRowCache();
        traktStamp = account;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    private static boolean isCandidateExcluded(
            Pick x,
            Set<String> watched,
            Set<String> disliked,
            Set<String> allWatchlistKeys,
            Set<String> localDownvotes
    ) {
        if (x == null) return true;
        String k = key(x);
        return watched.contains(k)
                || disliked.contains(k)
                || allWatchlistKeys.contains(k)
                || localDownvotes.contains(k)
                || isPickWatched(x);
    }

    private List<Pick> fetchInitialGenreItems(
            String subKind,
            String genre,
            String broadCommon,
            Set<String> watched,
            Set<String> disliked,
            Set<String> allWatchlistKeys,
            Set<String> localDownvotes
    ) {
        List<Pick> out = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        String gq = tmdbGenreQueryFor(subKind, genre);
        String voteFloor = ("Documentary".equals(genre) || "Reality".equals(genre) || "Stand-Up & Talk".equals(genre)
                || "Western".equals(genre) || "Sports".equals(genre) || "Music".equals(genre)
                || "K-Drama".equals(genre) || "Biography".equals(genre) || "History".equals(genre)
                || "War & Politics".equals(genre)) ? "&vote_count.gte=5" : "&vote_count.gte=10";
        int fetchedPage = 0;
        for (int page = 1; page <= 2; page++) {
            try {
                JSONArray arr = get("/discover/" + subKind + "?" + gq + voteFloor + broadCommon + "&page=" + page).optJSONArray("results");
                if (arr == null || arr.length() == 0) break;
                fetchedPage = page;
                List<Pick> rawPage = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    addWithExplicitGenre(rawPage, arr.getJSONObject(i), subKind, genre);
                }
                for (Pick cand : rawPage) {
                    if (!isCandidateExcluded(cand, watched, disliked, allWatchlistKeys, localDownvotes)
                            && matchesGenre(cand, genre)
                            && seenKeys.add(key(cand))) {
                        out.add(cand);
                    }
                }
                if (out.size() >= MIN_INITIAL_GENRE_ITEMS) {
                    break;
                }
            } catch (Exception ignored) {
                break;
            }
        }
        if (fetchedPage > 0) {
            synchronized (rowPageMap) {
                rowPageMap.put(subKind + ":genre:" + genre, fetchedPage);
            }
        }
        return out;
    }

    /** Resolves "tv:1396" or "tv:tt0903747" to a canonical TMDB key ("tv:1396"), caching IMDb->TMDB lookups locally. */
    private String resolveTmdbKey(String rawKey) {
        if (rawKey == null || !rawKey.contains(":")) return null;
        String kindHint = rawKey.substring(0, rawKey.indexOf(':'));
        String idPart = rawKey.substring(rawKey.indexOf(':') + 1).trim();
        if (idPart.startsWith("tt")) {
            String prefKey = "imdb2tmdb:" + idPart;
            String cached = cache.getString(prefKey, "");
            if (cached != null && !cached.isEmpty() && cached.contains(":")) {
                return cached;
            }
            try {
                JSONObject found = get("/find/" + idPart + "?external_source=imdb_id&language=" + lang());
                JSONArray tvArr = found.optJSONArray("tv_results");
                JSONArray mvArr = found.optJSONArray("movie_results");
                if ("tv".equals(kindHint) && tvArr != null && tvArr.length() > 0) {
                    String res = "tv:" + tvArr.getJSONObject(0).getInt("id");
                    cache.edit().putString(prefKey, res).apply();
                    return res;
                }
                if (mvArr != null && mvArr.length() > 0) {
                    String res = "movie:" + mvArr.getJSONObject(0).getInt("id");
                    cache.edit().putString(prefKey, res).apply();
                    return res;
                }
                if (tvArr != null && tvArr.length() > 0) {
                    String res = "tv:" + tvArr.getJSONObject(0).getInt("id");
                    cache.edit().putString(prefKey, res).apply();
                    return res;
                }
            } catch (Exception ignored) {
            }
            return null;
        }
        try {
            int num = Integer.parseInt(idPart);
            return num > 0 ? (kindHint + ":" + num) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A Trakt or Stremio title ("movie:603" or "tv:tt0903747") with its details, added to `out`. */
    private boolean fromTrakt(String k, String tag, int mine, List<Pick> out, Map<String, Pick> known) {
        String resolved = resolveTmdbKey(k);
        if (resolved == null) return false;
        Pick p = new Pick();
        p.kind = resolved.substring(0, resolved.indexOf(':'));
        p.id = Integer.parseInt(resolved.substring(resolved.indexOf(':') + 1));
        String origIdPart = k.substring(k.indexOf(':') + 1).trim();
        if (origIdPart.startsWith("tt")) p.imdbId = origIdPart;
        p.title = "";
        p.tag = (mine == PROGRESS) ? tag : null;
        p.mine = mine;
        p.onWatchlist = (mine == WATCHLIST_MINE);
        p.highQuality = true;
        try {
            if (!reuse(p, known)) fetchDetails(p);
        } catch (Exception e) {
            return false;
        }
        if (p.pkg == null && (mine == WATCHLIST_MINE || mine == PROGRESS || p.onWatchlist || stremio())) {
            p.app = "stremio";
            p.pkg = STREMIO;
        }
        if (!isValidBackdropCandidate(p.backdrop, p.poster)) {
            String fb = resolveFallbackBackdropUrl(p.imdbId);
            p.backdrop = fb != null ? fb : "";
        }
        if (p.pkg == null) return false;
        if ((p.backdrop == null || p.backdrop.isEmpty()) && (p.poster == null || p.poster.isEmpty())) return false;
        out.add(p);
        return true;
    }

    /** The details of the same title in the list before (`known`), with this one's tag; false: not there. */
    private static boolean reuse(Pick kPick, Map<String, Pick> known) {
        Pick k = known.get(key(kPick));
        if (k == null) return false;
        kPick.title = k.title;
        kPick.backdrop = k.backdrop;
        kPick.poster = k.poster;
        kPick.app = k.app;
        kPick.pkg = k.pkg;
        kPick.servicePkg = k.servicePkg;
        kPick.link = k.link;
        kPick.rating = k.rating;
        kPick.votesCount = k.votesCount;
        kPick.popularity = k.popularity;
        kPick.lang = k.lang;
        kPick.isAnime = k.isAnime;
        if (kPick.imdbId == null || kPick.imdbId.isEmpty()) kPick.imdbId = k.imdbId;
        if (kPick.imdbScore == null || kPick.imdbScore.isEmpty()) kPick.imdbScore = k.imdbScore;
        if (kPick.rtScore == null || kPick.rtScore.isEmpty()) kPick.rtScore = k.rtScore;
        if (kPick.mcScore == null || kPick.mcScore.isEmpty()) kPick.mcScore = k.mcScore;
        kPick.overview = (k.overview != null && !k.overview.isEmpty()) ? k.overview : kPick.overview;
        if (k.runtime > 0) kPick.runtime = k.runtime;
        if (k.releaseDate != null && !k.releaseDate.isEmpty()) kPick.releaseDate = k.releaseDate;
        if (k.firstAirDate != null && !k.firstAirDate.isEmpty()) kPick.firstAirDate = k.firstAirDate;
        if (k.lastAirDate != null && !k.lastAirDate.isEmpty()) kPick.lastAirDate = k.lastAirDate;
        if (k.seasonsCount > 0) kPick.seasonsCount = k.seasonsCount;
        if (k.airedSeasonsCount > 0) kPick.airedSeasonsCount = k.airedSeasonsCount;
        if (k.status != null && !k.status.isEmpty()) kPick.status = k.status;
        kPick.hasConfirmedRenewal = k.hasConfirmedRenewal;
        kPick.matchScore = k.matchScore + (kPick.mine != 0 ? 25.0 : 0.0);
        kPick.trendingScore = k.trendingScore;
        kPick.highQuality = k.highQuality || kPick.mine != 0;
        kPick.genres = new ArrayList<>(k.genres);
        String meta = cleanMeta(k.meta);
        if (k.tag != null && meta.startsWith(k.tag)) meta = meta.substring(k.tag.length()).replaceFirst("^ · ", "");
        kPick.meta = (kPick.tag != null && kPick.mine == PROGRESS)
                ? (meta.isEmpty() ? kPick.tag : kPick.tag + " · " + meta)
                : meta;
        kPick.detailed = true;
        return true;
    }

    /** Removes redundant tokens ("Series", "Movie", "Stremio", "On your watchlist", "Recommended for you", "Recommended on Trakt") from item subtitles. */
    static String cleanMeta(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] parts = raw.split("\\s*·\\s*");
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            if (t.equalsIgnoreCase("Series") || t.equalsIgnoreCase("Movie")
                    || t.equalsIgnoreCase("Stremio") || t.equalsIgnoreCase(Ui.tr("Stremio"))
                    || t.equalsIgnoreCase(Ui.tr("Series")) || t.equalsIgnoreCase(Ui.tr("Movie"))
                    || t.equalsIgnoreCase("On your watchlist") || t.equalsIgnoreCase(Ui.tr("On your watchlist"))
                    || t.equalsIgnoreCase("Recommended for you") || t.equalsIgnoreCase(Ui.tr("Recommended for you"))
                    || t.equalsIgnoreCase("Recommended on Trakt") || t.equalsIgnoreCase(Ui.tr("Recommended on Trakt"))) {
                continue;
            }
            kept.add(t);
        }
        return String.join(" · ", kept);
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    private static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    /** A list of just one of `list`, the one this hour's period points at (empty for an empty list). */
    private static List<Pick> oneOf(List<Pick> list, int period) {
        List<Pick> out = new ArrayList<>();
        if (!list.isEmpty()) out.add(list.get(Math.floorMod(period, list.size())));
        return out;
    }

    /** The picks whose backdrop could be downloaded (the poster is a bonus: without it the card crops the backdrop). */
    private List<Pick> withImages(List<Pick> list) {
        List<Pick> out = new ArrayList<>();
        for (Pick pick : list) {
            try {
                download(pick);
                out.add(pick);
            } catch (IOException e) {
                AppLog.w("plex image: " + e.getMessage());
            }
        }
        return out;
    }

    private static List<Pick> interleave(List<Pick> a, List<Pick> b) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        addWithExplicitGenre(list, o, kind, null);
    }

    private static void addWithExplicitGenre(List<Pick> list, JSONObject o, String kind, String explicitGenre) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (Prefs.hideAnimation() && animated(o)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        p.poster = o.isNull("poster_path") ? "" : o.optString("poster_path");
        String overview = o.optString("overview", "");
        if (overview != null && !overview.isEmpty() && !"null".equalsIgnoreCase(overview)) {
            p.overview = overview.trim();
        }
        if ("movie".equals(kind)) {
            String rd = o.optString("release_date", "");
            if (!rd.isEmpty() && !"null".equalsIgnoreCase(rd)) p.releaseDate = rd.trim();
            p.runtime = o.optInt("runtime", 0);
        } else {
            String fad = o.optString("first_air_date", "");
            if (!fad.isEmpty() && !"null".equalsIgnoreCase(fad)) p.firstAirDate = fad.trim();
            String lad = o.optString("last_air_date", "");
            if (!lad.isEmpty() && !"null".equalsIgnoreCase(lad)) p.lastAirDate = lad.trim();
            p.seasonsCount = o.optInt("number_of_seasons", 0);
            String st = o.optString("status", "");
            if (!st.isEmpty() && !"null".equalsIgnoreCase(st)) p.status = st.trim();
            boolean hasNextEp = !o.isNull("next_episode_to_air") && o.optJSONObject("next_episode_to_air") != null;
            p.hasConfirmedRenewal = hasNextEp || "Returning Series".equalsIgnoreCase(p.status);
        }
        String origLang = o.optString("original_language", "en");
        JSONArray gids = o.optJSONArray("genre_ids");
        int[] ids = new int[gids == null ? 0 : gids.length()];
        for (int i = 0; i < ids.length; i++) ids[i] = gids.optInt(i);
        applyGenresAndLanguage(p, ids, explicitGenre, origLang, overview);
        scorePick(p, o);
        if (Config.OPEN_IN_STREMIO) {
            p.app = "stremio";
            p.pkg = STREMIO;
        }
        String gStr = p.genres.isEmpty() ? "" : p.genres.get(0);
        String appName = (p.app != null && !"stremio".equalsIgnoreCase(p.app)) ? name(p.app) : "";
        p.meta = cleanMeta(gStr.isEmpty() ? appName : (appName.isEmpty() ? gStr : (gStr + " · " + appName)));
        list.add(p);
    }

    /**
     * Applies language, Anime isolation, K-Drama classification, and Rom-Com rules to a Pick.
     * - Anime (genre 16 + ja/zh/ko or explicit "Anime") is isolated strictly to "Anime" and "Animation".
     * - Korean titles ("ko") are tagged as "K-Drama" and never tagged as "Rom-Coms".
     */
    static void applyGenresAndLanguage(Pick p, int[] genreIds, String explicitGenre, String origLang, String overview) {
        if (origLang != null && !origLang.trim().isEmpty()) {
            p.lang = origLang.trim().toLowerCase(Locale.ROOT);
        }
        boolean hasAnim = false;
        if (genreIds != null) {
            for (int gid : genreIds) {
                if (gid == ANIMATION) {
                    hasAnim = true;
                    break;
                }
            }
        }
        boolean asianLang = "ja".equals(p.lang) || "zh".equals(p.lang) || "ko".equals(p.lang);
        if ("Anime".equalsIgnoreCase(explicitGenre) || (hasAnim && asianLang)) {
            p.isAnime = true;
            p.genres.clear();
            addUnique(p.genres, "Anime");
            addUnique(p.genres, "Animation");
            return;
        }
        if (explicitGenre != null && !explicitGenre.isEmpty()) {
            if (!("Rom-Coms".equalsIgnoreCase(explicitGenre) && asianLang)) {
                addUnique(p.genres, explicitGenre);
            }
        }
        if (genreIds != null) {
            for (int gid : genreIds) {
                if (gid == 10765) {
                    if ("Sci-Fi".equals(explicitGenre) || "Fantasy".equals(explicitGenre)) {
                        addUnique(p.genres, explicitGenre);
                    } else {
                        classifyTvSciFiOrFantasy(p.genres, p.title, overview);
                    }
                } else {
                    addGenresById(p.genres, gid);
                }
            }
        }
        if ("ko".equals(p.lang)) {
            addUnique(p.genres, "K-Drama");
            p.genres.remove("Rom-Coms");
        } else {
            String text = ((p.title == null ? "" : p.title) + " " + (overview == null ? "" : overview)).toLowerCase(Locale.ROOT);
            boolean romanticCue = text.contains("romanc") || text.contains("love story") || text.contains("fall in love")
                    || text.contains("falls in love") || text.contains("dating") || text.contains("soulmate")
                    || text.contains("boyfriend") || text.contains("girlfriend") || text.contains("wedding")
                    || text.contains("heartbreak") || text.contains("relationship");
            if (romanticCue && (p.genres.contains("Comedy") || p.genres.contains("Drama"))) {
                addUnique(p.genres, "Romance");
            }
            if (!asianLang && p.genres.contains("Comedy") && p.genres.contains("Romance") && !p.genres.contains("Rom-Coms")) {
                p.genres.add("Rom-Coms");
            }
        }
    }

    static void scorePick(Pick p, JSONObject o) {
        if (o != null) {
            p.rating = o.optDouble("vote_average", p.rating);
            p.votesCount = o.optInt("vote_count", p.votesCount);
            p.popularity = o.optDouble("popularity", p.popularity > 0 ? p.popularity : 20.0);
            String ol = o.optString("original_language", "");
            if (!ol.isEmpty()) p.lang = ol.toLowerCase(Locale.ROOT);
        }
        double pop = p.popularity > 0 ? p.popularity : 20.0;
        // Bayesian vote-weighted rating (m=250, C=6.8) prevents low-vote niche titles from outranking acclaimed hits
        double v = Math.max(0, p.votesCount);
        double m = 250.0;
        double bayesianRating = (v / (v + m)) * p.rating + (m / (v + m)) * 6.8;
        double ratingPoints = bayesianRating * 12.0;
        double voteConfidence = Math.min(25.0, Math.log10(Math.max(1, p.votesCount)) * 6.5);
        double popPoints = Math.min(10.0, pop * 0.04);
        double affinity = (p.mine == LIKED ? 35.0 : p.mine == WATCHLIST_MINE ? 25.0 : 0.0);
        String lang = p.lang == null ? "en" : p.lang;
        double langAdjust = ("en".equals(lang) || "he".equals(lang)) ? 14.0
                : (("ko".equals(lang) || "ja".equals(lang) || "zh".equals(lang)) ? -18.0 : 0.0);
        double unprovenPenalty = (p.votesCount < 25 && p.mine == 0) ? -35.0 : 0.0;
        p.matchScore = affinity + ratingPoints + voteConfidence + popPoints + langAdjust + unprovenPenalty;
        p.trendingScore = Math.min(95.0, Math.log1p(Math.max(0.0, pop)) * 18.0)
                + bayesianRating * 4.5
                + Math.min(20.0, Math.log10(Math.max(1, p.votesCount)) * 4.5)
                + (langAdjust * 0.4);
        p.highQuality = (p.mine != 0) || (bayesianRating >= 6.85 && p.votesCount >= 60);
    }

    /**
     * Formats the external ratings subtitle (`IMDb`, `Metacritic`, `Rotten Tomatoes`) separated by spaces.
     * Any missing score for a specific show/movie is skipped cleanly without skipping the show/movie itself.
     */
    static String ratingsSubtitle(Pick p) {
        if (p == null) return "";
        List<String> scores = new ArrayList<>();
        String imdb = p.imdbScore == null ? "" : p.imdbScore.trim();
        if (!imdb.isEmpty() && !"N/A".equalsIgnoreCase(imdb) && !"0".equals(imdb) && !"0.0".equals(imdb)) {
            scores.add("IMDb " + imdb);
        } else if (p.rating > 0.0 && p.votesCount > 0) {
            scores.add(String.format(Locale.US, "IMDb %.1f", p.rating));
        }
        String mc = p.mcScore == null ? "" : p.mcScore.trim();
        if (!mc.isEmpty() && !"N/A".equalsIgnoreCase(mc)) {
            scores.add("MC " + mc);
        }
        String rt = p.rtScore == null ? "" : p.rtScore.trim();
        if (!rt.isEmpty() && !"N/A".equalsIgnoreCase(rt)) {
            scores.add("RT " + (rt.endsWith("%") ? rt : (rt + "%")));
        }
        if (!scores.isEmpty()) {
            return String.join(" ", scores);
        }
        return cleanMeta(p.meta);
    }

    // MARK: - Stremio Header Formatting Engines

    /**
     * Formats duration in minutes according to Home header specification:
     * - Under 1 hour: "MMmin" (e.g. 45 -> "45min", 59 -> "59min")
     * - 1 hour or more: "H:MMh" (e.g. 60 -> "1:00h", 97 -> "1:37h", 105 -> "1:45h")
     * - Returns null if minutes <= 0
     */
    public static String formatDuration(int minutes) {
        if (minutes <= 0) return null;
        if (minutes < 60) return minutes + "min";
        int h = minutes / 60;
        int m = minutes % 60;
        return String.format(Locale.US, "%d:%02dh", h, m);
    }

    public static String formatDuration(Pick p) {
        return p == null ? null : formatDuration(p.runtime);
    }

    /**
     * Safely extracts a 4-digit year from a date string (e.g. "2022-08-10" -> "2022").
     */
    public static String extractYear(String dateStr) {
        if (dateStr == null) return null;
        String raw = dateStr.trim();
        if (raw.length() >= 4 && raw.substring(0, 4).matches("\\d{4}")) {
            return raw.substring(0, 4);
        }
        return null;
    }

    /**
     * Formats a single release/air date string:
     * - If the year equals `currentYear`, includes the abbreviated month ("MMM yyyy", e.g. "Jan 2026").
     * - Otherwise omits the month and returns only the 4-digit year ("yyyy", e.g. "2023").
     * Returns null if empty or invalid.
     */
    public static String formatMonthYear(String dateStr, int currentYear) {
        if (dateStr == null) return null;
        String raw = dateStr.trim();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw) || "N/A".equalsIgnoreCase(raw)) return null;
        String yr = extractYear(raw);
        if (yr == null) return null;
        int parsedYear;
        try {
            parsedYear = Integer.parseInt(yr);
        } catch (NumberFormatException e) {
            return null;
        }
        if (parsedYear != currentYear || raw.matches("^\\d{4}$")) {
            return yr;
        }
        String[] patterns = {"yyyy-MM-dd", "yyyy-MM"};
        for (String pat : patterns) {
            try {
                SimpleDateFormat inFmt = new SimpleDateFormat(pat, Locale.US);
                inFmt.setLenient(false);
                Date d = inFmt.parse(raw);
                if (d != null) {
                    return new SimpleDateFormat("MMM yyyy", Locale.US).format(d);
                }
            } catch (Exception ignored) {}
        }
        return yr;
    }

    public static String formatMonthYear(String dateStr) {
        return formatMonthYear(dateStr, Calendar.getInstance().get(Calendar.YEAR));
    }

    /**
     * Formats release or air dates for movies and TV series:
     * 1. Movie: "MMM YYYY" when released in `currentYear` (e.g. "Jan 2026"), else "YYYY" (e.g. "2023").
     * 2. Series (>1 season, ended): "YYYY-YYYY" (e.g. "2009-2016") or "YYYY" if same year.
     * 3. Series (still running, confirmed renewal): "YYYY-Now" (e.g. "2021-Now").
     * 4. Series (single season without confirmed renewal / miniseries): "MMM YYYY" if `currentYear`, else "YYYY".
     */
    public static String formatDates(String kind, String releaseDate, String firstAirDate,
                                     String lastAirDate, int seasonsCount, String status,
                                     boolean hasConfirmedRenewal, int currentYear) {
        if ("movie".equalsIgnoreCase(kind)) {
            return formatMonthYear(releaseDate, currentYear);
        }
        if ("tv".equalsIgnoreCase(kind)) {
            String firstYear = extractYear(firstAirDate);
            if (firstYear == null && releaseDate != null) {
                firstYear = extractYear(releaseDate);
            }
            if (firstYear == null) {
                return formatMonthYear(firstAirDate != null && !firstAirDate.isEmpty() ? firstAirDate : releaseDate, currentYear);
            }

            boolean isOngoing = hasConfirmedRenewal || "Returning Series".equalsIgnoreCase(status);

            if (seasonsCount > 1) {
                if (isOngoing) {
                    return firstYear + "-Now";
                } else {
                    String lastYear = extractYear(lastAirDate);
                    if (lastYear != null && !lastYear.isEmpty() && !lastYear.equals(firstYear)) {
                        return firstYear + "-" + lastYear;
                    }
                    return firstYear;
                }
            } else {
                // seasonsCount <= 1
                if (hasConfirmedRenewal) {
                    return firstYear + "-Now";
                }
                String my = formatMonthYear(firstAirDate != null && !firstAirDate.isEmpty() ? firstAirDate : releaseDate, currentYear);
                return my != null ? my : firstYear;
            }
        }
        return formatMonthYear(releaseDate != null && !releaseDate.isEmpty() ? releaseDate : firstAirDate, currentYear);
    }

    public static String formatDates(String kind, String releaseDate, String firstAirDate,
                                     String lastAirDate, int seasonsCount, String status,
                                     boolean hasConfirmedRenewal) {
        return formatDates(kind, releaseDate, firstAirDate, lastAirDate, seasonsCount, status,
                hasConfirmedRenewal, Calendar.getInstance().get(Calendar.YEAR));
    }

    public static String formatDates(Pick p) {
        if (p == null) return null;
        return formatDates(p.kind, p.releaseDate, p.firstAirDate, p.lastAirDate,
                p.seasonsCount, p.status, p.hasConfirmedRenewal);
    }

    /**
     * Scales Metacritic score (0–100 integer or existing decimal) to 0–10 scale with 1 decimal digit.
     * Returns null if rawScore is missing, "N/A", "0", or malformed.
     */
    public static String formatMetacritic(String rawScore) {
        if (rawScore == null) return null;
        String s = rawScore.trim();
        if (s.isEmpty() || "N/A".equalsIgnoreCase(s) || "0".equals(s) || "0.0".equals(s)) {
            return null;
        }
        if (s.endsWith("%")) s = s.substring(0, s.length() - 1).trim();
        if (s.endsWith("/100")) s = s.substring(0, s.length() - 4).trim();
        try {
            if (!s.contains(".")) {
                int val = Integer.parseInt(s);
                if (val <= 0) return null;
                return String.format(Locale.US, "%.1f", val / 10.0);
            } else {
                double val = Double.parseDouble(s);
                if (val <= 0.0) return null;
                return String.format(Locale.US, "%.1f", val);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String formatMetacritic(Pick p) {
        return p == null ? null : formatMetacritic(p.mcScore);
    }

    /**
     * Joins non-empty genre strings with " | " (e.g. "Mystery | Thriller | Drama | Action").
     * Returns empty string if genres list is null or empty.
     */
    public static String formatGenres(List<String> genres) {
        if (genres == null || genres.isEmpty()) return "";
        List<String> clean = new ArrayList<>();
        for (String g : genres) {
            if (g != null) {
                String trimmed = g.trim();
                if (!trimmed.isEmpty()) {
                    clean.add(trimmed);
                }
            }
        }
        if (clean.isEmpty()) return "";
        return String.join(" | ", clean);
    }

    public static String formatGenres(Pick p) {
        return p == null ? "" : formatGenres(p.genres);
    }

    /**
     * Formats an overview synopsis for multi-line paragraph display without ever truncating characters,
     * preserving clean line breaks while collapsing excess blank lines and horizontal multi-spaces.
     */
    public static String formatOverviewText(String rawOverview) {
        if (rawOverview == null) return "";
        String trimmed = rawOverview.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) return "";
        String norm = trimmed.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = norm.split("\n");
        StringBuilder sb = new StringBuilder(norm.length());
        for (String line : lines) {
            String cleanLine = line.replaceAll("[ \\t\\x0B\\f]+", " ").trim();
            if (cleanLine.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(cleanLine);
        }
        return sb.toString();
    }

    /**
     * Validates that a 16:9 hero backdrop candidate is non-empty and is NEVER a 2:3 portrait poster path/URL.
     */
    public static boolean isValidBackdropCandidate(String candidate, String poster) {
        if (candidate == null) return false;
        String c = candidate.trim();
        if (c.isEmpty() || "null".equalsIgnoreCase(c)) return false;
        if (poster != null && !poster.trim().isEmpty() && c.equals(poster.trim())) return false;
        String lower = c.toLowerCase(Locale.ROOT);
        if (lower.contains("/poster/") || lower.contains("metahub.space/poster")) return false;
        return true;
    }

    /**
     * Resolves a native 16:9 HD landscape background URL from Stremio Metahub when TMDB lacks a backdrop.
     */
    public static String resolveFallbackBackdropUrl(String imdbId) {
        if (imdbId == null) return null;
        String id = imdbId.trim();
        if (!id.startsWith("tt") || id.length() < 5) return null;
        return "https://images.metahub.space/background/large/" + id + "/img";
    }

    /**
     * Separates TMDB's combined TV genre 10765 ("Sci-Fi & Fantasy") into distinct "Sci-Fi" or "Fantasy" tags
     * so TV Shows Sci-Fi and TV Shows Fantasy rows never show identical items.
     */
    static void classifyTvSciFiOrFantasy(List<String> out, String title, String overview) {
        String text = ((title == null ? "" : title) + " " + (overview == null ? "" : overview)).toLowerCase(Locale.ROOT);
        String[] fantasyCues = {
            "magic", "dragon", "witch", "wizard", "sorcer", "kingdom", "throne", "sword", "myth",
            "supernatural", "demon", "vampire", "werewolf", "elf", "elves", "fairy", "fae", "curse",
            "spell", "gods", "prophecy", "medieval", "dungeon", "rings", "wheel of time", "witcher",
            "avatar", "shadow and bone", "sandman", "good omens", "lucifer", "ghost", "undead",
            "fantasy", "alchem", "arcane", "frieren", "one piece", "naruto", "bleach", "jujutsu"
        };
        String[] scifiCues = {
            "space", "planet", "alien", "galaxy", "starship", "star trek", "star wars", "future",
            "dystopia", "cyber", "robot", "android", "artificial intelligence", "time travel",
            "timeline", "quantum", "clone", "genetic", "mars", "orbit", "astronaut", "sci-fi",
            "science fiction", "apocalyp", "radiation", "bunker", "silo", "severance", "black mirror",
            "doctor who", "expanse", "foundation", "halo", "mandalorian", "andor", "stranger things",
            "3 body", "dark matter", "fallout", "westworld", "rick and morty", "futurama", "laboratory", "virus"
        };
        boolean isFantasy = false, isSciFi = false;
        for (String cue : fantasyCues) {
            if (text.contains(cue)) { isFantasy = true; break; }
        }
        for (String cue : scifiCues) {
            if (text.contains(cue)) { isSciFi = true; break; }
        }
        if (isFantasy && !isSciFi) {
            addUnique(out, "Fantasy");
        } else if (isSciFi && !isFantasy) {
            addUnique(out, "Sci-Fi");
        } else if (isFantasy && isSciFi) {
            // Pick primary by whichever cue appears earlier in title/overview
            int fIdx = Integer.MAX_VALUE, sIdx = Integer.MAX_VALUE;
            for (String cue : fantasyCues) { int idx = text.indexOf(cue); if (idx >= 0 && idx < fIdx) fIdx = idx; }
            for (String cue : scifiCues) { int idx = text.indexOf(cue); if (idx >= 0 && idx < sIdx) sIdx = idx; }
            addUnique(out, fIdx <= sIdx ? "Fantasy" : "Sci-Fi");
        } else {
            // Deterministic split when overview has no explicit keyword
            addUnique(out, (Math.abs((title == null ? "" : title).hashCode()) % 2 == 0) ? "Sci-Fi" : "Fantasy");
        }
    }

    private static void addGenresById(List<String> out, int id) {
        switch (id) {
            case 28: addUnique(out, "Action"); break;
            case 12: addUnique(out, "Adventure"); break;
            case 10759: addUnique(out, "Action"); addUnique(out, "Adventure"); break;
            case 35: addUnique(out, "Comedy"); break;
            case 53: addUnique(out, "Thriller"); break;
            case 18: addUnique(out, "Drama"); break;
            case 878: addUnique(out, "Sci-Fi"); break;
            case 14: addUnique(out, "Fantasy"); break;
            case 80: addUnique(out, "Crime"); break;
            case 9648: addUnique(out, "Mystery"); break;
            case 99: addUnique(out, "Documentary"); break;
            case 16: addUnique(out, "Animation"); break;
            case 10749: addUnique(out, "Romance"); break;
            case 27: addUnique(out, "Horror"); break;
            case 10751: case 10762: addUnique(out, "Family"); break;
            case 36: addUnique(out, "History"); break;
            case 10752: case 10768: addUnique(out, "War & Politics"); break;
            case 10402: addUnique(out, "Music"); break;
            case 37: addUnique(out, "Western"); break;
            case 10764: addUnique(out, "Reality"); break;
            case 10767: addUnique(out, "Stand-Up & Talk"); break;
        }
    }

    private static void addUnique(List<String> out, String g) {
        if (g != null && !out.contains(g)) out.add(g);
    }

    private static String canonicalGenreByName(String raw) {
        if (raw == null) return null;
        String r = raw.toLowerCase(Locale.ROOT);
        if (r.contains("sci-fi & fantasy") || r.contains("sci-fi and fantasy")) return null;
        if (r.contains("action")) return "Action";
        if (r.contains("adventure")) return "Adventure";
        if (r.contains("comedy")) return "Comedy";
        if (r.contains("thriller") || r.contains("suspense")) return "Thriller";
        if (r.contains("drama")) return "Drama";
        if (r.contains("sci-fi") || r.contains("science fiction")) return "Sci-Fi";
        if (r.contains("fantasy")) return "Fantasy";
        if (r.contains("crime")) return "Crime";
        if (r.contains("mystery")) return "Mystery";
        if (r.contains("documentary")) return "Documentary";
        if (r.contains("anime")) return "Anime";
        if (r.contains("animation")) return "Animation";
        if (r.contains("romance")) return "Romance";
        if (r.contains("horror")) return "Horror";
        if (r.contains("family") || r.contains("kids")) return "Family";
        if (r.contains("history") || r.contains("historical")) return "History";
        if (r.contains("war") || r.contains("politics")) return "War & Politics";
        if (r.contains("biography") || r.contains("biopic")) return "Biography";
        if (r.contains("music") || r.contains("musical")) return "Music";
        if (r.contains("sport")) return "Sports";
        if (r.contains("reality")) return "Reality";
        if (r.contains("western")) return "Western";
        if (r.contains("talk") || r.contains("stand-up")) return "Stand-Up & Talk";
        return raw;
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    private static String runtime(int minutes) {
        if (minutes <= 0) return null;
        return minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes);
    }

    private static String seasons(int n) {
        return n <= 0 ? null : Ui.plural("plural|one:{n} season|other:{n} seasons", n);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has (no redundant "Series"/"Movie"/"On your watchlist"). */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images,external_ids&include_image_language=null," + Ui.language.replaceAll("-.*", "") + ",en");
        p.title = title(p, o);
        scorePick(p, o);
        String bestPost = bestPoster(o.optJSONObject("images"));
        String poster = o.optString("poster_path", "");
        if ((p.poster == null || p.poster.isEmpty()) && !poster.isEmpty() && !"null".equals(poster)) p.poster = poster;
        if ((p.poster == null || p.poster.isEmpty()) && bestPost != null) {
            p.poster = bestPost;
        }
        // Prefer 4K textless 16:9 backdrop from /images over localized title-card backdrop_path
        String bestBd = bestBackdrop(o.optJSONObject("images"));
        if (isValidBackdropCandidate(bestBd, p.poster)) {
            p.backdrop = bestBd;
        } else if (!isValidBackdropCandidate(p.backdrop, p.poster)) {
            String primaryBd = o.isNull("backdrop_path") ? "" : o.optString("backdrop_path", "");
            if (isValidBackdropCandidate(primaryBd, p.poster)) {
                p.backdrop = primaryBd;
            } else {
                p.backdrop = "";
            }
        }
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        String overview = o.optString("overview", "");
        if (overview != null && !overview.trim().isEmpty() && !"null".equalsIgnoreCase(overview)) {
            p.overview = overview.trim();
        } else {
            // English overview fallback when requested language has no translation
            try {
                JSONObject enObj = get("/" + p.kind + "/" + p.id + "?language=en-US");
                String enOv = enObj.optString("overview", "");
                if (!enOv.isEmpty() && !"null".equalsIgnoreCase(enOv)) {
                    p.overview = enOv.trim();
                }
            } catch (Exception ignored) {}
        }
        if ("movie".equals(p.kind)) {
            int r = o.optInt("runtime", 0);
            if (r > 0) p.runtime = r;
            String rd = o.optString("release_date", "");
            if (!rd.isEmpty() && !"null".equalsIgnoreCase(rd)) p.releaseDate = rd.trim();
        } else {
            // TV episode runtime resolution: episode_run_time array -> last_episode -> next_episode
            JSONArray ert = o.optJSONArray("episode_run_time");
            if (ert != null && ert.length() > 0) {
                int r = ert.optInt(0, 0);
                if (r > 0) p.runtime = r;
            }
            if (p.runtime <= 0) {
                JSONObject lastEp = o.optJSONObject("last_episode_to_air");
                if (lastEp != null) {
                    int r = lastEp.optInt("runtime", 0);
                    if (r > 0) p.runtime = r;
                }
            }
            if (p.runtime <= 0) {
                JSONObject nextEp = o.optJSONObject("next_episode_to_air");
                if (nextEp != null) {
                    int r = nextEp.optInt("runtime", 0);
                    if (r > 0) p.runtime = r;
                }
            }
            String fad = o.optString("first_air_date", "");
            if (!fad.isEmpty() && !"null".equalsIgnoreCase(fad)) p.firstAirDate = fad.trim();
            String lad = o.optString("last_air_date", "");
            if (!lad.isEmpty() && !"null".equalsIgnoreCase(lad)) p.lastAirDate = lad.trim();
            int sc = o.optInt("number_of_seasons", 0);
            if (sc > 0) p.seasonsCount = sc;
            int releasedSc = computeReleasedSeasonsCount(o, day(0));
            if (releasedSc > 0) p.airedSeasonsCount = releasedSc;
            String st = o.optString("status", "");
            if (!st.isEmpty() && !"null".equalsIgnoreCase(st)) p.status = st.trim();
            boolean hasNextEp = !o.isNull("next_episode_to_air") && o.optJSONObject("next_episode_to_air") != null;
            p.hasConfirmedRenewal = hasNextEp || "Returning Series".equalsIgnoreCase(p.status);
        }

        // Secondary fallback to Cinemeta for overview, runtime and genres if still missing
        JSONObject extIds = o.optJSONObject("external_ids");
        String imdbId = extIds == null ? p.imdbId : extIds.optString("imdb_id", p.imdbId);
        if (imdbId != null && imdbId.startsWith("tt") && (p.overview.isEmpty() || p.runtime <= 0 || !isValidBackdropCandidate(p.backdrop, p.poster))) {
            try {
                String cType = "movie".equals(p.kind) ? "movie" : "series";
                HttpURLConnection conn = (HttpURLConnection) new URL("https://v3-cinemeta.strem.io/meta/"
                        + cType + "/" + URLEncoder.encode(imdbId, "UTF-8") + ".json").openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                try {
                    if (conn.getResponseCode() == 200) {
                        JSONObject cmMeta = new JSONObject(read(conn.getInputStream())).optJSONObject("meta");
                        if (cmMeta != null) {
                            if (p.overview.isEmpty()) {
                                String cmDesc = cmMeta.optString("description", "").trim();
                                if (!cmDesc.isEmpty()) p.overview = cmDesc;
                            }
                            if (p.runtime <= 0) {
                                String cmRt = cmMeta.optString("runtime", "");
                                if (cmRt.contains("min")) {
                                    try { p.runtime = Integer.parseInt(cmRt.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                                }
                            }
                            if (p.poster == null || p.poster.isEmpty()) {
                                String cmPost = WatchNext.sanitizePosterUrl(cmMeta.optString("poster", ""), imdbId, true);
                                if (cmPost != null && !cmPost.isEmpty()) p.poster = cmPost;
                            }
                            if (!isValidBackdropCandidate(p.backdrop, p.poster)) {
                                String cmBg = cmMeta.optString("background", "");
                                if (isValidBackdropCandidate(cmBg, p.poster)) p.backdrop = cmBg;
                            }
                        }
                    }
                } finally {
                    conn.disconnect();
                }
            } catch (Exception ignored) {}
        }
        String origLang = o.optString("original_language", p.lang);
        if (!origLang.isEmpty()) p.lang = origLang.toLowerCase(Locale.ROOT);
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
            int[] ids = new int[genres.length()];
            for (int i = 0; i < genres.length(); i++) {
                JSONObject go = genres.optJSONObject(i);
                ids[i] = go == null ? 0 : go.optInt("id");
            }
            applyGenresAndLanguage(p, ids, null, p.lang, p.overview);
            if (!p.isAnime) {
                for (int i = 0; i < genres.length(); i++) {
                    JSONObject go = genres.optJSONObject(i);
                    if (go == null) continue;
                    String cg = canonicalGenreByName(go.optString("name"));
                    addUnique(p.genres, cg);
                }
                if ("ko".equals(p.lang)) {
                    p.genres.remove("Rom-Coms");
                }
            }
        }
        String length = p.kind.equals("movie") ? runtime(p.runtime) : seasons(p.seasonsCount);
        if (length != null) parts.add(length);
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"), flat.getJSONObject(i).optInt("provider_id"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        JSONObject ext = o.optJSONObject("external_ids");
        String imdb = ext == null ? "" : ext.optString("imdb_id", "");
        if (!imdb.startsWith("tt") && p.imdbId != null && p.imdbId.startsWith("tt")) {
            imdb = p.imdbId;
        }
        if (imdb.startsWith("tt")) {
            p.imdbId = imdb;
            fetchExternalRatings(p);
        }
        if (p.app != null) {
            if (!"stremio".equalsIgnoreCase(p.app)) parts.add(name(p.app));
            if (stremio() && imdb.startsWith("tt")) {
                p.servicePkg = p.pkg;
                p.app = "stremio";
                p.pkg = stremioPkg();
                boolean isTv = "tv".equals(p.kind) || "series".equals(p.kind);
                p.link = buildStremioDetailUri(isTv, imdb, p.progressSeason, p.progressEpisode, null);
            }
        } else if (stremio() || p.mine == WATCHLIST_MINE || p.mine == PROGRESS || p.onWatchlist) {
            p.app = "stremio";
            p.pkg = stremioPkg();
            if (imdb.startsWith("tt")) {
                boolean isTv = "tv".equals(p.kind) || "series".equals(p.kind);
                p.link = buildStremioDetailUri(isTv, imdb, p.progressSeason, p.progressEpisode, null);
            }
        }
        if (!isValidBackdropCandidate(p.backdrop, p.poster)) {
            String fb = resolveFallbackBackdropUrl(p.imdbId);
            p.backdrop = fb != null ? fb : "";
        }
        if ((p.poster == null || p.poster.isEmpty()) && p.imdbId != null && p.imdbId.startsWith("tt")) {
            p.poster = "https://images.metahub.space/poster/medium/" + p.imdbId + "/img";
        }
        if (p.tag != null && p.mine == PROGRESS) parts.add(0, p.tag);
        p.meta = cleanMeta(String.join(" · ", parts));
        p.detailed = true;
        reconcileWatchedSeriesSeasonState(p, 0);
    }

    private final Set<String> ratingsPending = java.util.Collections.synchronizedSet(new HashSet<>());
    static final long OMDB_COOLDOWN_MS = 15 * 60_000L;
    private static volatile long sOmdbCooldownUntilMs = 0L;

    /** Pure helper: returns ONLY the user's private OMDb key when configured (zero shared hardcoded keys). */
    static List<String> buildOmdbCandidateKeys(String privateKey) {
        List<String> keys = new ArrayList<>(1);
        if (privateKey != null) {
            String clean = privateKey.trim();
            if (!clean.isEmpty()) keys.add(clean);
        }
        return keys;
    }

    static boolean isOmdbCoolingDown(long nowMs) {
        return nowMs < sOmdbCooldownUntilMs;
    }

    static void recordOmdbHttpStatus(int httpCode, long nowMs) {
        if (httpCode == 401 || httpCode == 403 || httpCode == 429) {
            sOmdbCooldownUntilMs = nowMs + OMDB_COOLDOWN_MS;
        }
    }

    static void resetOmdbCooldownForTesting() {
        sOmdbCooldownUntilMs = 0L;
    }

    private static String resolvePrivateOmdbKey(Context ctx) {
        try {
            if (ctx != null) {
                String fromSecrets = Secrets.get(ctx, "omdb_key");
                if (fromSecrets != null && !fromSecrets.trim().isEmpty()) {
                    return fromSecrets.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return Prefs.omdbKey();
    }

    /**
     * Fetches IMDb, Rotten Tomatoes (`RT`), and Metacritic (`MC`) scores for a Pick with an IMDb ID (`tt...`).
     * Uses user-configured MDBList API key (`Prefs.mdblistKey()`) and/or private OMDb API key (`Secrets`/`Prefs.omdbKey()`),
     * plus Cinemeta IMDb fallback, caching results permanently on disk in `SharedPreferences`.
     */
    private void fetchExternalRatings(Pick p) {
        if (p == null || p.imdbId == null || !p.imdbId.startsWith("tt")) return;
        String userOmdb = resolvePrivateOmdbKey(context);
        String userMdblist = Prefs.mdblistKey();
        boolean hasRichKey = !userOmdb.isEmpty() || !userMdblist.isEmpty();
        String cacheKey = "ext_r:" + p.imdbId;
        String cached = cache.getString(cacheKey, null);
        if (cached != null) {
            String[] tokens = cached.split("\\|", -1);
            if (tokens.length >= 3) {
                if (!tokens[0].isEmpty()) p.imdbScore = tokens[0];
                if (!tokens[1].isEmpty()) p.rtScore = tokens[1];
                if (!tokens[2].isEmpty()) p.mcScore = tokens[2];
                // If RT or MC is already cached, or user hasn't configured an OMDb/MDBList key yet, use cached result
                if (!tokens[1].isEmpty() || !tokens[2].isEmpty() || !hasRichKey) {
                    return;
                }
            }
        }
        String imdb = p.imdbScore == null ? "" : p.imdbScore;
        String rt = p.rtScore == null ? "" : p.rtScore;
        String mc = p.mcScore == null ? "" : p.mcScore;

        // 1. MDBList API (1,000 req/day free key; returns IMDb, Rotten Tomatoes & Metacritic)
        if (!userMdblist.isEmpty()) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://mdblist.com/api/?i="
                        + URLEncoder.encode(p.imdbId, "UTF-8") + "&apikey=" + URLEncoder.encode(userMdblist, "UTF-8")).openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(2500);
                try {
                    if (c.getResponseCode() == 200) {
                        JSONObject o = new JSONObject(read(c.getInputStream()));
                        JSONArray ratings = o.optJSONArray("ratings");
                        for (int i = 0; ratings != null && i < ratings.length(); i++) {
                            JSONObject r = ratings.optJSONObject(i);
                            if (r == null) continue;
                            String src = r.optString("source", "").toLowerCase(Locale.ROOT);
                            int valInt = r.optInt("value", -1);
                            double valDbl = r.optDouble("value", -1.0);
                            if ("imdb".equals(src) && valDbl > 0) {
                                imdb = String.format(Locale.US, "%.1f", valDbl);
                            } else if (("tomatoes".equals(src) || src.contains("rotten")) && valInt > 0) {
                                rt = valInt + "%";
                            } else if ("metacritic".equals(src) && valInt > 0) {
                                mc = String.valueOf(valInt);
                            }
                        }
                    }
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                AppLog.w("mdblist rating lookup failed for " + p.imdbId + ": " + e.getMessage());
            }
        }

        // 2. Private-only OMDb API (1,000 req/day free key; 2000ms timeouts + 15m cooldown on HTTP 401/429)
        List<String> omdbKeys = buildOmdbCandidateKeys(userOmdb);
        long nowMs = System.currentTimeMillis();
        if (!omdbKeys.isEmpty() && !isOmdbCoolingDown(nowMs) && (rt.isEmpty() || mc.isEmpty() || imdb.isEmpty())) {
            for (String apiKey : omdbKeys) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("https://www.omdbapi.com/?i="
                            + URLEncoder.encode(p.imdbId, "UTF-8") + "&apikey=" + URLEncoder.encode(apiKey, "UTF-8")).openConnection();
                    c.setConnectTimeout(2000);
                    c.setReadTimeout(2000);
                    try {
                        int code = c.getResponseCode();
                        recordOmdbHttpStatus(code, System.currentTimeMillis());
                        if (code == 200) {
                            JSONObject o = new JSONObject(read(c.getInputStream()));
                            if ("True".equalsIgnoreCase(o.optString("Response"))) {
                                String ir = o.optString("imdbRating", "");
                                if (!ir.isEmpty() && !"N/A".equalsIgnoreCase(ir)) imdb = ir;
                                String ms = o.optString("Metascore", "");
                                if (!ms.isEmpty() && !"N/A".equalsIgnoreCase(ms)) mc = ms;
                                JSONArray ratings = o.optJSONArray("Ratings");
                                for (int i = 0; ratings != null && i < ratings.length(); i++) {
                                    JSONObject r = ratings.optJSONObject(i);
                                    if (r == null) continue;
                                    String src = r.optString("Source", "");
                                    String val = r.optString("Value", "");
                                    if (val.isEmpty() || "N/A".equalsIgnoreCase(val)) continue;
                                    if ("Rotten Tomatoes".equalsIgnoreCase(src)) {
                                        rt = val;
                                    } else if ("Metacritic".equalsIgnoreCase(src) && mc.isEmpty()) {
                                        mc = val.replace("/100", "").trim();
                                    } else if ("Internet Movie Database".equalsIgnoreCase(src) && imdb.isEmpty()) {
                                        imdb = val.replace("/10", "").trim();
                                    }
                                }
                                break;
                            }
                        } else if (isOmdbCoolingDown(System.currentTimeMillis())) {
                            AppLog.w("omdb rating lookup entered 15m cooldown (HTTP " + code + ")");
                            break;
                        }
                    } finally {
                        c.disconnect();
                    }
                } catch (Exception e) {
                    AppLog.w("omdb rating lookup failed for " + p.imdbId + ": " + e.getMessage());
                }
            }
        }
        // 3. Stremio Cinemeta keyless fallback for IMDb score
        if (imdb.isEmpty()) {
            try {
                String cType = "movie".equals(p.kind) ? "movie" : "series";
                HttpURLConnection c = (HttpURLConnection) new URL("https://v3-cinemeta.strem.io/meta/"
                        + cType + "/" + URLEncoder.encode(p.imdbId, "UTF-8") + ".json").openConnection();
                c.setConnectTimeout(4500);
                c.setReadTimeout(4500);
                try {
                    if (c.getResponseCode() == 200) {
                        JSONObject meta = new JSONObject(read(c.getInputStream())).optJSONObject("meta");
                        if (meta != null) {
                            String ir = meta.optString("imdbRating", "");
                            if (!ir.isEmpty() && !"N/A".equalsIgnoreCase(ir)) imdb = ir;
                        }
                    }
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {
            }
        }
        p.imdbScore = imdb;
        p.rtScore = rt;
        p.mcScore = mc;
        if (!imdb.isEmpty() || !rt.isEmpty() || !mc.isEmpty()) {
            cache.edit().putString(cacheKey, imdb + "|" + rt + "|" + mc).apply();
        }
    }

    /**
     * Ensures external ratings (`IMDb`, `Rotten Tomatoes`, `Metacritic`) are loaded for a visible Pick.
     * Applies cached scores synchronously if already on disk, or fetches them asynchronously on `imgIo`.
     */
    interface MetadataCallback {
        void onMetadataReady(Pick p);
    }

    void ensureRatingsAsync(Pick p) {
        ensureMetadataAsync(p, null);
    }

    Pick findMatchingPick(WatchNext.Item it) {
        if (it == null) return null;
        String imdb = WatchNext.extractImdbId(it);
        if (imdb != null && !imdb.isEmpty()) {
            Pick extra = watchExtraPicks.get(imdb);
            if (extra != null) return extra;
            for (Pick p : picks) {
                if (p != null && imdb.equals(p.imdbId)) return p;
            }
        }
        if (it.title != null && !it.title.trim().isEmpty()) {
            String target = it.title.trim();
            Pick extraByTitle = watchExtraPicks.get("title:" + target.toLowerCase(Locale.ROOT));
            if (extraByTitle != null) return extraByTitle;
            for (Pick p : picks) {
                if (p != null && p.title != null && p.title.trim().equalsIgnoreCase(target)) {
                    return p;
                }
            }
        }
        return null;
    }

    private final java.util.concurrent.ConcurrentHashMap<String, String> episodeOverviewMem =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> episodeTitleMem =
            new java.util.concurrent.ConcurrentHashMap<>();

    private String episodeIdPart(Pick matched, String imdbHint, String titleHint) {
        if (imdbHint != null && imdbHint.startsWith("tt")) {
            return "imdb:" + imdbHint;
        }
        if (matched != null && matched.imdbId != null && matched.imdbId.startsWith("tt")) {
            return "imdb:" + matched.imdbId;
        }
        if (matched != null && matched.id > 0) {
            return "tmdb:" + matched.id;
        }
        return "title:" + plain(titleHint != null ? titleHint : (matched != null ? matched.title : ""));
    }

    private String episodeOverviewCacheKey(Pick matched, String imdbHint, String titleHint, int season, int episode) {
        return "ep_ov:" + episodeIdPart(matched, imdbHint, titleHint) + ":S" + season + "E" + episode + ":" + lang();
    }

    private String episodeTitleCacheKey(Pick matched, String imdbHint, String titleHint, int season, int episode) {
        return "ep_ti:" + episodeIdPart(matched, imdbHint, titleHint) + ":S" + season + "E" + episode + ":" + lang();
    }

    String getCachedEpisodeTitleSync(WatchNext.Item it) {
        return getCachedEpisodeTitle(it, findMatchingPick(it));
    }

    String getCachedEpisodeTitle(WatchNext.Item it, Pick matched) {
        if (it == null || !it.isSeries) return "";
        int[] se = WatchNext.extractSeasonEpisode(it.seasonNumber, it.episodeNumber, it.episode, it.intent);
        String cleanExisting = WatchNext.sanitizeEpisodeTitle(it.episodeTitle, it.title, se[0], se[1]);
        if (!cleanExisting.isEmpty()) {
            it.episodeTitle = cleanExisting;
            return cleanExisting;
        }
        if (se[0] <= 0 || se[1] <= 0) return "";
        String imdb = WatchNext.extractImdbId(it);
        String tKey = episodeTitleCacheKey(matched, imdb, it.title, se[0], se[1]);
        String mem = episodeTitleMem.get(tKey);
        if (mem != null && !mem.isEmpty()) {
            it.episodeTitle = mem;
            return mem;
        }
        String disk = cache.getString(tKey, "");
        String cleanDisk = WatchNext.sanitizeEpisodeTitle(disk, it.title, se[0], se[1]);
        if (!cleanDisk.isEmpty()) {
            episodeTitleMem.put(tKey, cleanDisk);
            it.episodeTitle = cleanDisk;
            return cleanDisk;
        }
        return "";
    }

    /**
     * Proactively resolves missing {@code episodeTitle} strings for all visible Continue Watching series items
     * using Cinemeta {@code /meta/series/<imdbId>.json} and TMDB {@code /tv/{id}/season/{s}/episode/{e}},
     * invoking {@code onUpdated} on the main thread when any new episode names arrive.
     */
    void ensureContinueWatchingEpisodeTitlesAsync(final List<WatchNext.Item> items, final Runnable onUpdated) {
        if (items == null || items.isEmpty()) return;
        final List<WatchNext.Item> targets = new ArrayList<>();
        for (WatchNext.Item it : items) {
            if (it == null || !it.isSeries) continue;
            int[] se = WatchNext.extractSeasonEpisode(it.seasonNumber, it.episodeNumber, it.episode, it.intent);
            if (se[0] <= 0 || se[1] <= 0) continue;
            if (getCachedEpisodeTitle(it, findMatchingPick(it)).isEmpty()) {
                targets.add(it);
            }
        }
        if (targets.isEmpty()) return;
        imgIo.execute(() -> {
            boolean anyChanged = false;
            for (WatchNext.Item it : targets) {
                int[] se = WatchNext.extractSeasonEpisode(it.seasonNumber, it.episodeNumber, it.episode, it.intent);
                if (se[0] <= 0 || se[1] <= 0) continue;
                String imdb = WatchNext.extractImdbId(it);
                Pick matched = findMatchingPick(it);
                if (matched == null && (imdb == null || !imdb.startsWith("tt"))
                        && it.title != null && !it.title.trim().isEmpty() && hasKey(context)) {
                    try {
                        JSONObject search = get("/search/tv?language=" + lang() + "&query=" + URLEncoder.encode(it.title.trim(), "UTF-8"));
                        JSONArray res = search.optJSONArray("results");
                        if (res != null && res.length() > 0) {
                            Pick p = new Pick();
                            p.kind = "tv";
                            p.id = res.getJSONObject(0).getInt("id");
                            p.title = it.title.trim();
                            matched = p;
                        }
                    } catch (Exception ignored) {
                    }
                }
                String dedup = "cw_ep_ti:" + episodeIdPart(matched, imdb, it.title) + ":S" + se[0] + "E" + se[1];
                if (!ratingsPending.add(dedup)) continue;
                try {
                    String before = it.episodeTitle == null ? "" : it.episodeTitle;
                    fetchEpisodeOverviewSync(matched, imdb, se[0], se[1], it);
                    String after = it.episodeTitle == null ? "" : it.episodeTitle;
                    if (!after.isEmpty() && !after.equals(before)) {
                        anyChanged = true;
                    }
                } finally {
                    ratingsPending.remove(dedup);
                }
            }
            if (anyChanged && onUpdated != null) {
                main.post(onUpdated);
            }
        });
    }

    /**
     * Returns any cached episode-specific synopsis for a Continue Watching series item, or {@code ""} if not yet loaded.
     */
    String getCachedEpisodeOverview(WatchNext.Item it, Pick matched) {
        if (it == null || !it.isSeries) return "";
        if (it.episodeOverview != null && !it.episodeOverview.isEmpty()) {
            return it.episodeOverview;
        }
        int[] se = WatchNext.extractSeasonEpisode(it.seasonNumber, it.episodeNumber, it.episode, it.intent);
        if (se[0] > 0 && se[1] > 0) {
            String imdb = WatchNext.extractImdbId(it);
            String cKey = episodeOverviewCacheKey(matched, imdb, it.title, se[0], se[1]);
            String mem = episodeOverviewMem.get(cKey);
            if (mem != null && !mem.isEmpty()) {
                it.episodeOverview = mem;
                return mem;
            }
            String disk = cache.getString(cKey, "");
            if (disk != null && !disk.isEmpty()) {
                episodeOverviewMem.put(cKey, disk);
                it.episodeOverview = disk;
                return disk;
            }
        }
        if (it.description != null && !it.description.isEmpty()) {
            String seriesOv = (matched != null && matched.overview != null) ? matched.overview.trim() : "";
            if (!it.description.equalsIgnoreCase(seriesOv)) {
                return it.description;
            }
        }
        return "";
    }

    String getCachedPickEpisodeOverview(Pick p) {
        if (p == null || !"tv".equals(p.kind)) return "";
        int[] se = WatchNext.extractSeasonEpisode(p.progressSeason, p.progressEpisode, p.tag, p.link);
        if (se[0] <= 0 || se[1] <= 0) return "";
        String cKey = episodeOverviewCacheKey(p, p.imdbId, p.title, se[0], se[1]);
        String mem = episodeOverviewMem.get(cKey);
        if (mem != null && !mem.isEmpty()) return mem;
        String disk = cache.getString(cKey, "");
        if (disk != null && !disk.isEmpty()) {
            episodeOverviewMem.put(cKey, disk);
            return disk;
        }
        return "";
    }

    void ensurePickEpisodeOverviewAsync(Pick p, Runnable onReady) {
        if (p == null || !"tv".equals(p.kind)) return;
        int[] se = WatchNext.extractSeasonEpisode(p.progressSeason, p.progressEpisode, p.tag, p.link);
        if (se[0] <= 0 || se[1] <= 0) return;
        if (!getCachedPickEpisodeOverview(p).isEmpty()) return;
        final String dedupKey = "ep_meta:" + key(p) + ":S" + se[0] + "E" + se[1];
        if (!ratingsPending.add(dedupKey)) return;
        heroIo.execute(() -> {
            try {
                String ov = fetchEpisodeOverviewSync(p, p.imdbId, se[0], se[1], null);
                if (!ov.isEmpty() && onReady != null) {
                    main.post(onReady);
                }
            } finally {
                ratingsPending.remove(dedupKey);
            }
        });
    }

    private String fetchEpisodeOverviewSync(Pick matched, String imdbHint, int season, int episode, WatchNext.Item itemToUpdate) {
        if (season <= 0 || episode <= 0) return "";
        String imdb = (imdbHint != null && imdbHint.startsWith("tt")) ? imdbHint
                : (matched != null && matched.imdbId != null && matched.imdbId.startsWith("tt") ? matched.imdbId : "");
        String titleHint = itemToUpdate != null ? itemToUpdate.title : (matched != null ? matched.title : "");
        String cKey = episodeOverviewCacheKey(matched, imdb, titleHint, season, episode);
        String tKey = episodeTitleCacheKey(matched, imdb, titleHint, season, episode);
        String existingOv = episodeOverviewMem.get(cKey);
        String existingTi = episodeTitleMem.get(tKey);
        boolean wantTitle = itemToUpdate != null && (itemToUpdate.episodeTitle == null || itemToUpdate.episodeTitle.isEmpty());
        if (existingOv != null && !existingOv.isEmpty() && (!wantTitle || (existingTi != null && !existingTi.isEmpty()))) {
            if (itemToUpdate != null) {
                itemToUpdate.episodeOverview = existingOv;
                if (existingTi != null && !existingTi.isEmpty()) {
                    itemToUpdate.episodeTitle = existingTi;
                }
            }
            return existingOv;
        }
        String ov = existingOv != null ? existingOv : "";
        String epTitle = existingTi != null ? existingTi : "";
        int resolvedSeason = season;
        int resolvedEpisode = episode;
        if (matched != null && matched.id > 0 && "tv".equals(matched.kind) && hasKey(context)) {
            try {
                JSONObject epJson = get("/tv/" + matched.id + "/season/" + season + "/episode/" + episode + "?language=" + lang());
                ov = epJson.optString("overview", "").trim();
                epTitle = WatchNext.sanitizeEpisodeTitle(epJson.optString("name", ""), titleHint, season, episode);
            } catch (IOException e) {
                // Season finale rollover: if S<s>E<n+1> returns HTTP 404, check S<s+1>E1
                if (episode > 1 && e.getMessage() != null && e.getMessage().contains("404")) {
                    try {
                        JSONObject nextSeasonEp1 = get("/tv/" + matched.id + "/season/" + (season + 1) + "/episode/1?language=" + lang());
                        String nextOv = nextSeasonEp1.optString("overview", "").trim();
                        String nextTi = WatchNext.sanitizeEpisodeTitle(nextSeasonEp1.optString("name", ""), titleHint, season + 1, 1);
                        if ((!nextOv.isEmpty() && !"null".equalsIgnoreCase(nextOv)) || !nextTi.isEmpty()) {
                            ov = nextOv;
                            epTitle = nextTi;
                            resolvedSeason = season + 1;
                            resolvedEpisode = 1;
                            if (itemToUpdate != null) {
                                itemToUpdate.seasonNumber = resolvedSeason;
                                itemToUpdate.episodeNumber = resolvedEpisode;
                                itemToUpdate.episode = WatchNext.compactEpisodeSpacing(
                                        Ui.trf("Up next: S{s} E{e}", "s", resolvedSeason, "e", resolvedEpisode));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            if (((ov.isEmpty() || "null".equalsIgnoreCase(ov)) || epTitle.isEmpty()) && !"en-US".equalsIgnoreCase(lang())) {
                try {
                    JSONObject enJson = get("/tv/" + matched.id + "/season/" + resolvedSeason + "/episode/" + resolvedEpisode + "?language=en-US");
                    if (ov.isEmpty() || "null".equalsIgnoreCase(ov)) {
                        ov = enJson.optString("overview", "").trim();
                    }
                    if (epTitle.isEmpty()) {
                        epTitle = WatchNext.sanitizeEpisodeTitle(enJson.optString("name", ""), titleHint, resolvedSeason, resolvedEpisode);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (((ov.isEmpty() || "null".equalsIgnoreCase(ov)) || epTitle.isEmpty()) && !imdb.isEmpty()) {
            CinemetaClient.EpisodeMeta cm = CinemetaClient.get(context).fetchEpisodeMeta(imdb, resolvedSeason, resolvedEpisode);
            if ((ov.isEmpty() || "null".equalsIgnoreCase(ov)) && !cm.overview.isEmpty()) {
                ov = cm.overview;
            }
            if (epTitle.isEmpty() && !cm.title.isEmpty()) {
                epTitle = WatchNext.sanitizeEpisodeTitle(cm.title, titleHint, resolvedSeason, resolvedEpisode);
            }
        }
        if ("null".equalsIgnoreCase(ov)) ov = "";
        SharedPreferences.Editor ed = null;
        if (!ov.isEmpty()) {
            String finalKey = episodeOverviewCacheKey(matched, imdb, titleHint, resolvedSeason, resolvedEpisode);
            episodeOverviewMem.put(finalKey, ov);
            episodeOverviewMem.put(cKey, ov);
            ed = cache.edit().putString(finalKey, ov).putString(cKey, ov);
            if (itemToUpdate != null) {
                itemToUpdate.episodeOverview = ov;
            }
        }
        if (!epTitle.isEmpty()) {
            String finalTKey = episodeTitleCacheKey(matched, imdb, titleHint, resolvedSeason, resolvedEpisode);
            episodeTitleMem.put(finalTKey, epTitle);
            episodeTitleMem.put(tKey, epTitle);
            if (ed == null) ed = cache.edit();
            ed.putString(finalTKey, epTitle).putString(tKey, epTitle);
            if (itemToUpdate != null) {
                itemToUpdate.episodeTitle = epTitle;
            }
        }
        if (ed != null) {
            ed.apply();
        }
        return ov;
    }

    void ensureWatchItemMetadataAsync(WatchNext.Item it, MetadataCallback callback) {
        if (it == null) return;
        Pick existing = findMatchingPick(it);
        if (existing != null) {
            ensureMetadataAsync(existing, callback);
            if (it.isSeries && (getCachedEpisodeOverview(it, existing).isEmpty() || getCachedEpisodeTitle(it, existing).isEmpty())) {
                final int[] se = WatchNext.extractSeasonEpisode(it.seasonNumber, it.episodeNumber, it.episode, it.intent);
                if (se[0] > 0 && se[1] > 0) {
                    final String imdb = WatchNext.extractImdbId(it);
                    final String epDedup = "wn_ep:" + existing.id + ":S" + se[0] + "E" + se[1];
                    if (ratingsPending.add(epDedup)) {
                        heroIo.execute(() -> {
                            try {
                                String ov = fetchEpisodeOverviewSync(existing, imdb, se[0], se[1], it);
                                if ((!ov.isEmpty() || (it.episodeTitle != null && !it.episodeTitle.isEmpty())) && callback != null) {
                                    main.post(() -> callback.onMetadataReady(existing));
                                }
                            } finally {
                                ratingsPending.remove(epDedup);
                            }
                        });
                    }
                }
            }
            return;
        }
        if (!hasKey(context)) return;
        final String imdb = WatchNext.extractImdbId(it);
        final String title = it.title != null ? it.title.trim() : "";
        final String kindHint = it.isSeries ? "tv" : "movie";
        final String dedupKey = "wn_meta:" + (imdb != null ? imdb : title.toLowerCase(Locale.ROOT));
        if (!ratingsPending.add(dedupKey)) return;
        heroIo.execute(() -> {
            try {
                String resolved = null;
                if (imdb != null && imdb.startsWith("tt")) {
                    resolved = resolveTmdbKey(kindHint + ":" + imdb);
                }
                if (resolved == null && !title.isEmpty()) {
                    JSONObject search = get("/search/" + kindHint + "?language=" + lang() + "&query=" + URLEncoder.encode(title, "UTF-8"));
                    JSONArray res = search.optJSONArray("results");
                    if (res != null && res.length() > 0) {
                        resolved = kindHint + ":" + res.getJSONObject(0).getInt("id");
                    }
                }
                if (resolved == null || !resolved.contains(":")) return;
                Pick p = new Pick();
                p.kind = resolved.substring(0, resolved.indexOf(':'));
                p.id = Integer.parseInt(resolved.substring(resolved.indexOf(':') + 1));
                if (imdb != null && imdb.startsWith("tt")) p.imdbId = imdb;
                p.title = title;
                fetchDetails(p);
                if (it.isSeries) {
                    int[] se = WatchNext.extractSeasonEpisode(it.seasonNumber, it.episodeNumber, it.episode, it.intent);
                    if (se[0] > 0 && se[1] > 0) {
                        fetchEpisodeOverviewSync(p, p.imdbId, se[0], se[1], it);
                    }
                }
                if (!isValidBackdropCandidate(p.backdrop, p.poster)) {
                    String fb = resolveFallbackBackdropUrl(p.imdbId);
                    p.backdrop = fb != null ? fb : "";
                }
                if (p.imdbId != null && !p.imdbId.isEmpty()) {
                    watchExtraPicks.put(p.imdbId, p);
                }
                if (!title.isEmpty()) {
                    watchExtraPicks.put("title:" + title.toLowerCase(Locale.ROOT), p);
                }
                if (callback != null) {
                    main.post(() -> callback.onMetadataReady(p));
                }
            } catch (Exception ignored) {
            } finally {
                ratingsPending.remove(dedupKey);
            }
        });
    }

    void ensureMetadataAsync(Pick p, MetadataCallback callback) {
        if (p == null || shouldSkipBackgroundWork(backgroundActivityPaused, callback != null)) return;
        if (p == null) return;
        boolean hasRichKey = !Prefs.omdbKey().isEmpty() || !Prefs.mdblistKey().isEmpty();
        boolean hasValidBd = isValidBackdropCandidate(p.backdrop, p.poster);
        if (p.detailed && hasValidBd && !p.overview.isEmpty() && p.imdbId != null && p.imdbId.startsWith("tt")) {
            String cached = cache.getString("ext_r:" + p.imdbId, null);
            if (cached != null) {
                String[] tokens = cached.split("\\|", -1);
                if (tokens.length >= 3) {
                    if (!tokens[0].isEmpty()) p.imdbScore = tokens[0];
                    if (!tokens[1].isEmpty()) p.rtScore = tokens[1];
                    if (!tokens[2].isEmpty()) p.mcScore = tokens[2];
                    if (!tokens[1].isEmpty() || !tokens[2].isEmpty() || !hasRichKey) {
                        if (callback != null) callback.onMetadataReady(p);
                        return;
                    }
                }
            }
        }
        final String pKey = (callback != null ? "focus:" : "") + key(p);
        if (!ratingsPending.add(pKey)) return;
        ExecutorService pool = callback != null ? heroIo : imgIo;
        pool.execute(() -> {
            try {
                if (shouldSkipBackgroundWork(backgroundActivityPaused, callback != null)) return;
                boolean changed = false;
                if (!p.detailed || !isValidBackdropCandidate(p.backdrop, p.poster)
                        || p.overview.isEmpty() || p.imdbId == null || !p.imdbId.startsWith("tt")) {
                    fetchDetails(p);
                    if (!isValidBackdropCandidate(p.backdrop, p.poster)) {
                        String fb = resolveFallbackBackdropUrl(p.imdbId);
                        p.backdrop = fb != null ? fb : "";
                    }
                    changed = true;
                } else {
                    fetchExternalRatings(p);
                    changed = (!p.imdbScore.isEmpty() || !p.rtScore.isEmpty() || !p.mcScore.isEmpty());
                }
                if (changed) {
                    scheduleDebouncedSave();
                }
                if (callback != null) {
                    main.post(() -> callback.onMetadataReady(p));
                }
            } catch (Exception ignored) {
            } finally {
                ratingsPending.remove(pKey);
            }
        });
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    static double scoreBackdropCandidate(boolean textless, int width, int height, double voteAverage) {
        if (height > 0 && width < height * 1.3) return -1.0; // Strictly reject portrait/square images in backdrops
        return (textless ? 1e7 : 0) + Math.min(width, 3840) * 100 + voteAverage * 10;
    }

    static double scorePosterCandidate(boolean isEnOrClean, int width, int height, double voteAverage) {
        if (height > 0 && (double) width / height > 0.85) return -1.0; // Strictly reject wide/landscape banners
        return (isEnOrClean ? 1e6 : 0) + Math.min(width, 1000) * 10 + voteAverage;
    }

    /**
     * The title's best 16:9 backdrop: textless first (our own header text sits over it), preferring true 4K width up to 3840
     * and strictly rejecting portrait images.
     */
    static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -0.5;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            int width = b.optInt("width", 0);
            int height = b.optInt("height", 0);
            boolean textless = b.isNull("iso_639_1") || b.optString("iso_639_1", "").isEmpty();
            double score = scoreBackdropCandidate(textless, width, height, b.optDouble("vote_average", 0));
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /**
     * The title's best 2:3 portrait poster: strictly rejects wide/landscape images (`width/height > 0.85`).
     */
    static String bestPoster(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("posters");
        String best = null;
        double bestScore = -0.5;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p == null || p.optString("file_path", "").isEmpty()) continue;
            int width = p.optInt("width", 0);
            int height = p.optInt("height", 0);
            String iso = p.optString("iso_639_1", "");
            boolean isEnOrClean = "en".equals(iso) || p.isNull("iso_639_1") || iso.isEmpty();
            double score = scorePosterCandidate(isEnOrClean, width, height, p.optDouble("vote_average", 0));
            if (score > bestScore) {
                bestScore = score;
                best = p.optString("file_path");
            }
        }
        return best;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name, int providerId) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        // "HBO Max Amazon Channel", "Apple TV Amazon Channel"...: a subscription sold inside another app, not this one.
        if (n.contains(" channel")) return null;
        for (String[] s : SERVICES) {
            // Apple TV: only the streaming service (TMDB provider 350); provider 2 is the Apple TV Store (rent or buy).
            if (s[1].equals("appletv") && providerId != 350) continue;
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : WHOLE_NAME.contains(s[0]) ? n.equals(s[0]) : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        if (first == null) for (String[] s : byName) if (s[0].equals(plain(name))) return s;
        return first;
    }

    /** The streaming services whose app is on this TV, as {key, name}, for Home Settings (Picks from). */
    static List<String[]> installedServices(Context c) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] s : SERVICES) {
            if (HomeApps.launchIntent(c.getPackageManager(), s[2]) != null && seen.add(s[1])) out.add(new String[] {s[1], SERVICE_NAME.get(s[1])});
        }
        for (String[] s : readByName(c)) {
            if (HomeApps.launchIntent(c.getPackageManager(), s[2]) != null && seen.add(s[1])) out.add(new String[] {s[1], s[3]});
        }
        return out;
    }

    /**
     * The service's app is on the TV, and the person hasn't switched the service off (Home Settings > Picks from; the
     * hide_services line of config.properties is where those switches start: an app the TV ships without a subscription).
     */
    private boolean installed(String pkg) {
        for (String[] s : SERVICES) if (s[2].equals(pkg) && Prefs.serviceOff(s[1])) return false;
        for (String[] s : byName) if (s[2].equals(pkg) && Prefs.serviceOff(s[1])) return false;
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private boolean stremio() {
        if (Config.OPEN_IN_STREMIO) return true;
        if (context == null) return false;
        android.content.pm.PackageManager pm = context.getPackageManager();
        return pm != null && (HomeApps.launchIntent(pm, STREMIO_TV) != null
                || HomeApps.launchIntent(pm, STREMIO_ONE) != null);
    }

    private long sourceStamp() {
        long stamp = (key().hashCode() * 31L + REGION.hashCode()) * 31L + (Prefs.hideAnimation() ? 1 : 0);
        if (stremio()) stamp = stamp * 31L + 1;   // off: the same stamp as before, so nothing is fetched again
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        for (String[] service : byName) if (installed(service[2])) stamp = stamp * 31L + service[2].hashCode();
        return stamp;
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = key();
        if (key.isEmpty()) throw new IOException("no TMDB key");
        return request(path, key);
    }

    private static final String REJECTED = "TMDB rejected the key";

    private static JSONObject request(String path, String key) throws IOException, org.json.JSONException {
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException(REJECTED);
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    /**
     * Advance lazy-loader: when the user scrolls horizontally near the end of a row on the Home Screen,
     * fetches the next TMDB page for that row/genre in the background and appends items seamlessly.
     */
    void prefetchMoreForRow(String subKind, String rowKind, String genreKey) {
        if (!hasKey(context)) return;
        final String kind = (subKind == null || subKind.isEmpty()) ? "tv" : subKind;
        final String g = (genreKey == null || genreKey.isEmpty()) ? "All" : genreKey;
        final String rk = (rowKind == null ? "EXPLORE" : rowKind);
        if (WATCHLIST.equals(rowKind)) return;
        final String mapKey = kind + ":" + rk + ":" + g;
        final int nextPage;
        synchronized (rowPageMap) {
            if (rowFetchingSet.contains(mapKey)) return;
            int current = rowPageMap.getOrDefault(mapKey, 1);
            if (current >= 25) return; // up to 500 items per row
            nextPage = current + 1;
            rowFetchingSet.add(mapKey);
        }
        imgIo.execute(() -> {
            try {
                String path;
                if (TRENDING.equals(rowKind) && "All".equalsIgnoreCase(g)) {
                    path = "/trending/" + kind + "/week?language=" + lang() + "&page=" + nextPage;
                } else {
                    String gParam = tmdbGenreQueryFor(kind, g);
                    path = "/discover/" + kind + "?language=" + lang() + "&include_adult=false&sort_by=popularity.desc&vote_count.gte=5"
                            + (gParam.isEmpty() ? "" : "&" + gParam)
                            + (Prefs.hideAnimation() && !"Animation".equalsIgnoreCase(g) ? "&without_genres=" + ANIMATION : "")
                            + "&page=" + nextPage;
                }
                JSONArray results = get(path).optJSONArray("results");
                if (results != null && results.length() > 0) {
                    List<Pick> batch = new ArrayList<>();
                    String explicit = "All".equalsIgnoreCase(g) ? null : g;
                    for (int i = 0; i < results.length(); i++) {
                        addWithExplicitGenre(batch, results.getJSONObject(i), kind, explicit);
                    }
                    if (!batch.isEmpty()) {
                        CachedRowMetadata meta = getOrBuildMetadata();
                        List<Pick> merged = new ArrayList<>(picks);
                        Set<String> seen = new HashSet<>();
                        for (Pick ex : merged) seen.add(key(ex));
                        List<Pick> newlyAdded = new ArrayList<>();
                        for (Pick np : batch) {
                            if (np == null) continue;
                            String k = key(np);
                            if (isPickWatched(np) || np.onWatchlist || meta.watchlistKeys.contains(k)
                                    || (np.imdbId != null && !np.imdbId.isEmpty() && meta.watchlistKeys.contains(np.imdbId))
                                    || meta.votes.getOrDefault(k, 0) < 0) {
                                continue;
                            }
                            if (explicit != null && !matchesGenre(np, explicit)) {
                                continue;
                            }
                            if (seen.add(k)) {
                                np.lazyPage = nextPage;
                                merged.add(np);
                                newlyAdded.add(np);
                            }
                        }
                        synchronized (rowPageMap) {
                            rowPageMap.put(mapKey, nextPage);
                        }
                        if (!newlyAdded.isEmpty()) {
                            picks = merged;
                            for (Pick np : newlyAdded) {
                                poster(np);
                            }
                            invalidateRowCache();
                            prewarmRowCache();
                            notifyListeners();
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                synchronized (rowPageMap) {
                    rowFetchingSet.remove(mapKey);
                }
            }
        });
    }

    static String tmdbGenreQueryFor(String kind, String genre) {
        if (genre == null || "All".equalsIgnoreCase(genre)) return "without_genres=16";
        boolean tv = "tv".equals(kind);
        switch (genre) {
            case "Action": return (tv ? "with_genres=10759" : "with_genres=28") + "&without_genres=16";
            case "Adventure": return (tv ? "with_genres=10759" : "with_genres=12") + "&without_genres=16";
            case "Action & Adventure": return (tv ? "with_genres=10759" : "with_genres=28,12") + "&without_genres=16";
            case "Comedy": return "with_genres=35&without_genres=16";
            case "Rom-Coms": return (tv ? "with_genres=35&with_keywords=9799|9840|9673" : "with_genres=35,10749") + "&with_original_language=en|he&without_genres=16";
            case "Romance": return (tv ? "with_genres=18|35&with_keywords=9840|9673|9799" : "with_genres=10749") + "&with_original_language=en|he|es|fr|it&without_genres=16";
            case "Thriller": return (tv ? "with_genres=80,9648" : "with_genres=53") + "&without_genres=16";
            case "Drama": return "with_genres=18&without_genres=16";
            case "Sci-Fi": return (tv ? "with_genres=10765&with_keywords=9882|4565|310|1612|9951|4379|1430" : "with_genres=878") + "&without_genres=16";
            case "Fantasy": return (tv ? "with_genres=10765&with_keywords=818|2343|12554|170362|6152|9715|10988" : "with_genres=14") + "&without_genres=16";
            case "Crime": return "with_genres=80&without_genres=16";
            case "Mystery": return "with_genres=9648&without_genres=16";
            case "Crime & Mystery": return "with_genres=80,9648&without_genres=16";
            case "Documentary": return "with_genres=99&without_genres=16";
            case "Animation": return "with_genres=16&with_original_language=en|fr|es|he";
            case "Anime": return "with_genres=16&with_original_language=ja";
            case "Superhero": return "with_keywords=9715|9717&without_genres=16";
            case "Horror": return (tv ? "with_genres=9648,10765&with_keywords=315058|10292|12339" : "with_genres=27") + "&without_genres=16";
            case "Family": return "with_genres=10751";
            case "History": return (tv ? "with_keywords=9826|50244|15060" : "with_genres=36") + "&without_genres=16";
            case "War & Politics": return (tv ? "with_genres=10768" : "with_genres=10752") + "&without_genres=16";
            case "Biography": return "with_keywords=9672|5565&without_genres=16";
            case "K-Drama": return "with_genres=18&with_original_language=ko&without_genres=16";
            case "Music": return (tv ? "with_keywords=6029|4344" : "with_genres=10402") + "&without_genres=16";
            case "Sports": return "with_keywords=6075&without_genres=16";
            case "Reality": return (tv ? "with_genres=10764" : "with_genres=99") + "&without_genres=16";
            case "Western": return "with_genres=37&without_genres=16";
            case "Stand-Up & Talk": return (tv ? "with_genres=10767" : "with_genres=35&with_keywords=9716") + "&without_genres=16";
            case "Psychological Thriller": return (tv ? "with_genres=9648,18&with_keywords=12565" : "with_genres=53&with_keywords=12565|256466") + "&without_genres=16";
            case "Sitcoms": return "with_genres=35&with_keywords=193129|9714&without_genres=16";
            case "Dark Comedy": return "with_genres=35&with_keywords=10123|9714&without_genres=16";
            case "Medical Drama": return "with_genres=18&with_keywords=11612|9918&without_genres=16";
            case "Legal Drama": return "with_genres=18&with_keywords=10909|10594&without_genres=16";
            case "Medical & Legal": return "with_genres=18&with_keywords=11612|9918|10909|10594&without_genres=16";
            case "Political Drama": return (tv ? "with_genres=10768,18" : "with_genres=18&with_keywords=6083") + "&without_genres=16";
            case "Teen & Coming-of-Age": return "with_genres=18&with_keywords=10683|6270&without_genres=16";
            case "Cyberpunk & AI": return (tv ? "with_genres=10765" : "with_genres=878") + "&with_keywords=12190|310&without_genres=16";
            case "Post-Apocalyptic": return (tv ? "with_genres=10765" : "with_genres=878") + "&with_keywords=4458|285366&without_genres=16";
            case "Space Opera": return (tv ? "with_genres=10765" : "with_genres=878") + "&with_keywords=9882|1612&without_genres=16";
            case "True Crime": return "with_genres=99,80&without_genres=16";
            case "Nature & Wildlife": return "with_genres=99&with_keywords=18330|271&without_genres=16";
            case "Martial Arts": return (tv ? "with_genres=10759" : "with_genres=28") + "&with_keywords=779|1794&without_genres=16";
            case "Spy & Espionage": return (tv ? "with_genres=10759,18" : "with_genres=28,53") + "&with_keywords=470|10364&without_genres=16";
            case "Heist & Con-Artist": return "with_genres=80&with_keywords=10051|642&without_genres=16";
            case "Time Travel": return (tv ? "with_genres=10765" : "with_genres=878") + "&with_keywords=4379&without_genres=16";
            case "Supernatural": return (tv ? "with_genres=10765" : "with_genres=14,27") + "&with_keywords=9663|6152&without_genres=16";
            case "Miniseries": return (tv ? "with_type=2&with_genres=18|80|9648" : "with_genres=18,36") + "&without_genres=16";
            case "Cooking & Food": return "with_genres=99&with_keywords=6054|10637&without_genres=16";
            case "Travel & Adventure": return "with_genres=99&with_keywords=4344|18330&without_genres=16";
            default: return "without_genres=16";
        }
    }

    /**
     * Optimal RAM management: evicts decoded row Bitmaps that are outside the currently visible row window,
     * keeping active/adjacent row cards, the first few cards of every row, and "wn:" Continue Watching backdrops in RAM.
     */
    void trimBitmapsToActiveWindow(Set<String> activeBackdrops) {
        if (activeBackdrops == null || activeBackdrops.isEmpty()) return;
        synchronized (images) {
            // Only trim heavy hero backdrops here; lightweight 2:3 posters stay in their own access-order LRU
            java.util.Iterator<Map.Entry<String, Backdrop>> it = images.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Backdrop> entry = it.next();
                String k = entry.getKey();
                Backdrop candidate = entry.getValue();
                if (!k.startsWith("wn:") && !pinnedBackdropKeys.contains(k)
                        && !Backdrop.isDisplayedBackdrop(candidate) && !activeBackdrops.contains(k)) {
                    it.remove();
                    if (candidate != null) Backdrop.scheduleSafeRecycle(candidate);
                }
            }
        }
    }

    // MARK: - Images (TMDB originals on disk, only active row window kept decoded in RAM)

    private void downloadBackdropOnly(Pick p) throws IOException {
        if (p.backdrop != null && !p.backdrop.isEmpty()) {
            File f = imageFile(p);
            if (!f.exists()) {
                String bdUrl = p.backdrop.startsWith("http") ? p.backdrop : (IMAGES + p.backdrop);
                fetchFile(f, (HttpURLConnection) new URL(bdUrl).openConnection());
            }
        }
    }

    private void download(Pick p) throws IOException {
        downloadBackdropOnly(p);
        downloadPosterOnly(p);
    }

    private void downloadPosterOnly(Pick p) {
        File poster = posterFile(p);
        if (poster == null || poster.exists()) return;
        try {
            String posterUrl = p.poster.startsWith("http") ? p.poster : (POSTERS + p.poster);
            fetchFile(poster, (HttpURLConnection) new URL(posterUrl).openConnection());
        } catch (IOException e) {
            AppLog.w("poster: " + e.getMessage());
        }
    }

    private File posterFile(Pick p) {
        return p.poster == null || p.poster.isEmpty() ? null
                : new File(new File(context.getCacheDir(), "recs"), "p780_" + safeCacheFileName(p.poster));
    }

    private void fetchFile(File f, HttpURLConnection c) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        if (p == null || p.backdrop == null || p.backdrop.isEmpty() || backgroundActivityPaused) return;
        final String bdKey = p.backdrop;
        synchronized (images) {
            if (images.containsKey(bdKey) || !decodingBackdrops.add(bdKey)) return;
        }
        try {
            loadImageLockedKey(p);
        } finally {
            synchronized (images) {
                decodingBackdrops.remove(bdKey);
            }
        }
    }

    private void loadImageLockedKey(Pick p) {
        final String bdKey = p.backdrop;
        if (bdKey == null || bdKey.isEmpty()) return;
        File f = imageFile(p);
        try {
            downloadBackdropOnly(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) {
                Backdrop.scheduleSafeRecycle(backdrop);
                return;   // the design changed midway: this one is stale
            }
            boolean notifyHero;
            BackdropCallback cb;
            synchronized (images) {
                Backdrop prev = images.put(bdKey, backdrop);
                if (prev != null && prev != backdrop) Backdrop.scheduleSafeRecycle(prev);
                // Evict oldest backdrop, protecting active Continue Watching ("wn:") items
                evictOldestBackdropsLocked(images, maxBackdrops());
                notifyHero = true;
                cb = backdropCallback;
            }
            if (notifyHero && cb != null) {
                final Backdrop ready = backdrop;
                main.post(() -> {
                    BackdropCallback currentCb = backdropCallback;
                    if (currentCb != null) {
                        currentCb.onBackdropLoaded(bdKey, ready);
                    }
                });
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    /** The 2:3 portrait poster at the narrow card's exact size, rejecting any accidental landscape image. */
    private Poster loadPoster(Pick p) {
        File f = posterFile(p);
        if (f == null || !f.exists()) return null;
        Bitmap full = BitmapFactory.decodeFile(f.getPath());
        if (full == null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        // If an external source accidentally returned a wide landscape banner (>0.90 aspect), replace with Metahub/TMDB 2:3 poster
        if (full.getWidth() > full.getHeight() * 0.90f) {
            full.recycle();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (p.imdbId != null && p.imdbId.startsWith("tt") && !p.poster.contains("metahub.space/poster")) {
                p.poster = "https://images.metahub.space/poster/medium/" + p.imdbId + "/img";
                downloadPosterOnly(p);
                File alt = posterFile(p);
                if (alt != null && alt.exists()) {
                    full = BitmapFactory.decodeFile(alt.getPath());
                } else {
                    return null;
                }
                if (full == null || full.getWidth() > full.getHeight() * 0.95f) {
                    if (full != null) full.recycle();
                    return null;
                }
            } else {
                return null;
            }
        }
        Bitmap card = Backdrop.card(full, Backdrop.TALL_W, Backdrop.TALL_H, 0);
        if (card != full && !full.isRecycled()) full.recycle();
        return new Poster(card);
    }

    static String posterCacheKey(Pick p) {
        if (p == null) return null;
        if (p.id != 0 && p.kind != null && !p.kind.isEmpty()) return "pk:" + p.kind + ":" + p.id;
        if (p.imdbId != null && !p.imdbId.isEmpty()) return "pk:" + p.imdbId;
        if (p.poster != null && !p.poster.isEmpty()) return "pk:" + p.poster;
        if (p.backdrop != null && !p.backdrop.isEmpty()) return "pk:" + p.backdrop;
        return null;
    }

    private void schedulePosterCallback() {
        if (!posterCallbackScheduled.compareAndSet(false, true)) return;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (isUserActivelyNavigating()) {
                    main.postDelayed(this, 120L);
                    return;
                }
                posterCallbackScheduled.set(false);
                final Runnable cb = posterCallback;
                if (cb != null) cb.run();
            }
        }, 96L);
    }

    Poster poster(Pick p) {
        String pk = posterCacheKey(p);
        if (pk == null) return null;
        long nowMs = System.currentTimeMillis();
        if (shouldSkipFailedPoster(failedPosters.get(pk), nowMs)) return null;
        synchronized (images) {
            Poster cached = posters.get(pk);
            if (cached != null) return cached;
            if (p.poster == null || p.poster.isEmpty()) {
                failedPosters.put(pk, nowMs);
                return null;
            }
            if (backgroundActivityPaused || !pendingPosters.add(pk)) return null;
        }
        posterIo.execute(() -> {
            if (backgroundActivityPaused) {
                synchronized (images) {
                    pendingPosters.remove(pk);
                }
                return;
            }
            Poster loaded = null;
            try {
                downloadPosterOnly(p);
                loaded = loadPoster(p);
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("poster decode: " + e.getMessage());
            } finally {
                synchronized (images) {
                    pendingPosters.remove(pk);
                    if (loaded != null) {
                        String finalKey = posterCacheKey(p);
                        String putKey = finalKey != null ? finalKey : pk;
                        failedPosters.remove(pk);
                        failedPosters.remove(putKey);
                        Poster prev = posters.put(putKey, loaded);
                        if (prev != null && prev != loaded) prev.recycle();
                        evictOldestPostersLocked(posters, maxPosters());
                    } else {
                        failedPosters.put(pk, System.currentTimeMillis());
                    }
                }
            }
            if (loaded != null) {
                schedulePosterCallback();
            }
        });
        return null;
    }

    void trim() {
        // Keep only a compact initial window in RAM when leaving the home screen; when paused for streaming,
        // skip wanted() allocation to prevent GC churn on :home.
        java.util.Set<String> keep = new java.util.HashSet<>();
        if (!backgroundActivityPaused) {
            List<Pick> w = wanted();
            for (int i = 0; i < Math.min(18, w.size()); i++) {
                Pick p = w.get(i);
                if (p.backdrop != null && !p.backdrop.isEmpty()) keep.add(p.backdrop);
                String pk = posterCacheKey(p);
                if (pk != null) keep.add(pk);
            }
        }
        synchronized (images) {
            java.util.Iterator<Map.Entry<String, Backdrop>> bi = images.entrySet().iterator();
            while (bi.hasNext()) {
                Map.Entry<String, Backdrop> entry = bi.next();
                String k = entry.getKey();
                Backdrop candidate = entry.getValue();
                if (!k.startsWith("wn:") && !pinnedBackdropKeys.contains(k)
                        && !Backdrop.isDisplayedBackdrop(candidate) && !keep.contains(k)) {
                    bi.remove();
                    if (candidate != null) Backdrop.scheduleSafeRecycle(candidate);
                }
            }
            java.util.Iterator<Map.Entry<String, Poster>> pi = posters.entrySet().iterator();
            while (pi.hasNext()) {
                Map.Entry<String, Poster> entry = pi.next();
                if (!keep.contains(entry.getKey())) {
                    Poster evicted = entry.getValue();
                    pi.remove();
                    if (evicted != null) evicted.recycle();
                }
            }
        }
    }

    private static void prune(File dir) {
        long now = System.currentTimeMillis();
        if (now - lastPruneAtMs < PRUNE_COOLDOWN_MS) return;
        lastPruneAtMs = now;
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = now - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= FILES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                JSONArray ga = new JSONArray();
                for (String g : p.genres) ga.put(g);
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                        .put("poster", p.poster == null ? "" : p.poster).put("link", p.link == null ? "" : p.link)
                        .put("meta", cleanMeta(p.meta)).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("servicePkg", p.servicePkg == null ? "" : p.servicePkg)
                        .put("tag", p.tag == null ? "" : p.tag).put("mine", p.mine).put("detailed", p.detailed)
                        .put("engaged", p.engaged).put("progressPct", p.progressPct)
                        .put("progressSeason", p.progressSeason).put("progressEpisode", p.progressEpisode)
                        .put("watched", p.watched).put("onWatchlist", p.onWatchlist)
                        .put("rating", p.rating).put("votesCount", p.votesCount).put("popularity", p.popularity)
                        .put("matchScore", p.matchScore).put("trendingScore", p.trendingScore).put("highQuality", p.highQuality)
                        .put("lang", p.lang == null ? "en" : p.lang).put("isAnime", p.isAnime)
                        .put("imdbId", p.imdbId == null ? "" : p.imdbId)
                        .put("imdbScore", p.imdbScore == null ? "" : p.imdbScore)
                        .put("rtScore", p.rtScore == null ? "" : p.rtScore)
                        .put("mcScore", p.mcScore == null ? "" : p.mcScore)
                        .put("overview", p.overview == null ? "" : p.overview)
                        .put("runtime", p.runtime)
                        .put("releaseDate", p.releaseDate == null ? "" : p.releaseDate)
                        .put("firstAirDate", p.firstAirDate == null ? "" : p.firstAirDate)
                        .put("lastAirDate", p.lastAirDate == null ? "" : p.lastAirDate)
                        .put("seasonsCount", p.seasonsCount)
                        .put("airedSeasonsCount", p.airedSeasonsCount)
                        .put("status", p.status == null ? "" : p.status)
                        .put("hasConfirmedRenewal", p.hasConfirmedRenewal)
                        .put("lazyPage", p.lazyPage)
                        .put("genres", ga));
            }
            Prefs.writeJsonCacheFile(context, "recs", "recs", "picks", a.toString());
            SharedPreferences.Editor e = cache.edit().remove("picks").putInt("period", period).putLong("listAt", listAt)
                    .putString("traktActivity", traktActivity).putLong("traktStamp", traktStamp)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            e.apply();
        } catch (Exception ex) {
            AppLog.w("recommendations: failed saving catalog cache", ex);
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.poster = o.optString("poster");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = cleanMeta(o.optString("meta"));
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.servicePkg = o.optString("servicePkg").isEmpty() ? null : o.optString("servicePkg");
            p.tag = o.optString("tag").isEmpty() ? null : WatchNext.compactEpisodeSpacing(o.optString("tag"));
            p.mine = o.optInt("mine");
            p.detailed = o.optBoolean("detailed");
            p.engaged = o.optLong("engaged", 0);
            p.progressPct = (float) o.optDouble("progressPct", 0);
            p.progressSeason = o.optInt("progressSeason", 0);
            p.progressEpisode = o.optInt("progressEpisode", 0);
            p.watched = o.optBoolean("watched", false);
            p.onWatchlist = o.optBoolean("onWatchlist", p.mine == WATCHLIST_MINE);
            p.rating = o.optDouble("rating", 7.0);
            p.votesCount = o.optInt("votesCount", 50);
            p.popularity = o.optDouble("popularity", 20.0);
            p.matchScore = o.optDouble("matchScore", p.rating * 10.0);
            p.trendingScore = o.optDouble("trendingScore", Math.log1p(Math.max(0.0, p.popularity)) * 18.0 + p.rating * 4.5);
            p.highQuality = o.optBoolean("highQuality", true);
            p.lang = o.optString("lang", "en");
            p.isAnime = o.optBoolean("isAnime", false);
            p.imdbId = o.optString("imdbId", "");
            if (p.poster != null && !p.poster.isEmpty() && !WatchNext.isCleanPosterUrl(p.poster, true)) {
                String clean = WatchNext.sanitizePosterUrl(p.poster, !p.imdbId.isEmpty() ? p.imdbId : p.link, true);
                p.poster = clean != null ? clean : "";
            }
            p.imdbScore = o.optString("imdbScore", "");
            p.rtScore = o.optString("rtScore", "");
            p.mcScore = o.optString("mcScore", "");
            p.overview = o.optString("overview", "");
            p.runtime = o.optInt("runtime", 0);
            p.releaseDate = o.optString("releaseDate", "");
            p.firstAirDate = o.optString("firstAirDate", "");
            p.lastAirDate = o.optString("lastAirDate", "");
            p.seasonsCount = o.optInt("seasonsCount", 0);
            p.airedSeasonsCount = o.optInt("airedSeasonsCount", 0);
            p.status = o.optString("status", "");
            p.hasConfirmedRenewal = o.optBoolean("hasConfirmedRenewal", false);
            p.lazyPage = o.optInt("lazyPage", 0);
            JSONArray ga = o.optJSONArray("genres");
            for (int j = 0; ga != null && j < ga.length(); j++) {
                String g = ga.optString(j);
                if (!g.isEmpty() && !p.genres.contains(g)) p.genres.add(g);
            }
            if (p.genres.contains("Anime")) p.isAnime = true;
            if ("ko".equalsIgnoreCase(p.lang)) p.genres.remove("Rom-Coms");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
