package io.soartv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client for Stremio's free, keyless Cinemeta API ({@code https://v3-cinemeta.strem.io}) and AIOStreams addon.
 *
 * <p><b>Why:</b> Replaces TheTVDB's 7-day expiring free key with a permanently free, high-availability IMDb metadata
 * and catalog service that runs natively on the TV without any local PC.
 *
 * <p><b>What:</b> Fetches trending/top movie and series catalogs, searches titles by query, and builds native
 * {@code stremio:///detail/{type}/{imdb_id}} deep links so selecting any movie or show opens directly in Stremio.
 */
public final class CinemetaClient {

    /** Immutable metadata record for a Cinemeta / Stremio catalog item. */
    public static class CatalogItem {
        public final String id;
        public final String imdbId;
        public final String type;
        public final String name;
        public final String releaseInfo;
        public final String imdbRating;
        public final String posterUrl;
        public final String backgroundUrl;
        public final String backdropUrl;
        public final String stremioDeepLink;

        public CatalogItem(String imdbId, String type, String name, String releaseInfo,
                           String imdbRating, String posterUrl, String backgroundUrl) {
            this.imdbId = imdbId == null ? "" : imdbId.trim();
            this.id = this.imdbId;
            this.type = "series".equalsIgnoreCase(type) || "tv".equalsIgnoreCase(type) ? "series" : "movie";
            this.name = name == null ? "" : name.trim();
            this.releaseInfo = releaseInfo == null ? "" : releaseInfo.trim();
            this.imdbRating = imdbRating == null ? "" : imdbRating.trim();
            String cleanPoster = WatchNext.sanitizePosterUrl(posterUrl, this.imdbId, true);
            this.posterUrl = cleanPoster != null ? cleanPoster : "";
            String cleanBg = WatchNext.isCleanPosterUrl(backgroundUrl, false) ? backgroundUrl.trim()
                    : (!this.imdbId.isEmpty() ? "https://images.metahub.space/background/medium/" + this.imdbId + "/img" : "");
            this.backgroundUrl = cleanBg;
            this.backdropUrl = cleanBg;
            this.stremioDeepLink = buildStremioLink(this.type, this.imdbId);
        }
    }

    /** Alias record for compatibility with AI Search and artwork resolvers. */
    public static final class MediaItem extends CatalogItem {
        public MediaItem(String imdbId, String type, String name, String releaseInfo,
                         String imdbRating, String posterUrl, String backgroundUrl) {
            super(imdbId, type, name, releaseInfo, imdbRating, posterUrl, backgroundUrl);
        }
    }

    private static volatile CinemetaClient instance;

    /** Returns the shared {@link CinemetaClient} instance. */
    public static CinemetaClient get(Object ignoredContext) {
        CinemetaClient c = instance;
        if (c == null) {
            c = new CinemetaClient();
            instance = c;
        }
        return c;
    }

    private final String baseUrl;

    /** Constructs a {@link CinemetaClient} using {@link Config#CINEMETA_URL}. */
    public CinemetaClient() {
        this(Config.CINEMETA_URL);
    }

    /**
     * Constructs a {@link CinemetaClient} with an explicit base URL.
     *
     * @param baseUrl Base URL of the Cinemeta-compatible Stremio addon.
     */
    public CinemetaClient(String baseUrl) {
        String u = baseUrl == null || baseUrl.trim().isEmpty() ? "https://v3-cinemeta.strem.io" : baseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.baseUrl = u;
    }

    /**
     * Builds a Stremio deep link URI for a given media type and IMDb ID.
     *
     * @param type   {@code "movie"}, {@code "tv"}, or {@code "series"}.
     * @param imdbId IMDb identifier (e.g., {@code "tt0903747"}).
     * @return Deep link URI {@code stremio:///detail/{movie|series}/{imdbId}}.
     */
    public static String buildStremioLink(String type, String imdbId) {
        return buildStremioLink(type, imdbId, 0, 0);
    }

    /**
     * Builds a Stremio deep link URI for a given media type, IMDb ID, season and episode.
     *
     * @param type    {@code "movie"}, {@code "tv"}, or {@code "series"}.
     * @param imdbId  IMDb identifier (e.g., {@code "tt0903747"}).
     * @param season  Season number (for series), or 0.
     * @param episode Episode number (for series), or 0.
     * @return Deep link URI {@code stremio:///detail/{movie|series}/{imdbId}} or {@code stremio:///detail/series/{imdbId}/{imdbId}:{season}:{episode}}.
     */
    public static String buildStremioLink(String type, String imdbId, int season, int episode) {
        if (imdbId == null || !imdbId.startsWith("tt")) return null;
        boolean isSeries = ("tv".equalsIgnoreCase(type) || "series".equalsIgnoreCase(type) || "show".equalsIgnoreCase(type));
        if (isSeries && season > 0 && episode > 0) {
            return "stremio:///detail/series/" + imdbId + "/" + imdbId + ":" + season + ":" + episode;
        }
        String stremioType = isSeries ? "series" : "movie";
        return "stremio:///detail/" + stremioType + "/" + imdbId;
    }

    /**
     * Fetches the top/popular catalog for {@code "movie"} or {@code "series"} from Cinemeta.
     *
     * @param type  {@code "movie"} or {@code "series"}.
     * @param limit Maximum number of items to return.
     * @return List of {@link CatalogItem} entries.
     */
    public List<CatalogItem> fetchTopCatalog(String type, int limit) {
        String stremioType = ("tv".equalsIgnoreCase(type) || "series".equalsIgnoreCase(type)) ? "series" : "movie";
        try {
            String json = get("/catalog/" + stremioType + "/top.json");
            return parseMetas(json, stremioType, limit);
        } catch (IOException e) {
            AppLog.w("Cinemeta catalog " + stremioType + " failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Searches Cinemeta for movies and series matching the given query string.
     *
     * @param query Search query (natural language or title).
     * @param limit Maximum number of combined results.
     * @return List of matching {@link CatalogItem} entries.
     */
    public List<CatalogItem> search(String query, int limit) {
        if (query == null || query.trim().isEmpty()) return Collections.emptyList();
        List<CatalogItem> results = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(query.trim(), "UTF-8");
            int half = Math.max(1, limit / 2);
            results.addAll(parseMetas(get("/catalog/movie/top/search=" + encoded + ".json"), "movie", half));
            results.addAll(parseMetas(get("/catalog/series/top/search=" + encoded + ".json"), "series", limit - results.size()));
        } catch (IOException e) {
            AppLog.w("Cinemeta search failed: " + e.getMessage());
        }
        return results;
    }

    /** Searches Cinemeta synchronously for a specific media type ({@code "movie"} or {@code "series"}). */
    public List<MediaItem> searchSync(String query, String type) {
        if (query == null || query.trim().isEmpty()) return Collections.emptyList();
        String stremioType = ("tv".equalsIgnoreCase(type) || "series".equalsIgnoreCase(type)) ? "series" : "movie";
        List<MediaItem> out = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(query.trim(), "UTF-8");
            for (CatalogItem ci : parseMetas(get("/catalog/" + stremioType + "/top/search=" + encoded + ".json"), stremioType, 5)) {
                out.add(new MediaItem(ci.imdbId, ci.type, ci.name, ci.releaseInfo, ci.imdbRating, ci.posterUrl, ci.backgroundUrl));
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Searches Cinemeta synchronously across both movies and series. */
    public List<MediaItem> searchAllSync(String query) {
        List<MediaItem> out = new ArrayList<>();
        for (CatalogItem ci : search(query, 6)) {
            out.add(new MediaItem(ci.imdbId, ci.type, ci.name, ci.releaseInfo, ci.imdbRating, ci.posterUrl, ci.backgroundUrl));
        }
        return out;
    }

    /**
     * Parses a Cinemeta {@code {"metas":[...]}} JSON payload into {@link CatalogItem} instances.
     *
     * @param json        Raw JSON string.
     * @param defaultType Fallback type ({@code "movie"} or {@code "series"}).
     * @param limit       Maximum number of items to return.
     * @return Parsed list of {@link CatalogItem}.
     */
    public static List<CatalogItem> parseMetas(String json, String defaultType, int limit) {
        if (json == null || json.isEmpty() || limit <= 0) return Collections.emptyList();
        List<CatalogItem> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray metas = root.optJSONArray("metas");
            if (metas == null) return Collections.emptyList();
            for (int i = 0; i < metas.length() && out.size() < limit; i++) {
                JSONObject m = metas.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("imdb_id", m.optString("id", ""));
                String name = m.optString("name", "");
                if (!id.startsWith("tt") || name.isEmpty()) continue;
                String type = m.optString("type", defaultType);
                String releaseInfo = m.optString("releaseInfo", "");
                String rating = m.optString("imdbRating", "");
                String poster = m.optString("poster", null);
                String background = m.optString("background", null);
                out.add(new CatalogItem(id, type, name, releaseInfo, rating, poster, background));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Immutable episode title + synopsis pair extracted from Cinemeta's {@code videos[]} list. */
    public static final class EpisodeMeta {
        public static final EpisodeMeta EMPTY = new EpisodeMeta("", "");
        public final String title;
        public final String overview;

        public EpisodeMeta(String title, String overview) {
            this.title = title == null ? "" : title.trim();
            this.overview = overview == null ? "" : overview.trim();
        }
    }

    /**
     * Parses a Cinemeta {@code /meta/series/<imdbId>.json} payload and extracts both the episode title and synopsis
     * of a specific {@code season} and {@code episode} from its {@code videos} array.
     */
    public static EpisodeMeta parseEpisodeMetaFromMetaJson(String json, int season, int episode) {
        if (json == null || json.isEmpty() || season <= 0 || episode <= 0) return EpisodeMeta.EMPTY;
        int len = json.length();
        int i = 0;
        int videosArrStart = -1;
        while (i < len) {
            char c = json.charAt(i);
            if (c == '"') {
                int strEnd = skipJsonString(json, i);
                if (strEnd - i == 8 && json.regionMatches(i + 1, "videos", 0, 6)) {
                    int k = skipWs(json, strEnd);
                    if (k < len && json.charAt(k) == ':') {
                        k = skipWs(json, k + 1);
                        if (k < len && json.charAt(k) == '[') {
                            videosArrStart = k;
                            break;
                        }
                    }
                }
                i = strEnd;
            } else {
                i++;
            }
        }
        if (videosArrStart < 0) return EpisodeMeta.EMPTY;

        int bracketDepth = 1;
        int braceDepth = 0;
        int objStart = -1;
        int p = videosArrStart + 1;
        while (p < len && bracketDepth > 0) {
            char c = json.charAt(p);
            if (c == '"') {
                p = skipJsonString(json, p);
                continue;
            }
            if (c == '[') {
                bracketDepth++;
            } else if (c == ']') {
                bracketDepth--;
                if (bracketDepth == 0) break;
            } else if (c == '{') {
                if (bracketDepth == 1 && braceDepth == 0) {
                    objStart = p + 1;
                }
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
                if (bracketDepth == 1 && braceDepth == 0 && objStart >= 0) {
                    EpisodeMeta found = extractMatchingVideoMeta(json, objStart, p, season, episode);
                    if (found != null) return found;
                    objStart = -1;
                }
            }
            p++;
        }
        return EpisodeMeta.EMPTY;
    }

    /**
     * Parses a Cinemeta {@code /meta/series/<imdbId>.json} payload and extracts the title of a specific
     * {@code season} and {@code episode} from its {@code videos} array, returning {@code ""} when unavailable.
     */
    public static String parseEpisodeTitleFromMetaJson(String json, int season, int episode) {
        return parseEpisodeMetaFromMetaJson(json, season, episode).title;
    }

    /**
     * Parses a Cinemeta {@code /meta/series/<imdbId>.json} payload and extracts the synopsis of a specific
     * {@code season} and {@code episode} from its {@code videos} array, returning {@code ""} when unavailable.
     */
    public static String parseEpisodeOverviewFromMetaJson(String json, int season, int episode) {
        return parseEpisodeMetaFromMetaJson(json, season, episode).overview;
    }

    private static int skipWs(String s, int idx) {
        int len = s.length();
        while (idx < len && s.charAt(idx) <= ' ') idx++;
        return idx;
    }

    private static int skipJsonString(String s, int openQuoteIdx) {
        int len = s.length();
        int i = openQuoteIdx + 1;
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i + 1;
            } else {
                i++;
            }
        }
        return len;
    }

    private static int skipJsonNested(String s, int startIdx, int limit) {
        int braces = 0, brackets = 0;
        int i = startIdx;
        while (i < limit) {
            char c = s.charAt(i);
            if (c == '"') {
                i = skipJsonString(s, i);
                continue;
            }
            if (c == '{') braces++;
            else if (c == '}') {
                braces--;
                if (braces <= 0 && brackets <= 0) return i + 1;
            } else if (c == '[') brackets++;
            else if (c == ']') {
                brackets--;
                if (braces <= 0 && brackets <= 0) return i + 1;
            }
            i++;
        }
        return limit;
    }

    private static String unescapeJsonRange(String s, int from, int to) {
        if (from >= to) return "";
        StringBuilder sb = new StringBuilder(to - from);
        int i = from;
        while (i < to) {
            char c = s.charAt(i++);
            if (c == '\\' && i < to) {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 <= to) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        break;
                    default: sb.append(esc); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static EpisodeMeta extractMatchingVideoMeta(String s, int from, int to, int targetSeason, int targetEpisode) {
        int vidSeason = -1;
        int vidEpisode = -1;
        String name = "";
        String title = "";
        String overview = "";
        String description = "";
        int i = from;
        while (i < to) {
            i = skipWs(s, i);
            while (i < to && s.charAt(i) == ',') {
                i = skipWs(s, i + 1);
            }
            if (i >= to || s.charAt(i) != '"') break;
            int keyEnd = skipJsonString(s, i);
            String key = unescapeJsonRange(s, i + 1, Math.max(i + 1, keyEnd - 1));
            i = skipWs(s, keyEnd);
            if (i >= to || s.charAt(i) != ':') break;
            i = skipWs(s, i + 1);
            if (i >= to) break;

            char vc = s.charAt(i);
            if (vc == '"') {
                int valEnd = skipJsonString(s, i);
                if ("name".equals(key)) {
                    name = unescapeJsonRange(s, i + 1, Math.max(i + 1, valEnd - 1)).trim();
                } else if ("title".equals(key)) {
                    title = unescapeJsonRange(s, i + 1, Math.max(i + 1, valEnd - 1)).trim();
                } else if ("overview".equals(key)) {
                    overview = unescapeJsonRange(s, i + 1, Math.max(i + 1, valEnd - 1)).trim();
                } else if ("description".equals(key)) {
                    description = unescapeJsonRange(s, i + 1, Math.max(i + 1, valEnd - 1)).trim();
                }
                i = valEnd;
            } else if (vc == '{' || vc == '[') {
                i = skipJsonNested(s, i, to);
            } else {
                int primStart = i;
                while (i < to) {
                    char pc = s.charAt(i);
                    if (pc == ',' || pc == '}' || pc <= ' ') break;
                    i++;
                }
                if ("season".equals(key) || "episode".equals(key) || "number".equals(key)) {
                    try {
                        int parsed = Integer.parseInt(s.substring(primStart, i).trim());
                        if ("season".equals(key)) vidSeason = parsed;
                        else if ("episode".equals(key)) vidEpisode = parsed;
                        else if (vidEpisode < 0) vidEpisode = parsed;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (vidSeason == targetSeason && vidEpisode == targetEpisode) {
            String bestTitle = !name.isEmpty() ? name : title;
            if ("null".equalsIgnoreCase(bestTitle)) bestTitle = "";
            String bestOv = !overview.isEmpty() ? overview : description;
            if ("null".equalsIgnoreCase(bestOv)) bestOv = "";
            return new EpisodeMeta(bestTitle, bestOv);
        }
        return null;
    }

    /**
     * Queries Cinemeta for both the title and synopsis of a specific TV episode ({@code season}, {@code episode}).
     */
    public EpisodeMeta fetchEpisodeMeta(String imdbId, int season, int episode) {
        if (imdbId == null || !imdbId.trim().startsWith("tt") || season <= 0 || episode <= 0) return EpisodeMeta.EMPTY;
        try {
            String raw = get("/meta/series/" + URLEncoder.encode(imdbId.trim(), "UTF-8") + ".json");
            return parseEpisodeMetaFromMetaJson(raw, season, episode);
        } catch (IOException ignored) {
            return EpisodeMeta.EMPTY;
        }
    }

    /**
     * Queries Cinemeta for the synopsis of a specific TV episode ({@code season}, {@code episode}).
     */
    public String fetchEpisodeOverview(String imdbId, int season, int episode) {
        return fetchEpisodeMeta(imdbId, season, episode).overview;
    }

    private String get(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(7000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "SoarTV/1.0");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            conn.disconnect();
        }
    }
}

