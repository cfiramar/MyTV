package io.soartv;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * TDD Verification Suite for the 5-Point Screensaver Follow-Up Specification:
 * 1. Pure 6-category 4K catalog (>= 205 items/cat) + full 205+ non-repeating random cycle.
 * 2. Repaired frosted Liquid Glass clock in both active DreamView and Settings preview.
 * 3. Eight curated horological/typographical clock designs with unique vector icons.
 * 4. Compact 104dp clock sizing (smaller than previous 136dp).
 * 5. Zero big white artwork titles; missing locations replaced by small artwork/subject names.
 */
public final class ScreensaverFollowUp5PointTests {

    private static int passed = 0;

    private ScreensaverFollowUp5PointTests() {}

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
    }

    private static void assertFalse(boolean cond, String msg) {
        if (cond) throw new AssertionError("FAIL: " + msg);
        passed++;
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if ((expected == null && actual != null) || (expected != null && !expected.equals(actual))) {
            throw new AssertionError("FAIL: " + msg + " | expected=[" + expected + "], actual=[" + actual + "]");
        }
        passed++;
    }

    private static File findFile(String relPath) {
        File f1 = new File(relPath);
        if (f1.exists()) return f1;
        File f2 = new File("SoarTV-launcher/" + relPath);
        if (f2.exists()) return f2;
        throw new AssertionError("Missing file: " + relPath);
    }

    private static String readSource(String relPath) throws Exception {
        return new String(Files.readAllBytes(findFile(relPath).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== STARTING 5-POINT SCREENSAVER FOLLOW-UP TDD SUITE ===");

        testPoint1_CatalogPurityAndFull205NonRepeatingCycle();
        testPoint2_RepairedLiquidGlassShaderAndPreview();
        testPoint3_EightCuratedClockDesignsAndIcons();
        testPoint4_SmallerClockDimensions();
        testPoint5_SmallMetadataReplacesMissingLocationAndZeroBigTitle();

        System.out.println("=== ALL " + passed + " 5-POINT SCREENSAVER FOLLOW-UP ASSERTIONS PASSED ===");
    }

    private static void testPoint1_CatalogPurityAndFull205NonRepeatingCycle() throws Exception {
        File catFile = findFile("app/assets/saver_catalog.json");
        String jsonText;
        try (InputStream in = new FileInputStream(catFile)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            jsonText = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
        Map<String, Object> root = CloudVaultCrypto.JsonCodec.parseJsonObject(jsonText);
        assertEquals(4, CloudVaultCrypto.JsonCodec.optInt(root, "version", 0), "Catalog version bumped to 4");
        List<?> items = (List<?>) root.get("items");
        assertEquals(1230, items.size(), "Catalog has 1,230 curated 4K items");

        Set<String> urls = new HashSet<>();
        int natureCount = 0;
        int artKanagawaCount = 0;
        for (Object raw : items) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            String id = CloudVaultCrypto.JsonCodec.optString(m, "id", "");
            String cat = CloudVaultCrypto.JsonCodec.optString(m, "category", "");
            String title = CloudVaultCrypto.JsonCodec.optString(m, "title", "");
            String url = CloudVaultCrypto.JsonCodec.optString(m, "imageUrl", "");
            int w = CloudVaultCrypto.JsonCodec.optInt(m, "w", 0);
            int h = CloudVaultCrypto.JsonCodec.optInt(m, "h", 0);

            assertTrue(ScreensaverCatalog.isTrue4kDimensions(w, h), "True 4K dimensions for " + id);
            assertTrue(urls.add(url), "Globally unique 4K URL for " + id);
            assertFalse(title.contains("title QS:"), "Zero Wikidata QS markup in " + id + ": " + title);

            String blob = (title + " " + url).toLowerCase(Locale.ROOT);
            if ("nature".equals(cat)) {
                natureCount++;
                assertFalse(blob.contains("kanagawa") || blob.contains("hokusai"),
                        "Nature must NEVER contain Hokusai / Great Wave (" + id + ")");
                assertFalse(blob.contains("pave_hawk") || blob.contains("helicopter"),
                        "Nature must NEVER contain helicopters (" + id + ")");
                assertFalse(blob.contains("rushmore"),
                        "Nature must NEVER contain Mount Rushmore (" + id + ")");
            }
            if ("space".equals(cat)) {
                assertFalse(blob.contains("bmw") || blob.contains("ponies"),
                        "Space must NEVER contain cars or ponies (" + id + ")");
            }
            if ("macro".equals(cat)) {
                assertFalse(blob.contains("van_gogh") || blob.contains("1cm3"),
                        "Macro must NEVER contain paintings or 1cm3 lab cubes (" + id + ")");
            }
            if ("paintings".equals(cat)) {
                assertFalse(blob.contains("autopsy"),
                        "Paintings must NEVER contain autopsy (" + id + ")");
            }
            if ("art".equals(cat) && blob.contains("kanagawa")) {
                artKanagawaCount++;
            }
        }
        assertEquals(205, natureCount, "Nature has exactly 205 pure 4K entries");
        assertEquals(1, artKanagawaCount, "Great Wave off Kanagawa appears once in 'art' only");

        // Verify full 205-entry pool is exposed to ShuffleBag (never capped at 12 cached files)
        List<ScreensaverCatalog.PhotoEntry> natureEntries =
                ScreensaverCatalog.catalogForCategories(null, Collections.singleton("nature"));
        assertEquals(205, natureEntries.size(), "catalogForCategories('nature') returns all 205 entries");

        List<Recs.Pick> fullDeck = ScreensaverCatalog.picksForEntries(
                natureEntries, new File(System.getProperty("java.io.tmpdir"), "soartv_test_saver_v4"));
        assertEquals(205, fullDeck.size(), "picksForEntries exposes all 205 slides to ShuffleBag even before background prefetch completes");

        DreamView.ShuffleBag<Recs.Pick> bag = new DreamView.ShuffleBag<>(new Random(20261106L));
        bag.setPool(fullDeck);
        assertEquals(205, bag.size(), "ShuffleBag initialized with all 205 nature slides");

        Set<String> seenInSingleCycle = new HashSet<>();
        String prevKey = null;
        for (int i = 0; i < 205; i++) {
            Recs.Pick drawn = (i % 3 == 0)
                    ? bag.drawPreferringCached(Collections.emptySet(), prevKey)
                    : bag.draw(Collections.emptySet(), prevKey);
            assertTrue(drawn != null, "Drawn slide at step " + i + " is non-null");
            String key = Recs.key(drawn);
            assertTrue(seenInSingleCycle.add(key),
                    "Zero repeats allowed within 205-slide cycle (duplicate at step " + i + ": " + key + ")");
            assertTrue(drawn.imageUrl != null && drawn.imageUrl.startsWith("https://"),
                    "Pick carries remote 4K imageUrl for on-demand caching");
            prevKey = key;
        }
        assertEquals(205, seenInSingleCycle.size(), "Visited all 205 unique nature slides before any repeat");
    }

    private static void testPoint2_RepairedLiquidGlassShaderAndPreview() throws Exception {
        String shader = Shaders.GLASS_TEXT;
        assertTrue(shader.contains("frost"), "GLASS_TEXT applies luminous frosted-crystal glass veil");
        assertFalse(shader.contains("smoothstep(0.5, 0.6, height)"),
                "GLASS_TEXT eliminates harsh 0.5..0.6 height cutoff that turned thin strokes into solid white slabs");

        String dreamSrc = readSource("app/src/io/soartv/DreamView.java");
        assertFalse(dreamSrc.contains("Math.round(6 * k)), 3"),
                "DreamView.makeGlass eliminates destructive 6*k 3-pass height blur on compact digits");
        assertTrue(dreamSrc.contains("1f - glyphA"),
                "DreamView.makeGlass masks interior glyph alpha out of drop shadow so crystal digits stay luminous");

        String rendererSrc = readSource("app/src/io/soartv/SettingsScreenRenderer.java");
        assertFalse(rendererSrc.contains("clockY + 3 * k"),
                "SettingsScreenRenderer eliminates blurry offset gray duplicate text in Liquid Glass preview");
        assertTrue(rendererSrc.contains("resolveGalleryPreviewBitmap"),
                "SettingsScreenRenderer renders Clock Style preview over real 4K starter photography");
    }

    private static void testPoint3_EightCuratedClockDesignsAndIcons() {
        List<String> styles = Arrays.asList(Prefs.SAVER_CLOCK_STYLES);
        assertEquals(8, styles.size(), "Prefs.SAVER_CLOCK_STYLES exposes 8 curated clock designs");
        String[] expected = {
                "liquid_glass", "frosted_pill", "stacked", "split_flap",
                "modern_serif", "digital_mono", "minimal", "outline"
        };
        Set<String> uniqueIcons = new HashSet<>();
        for (String st : expected) {
            assertTrue(styles.contains(st), "Contains curated clock design: " + st);
            String icon = SettingsTree.iconForClockStyle(st);
            assertTrue(Symbols.isSupportedIcon(icon), "Icon '" + icon + "' for '" + st + "' is supported in Symbols");
            assertTrue(uniqueIcons.add(icon), "Icon '" + icon + "' for '" + st + "' is 100% unique");
            String title = SettingsScreenRenderer.clockStylePreviewTitle(st);
            String desc = SettingsScreenRenderer.clockStylePreviewDescription(st);
            assertTrue(title != null && !title.isEmpty(), "Preview title non-empty for " + st);
            assertTrue(desc != null && !desc.isEmpty(), "Preview description non-empty for " + st);
        }

        // Backward compatibility for legacy saved styles and null safety in SettingsTree
        String prev = Prefs.saverClockStyle();
        try {
            Prefs.setSaverClockStyle("editorial");
            assertEquals("stacked", Prefs.saverClockStyle(), "Legacy 'editorial' migrates cleanly to 'stacked'");
            Prefs.setSaverClockStyle("classic");
            assertEquals("frosted_pill", Prefs.saverClockStyle(), "Legacy 'classic' migrates cleanly to 'frosted_pill'");
            Prefs.setSaverClockStyle("corrupt_style_999");
            assertEquals("liquid_glass", Prefs.saverClockStyle(), "Unknown style falls back to 'liquid_glass'");
        } finally {
            Prefs.setSaverClockStyle(prev);
        }

        SettingsTree.StateSnapshot nullStyleSnap = new SettingsTree.StateSnapshot();
        nullStyleSnap.saverClockStyle = null;
        SettingsTree.Node tree = SettingsTree.buildScreensaverTree(nullStyleSnap);
        assertTrue(tree != null, "SettingsTree.buildScreensaverTree handles null saverClockStyle without NPE");
    }

    private static void testPoint4_SmallerClockDimensions() throws Exception {
        Field clockSizeField = DreamView.class.getDeclaredField("CLOCK_SIZE");
        clockSizeField.setAccessible(true);
        float clockSize = clockSizeField.getFloat(null);
        assertEquals(104f, clockSize, "DreamView.CLOCK_SIZE is shrunk to compact 104dp (208px on 4K)");

        Field dateBaseField = DreamView.class.getDeclaredField("DATE_BASE");
        dateBaseField.setAccessible(true);
        float dateBase = dateBaseField.getFloat(null);

        Field clockTopField = DreamView.class.getDeclaredField("CLOCK_TOP");
        clockTopField.setAccessible(true);
        float clockTop = clockTopField.getFloat(null);

        assertTrue(dateBase < clockTop, "Date baseline sits cleanly above clock top");
        assertEquals(28f, clockTop - dateBase, "Clean 28dp vertical spacing preserved between date and clock");
    }

    private static void testPoint5_SmallMetadataReplacesMissingLocationAndZeroBigTitle() throws Exception {
        // 1. Big white artwork title MUST be suppressed across ALL categories
        for (String cat : new String[]{"nature", "urban", "macro", "space", "paintings", "art"}) {
            assertEquals("", ScreensaverCatalog.buildCaptionTitle(cat, "The Starry Night", true),
                    "buildCaptionTitle must return empty string for category '" + cat + "' (no big white title)");
        }

        // 2. Subject / locationless items replace location with small artwork/subject name
        assertEquals("Vincent van Gogh \u00B7 The Starry Night",
                ScreensaverCatalog.buildCaptionMeta("paintings", "The Starry Night", "Vincent van Gogh", "", "", true, true),
                "Paintings shows artist and artwork name in small metadata line");

        assertEquals("Katsushika Hokusai \u00B7 The Great Wave off Kanagawa",
                ScreensaverCatalog.buildCaptionMeta("art", "The Great Wave off Kanagawa", "Katsushika Hokusai", "", "", true, true),
                "Art shows artist and artwork name in small metadata line");

        assertEquals("NASA / ESA \u00B7 Cosmic Cliffs in the Carina Nebula",
                ScreensaverCatalog.buildCaptionMeta("space", "Cosmic Cliffs in the Carina Nebula", "NASA / ESA", "", "", true, true),
                "Space agency credit + subject name in small metadata line");

        assertEquals("Photo by Alexey Kljatov \u00B7 Snowflake Crystal",
                ScreensaverCatalog.buildCaptionMeta("macro", "Snowflake Crystal", "Alexey Kljatov", "", "", true, true),
                "Macro photographer + subject name in small metadata line");

        // 3. Nature / Urban with location shows location (not title); without location falls back to title!
        assertEquals("Photo by Luca Bravo \u00B7 Lago di Braies, Italy",
                ScreensaverCatalog.buildCaptionMeta("nature", "Alpine Dawn", "Luca Bravo", "Lago di Braies, Italy", "", true, true),
                "Nature with location displays photographer + location");

        assertEquals("Photo by Martin Falbisoner \u00B7 Hverfjall Volcanic Crater",
                ScreensaverCatalog.buildCaptionMeta("nature", "Hverfjall Volcanic Crater", "Martin Falbisoner", "", "", true, true),
                "Nature without location replaces missing location with photo subject name");

        assertEquals("Photo by Basile Morin \u00B7 Marina Bay Sands, Singapore",
                ScreensaverCatalog.buildCaptionMeta("urban", "Night Skyline", "Basile Morin", "Singapore", "Marina Bay Sands", true, true),
                "Urban with building + location displays building, location");

        // 4. Toggling showLocation=false hides both geographic location and replacement artwork name
        assertEquals("Vincent van Gogh",
                ScreensaverCatalog.buildCaptionMeta("paintings", "The Starry Night", "Vincent van Gogh", "", "", true, false),
                "Disabling Location / Artwork Name hides artwork name replacement");

        assertEquals("The Starry Night",
                ScreensaverCatalog.buildCaptionMeta("paintings", "The Starry Night", "Vincent van Gogh", "", "", false, true),
                "Disabling Artist Name leaves clean artwork name without leading separator");

        // 5. DreamView.resolveLiveTitle returns empty and resolveLiveMeta returns small single-line meta
        Method resolveTitle = DreamView.class.getDeclaredMethod("resolveLiveTitle", Recs.Pick.class);
        resolveTitle.setAccessible(true);
        Method resolveMeta = DreamView.class.getDeclaredMethod("resolveLiveMeta", Recs.Pick.class);
        resolveMeta.setAccessible(true);

        Recs.Pick p = new Recs.Pick();
        p.photo = new File("dummy.jpg");
        p.category = "space";
        p.rawTitle = "Pillars of Creation";
        p.rawArtist = "NASA / ESA / CSA";
        p.rawLocation = "";

        assertEquals("", resolveTitle.invoke(null, p), "DreamView.resolveLiveTitle returns empty string");
        assertEquals("NASA / ESA / CSA \u00B7 Pillars of Creation", resolveMeta.invoke(null, p),
                "DreamView.resolveLiveMeta formats small single-line credit + artwork name");
    }
}
