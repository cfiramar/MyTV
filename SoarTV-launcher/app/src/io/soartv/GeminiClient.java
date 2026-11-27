package io.soartv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Native Google Gemini AI client (`gemini-2.5-flash`) for natural-language media search,
 * mood-based recommendations, and conversational discovery on the TV and Companion Remote.
 * Runs 100% natively on the TV with zero PC dependency.
 */
final class GeminiClient {

    static final class Candidate {
        final String title;
        final String type; // "movie" or "series"
        final String year;
        final String reason;

        Candidate(String title, String type, String year, String reason) {
            this.title = title;
            this.type = type;
            this.year = year;
            this.reason = reason;
        }
    }

    static final class AiResult {
        final String summary;
        final String primaryTitle;
        final String mediaType; // "movie" or "series"
        final List<Candidate> candidates;
        final List<String> suggestedTitles;
        final CinemetaClient.MediaItem resolvedMedia;
        final CinemetaClient.MediaItem topItem;

        AiResult(String summary, String primaryTitle, String mediaType,
                 List<Candidate> candidates, CinemetaClient.MediaItem resolvedMedia) {
            this.summary = summary;
            this.primaryTitle = primaryTitle;
            this.mediaType = mediaType;
            this.candidates = candidates != null ? candidates : new ArrayList<>();
            List<String> titles = new ArrayList<>();
            if (primaryTitle != null && !primaryTitle.isEmpty()) titles.add(primaryTitle);
            for (Candidate c : this.candidates) {
                if (c.title != null && !c.title.isEmpty() && !titles.contains(c.title)) {
                    titles.add(c.title);
                }
            }
            this.suggestedTitles = titles;
            this.resolvedMedia = resolvedMedia;
            this.topItem = resolvedMedia;
        }
    }

    interface Callback {
        void onResult(AiResult result);
    }

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";

    private static GeminiClient instance;
    private final Context appContext;
    private final ExecutorService executor = new ThreadPoolExecutor(
            1,
            2,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),
            r -> {
                Thread t = new Thread(r, "gemini-ai-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private GeminiClient(Context context) {
        this.appContext = context.getApplicationContext();
    }

    static synchronized GeminiClient get(Context context) {
        if (instance == null) {
            instance = new GeminiClient(context);
        }
        return instance;
    }

    private static String resolveGeminiKey(Context c) {
        Context target = c;
        if (target == null) {
            try {
                target = Prefs.context();
            } catch (RuntimeException ignored) {
            }
        }
        String secretKey = Secrets.get(target, Secrets.GEMINI_API_KEY);
        if (secretKey != null && !secretKey.trim().isEmpty()) {
            return secretKey.trim();
        }
        try {
            if (Prefs.context() == null && target != null) {
                Prefs.init(target);
            }
            if (Prefs.context() != null) {
                String prefKey = Prefs.geminiKey();
                if (prefKey != null && !prefKey.trim().isEmpty()) {
                    return prefKey.trim();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return Config.GEMINI_API_KEY != null ? Config.GEMINI_API_KEY.trim() : "";
    }

    static boolean isConfigured() {
        return !resolveGeminiKey(instance != null ? instance.appContext : null).isEmpty();
    }

    static boolean isConfigured(Context c) {
        return !resolveGeminiKey(c).isEmpty();
    }

    void resolveQueryAsync(String userQuery, Callback callback) {
        executor.execute(() -> {
            AiResult res = resolveQuerySync(userQuery);
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(res));
            }
        });
    }

    AiResult resolveQuerySync(String userQuery) {
        if (userQuery == null || userQuery.trim().isEmpty()) return null;
        String key = resolveGeminiKey(appContext);
        if (key.isEmpty()) {
            // Fallback to direct Cinemeta search when no Gemini key is configured
            List<CinemetaClient.MediaItem> direct = CinemetaClient.get(appContext).searchAllSync(userQuery.trim());
            if (!direct.isEmpty()) {
                CinemetaClient.MediaItem top = direct.get(0);
                return new AiResult(top.name + (top.releaseInfo.isEmpty() ? "" : " (" + top.releaseInfo + ")"),
                        top.name, top.type, new ArrayList<>(), top);
            }
            return null;
        }
        try {
            String systemPrompt = "You are SoarTV's built-in AI cinema assistant on a 4K smart TV. "
                    + "Given the user's query (which may be a natural-language description, mood, actor, plot memory, or title), "
                    + "respond ONLY with a valid JSON object (no markdown fences) in this exact schema: "
                    + "{\"summary\":\"short 1-sentence friendly recommendation\",\"primaryTitle\":\"exact movie or TV show title\","
                    + "\"mediaType\":\"movie or series\",\"candidates\":[{\"title\":\"Exact Title\",\"type\":\"movie or series\","
                    + "\"year\":\"YYYY\",\"reason\":\"short reason\"}]}";

            JSONObject payload = new JSONObject();
            JSONArray contents = new JSONArray();
            JSONObject userTurn = new JSONObject();
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", systemPrompt + "\n\nUser query: " + userQuery.trim()));
            userTurn.put("role", "user").put("parts", parts);
            contents.put(userTurn);
            payload.put("contents", contents);

            JSONObject genConfig = new JSONObject();
            genConfig.put("temperature", 0.3);
            genConfig.put("maxOutputTokens", 450);
            genConfig.put("responseMimeType", "application/json");
            payload.put("generationConfig", genConfig);

            HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT + key).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                throw new RuntimeException("Gemini HTTP " + code);
            }
            String respText;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                respText = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                conn.disconnect();
            }

            JSONObject root = new JSONObject(respText);
            JSONArray cands = root.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) return null;
            JSONObject content = cands.getJSONObject(0).optJSONObject("content");
            JSONArray respParts = content == null ? null : content.optJSONArray("parts");
            if (respParts == null || respParts.length() == 0) return null;
            String jsonStr = respParts.getJSONObject(0).optString("text", "").trim();
            if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
            }

            JSONObject parsed = new JSONObject(jsonStr);
            String summary = parsed.optString("summary", "");
            String primaryTitle = parsed.optString("primaryTitle", userQuery.trim());
            String mediaType = parsed.optString("mediaType", "movie").toLowerCase().contains("series")
                    || parsed.optString("mediaType", "movie").toLowerCase().contains("tv") ? "series" : "movie";
            List<Candidate> list = new ArrayList<>();
            JSONArray arr = parsed.optJSONArray("candidates");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String t = o.optString("title", "");
                    if (!t.isEmpty()) {
                        list.add(new Candidate(
                                t,
                                o.optString("type", mediaType),
                                o.optString("year", ""),
                                o.optString("reason", "")
                        ));
                    }
                }
            }

            CinemetaClient.MediaItem resolved = null;
            List<CinemetaClient.MediaItem> matches = CinemetaClient.get(appContext).searchSync(primaryTitle, mediaType);
            if (matches.isEmpty()) {
                matches = CinemetaClient.get(appContext).searchAllSync(primaryTitle);
            }
            if (!matches.isEmpty()) {
                resolved = matches.get(0);
            }
            return new AiResult(summary, primaryTitle, mediaType, list, resolved);
        } catch (Exception e) {
            try {
                AppLog.w("GeminiClient fallback: " + e.getMessage());
            } catch (RuntimeException ignored) {
            }
            List<CinemetaClient.MediaItem> direct = CinemetaClient.get(appContext).searchAllSync(userQuery.trim());
            if (!direct.isEmpty()) {
                CinemetaClient.MediaItem top = direct.get(0);
                return new AiResult(top.name, top.name, top.type, new ArrayList<>(), top);
            }
            return null;
        }
    }
}
