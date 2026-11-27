package io.mytv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Encapsulates multi-signal content similarity scoring and 1D seriation (Nearest-Neighbor Chain +
 * 2-Opt path refinement) for ordering the user's Watch List so similar series/movies sit side-by-side.
 *
 * <p>Why 1D seriation instead of single-genre bucketing:
 * Grouping strictly by primary genre produces abrupt category boundaries and splits multi-genre titles
 * arbitrarily. Building a continuous similarity chain bridges overlapping genres naturally (e.g.
 * Comedy -> Rom-Com -> Drama -> Crime Drama -> Sci-Fi Thriller -> Sci-Fi) while keeping medium
 * boundaries (Anime, Western Animation, Documentary, Live-Action) and synopsis themes cohesive.</p>
 */
final class WatchlistSimilaritySorter {

    private static final double WEIGHT_GENRES = 0.50;
    private static final double WEIGHT_MEDIUM = 0.18;
    private static final double WEIGHT_RUNTIME = 0.06;
    private static final double WEIGHT_KEYWORDS = 0.16;
    private static final double WEIGHT_LANGUAGE = 0.06;
    private static final double WEIGHT_ERA = 0.04;
    private static final double FRANCHISE_BONUS = 0.35;

    private static final double GENRE_SPEC_BROAD = 1.0;
    private static final double GENRE_SPEC_MEDIUM = 1.6;
    private static final double GENRE_SPEC_HIGH = 2.2;

    private static final double RUNTIME_DECAY_MINUTES = 25.0;
    private static final double ERA_DECAY_YEARS = 12.0;
    private static final int MIN_KEYWORD_LEN = 4;
    private static final int KEYWORD_DENOM_MIN = 3;
    private static final int KEYWORD_DENOM_CAP = 5;
    private static final int TWO_OPT_MAX_PASSES = 3;
    private static final double TWO_OPT_EPSILON = 1e-6;
    private static final double TIE_BREAK_ANCHOR_SCALE = 1e-4;

    private static final int MEDIUM_LIVE_ACTION = 0;
    private static final int MEDIUM_ANIMATION = 1;
    private static final int MEDIUM_ANIME = 2;
    private static final int MEDIUM_DOC_REALITY = 3;

    private static final Map<String, Double> GENRE_WEIGHTS = new HashMap<>();
    private static final Set<String> STOPWORDS = new HashSet<>();

    static {
        // Broad genres
        GENRE_WEIGHTS.put("drama", GENRE_SPEC_BROAD);
        GENRE_WEIGHTS.put("comedy", GENRE_SPEC_BROAD);
        GENRE_WEIGHTS.put("action", GENRE_SPEC_BROAD);
        GENRE_WEIGHTS.put("adventure", GENRE_SPEC_BROAD);

        // Medium-specificity genres
        GENRE_WEIGHTS.put("thriller", GENRE_SPEC_MEDIUM);
        GENRE_WEIGHTS.put("crime", 2.0);
        GENRE_WEIGHTS.put("romance", GENRE_SPEC_MEDIUM);
        GENRE_WEIGHTS.put("fantasy", GENRE_SPEC_MEDIUM);
        GENRE_WEIGHTS.put("mystery", GENRE_SPEC_MEDIUM);
        GENRE_WEIGHTS.put("family", GENRE_SPEC_MEDIUM);
        GENRE_WEIGHTS.put("animation", GENRE_SPEC_MEDIUM);
        GENRE_WEIGHTS.put("history", GENRE_SPEC_MEDIUM);
        GENRE_WEIGHTS.put("biography", GENRE_SPEC_MEDIUM);
        GENRE_WEIGHTS.put("music", GENRE_SPEC_MEDIUM);

        // High-specificity genres
        GENRE_WEIGHTS.put("sci-fi", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("science fiction", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("rom-coms", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("rom-com", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("horror", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("superhero", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("anime", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("k-drama", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("documentary", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("war & politics", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("western", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("sports", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("reality", GENRE_SPEC_HIGH);
        GENRE_WEIGHTS.put("stand-up & talk", GENRE_SPEC_HIGH);

        String[] stops = {
            "about", "above", "after", "again", "against", "along", "also", "amid", "among", "another",
            "around", "back", "because", "become", "becomes", "been", "before", "begin", "begins",
            "behind", "being", "between", "beyond", "both", "bring", "brings", "came", "come", "comes",
            "could", "days", "despite", "discover", "discovers", "does", "down", "during", "each",
            "even", "every", "everything", "face", "faces", "family", "film", "find", "finds", "first",
            "five", "follow", "following", "follows", "forced", "form", "former", "four", "friend",
            "friends", "from", "gets", "give", "gives", "goes", "going", "good", "group", "have",
            "having", "help", "helps", "here", "high", "himself", "herself", "home", "into", "itself",
            "just", "keep", "keeps", "know", "last", "later", "lead", "leads", "learn", "learns",
            "leave", "leaves", "left", "life", "like", "live", "lives", "living", "local", "long",
            "look", "looking", "looks", "lost", "love", "made", "make", "makes", "making", "many",
            "meet", "meets", "more", "most", "move", "moves", "movie", "much", "must", "name", "named",
            "need", "needs", "never", "next", "only", "open", "other", "others", "over", "part", "past",
            "people", "place", "play", "plays", "power", "pull", "pulls", "real", "return", "returns",
            "right", "same", "save", "search", "season", "second", "secret", "secrets", "seem", "seems",
            "send", "sends", "series", "sets", "seven", "several", "show", "shows", "side", "since",
            "small", "some", "someone", "something", "soon", "start", "starts", "still", "stop", "story",
            "strange", "struggle", "struggles", "take", "taken", "takes", "taking", "team", "tell",
            "tells", "than", "that", "their", "them", "themselves", "then", "there", "these", "they",
            "thing", "things", "think", "this", "those", "three", "through", "time", "times", "together",
            "town", "turn", "turns", "under", "unlikely", "until", "upon", "very", "want", "wants",
            "ways", "well", "were", "what", "when", "where", "which", "while", "whose", "will", "with",
            "within", "without", "woman", "women", "work", "working", "works", "world", "year", "years",
            "young"
        };
        Collections.addAll(STOPWORDS, stops);
    }

    private WatchlistSimilaritySorter() {}

    /** Precomputed feature vector for one {@link Recs.Pick} to avoid repeated string/set work during O(N^2) matrix build. */
    private static final class PickFeatures {
        final Recs.Pick pick;
        final String key;
        final String franchiseStem;
        final Set<String> genres;
        final Set<String> keywords;
        final int medium;
        final int runtime;
        final int year;
        final String lang;
        final double anchorScore;

        PickFeatures(Recs.Pick p) {
            this.pick = p;
            this.key = Recs.key(p);
            this.franchiseStem = extractFranchiseStem(p.title);
            this.genres = extractNormalizedGenres(p);
            this.keywords = extractContentKeywords(p.title, p.overview);
            this.medium = classifyMedium(p, this.genres);
            this.runtime = Math.max(0, p.runtime);
            this.year = parseYear(p.firstAirDate != null && !p.firstAirDate.isEmpty() ? p.firstAirDate : p.releaseDate);
            this.lang = p.lang == null ? "" : p.lang.trim().toLowerCase(Locale.ROOT);
            this.anchorScore = computeAnchorScore(p);
        }
    }

    /**
     * Computes symmetric pairwise similarity in {@code [0.0, 1.0]} between two media items
     * using genre specificity, medium compatibility, runtime format, synopsis keywords,
     * franchise stems, original language, and release era.
     */
    static double similarity(Recs.Pick a, Recs.Pick b) {
        if (a == null || b == null) return 0.0;
        if (a == b) return 1.0;
        return similarityFeatures(new PickFeatures(a), new PickFeatures(b));
    }

    /**
     * Orders a Watch List so unwatched items precede watched items, and items within each
     * partition sit next to their most similar neighbors.
     *
     * <ul>
     *   <li>When {@code customOrder} is empty, builds an optimal 1D similarity chain (Anchored
     *       Greedy Nearest-Neighbor + 2-Opt refinement).</li>
     *   <li>When {@code customOrder} is non-empty (user manually reordered cards), preserves the
     *       relative sequence of known items and slots any newly added items immediately adjacent
     *       to their most similar existing neighbor.</li>
     * </ul>
     */
    static List<Recs.Pick> sortWatchlist(List<Recs.Pick> items, List<String> customOrder) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        if (items.size() == 1) return new ArrayList<>(items);

        List<Recs.Pick> unwatched = new ArrayList<>();
        List<Recs.Pick> watched = new ArrayList<>();
        for (Recs.Pick p : items) {
            if (p == null) continue;
            if (p.watched) watched.add(p);
            else unwatched.add(p);
        }

        List<String> safeCustom = customOrder == null ? Collections.emptyList() : customOrder;
        List<Recs.Pick> orderedUnwatched = orderPartition(unwatched, safeCustom);
        List<Recs.Pick> orderedWatched = orderPartition(watched, safeCustom);

        List<Recs.Pick> result = new ArrayList<>(orderedUnwatched.size() + orderedWatched.size());
        result.addAll(orderedUnwatched);
        result.addAll(orderedWatched);
        return result;
    }

    private static List<Recs.Pick> orderPartition(List<Recs.Pick> partition, List<String> customOrder) {
        int n = partition.size();
        if (n <= 1) return new ArrayList<>(partition);

        List<PickFeatures> feats = new ArrayList<>(n);
        for (Recs.Pick p : partition) feats.add(new PickFeatures(p));

        if (!customOrder.isEmpty()) {
            Map<String, Integer> rank = new HashMap<>(customOrder.size() * 2);
            for (int i = 0; i < customOrder.size(); i++) {
                String k = customOrder.get(i);
                if (k != null && !k.isEmpty() && !rank.containsKey(k)) {
                    rank.put(k, i);
                }
            }
            List<PickFeatures> pinned = new ArrayList<>();
            List<PickFeatures> newcomers = new ArrayList<>();
            for (PickFeatures f : feats) {
                if (rank.containsKey(f.key)) pinned.add(f);
                else newcomers.add(f);
            }
            if (!pinned.isEmpty()) {
                pinned.sort((a, b) -> Integer.compare(rank.get(a.key), rank.get(b.key)));
                List<PickFeatures> orderedNew = buildSimilarityChain(newcomers);
                for (PickFeatures newcomer : orderedNew) {
                    insertAtMostSimilarNeighbor(pinned, newcomer);
                }
                List<Recs.Pick> out = new ArrayList<>(pinned.size());
                for (PickFeatures f : pinned) out.add(f.pick);
                return out;
            }
        }

        List<PickFeatures> chain = buildSimilarityChain(feats);
        List<Recs.Pick> out = new ArrayList<>(chain.size());
        for (PickFeatures f : chain) out.add(f.pick);
        return out;
    }

    /** Inserts a newly added Watch List item right beside its strongest existing neighbor, choosing left vs right for smoother transition. */
    private static void insertAtMostSimilarNeighbor(List<PickFeatures> chain, PickFeatures newcomer) {
        if (chain.isEmpty()) {
            chain.add(newcomer);
            return;
        }
        int bestIdx = 0;
        double bestSim = -1.0;
        for (int i = 0; i < chain.size(); i++) {
            double s = similarityFeatures(chain.get(i), newcomer);
            if (s > bestSim) {
                bestSim = s;
                bestIdx = i;
            }
        }
        // Decide whether inserting right BEFORE bestIdx or right AFTER bestIdx yields higher adjacent cohesion
        double gainBefore = bestSim;
        if (bestIdx > 0) {
            PickFeatures prev = chain.get(bestIdx - 1);
            gainBefore = similarityFeatures(prev, newcomer) + bestSim - similarityFeatures(prev, chain.get(bestIdx));
        }
        double gainAfter = bestSim;
        if (bestIdx + 1 < chain.size()) {
            PickFeatures next = chain.get(bestIdx + 1);
            gainAfter = bestSim + similarityFeatures(newcomer, next) - similarityFeatures(chain.get(bestIdx), next);
        }
        int insertPos = (gainBefore > gainAfter + TWO_OPT_EPSILON) ? bestIdx : (bestIdx + 1);
        chain.add(insertPos, newcomer);
    }

    /** Builds a 1D chain starting at the highest-affinity item via Greedy Nearest-Neighbor followed by 2-Opt path polish. */
    private static List<PickFeatures> buildSimilarityChain(List<PickFeatures> feats) {
        int n = feats.size();
        if (n <= 1) return new ArrayList<>(feats);

        double[][] sim = new double[n][n];
        for (int i = 0; i < n; i++) {
            sim[i][i] = 1.0;
            for (int j = i + 1; j < n; j++) {
                double s = similarityFeatures(feats.get(i), feats.get(j));
                sim[i][j] = s;
                sim[j][i] = s;
            }
        }

        // 1. Anchor index 0 with highest personal matchScore/rating item (deterministic key tie-break)
        int anchor = 0;
        for (int i = 1; i < n; i++) {
            if (compareAnchor(feats.get(i), feats.get(anchor)) < 0) {
                anchor = i;
            }
        }

        int[] order = new int[n];
        boolean[] used = new boolean[n];
        order[0] = anchor;
        used[anchor] = true;

        // 2. Greedy Nearest-Neighbor walk
        for (int step = 1; step < n; step++) {
            int curr = order[step - 1];
            int bestNext = -1;
            double bestScore = -1.0;
            for (int cand = 0; cand < n; cand++) {
                if (used[cand]) continue;
                double score = sim[curr][cand] + TIE_BREAK_ANCHOR_SCALE * feats.get(cand).anchorScore;
                if (bestNext < 0 || score > bestScore + 1e-9
                        || (Math.abs(score - bestScore) <= 1e-9 && feats.get(cand).key.compareTo(feats.get(bestNext).key) < 0)) {
                    bestScore = score;
                    bestNext = cand;
                }
            }
            order[step] = bestNext;
            used[bestNext] = true;
        }

        // 3. Bounded 2-Opt local refinement keeping index 0 pinned
        if (n >= 4) {
            for (int pass = 0; pass < TWO_OPT_MAX_PASSES; pass++) {
                boolean improved = false;
                for (int i = 1; i < n - 1; i++) {
                    for (int j = i + 1; j < n; j++) {
                        double currentEdges = sim[order[i - 1]][order[i]]
                                + (j + 1 < n ? sim[order[j]][order[j + 1]] : 0.0);
                        double swappedEdges = sim[order[i - 1]][order[j]]
                                + (j + 1 < n ? sim[order[i]][order[j + 1]] : 0.0);
                        if (swappedEdges > currentEdges + TWO_OPT_EPSILON) {
                            reverseRange(order, i, j);
                            improved = true;
                        }
                    }
                }
                if (!improved) break;
            }
        }

        List<PickFeatures> result = new ArrayList<>(n);
        for (int idx : order) result.add(feats.get(idx));
        return result;
    }

    private static void reverseRange(int[] arr, int left, int right) {
        while (left < right) {
            int tmp = arr[left];
            arr[left] = arr[right];
            arr[right] = tmp;
            left++;
            right--;
        }
    }

    private static int compareAnchor(PickFeatures a, PickFeatures b) {
        int cmp = Double.compare(b.anchorScore, a.anchorScore);
        if (cmp != 0) return cmp;
        return a.key.compareTo(b.key);
    }

    private static double similarityFeatures(PickFeatures a, PickFeatures b) {
        double genreSim = weightedGenreJaccard(a.genres, b.genres);
        double mediumSim = (a.medium == b.medium) ? 1.0 : 0.0;
        double runtimeSim = (a.runtime > 0 && b.runtime > 0)
                ? Math.exp(-Math.abs(a.runtime - b.runtime) / RUNTIME_DECAY_MINUTES)
                : 0.5;
        double keywordSim = keywordOverlap(a.keywords, b.keywords);
        double langSim = 0.0;
        if (!a.lang.isEmpty() && a.lang.equals(b.lang)) {
            langSim = "en".equals(a.lang) ? 0.5 : 1.0;
        }
        double eraSim = (a.year > 0 && b.year > 0)
                ? Math.exp(-Math.abs(a.year - b.year) / ERA_DECAY_YEARS)
                : 0.5;

        double total = WEIGHT_GENRES * genreSim
                + WEIGHT_MEDIUM * mediumSim
                + WEIGHT_RUNTIME * runtimeSim
                + WEIGHT_KEYWORDS * keywordSim
                + WEIGHT_LANGUAGE * langSim
                + WEIGHT_ERA * eraSim;

        if (!a.franchiseStem.isEmpty() && a.franchiseStem.equals(b.franchiseStem)) {
            total += FRANCHISE_BONUS;
        }
        return Math.max(0.0, Math.min(1.0, total));
    }

    private static double weightedGenreJaccard(Set<String> ga, Set<String> gb) {
        if (ga.isEmpty() || gb.isEmpty()) return 0.0;
        double inter = 0.0;
        double union = 0.0;
        for (String g : ga) {
            double w = genreWeight(g);
            union += w;
            if (gb.contains(g)) inter += w;
        }
        for (String g : gb) {
            if (!ga.contains(g)) union += genreWeight(g);
        }
        return union <= 0.0 ? 0.0 : (inter / union);
    }

    private static double genreWeight(String g) {
        Double w = GENRE_WEIGHTS.get(g);
        return w != null ? w : GENRE_SPEC_MEDIUM;
    }

    private static double keywordOverlap(Set<String> ka, Set<String> kb) {
        if (ka.isEmpty() || kb.isEmpty()) return 0.0;
        int shared = 0;
        Set<String> smaller = ka.size() <= kb.size() ? ka : kb;
        Set<String> larger = ka.size() <= kb.size() ? kb : ka;
        for (String token : smaller) {
            if (larger.contains(token)) shared++;
        }
        if (shared == 0) return 0.0;
        int denom = Math.max(KEYWORD_DENOM_MIN, Math.min(KEYWORD_DENOM_CAP, smaller.size()));
        return Math.min(1.0, (double) shared / denom);
    }

    private static Set<String> extractNormalizedGenres(Recs.Pick p) {
        Set<String> out = new HashSet<>();
        boolean hasComedy = false;
        boolean hasRomance = false;
        if (p.genres != null) {
            for (String g : p.genres) {
                if (g == null) continue;
                String norm = g.trim().toLowerCase(Locale.ROOT);
                if ("science fiction".equals(norm)) norm = "sci-fi";
                if ("rom-com".equals(norm)) norm = "rom-coms";
                if (!norm.isEmpty()) {
                    out.add(norm);
                    if ("comedy".equals(norm)) hasComedy = true;
                    if ("romance".equals(norm)) hasRomance = true;
                }
            }
        }
        if (p.isAnime) out.add("anime");
        if (hasComedy && hasRomance && !p.isAnime) {
            String lang = p.lang == null ? "en" : p.lang.toLowerCase(Locale.ROOT);
            if (!"ko".equals(lang) && !"ja".equals(lang) && !"zh".equals(lang)) {
                out.add("rom-coms");
            }
        }
        return out;
    }

    private static int classifyMedium(Recs.Pick p, Set<String> genres) {
        if (p.isAnime || genres.contains("anime")) return MEDIUM_ANIME;
        if (genres.contains("animation")) return MEDIUM_ANIMATION;
        if (genres.contains("documentary") || genres.contains("reality") || genres.contains("stand-up & talk")) {
            return MEDIUM_DOC_REALITY;
        }
        return MEDIUM_LIVE_ACTION;
    }

    private static Set<String> extractContentKeywords(String title, String overview) {
        Set<String> tokens = new HashSet<>();
        String combined = ((title == null ? "" : title) + " " + (overview == null ? "" : overview))
                .toLowerCase(Locale.ROOT);
        int len = combined.length();
        StringBuilder word = new StringBuilder(24);
        for (int i = 0; i <= len; i++) {
            char c = i < len ? combined.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                word.append(c);
            } else if (word.length() > 0) {
                String raw = word.toString();
                word.setLength(0);
                if (raw.length() >= MIN_KEYWORD_LEN && !STOPWORDS.contains(raw)) {
                    String stem = lightStem(raw);
                    if (stem.length() >= MIN_KEYWORD_LEN && !STOPWORDS.contains(stem)) {
                        tokens.add(stem);
                    }
                }
            }
        }
        return tokens;
    }

    private static String lightStem(String w) {
        if (w.length() >= 7 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() >= 6 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() >= 6 && w.endsWith("es")) return w.substring(0, w.length() - 2);
        if (w.length() >= 5 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    private static String extractFranchiseStem(String title) {
        if (title == null || title.isEmpty()) return "";
        String t = title.toLowerCase(Locale.ROOT).trim();
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        String base = (cut > 0 ? t.substring(0, cut) : t)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        // Strip trailing sequel markers ("toy story 3" -> "toy story", "rocky ii" -> "rocky") while keeping leading numbers ("1917", "3 body problem")
        base = base.replaceAll("\\s+(part\\s+[0-9ivx]+|vol\\s+[0-9ivx]+|[2-9]|ii|iii|iv|v)$", "").trim();
        return base.length() >= 4 ? base : "";
    }

    private static int parseYear(String dateStr) {
        if (dateStr == null || dateStr.length() < 4) return 0;
        for (int i = 0; i + 4 <= dateStr.length(); i++) {
            char c0 = dateStr.charAt(i);
            char c1 = dateStr.charAt(i + 1);
            char c2 = dateStr.charAt(i + 2);
            char c3 = dateStr.charAt(i + 3);
            if (c0 >= '1' && c0 <= '2' && Character.isDigit(c1) && Character.isDigit(c2) && Character.isDigit(c3)) {
                int y = (c0 - '0') * 1000 + (c1 - '0') * 100 + (c2 - '0') * 10 + (c3 - '0');
                if (y >= 1900 && y <= 2100) return y;
            }
        }
        return 0;
    }

    private static double computeAnchorScore(Recs.Pick p) {
        double rating10 = p.rating;
        if (p.imdbScore != null && !p.imdbScore.isEmpty()) {
            try {
                rating10 = Double.parseDouble(p.imdbScore.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return p.matchScore + 0.5 * rating10;
    }
}
