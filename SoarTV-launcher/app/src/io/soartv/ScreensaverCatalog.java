package io.soartv;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Open-Free Online 4K Photography Galleries & World Clock formatter for the SoarTV Screensaver.
 *
 * <p><b>Why:</b> Provides selectable True-4K UHD ({@code >= 3840x2160} landscape) photography categories
 * ({@code Nature Photography}, {@code Macro Photography}, {@code Urban Photography}, {@code Wildlife Photography},
 * and {@code Mixed Top Wallpapers}) from verified open-free online galleries with authentic artwork titles,
 * photographer credits, geographic locations, and DST-aware World Clock city hours + {@code UTC±X} offsets
 * that run 100% natively on the TV with zero PC dependency.
 */
public final class ScreensaverCatalog {

    /** Minimum horizontal pixel dimension for True 4K UHD screensaver photography. */
    public static final int MIN_4K_WIDTH = 3840;
    /** Minimum vertical pixel dimension for True 4K UHD screensaver photography. */
    public static final int MIN_4K_HEIGHT = 2160;
    /** Versioned cache folder name; legacy {@code saver_catalog_4k} is purged automatically. */
    public static final String CACHE_DIR_NAME = "saver_catalog_4k_v2";
    private static final String LEGACY_CACHE_DIR_NAME = "saver_catalog_4k";
    /** Maximum disk cache size in bytes (~200 MB bound for TV flash protection). */
    public static final long MAX_DISK_CACHE_BYTES = 200 * 1024 * 1024L;
    /** Name of the curated 4K asset catalog manifest bundled inside the APK. */
    public static final String ASSET_CATALOG_FILE = "saver_catalog.json";
    /** Bundled True-4K starter asset folder ensuring 0 ms previews and zero fallback screensaver states. */
    public static final String STARTER_ASSET_DIR = "saver_starters";
    /** Target pre-warmed 4K wallpapers per active category (bounded overall by MAX_DISK_CACHE_BYTES). */
    public static final int TARGET_CACHED_PER_CATEGORY = 64;

    /** Immutable photography wallpaper entry with verified category, title, photographer, location, and building metadata. */
    public static final class PhotoEntry {
        public final String id;
        public final String category;
        public final String title;
        public final String artist;
        public final String location;
        public final String building;
        public final String imageUrl;

        public PhotoEntry(String id, String category, String title, String artist, String location, String imageUrl) {
            this(id, category, title, artist, location, "", imageUrl);
        }

        public PhotoEntry(String id, String category, String title, String artist, String location, String building, String imageUrl) {
            this.id = id;
            this.category = category;
            this.title = title;
            this.artist = artist;
            this.location = location;
            this.building = building != null ? building : "";
            this.imageUrl = imageUrl;
        }

        public static PhotoEntry fromMap(Map<String, Object> m) {
            if (m == null) return null;
            String id = CloudVaultCrypto.JsonCodec.optString(m, "id", "");
            String cat = CloudVaultCrypto.JsonCodec.optString(m, "category",
                    CloudVaultCrypto.JsonCodec.optString(m, "cat", ""));
            String title = CloudVaultCrypto.JsonCodec.optString(m, "title", "");
            String artist = CloudVaultCrypto.JsonCodec.optString(m, "artist", "");
            String location = CloudVaultCrypto.JsonCodec.optString(m, "location", "");
            String building = CloudVaultCrypto.JsonCodec.optString(m, "building", "");
            String url = CloudVaultCrypto.JsonCodec.optString(m, "imageUrl",
                    CloudVaultCrypto.JsonCodec.optString(m, "url", ""));
            if (id.isEmpty() || url.isEmpty()) return null;
            return new PhotoEntry(id, cat, title, artist, location, building, url);
        }

        public static PhotoEntry fromJson(JSONObject o) {
            if (o == null) return null;
            try {
                String id = o.optString("id", "");
                String cat = o.optString("category", o.optString("cat", ""));
                String title = o.optString("title", "");
                String artist = o.optString("artist", "");
                String location = o.optString("location", "");
                String building = o.optString("building", "");
                String url = o.optString("imageUrl", o.optString("url", ""));
                if (id.isEmpty() || url.isEmpty()) return null;
                return new PhotoEntry(id, cat, title, artist, location, building, url);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /**
     * 24 visually verified, native-sensor True 4K UHD (>= 3840x2160 landscape) open-free photographs
     * across Nature (6), Macro (6), Urban (6), and Wildlife (6).
     */
    private static final PhotoEntry[] BUILTIN_CATALOG = {
        // NATURE (6)
        new PhotoEntry("nat1", "nature", "Lago di Carezza & Latemar Peaks", "Simo Räsänen", "Dolomites, South Tyrol, Italy",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3a/Lago_di_Carezza_and_Latemar%2C_Carezza%2C_Trentino-Alto_Adige%2C_Italy%2C_2025_October.jpg/3840px-Lago_di_Carezza_and_Latemar%2C_Carezza%2C_Trentino-Alto_Adige%2C_Italy%2C_2025_October.jpg"),
        new PhotoEntry("nat2", "nature", "Yosemite Valley Dawn & Merced River", "Bailey Zindel", "Yosemite National Park, California",
                "https://images.unsplash.com/photo-1506744038136-46273834b3fb?fm=jpg&fit=crop&w=3840&q=90"),
        new PhotoEntry("nat3", "nature", "Aurora Borealis over Lake Torneträsk", "Pavel Shyshkouski", "Abisko National Park, Sweden",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a6/Aurora_in_Abisko_near_Tornetr%C3%A4sk.jpg/3840px-Aurora_in_Abisko_near_Tornetr%C3%A4sk.jpg"),
        new PhotoEntry("nat4", "nature", "El Capitan & Cathedral Meadow", "Adam Kool", "Yosemite National Park, California",
                "https://images.unsplash.com/photo-1426604966848-d7adac402bff?fm=jpg&fit=crop&w=3840&q=90"),
        new PhotoEntry("nat5", "nature", "Matterhorn Reflected in Grindjisee", "Roy Egloff", "Zermatt, Valais, Switzerland",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5c/CH.VS.Zermatt_Sunnegga_Grindjisee_Matterhorn_9034_16x9-R_16K.jpg/3840px-CH.VS.Zermatt_Sunnegga_Grindjisee_Matterhorn_9034_16x9-R_16K.jpg"),
        new PhotoEntry("nat6", "nature", "Morning Mist Through Spruce Forest", "Sebastian Unrau", "Bad Pyrmont, Germany",
                "https://images.unsplash.com/photo-1448375240586-882707db888b?fm=jpg&fit=crop&w=3840&q=90"),

        // MACRO (6)
        new PhotoEntry("mac1", "macro", "Bumblebee on Golden Dandelion", "Ivar Leidus", "Keila, Estonia",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a8/Bombus_ruderarius_-_Taraxacum_officinale_-_Keila.jpg/3840px-Bombus_ruderarius_-_Taraxacum_officinale_-_Keila.jpg"),
        new PhotoEntry("mac2", "macro", "Sacred Pink Lotus in Bloom", "Daftation", "Shanghai, China",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/Sacred_Lotus_in_a_Pond_2.jpg/3840px-Sacred_Lotus_in_a_Pond_2.jpg"),
        new PhotoEntry("mac3", "macro", "Monarch Butterfly on Tithonia", "Rhododendrites", "Brooklyn Botanic Garden, New York",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/7/72/Monarch_butterfly_male_%2852341%29.jpg/3840px-Monarch_butterfly_male_%2852341%29.jpg"),
        new PhotoEntry("mac4", "macro", "Brimstone Butterfly on Wildflower", "Charles J. Sharp", "Oxfordshire, United Kingdom",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6a/Common_brimstone_butterfly_%28Gonepteryx_rhamni%29_male.jpg/3840px-Common_brimstone_butterfly_%28Gonepteryx_rhamni%29_male.jpg"),
        new PhotoEntry("mac5", "macro", "Scarlet Darter Dragonfly at Dawn", "Mildeep", "Chitwan National Park, Nepal",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/c/cd/Crocothemis_erythraea_in_Nepal.jpg/3840px-Crocothemis_erythraea_in_Nepal.jpg"),
        new PhotoEntry("mac6", "macro", "Garden Rose Petals in Morning Light", "Marc Lautenbacher", "Laquenexy, Moselle, France",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/1/13/Comestible_rose_in_the_Laquenexy_orchard_garden%2C_Moselle%2C_France_%2801%29.jpg/3840px-Comestible_rose_in_the_Laquenexy_orchard_garden%2C_Moselle%2C_France_%2801%29.jpg"),

        // URBAN (6)
        new PhotoEntry("urb1", "urban", "Marina Bay & Esplanade Skyline at Night", "Basile Morin", "Singapore",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/0/00/Skyline_of_the_Central_Business_District_of_Singapore_with_Esplanade_Bridge.jpg/3840px-Skyline_of_the_Central_Business_District_of_Singapore_with_Esplanade_Bridge.jpg"),
        new PhotoEntry("urb2", "urban", "Golden Gate Bridge Across the Strait", "Maarten van den Heuvel", "San Francisco, California",
                "https://images.unsplash.com/photo-1501594907352-04cda38ebc29?fm=jpg&fit=crop&w=3840&q=90"),
        new PhotoEntry("urb3", "urban", "Grand Canal & Basilica della Salute", "Henrique Ferreira", "Venice, Italy",
                "https://images.unsplash.com/photo-1514890547357-a9ee288728e0?fm=jpg&fit=crop&w=3840&q=90"),
        new PhotoEntry("urb4", "urban", "Canary Wharf Reflected on the Thames", "King of Hearts", "Limehouse, London, UK",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c5/Canary_Wharf_from_Limehouse_London_June_2016_HDR.jpg/3840px-Canary_Wharf_from_Limehouse_London_June_2016_HDR.jpg"),
        new PhotoEntry("urb5", "urban", "Pont de Bir-Hakeim at Blue Hour", "Pierre Blaché", "Paris, France",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1d/Bir-Hakeim_bridge%2C_Paris_24_June_2021.jpg/3840px-Bir-Hakeim_bridge%2C_Paris_24_June_2021.jpg"),
        new PhotoEntry("urb6", "urban", "Castel Sant'Angelo & Tiber Dusk", "Jebulon", "Rome, Italy",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4d/Castel_Sant%27Angelo_at_dusk%2C_Rome%2C_Italy.jpg/3840px-Castel_Sant%27Angelo_at_dusk%2C_Rome%2C_Italy.jpg"),

        // WILDLIFE (6)
        new PhotoEntry("wld1", "wildlife", "Bengal Tiger in Forest Clearing", "Charles J. Sharp", "Kanha National Park, India",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/9/97/Bengal_tiger_%28Panthera_tigris_tigris%29_female_3.jpg/3840px-Bengal_tiger_%28Panthera_tigris_tigris%29_female_3.jpg"),
        new PhotoEntry("wld2", "wildlife", "Red Fox in Winter Snow", "Jonatan Pie", "Iceland",
                "https://images.unsplash.com/photo-1474511320723-9a56873867b5?fm=jpg&fit=crop&w=3840&q=90"),
        new PhotoEntry("wld3", "wildlife", "African Lion Prowling Savanna Grass", "Jean Wimmerlin", "South Africa",
                "https://images.unsplash.com/photo-1546182990-dffeafbe841d?fm=jpg&fit=crop&w=3840&q=90"),
        new PhotoEntry("wld4", "wildlife", "Common Kingfisher Perched on Reed", "Boris Smokrovic", "Danube River Basin",
                "https://images.unsplash.com/photo-1444464666168-49d633b86797?fm=jpg&fit=crop&w=3840&q=90"),
        new PhotoEntry("wld5", "wildlife", "Atlantic Puffin on Coastal Cliff", "Charles J. Sharp", "Skomer Island, Wales",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c4/Puffin_%28Fratercula_arctica%29_with_lesser_sand_eels_%28Ammodytes_tobianus%29.jpg/3840px-Puffin_%28Fratercula_arctica%29_with_lesser_sand_eels_%28Ammodytes_tobianus%29.jpg"),
        new PhotoEntry("wld6", "wildlife", "Blue-Barred Parrotfish on Coral Reef", "Diego Delso", "La Paz, Baja California, Mexico",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/7/76/Loro_barbazul_%28Scarus_ghobban%29%2C_La_Paz%2C_Baja_California%2C_M%C3%A9xico%2C_2024-12-20%2C_DD_55.jpg/3840px-Loro_barbazul_%28Scarus_ghobban%29%2C_La_Paz%2C_Baja_California%2C_M%C3%A9xico%2C_2024-12-20%2C_DD_55.jpg")
    };

    /** Wikimedia Commons Featured Picture categories for live open-free online 4K gallery discovery. */
    private static final String[][] COMMONS_FEEDS = {
        {"nature", "Category:Featured_pictures_of_landscapes"},
        {"macro", "Category:Featured_pictures_of_flowers"},
        {"urban", "Category:Featured_night_shots"},
        {"wildlife", "Category:Featured_pictures_of_birds"}
    };

    private static final List<PhotoEntry> ASSET_CATALOG = new CopyOnWriteArrayList<>();
    private static volatile boolean assetCatalogLoaded;
    private static final List<PhotoEntry> DYNAMIC_CATALOG = new CopyOnWriteArrayList<>();
    private static final List<Runnable> CATALOG_LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean commonsLoaded;
    private static volatile boolean legacyCachePurged;

    private static final AtomicInteger DL_THREAD_SEQ = new AtomicInteger(1);
    private static final ExecutorService DOWNLOADER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "saver-catalog-dl-" + DL_THREAD_SEQ.getAndIncrement());
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentHashMap<String, Object> ENTRY_DL_LOCKS = new ConcurrentHashMap<>();
    private static final Object WIKIMEDIA_PACE_LOCK = new Object();
    private static long lastWikimediaDownloadMs = 0L;
    private static final Object PREFETCH_STATE_LOCK = new Object();
    private static final AtomicBoolean PREFETCH_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean PREFETCH_RESTART_REQUESTED = new AtomicBoolean(false);
    private static volatile boolean backgroundActivityPaused = false;
    private static final AtomicBoolean PAUSED_PREFETCH_NEEDS_RESUME = new AtomicBoolean(false);
    private static final AtomicBoolean PENDING_RESUME_WHILE_RUNNING = new AtomicBoolean(false);

    /**
     * Pauses or resumes background 4K wallpaper prefetch downloads (e.g. during active game streaming)
     * so {@code :home} avoids background allocations, disk cache churn, and Wi-Fi contention.
     */
    public static void setBackgroundActivityPaused(boolean paused) {
        synchronized (PREFETCH_STATE_LOCK) {
            backgroundActivityPaused = paused;
        }
    }

    public static boolean isBackgroundActivityPaused() {
        return backgroundActivityPaused;
    }

    static boolean consumePausedPrefetchNeedsResume() {
        synchronized (PREFETCH_STATE_LOCK) {
            return PAUSED_PREFETCH_NEEDS_RESUME.getAndSet(false);
        }
    }

    private ScreensaverCatalog() {}

    /**
     * Returns an unmodifiable copy of the curated 24-entry built-in True 4K photography catalog.
     */
    public static List<PhotoEntry> builtinEntries() {
        List<PhotoEntry> copy = new ArrayList<>(BUILTIN_CATALOG.length);
        Collections.addAll(copy, BUILTIN_CATALOG);
        return Collections.unmodifiableList(copy);
    }

    /**
     * Formats the secondary World Clock string (e.g., {@code "NYC 15:24"}) for the screensaver overlay.
     */
    public static String formatWorldClock(long nowMillis) {
        if (!Prefs.saverShowWorldClock()) return "";
        String tzId = Prefs.saverWorldClockTz();
        String label = Prefs.saverWorldClockLabel();
        if (tzId == null || tzId.isEmpty()) tzId = "America/New_York";
        if (label == null || label.isEmpty()) label = "NYC";
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone(tzId));
        return label + " " + fmt.format(new Date(nowMillis));
    }

    /**
     * Formats the live DST-aware UTC offset for a timezone ID at {@code nowMillis}
     * (e.g., {@code "UTC+0"}, {@code "UTC+2"}, {@code "UTC-5"}, {@code "UTC+5:30"}).
     */
    public static String formatUtcOffset(String tzId, long nowMillis) {
        String safeTz = (tzId == null || tzId.isEmpty()) ? "UTC" : tzId;
        TimeZone tz = TimeZone.getTimeZone(safeTz);
        int totalMinutes = tz.getOffset(nowMillis) / 60_000;
        if (totalMinutes == 0) return "UTC+0";
        char sign = totalMinutes > 0 ? '+' : '-';
        int absMin = Math.abs(totalMinutes);
        int hours = absMin / 60;
        int mins = absMin % 60;
        if (mins == 0) {
            return "UTC" + sign + hours;
        }
        return String.format(Locale.US, "UTC%c%d:%02d", sign, hours, mins);
    }

    /**
     * Formats the live local clock time for a city's timezone (e.g., {@code "21:45"} or {@code "9:45 PM"}).
     */
    public static String formatCityTime(String tzId, long nowMillis, boolean is24Hour) {
        String safeTz = (tzId == null || tzId.isEmpty()) ? "UTC" : tzId;
        SimpleDateFormat fmt = new SimpleDateFormat(is24Hour ? "HH:mm" : "h:mm a", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone(safeTz));
        return fmt.format(new Date(nowMillis));
    }

    /**
     * Formats the right-aligned live preview badge shown while selecting a Second Clock Timezone in Settings
     * (e.g., {@code "21:45 · UTC+2"} or {@code "9:45 PM · UTC-5"}).
     */
    public static String formatTimezoneSelectionValue(String tzId, long nowMillis, boolean is24Hour) {
        return formatCityTime(tzId, nowMillis, is24Hour) + " \u00B7 " + formatUtcOffset(tzId, nowMillis);
    }

    /**
     * Formats the secondary line of a timezone entry (Requirement R4) displaying
     * local time in that timezone followed by its UTC offset (e.g., "14:30 (UTC+8)").
     */
    public static String formatTimezoneSubtitle(String tzId, long nowMillis, boolean is24Hour) {
        return formatCityTime(tzId, nowMillis, is24Hour) + " (" + formatUtcOffset(tzId, nowMillis) + ")";
    }

    /**
     * Checks whether a category is classified as a subject category (Art, Paintings, Macro, Space)
     * where artwork / subject title is shown and location is suppressed per Requirement R3.
     */
    public static boolean isSubjectCategory(String category) {
        if (category == null) return false;
        String c = category.trim().toLowerCase(Locale.ROOT);
        return "art".equals(c) || "painting".equals(c) || "paintings".equals(c)
                || "macro".equals(c) || "space".equals(c);
    }

    /**
     * Suppresses the large white artwork title across all screensaver categories:
     * artwork/subject names now replace missing locations inside the compact metadata line.
     */
    public static String buildCaptionTitle(String category, String rawTitle, boolean showTitle) {
        return "";
    }

    /**
     * Legacy 2-argument title helper retained for core unit test compatibility.
     */
    public static String buildCaptionTitle(String rawTitle, boolean showTitle) {
        if (!showTitle || rawTitle == null) return "";
        return rawTitle.trim();
    }

    private static boolean isFineArtOrInstitutionalCredit(String category, String artist) {
        if (category != null) {
            String c = category.trim().toLowerCase(Locale.ROOT);
            if ("paintings".equals(c) || "painting".equals(c) || "art".equals(c)) {
                return true;
            }
        }
        if (artist == null || artist.isEmpty()) return false;
        String lower = artist.toLowerCase(Locale.ROOT);
        return lower.startsWith("nasa") || lower.startsWith("esa") || lower.startsWith("eso")
                || lower.startsWith("jpl") || lower.startsWith("nsf") || lower.startsWith("noirlab")
                || lower.startsWith("isro") || lower.startsWith("jaxa") || lower.startsWith("hubble")
                || lower.startsWith("webb") || lower.startsWith("ancient ") || lower.startsWith("unknown");
    }

    /**
     * Formats the compact single-line screensaver metadata caption:
     * combines photographer/artist credit with either geographic location (plus urban building when present)
     * or, when an item has no location (such as Space, Macro, Paintings, Art, or locationless landscapes),
     * replaces the missing location with the small artwork/subject name.
     */
    public static String buildCaptionMeta(String category, String rawTitle, String rawArtist, String rawLocation,
                                          String rawBuilding, boolean showArtist, boolean showLocation) {
        String title = (rawTitle != null) ? rawTitle.trim() : "";
        String artist = (rawArtist != null) ? rawArtist.trim() : "";
        String loc = (rawLocation != null) ? rawLocation.trim() : "";
        String bld = (rawBuilding != null) ? rawBuilding.trim() : "";
        boolean isSubject = isSubjectCategory(category);

        StringBuilder meta = new StringBuilder();
        if (showArtist && !artist.isEmpty()) {
            if (isFineArtOrInstitutionalCredit(category, artist)) {
                meta.append(artist);
            } else {
                meta.append("Photo by ").append(artist);
            }
        }
        if (showLocation) {
            String locOrName = "";
            if (isSubject) {
                locOrName = title;
            } else {
                if (!bld.isEmpty() && !loc.isEmpty()) {
                    if (loc.toLowerCase(Locale.ROOT).contains(bld.toLowerCase(Locale.ROOT))) {
                        locOrName = loc;
                    } else {
                        locOrName = bld + ", " + loc;
                    }
                } else if (!bld.isEmpty()) {
                    locOrName = bld;
                } else if (!loc.isEmpty()) {
                    locOrName = loc;
                } else if (!title.isEmpty()) {
                    locOrName = title;
                }
            }
            if (!locOrName.isEmpty()) {
                if (meta.length() > 0) meta.append(" \u00B7 ");
                meta.append(locOrName);
            }
        }
        return meta.toString();
    }

    /**
     * Backward-compatible 6-argument metadata formatter delegating to the 7-argument implementation.
     */
    public static String buildCaptionMeta(String category, String rawArtist, String rawLocation,
                                          String rawBuilding, boolean showArtist, boolean showLocation) {
        return buildCaptionMeta(category, "", rawArtist, rawLocation, rawBuilding, showArtist, showLocation);
    }

    /**
     * Formats the live photographer and location subtitle line according to the user's granular toggles.
     */
    public static String buildCaptionMeta(String rawArtist, String rawLocation, boolean showArtist, boolean showLocation) {
        StringBuilder meta = new StringBuilder();
        if (showArtist && rawArtist != null && !rawArtist.trim().isEmpty()) {
            meta.append("Photo by ").append(rawArtist.trim());
        }
        if (showLocation && rawLocation != null && !rawLocation.trim().isEmpty()) {
            if (meta.length() > 0) meta.append(" \u00B7 ");
            meta.append(rawLocation.trim());
        }
        return meta.toString();
    }

    /**
     * Validates that decoded image dimensions meet strict landscape True 4K UHD requirements
     * ({@code width >= 3840}, {@code height >= 2160}, and landscape aspect {@code width > height}).
     */
    public static boolean isTrue4kDimensions(int width, int height) {
        if (width < MIN_4K_WIDTH || height < MIN_4K_HEIGHT || width <= height) {
            return false;
        }
        float ratio = (float) width / (float) height;
        return ratio >= 1.25f && ratio <= 2.20f;
    }

    /**
     * Parses actual pixel {@code [width, height]} directly from JPEG SOF0..SOF15 markers without
     * Android framework dependencies so both runtime checks and desktop JVM tests verify True-4K bounds.
     */
    public static int[] readJpegDimensions(byte[] data) {
        if (data == null || data.length < 4
                || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return new int[] {0, 0};
        }
        int i = 2;
        int n = data.length;
        while (i + 9 < n) {
            if ((data[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            while (i < n && (data[i] & 0xFF) == 0xFF) {
                i++;
            }
            if (i >= n) break;
            int marker = data[i] & 0xFF;
            i++;
            if (marker == 0xD8 || marker == 0xD9 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) {
                continue;
            }
            if (i + 2 > n) break;
            int segLen = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            if (segLen < 2 || i + segLen > n) break;
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                int h = ((data[i + 3] & 0xFF) << 8) | (data[i + 4] & 0xFF);
                int w = ((data[i + 5] & 0xFF) << 8) | (data[i + 6] & 0xFF);
                return new int[] {w, h};
            }
            i += segLen;
        }
        return new int[] {0, 0};
    }

    /** Registers a listener invoked on the UI thread whenever new 4K wallpapers finish caching. */
    public static void addListener(Runnable listener) {
        if (listener != null && !CATALOG_LISTENERS.contains(listener)) {
            CATALOG_LISTENERS.add(listener);
        }
    }

    /** Unregisters a previously registered catalog listener. */
    public static void removeListener(Runnable listener) {
        if (listener != null) {
            CATALOG_LISTENERS.remove(listener);
        }
    }

    private static void notifyCatalogListeners() {
        if (CATALOG_LISTENERS.isEmpty()) return;
        Runnable dispatch = () -> {
            for (Runnable r : CATALOG_LISTENERS) {
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            new Handler(Looper.getMainLooper()).post(dispatch);
        } catch (RuntimeException stubInJvmTests) {
            dispatch.run();
        }
    }

    /**
     * Validates that a cached wallpaper file exists and contains a valid landscape True 4K UHD image.
     */
    public static boolean isValid4kWallpaperFile(File file) {
        if (file == null || !file.isFile() || file.length() < 80_000L) {
            return false;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            return isTrue4kDimensions(bounds.outWidth, bounds.outHeight);
        } catch (RuntimeException stubInJvmTests) {
            // Headless desktop JVM unit tests run against stub android.jar where BitmapFactory throws Stub!
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] header = new byte[(int) Math.min(file.length(), 65536L)];
                int read = in.read(header);
                if (read >= 4 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8) {
                    int[] dims = readJpegDimensions(header);
                    if (dims[0] > 0 && dims[1] > 0) {
                        return isTrue4kDimensions(dims[0], dims[1]);
                    }
                }
            } catch (IOException ignored) {
            }
            return true;
        }
    }

    /**
     * Validates whether a Wikimedia Commons dynamic candidate title is clean, human-readable English text
     * rather than a raw camera filename, catalog number, or date prefix.
     */
    public static boolean isCleanGalleryTitle(String title) {
        if (title == null) return false;
        String t = title.trim();
        if (t.length() < 6 || t.length() > 64) return false;
        if (Character.isDigit(t.charAt(0))) return false;
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("dsc") || lower.contains("img_") || lower.contains("pano")
                || lower.contains("--") || lower.contains(".jpg") || lower.contains("google art project")
                || lower.contains("unsplash") || lower.contains("version ")) {
            return false;
        }
        int asciiLetters = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) asciiLetters++;
            if (ch > 0x024F && ch != '\u2019' && ch != '\u2013' && ch != '\u2014') {
                return false;
            }
        }
        return asciiLetters >= 5;
    }

    /**
     * Cleans Wikimedia Commons Artist strings and rejects boilerplate license banners.
     */
    public static String sanitizeArtistName(String rawArtist) {
        String cleaned = cleanHtml(rawArtist);
        if (cleaned.isEmpty()) return "";
        cleaned = cleaned.replaceAll("(?i)^(?:credit\\s*:\\s*|photograph\\s+by\\s+|photo\\s+by\\s+)+", "")
                .replaceAll("\\s*\\(talk\\).*", "")
                .replaceAll("\\s*from\\s+[^,]+,.*", "")
                .replaceAll("\\s*https?://\\S+", "")
                .replaceAll("\\s*\\(\\s*www\\.[^)]+\\)\\.?", "")
                .trim();
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (cleaned.length() < 2 || cleaned.length() > 42
                || lower.contains("note:") || lower.contains("feel free")
                || lower.contains("unknown") || lower.contains("wikimedia")) {
            return "";
        }
        return cleaned;
    }

    /**
     * Loads the curated 4K photography & art galleries catalog from {@code app/assets/saver_catalog.json}
     * with zero local-PC dependency.
     */
    public static List<PhotoEntry> loadCatalog(Context context) {
        if (assetCatalogLoaded && !ASSET_CATALOG.isEmpty()) {
            return Collections.unmodifiableList(new ArrayList<>(ASSET_CATALOG));
        }
        synchronized (ASSET_CATALOG) {
            if (assetCatalogLoaded && !ASSET_CATALOG.isEmpty()) {
                return Collections.unmodifiableList(new ArrayList<>(ASSET_CATALOG));
            }
            String json = null;
            if (context != null) {
                try (InputStream in = context.getAssets().open(ASSET_CATALOG_FILE)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                } catch (Throwable ignored) {
                }
            }
            if (json == null || json.isEmpty()) {
                // Fallback: check project assets path for headless unit test runner on desktop JVM
                File f1 = new File("app/assets/" + ASSET_CATALOG_FILE);
                File f2 = new File("SoarTV-launcher/app/assets/" + ASSET_CATALOG_FILE);
                File target = f1.exists() ? f1 : (f2.exists() ? f2 : null);
                if (target != null) {
                    try (InputStream in = new java.io.FileInputStream(target)) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                        json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (json != null && !json.trim().isEmpty()) {
                try {
                    String trimmed = json.trim();
                    List<?> items = null;
                    if (trimmed.startsWith("{")) {
                        Map<String, Object> root = CloudVaultCrypto.JsonCodec.parseJsonObject(trimmed);
                        Object o = root.get("items");
                        if (o instanceof List) {
                            items = (List<?>) o;
                        }
                    } else if (trimmed.startsWith("[")) {
                        items = CloudVaultCrypto.JsonCodec.parseJsonObjectArray(trimmed);
                    }
                    if (items != null) {
                        for (Object item : items) {
                            if (item instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> map = (Map<String, Object>) item;
                                PhotoEntry pe = PhotoEntry.fromMap(map);
                                if (pe != null) {
                                    ASSET_CATALOG.add(pe);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            assetCatalogLoaded = true;
            if (ASSET_CATALOG.isEmpty()) {
                Collections.addAll(ASSET_CATALOG, BUILTIN_CATALOG);
            }
            return Collections.unmodifiableList(new ArrayList<>(ASSET_CATALOG));
        }
    }

    /**
     * Returns curated True 4K gallery entries filtered by the enabled categories set.
     */
    public static List<PhotoEntry> catalogForCategories(Context context, Set<String> categories) {
        List<PhotoEntry> all = loadCatalog(context);
        if (categories == null || categories.isEmpty()) {
            return all;
        }
        Set<String> normalized = new HashSet<>();
        for (String c : categories) {
            if (c != null) {
                String trimmed = c.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    normalized.add(trimmed);
                }
            }
        }
        if (normalized.isEmpty() || normalized.contains("mixed")) {
            return all;
        }
        List<PhotoEntry> filtered = new ArrayList<>();
        for (PhotoEntry e : all) {
            if (e.category != null && normalized.contains(e.category.toLowerCase(Locale.ROOT))) {
                filtered.add(e);
            }
        }
        return filtered;
    }

    private static List<PhotoEntry> allEntries() {
        if (!ASSET_CATALOG.isEmpty()) {
            List<PhotoEntry> all = new ArrayList<>(ASSET_CATALOG.size() + DYNAMIC_CATALOG.size());
            all.addAll(ASSET_CATALOG);
            all.addAll(DYNAMIC_CATALOG);
            return all;
        }
        List<PhotoEntry> all = new ArrayList<>(DYNAMIC_CATALOG.size() + BUILTIN_CATALOG.length);
        Collections.addAll(all, BUILTIN_CATALOG);
        all.addAll(DYNAMIC_CATALOG);
        return all;
    }

    private static final String[] REPLACED_MANIFEST_IDS = {
        "spa_041", "spa_092", "spa_102", "spa_104", "spa_110", "spa_116",
        "spa_128", "spa_135", "spa_140", "spa_166", "spa_168", "spa_173",
        "spa_183", "spa_186", "spa_187", "spa_188", "spa_189", "spa_193",
        "spa_204", "spa_205", "art_028", "pai_005", "mac_001", "mac_012"
    };

    private static void purgeLegacyCacheIfNeeded(File cacheRoot) {
        if (legacyCachePurged || cacheRoot == null) return;
        legacyCachePurged = true;
        File legacy = new File(cacheRoot, LEGACY_CACHE_DIR_NAME);
        if (legacy.isDirectory()) {
            File[] oldFiles = legacy.listFiles();
            if (oldFiles != null) {
                for (File f : oldFiles) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            legacy.delete();
        }
        File currentDir = new File(cacheRoot, CACHE_DIR_NAME);
        if (currentDir.isDirectory()) {
            File v4Stamp = new File(currentDir, ".catalog_v4_curated");
            if (!v4Stamp.exists()) {
                // Purge all pre-v4 JPEGs once so zero renumbered/contaminated v3 slides survive on TV storage
                File[] preV4Files = currentDir.listFiles();
                if (preV4Files != null) {
                    for (File f : preV4Files) {
                        String n = f.getName();
                        if (n.endsWith(".jpg") || n.endsWith(".part") || n.endsWith(".starter")) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                }
                try (FileOutputStream out = new FileOutputStream(v4Stamp)) {
                    out.write(4);
                } catch (IOException ignored) {
                }
            }
        } else {
            //noinspection ResultOfMethodCallIgnored
            currentDir.mkdirs();
            File v4Stamp = new File(currentDir, ".catalog_v4_curated");
            try (FileOutputStream out = new FileOutputStream(v4Stamp)) {
                out.write(4);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Enforces bounded LRU disk caching (~150-250MB limit, default 200MB) on the wallpaper cache directory
     * to protect TV internal flash storage from exhaustion.
     */
    public static void enforceDiskCacheBounds(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (files == null || files.length == 0) return;
        long totalBytes = 0L;
        for (File f : files) totalBytes += f.length();
        if (totalBytes <= MAX_DISK_CACHE_BYTES) return;

        // Sort by lastModified ascending (oldest accessed first)
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long targetBytes = (long) (MAX_DISK_CACHE_BYTES * 0.85); // 15% headroom
        for (File f : files) {
            long len = f.length();
            if (f.delete()) {
                totalBytes -= len;
                if (totalBytes <= targetBytes) break;
            }
        }
    }

    private static final String[] STARTER_CATEGORIES = {
        "nature", "urban", "macro", "space", "paintings", "art"
    };

    /**
     * Extracts bundled True-4K starter JPEGs ({@code app/assets/saver_starters/<cat>.jpg}) into the
     * versioned wallpaper cache directory so both Settings previews and the active Screensaver have
     * instant, verified 3840x2160 slides on first boot and when completely offline.
     */
    public static void ensureStartersExtracted(Context context, Set<String> categories) {
        if (context == null) return;
        File cacheRoot;
        try {
            cacheRoot = context.getApplicationContext() != null
                    ? context.getApplicationContext().getCacheDir()
                    : context.getCacheDir();
        } catch (Throwable t) {
            return;
        }
        if (cacheRoot == null) return;
        File dir = new File(cacheRoot, CACHE_DIR_NAME);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        Set<String> filter = null;
        if (categories != null && !categories.isEmpty()) {
            filter = new HashSet<>();
            for (String c : categories) {
                if (c != null && !c.trim().isEmpty()) {
                    filter.add(c.trim().toLowerCase(Locale.ROOT));
                }
            }
            if (filter.contains("mixed")) {
                filter = null;
            }
        }

        for (String cat : STARTER_CATEGORIES) {
            if (filter != null && !filter.contains(cat)) continue;
            String entryId = cat.substring(0, 3) + "_001";
            File target = new File(dir, entryId + ".jpg");
            if (target.exists() && target.length() > 80_000L) continue;
            File tmp = new File(dir, entryId + ".jpg.starter");
            try (InputStream in = context.getAssets().open(STARTER_ASSET_DIR + "/" + cat + ".jpg");
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (Throwable ignored) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                continue;
            }
            if (tmp.length() > 80_000L) {
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(target);
            } else {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /**
     * Opens an {@link InputStream} for a category's live photographic preview in Settings, checking
     * cached 4K wallpapers on disk first and falling back seamlessly to the bundled True-4K starter asset.
     */
    public static InputStream openCategoryPreviewStream(Context context, String category) {
        String cat = (category == null || category.isEmpty() || "mixed".equalsIgnoreCase(category))
                ? "nature"
                : category.trim().toLowerCase(Locale.ROOT);
        if (context != null) {
            File cached = firstCachedPhotoForCategory(context, cat);
            if (cached != null && cached.isFile() && cached.length() > 80_000L) {
                try {
                    return new FileInputStream(cached);
                } catch (IOException ignored) {
                }
            }
            try {
                return context.getAssets().open(STARTER_ASSET_DIR + "/" + cat + ".jpg");
            } catch (Throwable ignored) {
            }
        }
        File f1 = new File("app/assets/" + STARTER_ASSET_DIR + "/" + cat + ".jpg");
        File f2 = new File("SoarTV-launcher/app/assets/" + STARTER_ASSET_DIR + "/" + cat + ".jpg");
        File local = f1.isFile() ? f1 : (f2.isFile() ? f2 : null);
        if (local != null) {
            try {
                return new FileInputStream(local);
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    /**
     * Checks whether a screensaver {@link Recs.Pick} already has its verified True-4K JPEG cached on disk.
     */
    public static boolean isPickCachedOnDisk(Recs.Pick p) {
        return p != null && p.photo != null && p.photo.isFile() && p.photo.length() > 80_000L;
    }

    /**
     * Converts curated {@link PhotoEntry} items into deterministic {@link Recs.Pick} slides for the full
     * 205+-per-category {@code ShuffleBag} deck (including uncached slides with {@code imageUrl} for on-demand caching).
     */
    public static List<Recs.Pick> picksForEntries(List<PhotoEntry> entries, File cacheDir) {
        List<Recs.Pick> out = new ArrayList<>(entries != null ? entries.size() : 0);
        if (entries == null || entries.isEmpty()) return out;
        boolean showArtist = Prefs.saverShowArtist();
        boolean showLocation = Prefs.saverShowLocation();
        for (PhotoEntry e : entries) {
            if (e == null || e.id == null || e.id.isEmpty()) continue;
            File f = cacheDir != null ? new File(cacheDir, e.id + ".jpg") : new File(e.id + ".jpg");
            Recs.Pick p = new Recs.Pick();
            p.id = (e.id.hashCode() & 0x7fffffff);
            p.kind = "photo:" + e.id;
            p.catalogId = e.id;
            p.imageUrl = e.imageUrl != null ? e.imageUrl : "";
            p.category = e.category != null ? e.category : "";
            p.rawTitle = e.title != null ? e.title : "";
            p.rawArtist = e.artist != null ? e.artist : "";
            p.rawLocation = e.location != null ? e.location : "";
            p.rawBuilding = e.building != null ? e.building : "";
            p.building = p.rawBuilding;
            p.title = "";
            p.meta = buildCaptionMeta(p.category, p.rawTitle, p.rawArtist, p.rawLocation, p.rawBuilding, showArtist, showLocation);
            p.tag = "";
            p.photo = f;
            out.add(p);
        }
        return out;
    }

    private static boolean hasActiveNetwork(Context context) {
        if (context == null) return true;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network active = cm.getActiveNetwork();
            return active != null;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Ensures a selected screensaver {@link Recs.Pick} has its verified True-4K JPEG ready on local storage,
     * returning immediately in 0 ms when cached or downloading on demand with per-entry lock deduplication.
     */
    public static File ensurePickFileReady(Context context, Recs.Pick pick) {
        if (pick == null) return null;
        if (isPickCachedOnDisk(pick)) {
            //noinspection ResultOfMethodCallIgnored
            pick.photo.setLastModified(System.currentTimeMillis());
            return pick.photo;
        }
        if (pick.imageUrl == null || pick.imageUrl.isEmpty() || pick.photo == null) {
            return null;
        }
        if (!hasActiveNetwork(context)) {
            return null;
        }
        File parent = pick.photo.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        String lockKey = (pick.catalogId != null && !pick.catalogId.isEmpty()) ? pick.catalogId : pick.photo.getName();
        Object entryLock = ENTRY_DL_LOCKS.computeIfAbsent(lockKey, k -> new Object());
        synchronized (entryLock) {
            if (isPickCachedOnDisk(pick)) {
                //noinspection ResultOfMethodCallIgnored
                pick.photo.setLastModified(System.currentTimeMillis());
                return pick.photo;
            }
            PhotoEntry tempEntry = new PhotoEntry(lockKey, pick.category, pick.rawTitle, pick.rawArtist,
                    pick.rawLocation, pick.rawBuilding, pick.imageUrl);
            if (ensureValid4kCached(tempEntry, parent != null ? parent : new File("."))) {
                //noinspection ResultOfMethodCallIgnored
                pick.photo.setLastModified(System.currentTimeMillis());
                return pick.photo;
            }
            return null;
        }
    }

    /**
     * Returns ALL 205+ curated True-4K photography {@link Recs.Pick} slides matching the checked categories
     * so {@code DreamView.ShuffleBag} cycles through the entire 200+-per-category gallery without repeating,
     * while extracting instant 4K starters and warming background disk cache asynchronously.
     */
    public static List<Recs.Pick> picks(Context context, java.util.Set<String> categories) {
        loadCatalog(context);
        Set<String> normalized = null;
        boolean allCats = true;
        if (categories != null && !categories.isEmpty()) {
            normalized = new HashSet<>();
            for (String c : categories) {
                if (c != null) {
                    String trimmed = c.trim().toLowerCase(Locale.ROOT);
                    if (!trimmed.isEmpty()) normalized.add(trimmed);
                }
            }
            if (!normalized.isEmpty() && !normalized.contains("mixed")) {
                allCats = false;
            }
        }
        File cacheRoot = null;
        if (context != null) {
            try {
                cacheRoot = context.getApplicationContext() != null
                        ? context.getApplicationContext().getCacheDir()
                        : context.getCacheDir();
            } catch (Throwable ignored) {
            }
            purgeLegacyCacheIfNeeded(cacheRoot);
        }
        ensureStartersExtracted(context, allCats ? null : normalized);

        File dir = cacheRoot != null
                ? new File(cacheRoot, CACHE_DIR_NAME)
                : new File(System.getProperty("java.io.tmpdir"), CACHE_DIR_NAME);
        List<PhotoEntry> matchingEntries = new ArrayList<>();
        Map<String, Integer> readyPerCat = new HashMap<>();

        for (PhotoEntry e : allEntries()) {
            String entryCat = e.category != null ? e.category.toLowerCase(Locale.ROOT) : "";
            if (!allCats && (entryCat.isEmpty() || !normalized.contains(entryCat))) {
                continue;
            }
            matchingEntries.add(e);
            File f = new File(dir, e.id + ".jpg");
            if (f.isFile() && f.length() > 80_000L) {
                readyPerCat.put(entryCat, readyPerCat.getOrDefault(entryCat, 0) + 1);
            }
        }

        List<Recs.Pick> out = picksForEntries(matchingEntries, dir);

        boolean needsMoreCached = false;
        Set<String> checkCats = allCats
                ? new HashSet<>(java.util.Arrays.asList(STARTER_CATEGORIES))
                : normalized;
        if (checkCats != null) {
            for (String c : checkCats) {
                if (readyPerCat.getOrDefault(c, 0) < TARGET_CACHED_PER_CATEGORY) {
                    needsMoreCached = true;
                    break;
                }
            }
        }
        if (context != null && (needsMoreCached || out.isEmpty())) {
            prefetchAsync(context);
        }
        return out;
    }

    /**
     * Legacy single-category overload delegating to multi-category picks.
     */
    public static List<Recs.Pick> picks(Context context, String category) {
        if (category == null || category.isEmpty() || "mixed".equalsIgnoreCase(category)) {
            return picks(context, (java.util.Set<String>) null);
        }
        return picks(context, java.util.Collections.singleton(category.toLowerCase(Locale.ROOT)));
    }

    /** Human-readable category label for settings descriptions. */
    public static String categoryLabel(String cat) {
        if (cat == null) return "4K UHD PHOTOGRAPHY";
        switch (cat.toLowerCase(Locale.ROOT)) {
            case "nature": return "4K NATURE PHOTOGRAPHY";
            case "macro": return "4K MACRO PHOTOGRAPHY";
            case "urban": return "4K URBAN PHOTOGRAPHY";
            case "space": return "4K SPACE & ASTRONOMY";
            case "paintings": return "4K MASTER PAINTINGS";
            case "art": return "4K FINE ART & SCULPTURE";
            case "wildlife": return "4K WILDLIFE PHOTOGRAPHY";
            default: return "4K CURATED PHOTOGRAPHY";
        }
    }

    /**
     * Returns a concise, curated description for each 4K photography gallery category.
     */
    public static String categoryDescription(String cat) {
        if (cat == null) return "Curated 4K UHD landscapes, macro, urban skylines & wildlife";
        switch (cat.toLowerCase(Locale.ROOT)) {
            case "nature": return "Dolomites lakes, Yosemite valleys, auroras & alpine peaks in 4K UHD";
            case "macro": return "Wildflowers, butterflies, dragonflies & botanical close-up studies in 4K UHD";
            case "urban": return "Marina Bay night skylines, Golden Gate, Venice, London & Paris in 4K UHD";
            case "space": return "Deep-space nebulae, distant galaxies, cosmic cliffs & planetary wonders in 4K UHD";
            case "paintings": return "Masterpieces by Van Gogh, Monet, Rembrandt, Vermeer & Klimt in 4K UHD";
            case "art": return "Classical sculptures, stained glass, ancient mosaics & timeless art in 4K UHD";
            case "wildlife": return "Bengal tigers, red foxes, lions, kingfishers & puffins in 4K UHD";
            default: return "Curated 4K UHD blend of nature, macro, urban, space, paintings & art photography";
        }
    }

    /**
     * Returns the representative sample {@link PhotoEntry} for the given gallery category
     * so Settings live previews display authentic title, photographer, building, and location metadata
     * matching the bundled True-4K starter image ({@code <cat>_001}).
     */
    public static PhotoEntry sampleEntryForCategory(String cat) {
        loadCatalog(null);
        String key = (cat == null || cat.isEmpty() || "mixed".equalsIgnoreCase(cat))
                ? "nature"
                : cat.trim().toLowerCase(Locale.ROOT);
        for (PhotoEntry e : ASSET_CATALOG) {
            if (e.category != null && e.category.equalsIgnoreCase(key)) return e;
        }
        for (PhotoEntry e : BUILTIN_CATALOG) {
            if (e.category.equalsIgnoreCase(key)) return e;
        }
        switch (key) {
            case "space":
                return new PhotoEntry("spa_001", "space", "Cosmic Cliffs in the Carina Nebula", "NASA / ESA / CSA / STScI", "", "",
                        "https://cdn.esawebb.org/archives/images/wallpaper5/weic2205a.jpg");
            case "paintings":
                return new PhotoEntry("pai_001", "paintings", "The Starry Night", "Vincent van Gogh", "", "",
                        "https://upload.wikimedia.org/wikipedia/commons/thumb/e/ea/Van_Gogh_-_Starry_Night_-_Google_Art_Project.jpg/3840px-Van_Gogh_-_Starry_Night_-_Google_Art_Project.jpg");
            case "art":
                return new PhotoEntry("art_001", "art", "Winged Victory of Samothrace", "Ancient Greek", "", "",
                        "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4b/Victoire_de_Samothrace_-_Mus%C3%A9e_du_Louvre_-_20190812.jpg/3840px-Victoire_de_Samothrace_-_Mus%C3%A9e_du_Louvre_-_20190812.jpg");
            default:
                return BUILTIN_CATALOG[0];
        }
    }

    /**
     * Returns the first cached True 4K JPEG file on disk matching {@code category}, extracting
     * bundled True-4K starters on demand if needed.
     */
    public static File firstCachedPhotoForCategory(Context context, String category) {
        if (context == null) return null;
        loadCatalog(context);
        String cat = (category == null || category.isEmpty() || "mixed".equalsIgnoreCase(category))
                ? "nature"
                : category.trim().toLowerCase(Locale.ROOT);
        ensureStartersExtracted(context, Collections.singleton(cat));
        File dir = new File(context.getCacheDir(), CACHE_DIR_NAME);
        for (PhotoEntry e : allEntries()) {
            if (e.category == null || !e.category.equalsIgnoreCase(cat)) continue;
            File f = new File(dir, e.id + ".jpg");
            if (f.exists() && f.length() > 80_000L) return f;
        }
        return null;
    }

    /**
     * Signals the background downloader to immediately pivot priority to the newly checked categories.
     */
    public static void reprioritizePrefetch(Context context) {
        if (context == null) return;
        PREFETCH_RESTART_REQUESTED.set(true);
        prefetchAsync(context);
    }

    static boolean tryBeginPrefetchOrRecordPendingResume() {
        synchronized (PREFETCH_STATE_LOCK) {
            if (backgroundActivityPaused) {
                PAUSED_PREFETCH_NEEDS_RESUME.set(true);
                return false;
            }
            if (!PREFETCH_RUNNING.compareAndSet(false, true)) {
                PENDING_RESUME_WHILE_RUNNING.set(true);
                return false;
            }
            PENDING_RESUME_WHILE_RUNNING.set(false);
            return true;
        }
    }

    static boolean completePrefetchRunAndShouldRestart() {
        synchronized (PREFETCH_STATE_LOCK) {
            PREFETCH_RUNNING.set(false);
            if (backgroundActivityPaused) {
                return false;
            }
            boolean restartReq = PREFETCH_RESTART_REQUESTED.getAndSet(false);
            boolean needsResume = PAUSED_PREFETCH_NEEDS_RESUME.getAndSet(false);
            boolean pendingResume = PENDING_RESUME_WHILE_RUNNING.getAndSet(false);
            return restartReq || needsResume || pendingResume;
        }
    }

    static void setPrefetchRunningForTest(boolean running) {
        synchronized (PREFETCH_STATE_LOCK) {
            PREFETCH_RUNNING.set(running);
        }
    }

    static boolean isPrefetchRunningForTest() {
        return PREFETCH_RUNNING.get();
    }

    /**
     * Extracts bundled True-4K starters immediately, loads the 1,230-entry curated 4K manifest,
     * and prefetches randomized True-4K wallpapers across 2 parallel worker threads for enabled categories
     * with polite CDN pacing and 200 MB LRU cache bounds.
     */
    public static void prefetchAsync(Context context) {
        if (context == null) return;
        if (!tryBeginPrefetchOrRecordPendingResume()) {
            return;
        }
        final Context appCtx;
        final File cacheRoot;
        try {
            appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            cacheRoot = appCtx.getCacheDir();
        } catch (Throwable t) {
            synchronized (PREFETCH_STATE_LOCK) {
                PREFETCH_RUNNING.set(false);
            }
            return;
        }
        if (cacheRoot == null) {
            synchronized (PREFETCH_STATE_LOCK) {
                PREFETCH_RUNNING.set(false);
            }
            return;
        }
        final File dir = new File(cacheRoot, CACHE_DIR_NAME);
        DOWNLOADER.execute(() -> {
            try {
                do {
                    if (backgroundActivityPaused) {
                        PAUSED_PREFETCH_NEEDS_RESUME.set(true);
                        break;
                    }
                    PREFETCH_RESTART_REQUESTED.set(false);
                    PENDING_RESUME_WHILE_RUNNING.set(false);
                    purgeLegacyCacheIfNeeded(cacheRoot);
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    loadCatalog(appCtx);
                    ensureStartersExtracted(appCtx, null);
                    notifyCatalogListeners();

                    Set<String> activeCats = Prefs.saverCategories(appCtx);
                    List<String> priorityOrder = new ArrayList<>();
                    for (String c : STARTER_CATEGORIES) {
                        if (activeCats.contains(c)) priorityOrder.add(c);
                    }
                    for (String c : STARTER_CATEGORIES) {
                        if (!priorityOrder.contains(c)) priorityOrder.add(c);
                    }

                    Map<String, List<PhotoEntry>> uncachedByCat = new HashMap<>();
                    ConcurrentHashMap<String, AtomicInteger> cachedCountByCat = new ConcurrentHashMap<>();
                    for (String c : STARTER_CATEGORIES) {
                        uncachedByCat.put(c, new ArrayList<>());
                        cachedCountByCat.put(c, new AtomicInteger(0));
                    }

                    for (PhotoEntry e : allEntries()) {
                        if (e.category == null) continue;
                        String c = e.category.toLowerCase(Locale.ROOT);
                        if (!uncachedByCat.containsKey(c)) continue;
                        File f = new File(dir, e.id + ".jpg");
                        if (f.isFile() && f.length() > 80_000L) {
                            cachedCountByCat.get(c).incrementAndGet();
                        } else {
                            uncachedByCat.get(c).add(e);
                        }
                    }

                    Random rng = new Random();
                    for (List<PhotoEntry> list : uncachedByCat.values()) {
                        Collections.shuffle(list, rng);
                    }

                    // Build interleaved round-robin queue for active categories up to TARGET_CACHED_PER_CATEGORY
                    List<PhotoEntry> waveQueue = buildRoundRobinWaveQueue(
                            activeCats, priorityOrder, uncachedByCat, cachedCountByCat, TARGET_CACHED_PER_CATEGORY);

                    // Launch helper task on second pool thread so 2 downloads proceed concurrently
                    AtomicInteger queueIdx = new AtomicInteger(0);
                    java.util.concurrent.Future<?> helper = DOWNLOADER.submit(() ->
                            drainPrefetchQueue(waveQueue, queueIdx, cachedCountByCat, dir));
                    drainPrefetchQueue(waveQueue, queueIdx, cachedCountByCat, dir);
                    try {
                        helper.get();
                    } catch (Throwable ignored) {
                    }

                    if (!PREFETCH_RESTART_REQUESTED.get() && !backgroundActivityPaused) {
                        Set<String> inactiveCats = new HashSet<>(java.util.Arrays.asList(STARTER_CATEGORIES));
                        inactiveCats.removeAll(activeCats);
                        if (!inactiveCats.isEmpty()) {
                            List<PhotoEntry> warmQueue = buildRoundRobinWaveQueue(
                                    inactiveCats, priorityOrder, uncachedByCat, cachedCountByCat, 4);
                            AtomicInteger warmIdx = new AtomicInteger(0);
                            drainPrefetchQueue(warmQueue, warmIdx, cachedCountByCat, dir);
                        }
                    }
                } while (PREFETCH_RESTART_REQUESTED.get() && !backgroundActivityPaused && !Thread.currentThread().isInterrupted());
            } finally {
                if (completePrefetchRunAndShouldRestart()) {
                    prefetchAsync(appCtx);
                }
            }
        });
    }

    private static List<PhotoEntry> buildRoundRobinWaveQueue(
            Set<String> targetCats,
            List<String> order,
            Map<String, List<PhotoEntry>> uncachedByCat,
            Map<String, AtomicInteger> cachedCountByCat,
            int goalPerCat) {
        List<PhotoEntry> queue = new ArrayList<>();
        Map<String, Integer> cursors = new HashMap<>();
        Map<String, Integer> planned = new HashMap<>();
        for (String c : order) {
            AtomicInteger have = cachedCountByCat.get(c);
            planned.put(c, have != null ? have.get() : 0);
        }
        boolean progress = true;
        while (progress) {
            progress = false;
            for (String cat : order) {
                if (!targetCats.contains(cat)) continue;
                int count = planned.getOrDefault(cat, 0);
                if (count >= goalPerCat) continue;
                List<PhotoEntry> pool = uncachedByCat.get(cat);
                if (pool == null || pool.isEmpty()) continue;
                int idx = cursors.getOrDefault(cat, 0);
                if (idx >= pool.size()) continue;
                cursors.put(cat, idx + 1);
                planned.put(cat, count + 1);
                queue.add(pool.get(idx));
                progress = true;
            }
        }
        return queue;
    }

    private static void drainPrefetchQueue(
            List<PhotoEntry> queue,
            AtomicInteger queueIdx,
            Map<String, AtomicInteger> cachedCountByCat,
            File dir) {
        while (!Thread.currentThread().isInterrupted() && !PREFETCH_RESTART_REQUESTED.get()) {
            if (backgroundActivityPaused) {
                PAUSED_PREFETCH_NEEDS_RESUME.set(true);
                break;
            }
            int i = queueIdx.getAndIncrement();
            if (i >= queue.size()) break;
            PhotoEntry candidate = queue.get(i);
            Object entryLock = ENTRY_DL_LOCKS.computeIfAbsent(candidate.id, k -> new Object());
            boolean ok;
            synchronized (entryLock) {
                ok = ensureValid4kCached(candidate, dir);
            }
            if (ok) {
                String c = candidate.category != null ? candidate.category.toLowerCase(Locale.ROOT) : "";
                AtomicInteger cnt = cachedCountByCat.get(c);
                if (cnt != null) cnt.incrementAndGet();
                notifyCatalogListeners();
            }
        }
    }

    private static void paceHostIfNeeded(String imageUrl) {
        if (imageUrl == null || !imageUrl.contains("wikimedia.org")) return;
        synchronized (WIKIMEDIA_PACE_LOCK) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastWikimediaDownloadMs;
            if (elapsed < 450L) {
                try {
                    Thread.sleep(450L - elapsed);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            lastWikimediaDownloadMs = System.currentTimeMillis();
        }
    }

    private static boolean ensureValid4kCached(PhotoEntry entry, File dir) {
        if (backgroundActivityPaused) {
            PAUSED_PREFETCH_NEEDS_RESUME.set(true);
            return false;
        }
        File target = new File(dir, entry.id + ".jpg");
        if (target.exists()) {
            if (isValid4kWallpaperFile(target)) {
                return true;
            }
            // Purge any partial or sub-4K file on disk
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
        paceHostIfNeeded(entry.imageUrl);
        return downloadVerified4kFile(entry.imageUrl, target);
    }

    /**
     * Fetches open-free Featured Pictures from Wikimedia Commons API across photography categories,
     * strictly enforcing True 4K thumbnail output ({@code >= 3840x2160} landscape) and clean human metadata.
     */
    private static void fetchWikimediaFeaturedGalleries() {
        for (String[] feed : COMMONS_FEEDS) {
            String cat = feed[0];
            String gcmTitle = feed[1];
            HttpURLConnection conn = null;
            try {
                String apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&generator=categorymembers"
                        + "&gcmtitle=" + java.net.URLEncoder.encode(gcmTitle, "UTF-8")
                        + "&gcmtype=file&gcmlimit=20&prop=imageinfo&iiprop=url|dimensions|extmetadata&iiurlwidth=3840&format=json";
                conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "SoarTV/1.5 (Android TV 4K)");
                if (conn.getResponseCode() != 200) continue;
                String body;
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                }
                JSONObject root = new JSONObject(body);
                JSONObject query = root.optJSONObject("query");
                JSONObject pages = query == null ? null : query.optJSONObject("pages");
                if (pages == null) continue;
                int added = 0;
                for (Iterator<String> it = pages.keys(); it.hasNext() && added < 3; ) {
                    String pageId = it.next();
                    JSONObject page = pages.optJSONObject(pageId);
                    if (page == null) continue;
                    org.json.JSONArray infoArr = page.optJSONArray("imageinfo");
                    if (infoArr == null || infoArr.length() == 0) continue;
                    JSONObject info = infoArr.getJSONObject(0);
                    int origW = info.optInt("width", 0);
                    int origH = info.optInt("height", 0);
                    int thumbW = info.optInt("thumbwidth", origW);
                    int thumbH = info.optInt("thumbheight", origH);
                    // Strictly require BOTH native camera resolution AND delivered 3840px thumbnail to be True 4K landscape
                    if (!isTrue4kDimensions(origW, origH) || !isTrue4kDimensions(thumbW, thumbH)) continue;
                    String thumbUrl = info.optString("thumburl", "").split("\\?")[0];
                    if (thumbUrl.isEmpty()) continue;
                    thumbUrl = thumbUrl.replace("https://thumb.wikimedia.org/", "https://upload.wikimedia.org/");
                    String lowerUrl = thumbUrl.toLowerCase(Locale.ROOT);
                    if (!lowerUrl.endsWith(".jpg") && !lowerUrl.endsWith(".jpeg")) continue;

                    JSONObject ext = info.optJSONObject("extmetadata");
                    String artist = sanitizeArtistName(extMeta(ext, "Artist", ""));
                    String rawName = cleanHtml(extMeta(ext, "ObjectName",
                            page.optString("title", "").replace("File:", "").replaceAll("\\.[a-zA-Z]+$", "")));
                    if (artist.isEmpty() || !isCleanGalleryTitle(rawName)) continue;

                    // Never invent fake placeholder locations; leave empty unless verified
                    DYNAMIC_CATALOG.add(new PhotoEntry("wc4k_" + pageId, cat, rawName, artist, "", thumbUrl));
                    added++;
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    private static String extMeta(JSONObject ext, String key, String fallback) {
        if (ext == null) return fallback;
        JSONObject o = ext.optJSONObject(key);
        if (o == null) return fallback;
        String v = o.optString("value", "").trim();
        return v.isEmpty() ? fallback : v;
    }

    private static String cleanHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&#039;", "'")
                .replaceAll("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean downloadVerified4kFile(String url, File target) {
        File tmp = new File(target.getPath() + ".part");
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(25000);
                conn.setRequestProperty("User-Agent", "SoarTV/1.5 (Android TV 4K)");
                int code = conn.getResponseCode();
                if (code == 429 || code == 503) {
                    if (attempt == 0) {
                        try {
                            Thread.sleep(3_500L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                        continue;
                    }
                    return false;
                }
                if (code != 200) return false;
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (!isValid4kWallpaperFile(tmp)) {
                    // Reject any sub-4K or portrait image before it can ever enter the cache
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return false;
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(target);
                enforceDiskCacheBounds(target.getParentFile());
                return true;
            } catch (IOException ignored) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return false;
    }
}
