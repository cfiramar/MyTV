package io.soartv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What you were watching in your apps, from Android TV's own lists (TvContract, needs READ_TV_LISTINGS): the system
 * Watch Next list, where Prime Video, Disney+, HBO Max, Plex and others note what you left half-way, and the home
 * screen channels some apps publish under the name "Continue Watching" instead (Stremio). Newest first, a few per app,
 * so one show doesn't fill the row.
 */
final class WatchNext {
    private WatchNext() {}

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    /** Declared by apps that write the TV's lists (apps can also write their own rows and Watch Next without it). */
    private static final String WRITE_EPG = "com.android.providers.tv.permission.WRITE_EPG_DATA";
    static final int PER_APP = Integer.MAX_VALUE, MAX = 24;
    /** Channel names that mean "continue watching" (apps name their own channel). */
    private static final Set<String> CHANNEL_NAMES = new HashSet<>(java.util.Arrays.asList(
            "continue watching", "seguir viendo", "continuar viendo", "continuar assistindo", "continue assistindo",
            "המשך צפייה"));
    /** Channel names that mean "library / watchlist" (Stremio calls its watch list "Library"). */
    private static final Set<String> LIBRARY_CHANNEL_NAMES = new HashSet<>(java.util.Arrays.asList(
            "library", "my library", "stremio library", "watchlist", "watch list", "my list", "saved",
            "ספריה", "הספריה שלי", "רשימת צפייה"));
    /** YouTube packages are strictly excluded from Continue Watching on the For You screen (per product requirements). */
    private static final Set<String> YOUTUBE_PKGS = new HashSet<>(java.util.Arrays.asList(
            "com.google.android.youtube.tv",
            "com.amazon.firetv.youtube",
            "com.google.android.youtube",
            "org.smarttube.stable",
            "com.liskovsoft.smarttubetv.beta",
            "com.liskovsoft.smarttubetv"
    ));

    static boolean isYouTubePackage(String pkg) {
        return pkg != null && (YOUTUBE_PKGS.contains(pkg) || pkg.toLowerCase(Locale.ROOT).contains("youtube"));
    }

    static boolean isStremioPackage(String pkg) {
        return pkg != null && pkg.toLowerCase(Locale.ROOT).contains("stremio");
    }

    /** Known third-party poster-rating overlay & Stremio proxy patterns that bake IMDb/Trakt/RT bars into poster JPEGs. */
    private static final String[] BADGED_POSTER_PATTERNS = {
            "ratingposterdb", "rpdb", "top-posters", "top-streaming", "topstreaming",
            "poster-ratings", "ratingposter", "posterrating",
            "aiostreams", "elfhosted", "cyberflix", "stremthru",
            "mediafusion", "torrentio", "comet.", "knightcrawler",
            "mdblist.com", "posters.mdblist"
    };

    /** Official clean studio/metadata artwork hosts guaranteed never to composite rating bars onto posters. */
    private static final String[] OFFICIAL_CLEAN_POSTER_HOSTS = {
            "image.tmdb.org", "themoviedb.org",
            "images.metahub.space", "live.metahub.space", "episodes.metahub.space",
            "m.media-amazon.com", "ia.media-imdb.com", "images-na.ssl-images-amazon.com",
            "artworks.thetvdb.com", "thetvdb.com",
            "static.tvmaze.com", "assets.fanart.tv", "plex.direct"
    };

    /**
     * Checks whether a poster/artwork URL comes from a clean, unbadged source without Stremio/RPDB/Top-Posters rating bars.
     * Uses pure Java string/URI parsing so it works identically on Android TV and headless desktop JVM unit tests.
     */
    static boolean isCleanPosterUrl(String url, boolean strictOfficialCatalogOnly) {
        if (url == null) return false;
        String u = url.trim();
        if (u.isEmpty()) return false;
        if (u.startsWith("/") && !u.startsWith("//")) return true; // Relative TMDB path ("/abc123.jpg")
        String lower = u.toLowerCase(Locale.ROOT);
        for (String bad : BADGED_POSTER_PATTERNS) {
            if (lower.contains(bad)) return false;
        }
        if (lower.startsWith("content://") || lower.startsWith("android.resource://")) {
            return true;
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        String host = extractLowerHost(lower);
        if (host.isEmpty()) return false;
        for (String cleanHost : OFFICIAL_CLEAN_POSTER_HOSTS) {
            if (host.equals(cleanHost) || host.endsWith("." + cleanHost)) return true;
        }
        // For Stremio / catalog / IMDb items, reject any unknown third-party proxy worker that may inject rating bars;
        // for native streaming apps (Netflix, Apple TV, HBO Max, Disney+, etc.), allow their non-blacklisted studio CDNs.
        return !strictOfficialCatalogOnly;
    }

    static boolean isCleanPosterUrl(String url) {
        return isCleanPosterUrl(url, true);
    }

    private static String extractLowerHost(String lowerUrl) {
        int schemeEnd = lowerUrl.indexOf("://");
        if (schemeEnd < 0) return "";
        int start = schemeEnd + 3;
        int end = lowerUrl.length();
        for (int i = start; i < lowerUrl.length(); i++) {
            char ch = lowerUrl.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#' || ch == ':') {
                end = i;
                break;
            }
        }
        int at = lowerUrl.lastIndexOf('@', end - 1);
        if (at >= start) start = at + 1;
        return start < end ? lowerUrl.substring(start, end) : "";
    }

    /** Extracts an IMDb ID ("tt1234567") from the first matching candidate string, or "" if none found. */
    static String extractImdbIdFromStrings(String... candidates) {
        if (candidates == null) return "";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(tt\\d{6,10})");
        for (String src : candidates) {
            if (src == null || src.isEmpty()) continue;
            java.util.regex.Matcher m = p.matcher(src);
            if (m.find()) return m.group(1);
        }
        return "";
    }

    /**
     * Strips third-party rating-overlay poster URLs (RPDB, Top-Posters, AIOStreams, custom Stremio proxies)
     * and replaces them with clean official Metahub IMDb poster URLs (or null so TMDB clean lookup runs).
     */
    static String sanitizePosterUrl(String rawPoster, String fallbackImdbSource, boolean strictOfficialCatalogOnly) {
        if (isCleanPosterUrl(rawPoster, strictOfficialCatalogOnly)) {
            return rawPoster.trim();
        }
        String imdbId = extractImdbIdFromStrings(fallbackImdbSource, rawPoster);
        if (!imdbId.isEmpty()) {
            return "https://images.metahub.space/poster/large/" + imdbId + "/img";
        }
        return null;
    }

    static final class Item {
        String pkg, title, episode;     // episode: "Continue: S2E5" or "Up next: S2E6", or null
        String app;                     // the app's name, for the card's details line
        long duration, position, engaged, realEngaged;
        String intent;
        boolean isSeries;               // true for TV series/episodes, false for movies
        int seasonNumber;               // resolved season number (>0 when known)
        int episodeNumber;              // resolved episode number (>0 when known)
        String description = "";        // provider short description from TvContract (when present)
        String episodeOverview = "";    // cached episode-specific synopsis for Continue Watching header
        String episodeTitle = "";       // resolved episode title from COLUMN_EPISODE_TITLE (or fallback)
        final Uri pictureUri;
        final String pictureKey, bannerKey;
        final String backdropUrl;
        private final String picture;

        Item(String pkg, String poster, String thumbnail) {
            this(pkg, poster, thumbnail, null);
        }

        Item(String pkg, String poster, String thumbnail, String intentHint) {
            this.pkg = pkg;
            this.intent = intentHint;
            boolean strict = isStremioPackage(pkg);
            String cleanPoster = sanitizePosterUrl(poster, intentHint, strict);
            String cleanThumbForCard = isCleanPosterUrl(thumbnail, strict) ? thumbnail.trim() : null;
            // Prefer clean 2:3 portrait poster for tall Home cards; never fall back to a badged thumbnail
            picture = cleanPoster != null && !cleanPoster.isEmpty() ? cleanPoster : cleanThumbForCard;
            backdropUrl = isCleanPosterUrl(thumbnail, false) ? thumbnail.trim() : null;
            Uri parsedUri = null;
            String scheme = null;
            if (picture != null && !picture.isEmpty()) {
                try {
                    parsedUri = Uri.parse(picture);
                    scheme = parsedUri == null ? null : parsedUri.getScheme();
                } catch (RuntimeException ignored) {
                    // Headless desktop JVM unit tests run against stub android.net.Uri
                }
            }
            pictureUri = parsedUri != null && ("content".equalsIgnoreCase(scheme)
                    || "android.resource".equalsIgnoreCase(scheme)
                    || "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme)) ? parsedUri : null;
            pictureKey = pictureUri != null ? picture : null;
            bannerKey = "art:" + pkg + ":" + (picture != null ? picture : "");
        }

        /** The picture for a 16:9 card: the thumbnail (usually 16:9) before the poster. */
        String picture() {
            return picture;
        }

        /** 0 to 1, or -1 when there is no position worth showing (not started, or no length). */
        float progress() {
            if (duration <= 0 || position < 1000 || position >= duration) return -1;
            return (float) position / duration;
        }

        /** Formats the episode number and title for tile display: "E1: name" (or "E1" if no title). */
        public String formatEpisodeLine() {
            return WatchNext.formatEpisodeLine(this);
        }
    }

    static String normalizeTitle(String title) {
        if (title == null) return "";
        return title.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Legacy exact hide key (`pkg|title`). */
    static String rawHideKey(Item it) {
        return (it.pkg == null ? "" : it.pkg) + "|" + (it.title == null ? "" : it.title);
    }

    /** How a hidden item is remembered: its app and normalized title. */
    static String hideKey(Item it) {
        return (it.pkg == null ? "" : it.pkg) + "|" + normalizeTitle(it.title);
    }

    /** App-agnostic title hide key so hiding a show hides both its Preview Channel and Trakt entries. */
    static String titleHideKey(Item it) {
        return "title|" + normalizeTitle(it.title);
    }

    /** Formats a positive season and episode pair in compact {@code S1E10} notation without spaces. */
    public static String formatCompactSeasonEpisode(int season, int episode) {
        if (season <= 0 || episode <= 0) return "";
        return "S" + season + "E" + episode;
    }

    /** Removes whitespace between season and episode tokens (e.g., {@code "S1 E10"} -> {@code "S1E10"}). */
    public static String compactEpisodeSpacing(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replaceAll("(?i)\\bS(\\d+)\\s+E(\\d+)\\b", "S$1E$2");
    }

    /**
     * Resolves {@code [season, episode]} from explicit integer fields, episode label text, or a Stremio/IMDb intent URI.
     */
    public static int[] extractSeasonEpisode(int explicitSeason, int explicitEpisode, String episodeText, String intentUri) {
        if (explicitSeason > 0 && explicitEpisode > 0) {
            return new int[] { explicitSeason, explicitEpisode };
        }
        if (episodeText != null && !episodeText.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)\\bS(\\d+)\\s*E(\\d+)\\b")
                    .matcher(episodeText);
            if (m.find()) {
                int s = parsePositiveInt(m.group(1));
                int e = parsePositiveInt(m.group(2));
                if (s > 0 && e > 0) return new int[] { s, e };
            }
        }
        if (intentUri != null && !intentUri.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("tt\\d+:(\\d+):(\\d+)")
                    .matcher(intentUri);
            if (m.find()) {
                int s = parsePositiveInt(m.group(1));
                int e = parsePositiveInt(m.group(2));
                if (s > 0 && e > 0) return new int[] { s, e };
            }
        }
        return new int[] { 0, 0 };
    }

    private static String safeContinueWatchingLabel() {
        try {
            return Ui.tr("Continue Watching");
        } catch (Throwable ignored) {
            return "Continue Watching";
        }
    }

    /**
     * Formats the dedicated gray Continue Watching header status line (e.g., {@code "Continue Watching S1E10"}
     * for series episodes, or {@code "Continue Watching"} for movies).
     */
    public static String formatContinueWatchingStatus(int season, int episode, String rawEpisode, boolean isSeries) {
        String baseLabel = safeContinueWatchingLabel();
        int[] se = extractSeasonEpisode(season, episode, rawEpisode, null);
        if (se[0] > 0 && se[1] > 0) {
            return baseLabel + " " + formatCompactSeasonEpisode(se[0], se[1]);
        }
        if (isSeries && rawEpisode != null && !rawEpisode.trim().isEmpty()) {
            String clean = compactEpisodeSpacing(rawEpisode.trim());
            if (!clean.equalsIgnoreCase("Continue watching")
                    && !clean.equalsIgnoreCase(baseLabel)
                    && !clean.regionMatches(true, 0, "Continue:", 0, 9)
                    && !clean.regionMatches(true, 0, "Up next:", 0, 8)) {
                return baseLabel + " · " + clean;
            }
        }
        return baseLabel;
    }

    static String formatContinueWatchingStatus(Item it) {
        if (it == null) return "";
        int[] se = extractSeasonEpisode(it.seasonNumber, it.episodeNumber, it.episode, it.intent);
        return formatContinueWatchingStatus(se[0], se[1], it.episode, it.isSeries);
    }

    /**
     * Cleans a raw episode title, stripping redundant {@code "S1E9 - "} / {@code "E9: "} prefixes and rejecting
     * generic placeholders ({@code "Episode 10"}, {@code "TBA"}, or duplicate series title) so Continue Watching
     * cards display real episode names when available and fall back cleanly to {@code "E10"} while fetching.
     */
    public static String sanitizeEpisodeTitle(String rawEpisodeTitle, String seriesTitle, int season, int episode) {
        if (rawEpisodeTitle == null) return "";
        String t = rawEpisodeTitle.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t) || "tba".equalsIgnoreCase(t) || "n/a".equalsIgnoreCase(t)) {
            return "";
        }
        // Strip leading "Continue: ", "Up next: ", "S1E9 - ", "E9: ", or "9. " prefixes
        t = t.replaceFirst("(?i)^(?:continue|up\\s+next)\\s*:\\s*", "").trim();
        t = t.replaceFirst("(?i)^(?:S\\d+\\s*E\\d+|E\\d+|\\d+)\\s*[:.\\-\\u2013\\u2014]\\s*", "").trim();
        if (t.isEmpty()) return "";
        if (seriesTitle != null && !seriesTitle.trim().isEmpty() && t.equalsIgnoreCase(seriesTitle.trim())) {
            return "";
        }
        if (t.matches("(?i)^(?:episode|ep\\.?|chapter|part|\\u05E4\\u05E8\\u05E7)\\s*#?\\s*\\d+$")) {
            return "";
        }
        if (t.matches("(?i)^s\\d+\\s*e\\d+$") || t.matches("(?i)^e\\d+$")) {
            return "";
        }
        return t;
    }

    /**
     * Formats the season and episode number for Continue Watching tile display: {@code "S2E10"}
     * (defaults season to 1 when only a positive episode number is supplied).
     */
    public static String formatEpisodeLine(int season, int episode, String episodeTitle) {
        if (episode <= 0) {
            return "";
        }
        int s = season > 0 ? season : 1;
        return "S" + s + "E" + episode;
    }

    public static String formatEpisodeLine(int episode, String episodeTitle) {
        return formatEpisodeLine(1, episode, episodeTitle);
    }

    public static String formatEpisodeLine(Item it) {
        if (it == null) return "";
        int s = it.seasonNumber;
        int ep = it.episodeNumber;
        if (s <= 0 || ep <= 0) {
            int[] se = extractSeasonEpisode(it.seasonNumber, it.episodeNumber, it.episode, it.intent);
            if (s <= 0) s = se[0];
            if (ep <= 0) ep = se[1];
        }
        return formatEpisodeLine(s, ep, it.episodeTitle);
    }

    /** Playback state signature (episode + playback minute) used to detect genuine new playback on channels without timestamps. */
    static String stateSignature(Item it) {
        if (it == null) return "";
        String ep = it.episode == null ? "" : compactEpisodeSpacing(it.episode.trim()).toLowerCase(Locale.ROOT);
        long posMin = it.position > 0 ? (it.position / 60_000L) : 0L;
        return ep + "|" + posMin;
    }

    /**
     * Determines whether a hidden Continue Watching item should be automatically unhidden.
     * Synthetic fallback ordering timestamps (`realEngaged == 0`) NEVER trigger auto-unhide;
     * an item only unhides if a genuine engagement timestamp is newer than `hiddenAt + 60_000L`
     * OR its episode/playback position genuinely changed after being hidden.
     */
    static boolean shouldAutoUnhide(Item it, long hiddenAt, String savedState) {
        if (it == null || hiddenAt <= 0L) return false;
        if (it.realEngaged > 0L && it.realEngaged > hiddenAt + 60_000L) {
            return true;
        }
        if (savedState != null && !savedState.isEmpty()) {
            String currentSig = stateSignature(it);
            if (!savedState.equals(currentSig) && (it.realEngaged == 0L || it.realEngaged > hiddenAt)) {
                return true;
            }
        }
        return false;
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Everything to show, ordered strictly by last-watched (engaged descending, newest first). */
    static List<Item> load(Context c) {
        List<Item> all = new ArrayList<>();
        if (allowed(c)) {
            ContentResolver cr = c.getContentResolver();
            all.addAll(read(cr, TvContract.WatchNextPrograms.CONTENT_URI));
            for (long channel : continueChannels(cr, c.getPackageName())) {
                all.addAll(read(cr, TvContract.buildPreviewProgramsUriForChannel(channel)));
            }
        }
        try {
            all.addAll(Recs.get(c).traktContinueItems(c));
        } catch (RuntimeException ignored) {
        }
        try {
            all.addAll(Recs.get(c).stremioContinueItems(c));
        } catch (RuntimeException ignored) {
        }
        Set<String> off = Prefs.watchOff(), hidden = Prefs.watchHidden();
        for (java.util.Iterator<Item> i = all.iterator(); i.hasNext(); ) {
            Item it = i.next();
            if (isYouTubePackage(it.pkg) || off.contains(it.pkg)) {
                i.remove();
                continue;
            }
            String hk = hideKey(it);
            String rawHk = rawHideKey(it);
            String titleHk = titleHideKey(it);
            String matchedKey = hidden.contains(hk) ? hk
                    : (hidden.contains(rawHk) ? rawHk
                    : (hidden.contains(titleHk) ? titleHk : null));
            if (matchedKey != null) {
                long hiddenAt = Prefs.watchHiddenAt(matchedKey);
                if (hiddenAt <= 0L) hiddenAt = Math.max(Prefs.watchHiddenAt(hk), Prefs.watchHiddenAt(titleHk));
                String savedState = Prefs.watchHiddenState(matchedKey);
                if (savedState.isEmpty()) savedState = Prefs.watchHiddenState(hk);
                if (savedState.isEmpty()) savedState = Prefs.watchHiddenState(titleHk);
                if (shouldAutoUnhide(it, hiddenAt, savedState)) {
                    Prefs.unhideWatchItem(hk);
                    Prefs.unhideWatchItem(rawHk);
                    Prefs.unhideWatchItem(titleHk);
                    hidden.remove(hk);
                    hidden.remove(rawHk);
                    hidden.remove(titleHk);
                } else {
                    i.remove();
                }
            }
        }
        // Order strictly by last-watched (most recently engaged first).
        Collections.sort(all, (a, b) -> Long.compare(b.engaged, a.engaged));
        List<Item> out = new ArrayList<>();
        Map<String, Item> keptByKey = new HashMap<>();
        for (Item it : all) {
            if (it.isSeries) {
                int[] se = extractSeasonEpisode(it.seasonNumber, it.episodeNumber, it.episode, it.intent);
                if (it.seasonNumber <= 0) it.seasonNumber = se[0];
                if (it.episodeNumber <= 0) it.episodeNumber = se[1];
                it.episodeTitle = sanitizeEpisodeTitle(it.episodeTitle, it.title, it.seasonNumber, it.episodeNumber);
                if (it.episodeTitle.isEmpty() && it.seasonNumber > 0 && it.episodeNumber > 0) {
                    try {
                        it.episodeTitle = Recs.get(c).getCachedEpisodeTitleSync(it);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            String normTitle = it.title == null ? "" : it.title.trim().toLowerCase(Locale.ROOT);
            String key = normTitle.isEmpty() ? (it.pkg + "|" + it.intent) : normTitle;
            Item existing = keptByKey.get(key);
            if (existing != null) {
                if (existing.episodeTitle.isEmpty() && !it.episodeTitle.isEmpty()
                        && existing.seasonNumber == it.seasonNumber
                        && existing.episodeNumber == it.episodeNumber) {
                    existing.episodeTitle = it.episodeTitle;
                }
                continue;
            }
            keptByKey.put(key, it);
            if (it.app == null || it.app.isEmpty()) it.app = appName(c, it.pkg);
            out.add(it);
            if (out.size() >= MAX) break;
        }
        return out;
    }

    /**
     * The apps that can feed Continue Watching (Settings, off the main thread): those allowed to write Android TV's
     * lists, plus any publishing right now. As {package, name}, sorted by name, without SoarTV.
     */
    static List<String[]> sources(Context c) {
        Set<String> pkgs = new HashSet<>();
        PackageManager pm = c.getPackageManager();
        try {
            for (android.content.pm.PackageInfo p : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                if (p.requestedPermissions == null) continue;
                for (String perm : p.requestedPermissions) {
                    if (WRITE_EPG.equals(perm)) { pkgs.add(p.packageName); break; }
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("continue watching apps: " + e.getMessage());
        }
        if (allowed(c)) {
            ContentResolver cr = c.getContentResolver();
            packages(cr, TvContract.WatchNextPrograms.CONTENT_URI, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, null, pkgs);
            // Home screen rows (not the tuner's channels): apps write these, and Watch Next, without any permission.
            packages(cr, TvContract.Channels.CONTENT_URI, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_TYPE, pkgs);
        }
        pkgs.remove(c.getPackageName());
        // Only apps you can open from the TV: leaves out system pieces (Shell) and home screens (other launchers),
        // which ask for the permission but have nothing to resume.
        Set<String> homes = new HashSet<>();
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(
                new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)) {
            homes.add(ri.activityInfo.packageName);
        }
        List<String[]> out = new ArrayList<>();
        for (String p : pkgs) {
            if (isYouTubePackage(p) || homes.contains(p) || HomeApps.launchIntent(pm, p) == null) continue;
            String name = appName(c, p);
            out.add(new String[] {p, name.isEmpty() ? p : name});
        }
        Collections.sort(out, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        return out;
    }

    /** The packages in a TvContract table (only preview channels when typeColumn is given). */
    private static void packages(ContentResolver cr, Uri uri, String column, String typeColumn, Set<String> out) {
        String[] projection = typeColumn == null ? new String[] {column} : new String[] {column, typeColumn};
        try (Cursor cur = cr.query(uri, projection, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                if (typeColumn != null && !TvContract.Channels.TYPE_PREVIEW.equals(cur.getString(1))) continue;
                String p = cur.isNull(0) ? null : cur.getString(0);
                if (p != null) out.add(p);
            }
        } catch (RuntimeException e) {
            AppLog.w("continue watching apps " + uri + ": " + e.getMessage());
        }
    }

    private static String appName(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static List<Long> continueChannels(ContentResolver cr, String self) {
        return channelsMatching(cr, self, CHANNEL_NAMES, false);
    }

    private static List<Long> libraryChannels(ContentResolver cr, String self) {
        return channelsMatching(cr, self, LIBRARY_CHANNEL_NAMES, true);
    }

    private static List<Long> channelsMatching(ContentResolver cr, String self, Set<String> names, boolean substringMatch) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cur = cr.query(TvContract.Channels.CONTENT_URI,
                new String[] {TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME,
                        TvContract.Channels.COLUMN_PACKAGE_NAME}, null, null, null)) {
            if (cur == null) return ids;
            int id = cur.getColumnIndex(TvContract.Channels._ID);
            int name = cur.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME);
            int pkg = cur.getColumnIndex(TvContract.Channels.COLUMN_PACKAGE_NAME);
            while (cur.moveToNext()) {
                String n = name < 0 ? null : cur.getString(name), p = pkg < 0 ? null : cur.getString(pkg);
                if (n == null || self.equals(p)) continue;
                String norm = n.trim().toLowerCase(Locale.ROOT);
                boolean matched = names.contains(norm);
                if (!matched && substringMatch) {
                    for (String candidate : names) {
                        if (norm.contains(candidate)) {
                            matched = true;
                            break;
                        }
                    }
                }
                if (matched) ids.add(cur.getLong(id));
            }
        } catch (RuntimeException e) {
            AppLog.w("channels query: " + e.getMessage());
        }
        return ids;
    }

    /**
     * Reads any Watchlist / Stremio "Library" items exposed by installed TV apps via TvContract.
     */
    static List<Item> loadWatchlistItems(Context c) {
        List<Item> out = new ArrayList<>();
        if (!allowed(c)) return out;
        ContentResolver cr = c.getContentResolver();
        out.addAll(readWatchlistOnly(cr, TvContract.WatchNextPrograms.CONTENT_URI, true));
        for (long channel : libraryChannels(cr, c.getPackageName())) {
            out.addAll(readWatchlistOnly(cr, TvContract.buildPreviewProgramsUriForChannel(channel), false));
        }
        return out;
    }

    /** Extracts an IMDb ID ("tt1234567") from a WatchNext item's intent or artwork URI if present. */
    static String extractImdbId(Item it) {
        if (it == null) return "";
        return extractImdbIdFromStrings(it.intent, it.picture(), it.backdropUrl);
    }

    private static List<Item> readWatchlistOnly(ContentResolver cr, Uri uri, boolean requireWatchlistType) {
        List<Item> out = new ArrayList<>();
        List<String> projection = new ArrayList<>(java.util.Arrays.asList(
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContract.WatchNextPrograms.COLUMN_TYPE,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI));
        if (requireWatchlistType) {
            projection.add(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
        }
        try (Cursor cur = cr.query(uri, projection.toArray(new String[0]), null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                if ("0".equals(text(cur, TvContract.WatchNextPrograms.COLUMN_BROWSABLE))) continue;
                if (requireWatchlistType) {
                    long watchNextType = number(cur, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
                    if (watchNextType != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) continue;
                }
                String title = text(cur, TvContract.WatchNextPrograms.COLUMN_TITLE);
                String pkg = text(cur, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                if (title == null || pkg == null || isYouTubePackage(pkg)) continue;
                String intentUri = text(cur, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                Item it = new Item(pkg, text(cur, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI),
                        text(cur, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI), intentUri);
                it.title = title;
                long progType = number(cur, TvContract.WatchNextPrograms.COLUMN_TYPE);
                String season = text(cur, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
                String episode = text(cur, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
                it.isSeries = progType == TvContract.WatchNextPrograms.TYPE_TV_EPISODE
                        || progType == TvContract.WatchNextPrograms.TYPE_TV_SERIES
                        || progType == TvContract.WatchNextPrograms.TYPE_TV_SEASON
                        || (season != null && !season.isEmpty())
                        || (episode != null && !episode.isEmpty());
                it.intent = intentUri;
                out.add(it);
            }
        } catch (RuntimeException e) {
            AppLog.w("watchlist programs " + uri + ": " + e.getMessage());
        }
        return out;
    }

    private static List<Item> read(ContentResolver cr, Uri uri) {
        List<Item> out = new ArrayList<>();
        List<String> projection = new ArrayList<>(java.util.Arrays.asList(
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContract.WatchNextPrograms.COLUMN_TYPE,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI));
        // Preview programs have no engagement time or Watch Next type.
        boolean isWatchNextUri = TvContract.WatchNextPrograms.CONTENT_URI.equals(uri);
        if (isWatchNextUri) {
            projection.add(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
            projection.add(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
        }
        long fallbackOrder = System.currentTimeMillis();
        try (Cursor cur = cr.query(uri, projection.toArray(new String[0]), null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                if ("0".equals(text(cur, TvContract.WatchNextPrograms.COLUMN_BROWSABLE))) continue;
                long watchNextType = number(cur, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
                // Saved for later, not something you're in the middle of or up next.
                if (watchNextType == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) continue;
                String title = text(cur, TvContract.WatchNextPrograms.COLUMN_TITLE);
                String pkg = text(cur, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                if (title == null || pkg == null) continue;
                String intentUri = text(cur, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                Item it = new Item(pkg, text(cur, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI),
                        text(cur, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI), intentUri);
                it.title = title;
                long progType = number(cur, TvContract.WatchNextPrograms.COLUMN_TYPE);
                String season = text(cur, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
                String episode = text(cur, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
                String epTitle = text(cur, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
                String shortDesc = text(cur, TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION);
                if (shortDesc != null && !shortDesc.trim().isEmpty()) {
                    it.description = shortDesc.trim();
                }
                if (epTitle != null && !epTitle.trim().isEmpty()) {
                    it.episodeTitle = epTitle.trim();
                } else if (shortDesc != null && !shortDesc.trim().isEmpty() && shortDesc.trim().length() <= 40) {
                    it.episodeTitle = shortDesc.trim();
                }
                it.isSeries = progType == TvContract.WatchNextPrograms.TYPE_TV_EPISODE
                        || progType == TvContract.WatchNextPrograms.TYPE_TV_SERIES
                        || progType == TvContract.WatchNextPrograms.TYPE_TV_SEASON
                        || (season != null && !season.isEmpty())
                        || (episode != null && !episode.isEmpty())
                        || (epTitle != null && !epTitle.isEmpty());
                it.duration = number(cur, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
                it.position = number(cur, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                long eng = number(cur, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
                // Exclude unstarted catalog/promotional items that have no playback position and are not explicit Up Next episodes
                boolean hasRealWatchState = it.position > 60_000L
                        || (isWatchNextUri && eng > 0L && (it.position > 0L
                                || (it.isSeries && watchNextType == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)));
                if (!hasRealWatchState) continue;
                boolean finished = it.duration > 0 && it.position >= (long) (it.duration * 0.90f);
                if (season != null && episode != null && !season.isEmpty() && !episode.isEmpty()) {
                    int sNum = parsePositiveInt(season);
                    int eNum = parsePositiveInt(episode);
                    if (finished) {
                        // Finished watching Episode N -> suggest Episode N + 1 (clear Episode N's stale title!)
                        int nextEp = eNum + 1;
                        String epStr = nextEp > 1 ? String.valueOf(nextEp) : episode;
                        it.seasonNumber = sNum;
                        it.episodeNumber = nextEp > 1 ? nextEp : eNum;
                        if (nextEp > 1) {
                            it.episodeTitle = "";
                        }
                        it.episode = compactEpisodeSpacing(Ui.trf("Up next: S{s} E{e}", "s", season, "e", epStr));
                        it.position = 0;
                    } else if (watchNextType == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                            || watchNextType == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW) {
                        it.seasonNumber = sNum;
                        it.episodeNumber = eNum;
                        it.episode = compactEpisodeSpacing(Ui.trf("Up next: S{s} E{e}", "s", season, "e", episode));
                        it.position = 0;
                    } else if (it.position > 0) {
                        it.seasonNumber = sNum;
                        it.episodeNumber = eNum;
                        it.episode = compactEpisodeSpacing(Ui.trf("Continue: S{s} E{e}", "s", season, "e", episode));
                    } else {
                        it.seasonNumber = sNum;
                        it.episodeNumber = eNum;
                        it.episode = compactEpisodeSpacing(Ui.trf("S{season} E{episode}", "season", season, "episode", episode));
                    }
                } else {
                    it.episode = epTitle != null ? compactEpisodeSpacing(epTitle) : null;
                    if (it.episodeTitle.isEmpty() && epTitle != null && !epTitle.trim().isEmpty()) {
                        it.episodeTitle = epTitle.trim();
                    }
                    if (finished && !it.isSeries) {
                        // A movie that was completely finished is not in-progress anymore
                        continue;
                    }
                }
                if (it.episode != null && (it.episode.isEmpty() || it.title.contains(it.episode))) it.episode = null;
                it.realEngaged = eng > 0 ? eng : 0L;
                it.engaged = eng > 0 ? eng : fallbackOrder--;
                it.intent = text(cur, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                if (it.isSeries && (it.seasonNumber <= 0 || it.episodeNumber <= 0)) {
                    int[] se = extractSeasonEpisode(0, 0, it.episode, it.intent);
                    it.seasonNumber = se[0];
                    it.episodeNumber = se[1];
                }
                if (it.episodeTitle.isEmpty() && it.episode != null && !it.episode.trim().isEmpty()) {
                    String epClean = it.episode.trim();
                    if (!epClean.matches("(?i).*S\\d+.*E\\d+.*")) {
                        it.episodeTitle = epClean;
                    }
                }
                out.add(it);
            }
        } catch (RuntimeException e) {   // a provider that refuses (no permission on some firmware) means nothing to show
            AppLog.w("continue watching " + uri + ": " + e.getMessage());
        }
        return out;
    }

    private static int parsePositiveInt(String s) {
        if (s == null) return 0;
        try {
            return Integer.parseInt(s.replaceAll("\\D+", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long number(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
}
