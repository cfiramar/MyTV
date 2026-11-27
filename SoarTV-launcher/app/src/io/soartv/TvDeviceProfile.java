package io.soartv;

import android.os.Build;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Encapsulates hardware and firmware specifics for a TV device model and brand.
 *
 * <p><b>Why:</b> SoarTV is architected to work cleanly across diverse Android TV, Google TV, and Fire OS
 * hardware families (Xiaomi, TCL, Hisense, Sony Bravia, Philips TP Vision, Skyworth/JVC, Vestel,
 * Chromecast / Google TV Streamer, Walmart onn, Amazon Fire TV, and generic AOSP/ATV boxes) without
 * scattering model strings across UI or media classes.
 *
 * <p><b>What:</b> Detects the active {@link Brand} from {@code Build.MANUFACTURER}, {@code Build.BRAND},
 * and {@code Build.MODEL}, and exposes immutable capability descriptors (Live TV packages, vendor
 * overlays, translucent window workarounds, and remote gear-key interception).
 */
public final class TvDeviceProfile {

    /** Supported TV hardware families with known Android TV / Google TV / Fire OS firmware behaviors. */
    public enum Brand {
        XIAOMI,
        TCL,
        HISENSE,
        SONY,
        PHILIPS,
        SKYWORTH,
        VESTEL,
        CHROMECAST,
        ONN,
        AMAZON,
        GENERIC
    }

    private static final Pattern ONN_WORD = Pattern.compile("(^|[^a-z0-9])onn([^a-z0-9]|$)");

    private final Brand brand;
    private final String manufacturer;
    private final String model;
    private final int sdkInt;
    private final List<String> liveTvPackages;
    private final List<String> vendorOverlayPackages;
    private final boolean requiresOpaqueResumeFix;
    private final boolean interceptsNotificationAsSettings;

    /**
     * Constructs an explicit {@link TvDeviceProfile} for a given manufacturer, brand name, model, and SDK level.
     *
     * @param manufacturer Raw manufacturer string (e.g., {@code "Xiaomi"}).
     * @param brandName    Raw brand string (e.g., {@code "Xiaomi"}).
     * @param model        Raw model identifier (e.g., {@code "SmartTV-4K"}).
     * @param sdkInt       Android API level (e.g., 34 for Android 14).
     */
    public TvDeviceProfile(String manufacturer, String brandName, String model, int sdkInt) {
        this.manufacturer = manufacturer == null ? "" : manufacturer.trim();
        this.model = model == null ? "" : model.trim();
        this.sdkInt = sdkInt;
        this.brand = detectBrand(this.manufacturer, brandName == null ? "" : brandName.trim(), this.model);
        this.liveTvPackages = resolveLiveTvPackages(this.brand);
        this.vendorOverlayPackages = resolveVendorOverlays(this.brand);
        this.requiresOpaqueResumeFix = (this.brand == Brand.TCL && sdkInt >= 30);
        this.interceptsNotificationAsSettings = (this.brand == Brand.TCL
                || this.brand == Brand.HISENSE
                || this.brand == Brand.SKYWORTH
                || this.brand == Brand.SONY);
    }

    private static volatile TvDeviceProfile currentProfile;

    /**
     * Returns the singleton {@link TvDeviceProfile} for the running Android TV hardware.
     *
     * @return The active {@link TvDeviceProfile}.
     */
    public static TvDeviceProfile current() {
        TvDeviceProfile p = currentProfile;
        if (p == null) {
            synchronized (TvDeviceProfile.class) {
                p = currentProfile;
                if (p == null) {
                    p = new TvDeviceProfile(Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.VERSION.SDK_INT);
                    currentProfile = p;
                }
            }
        }
        return p;
    }

    /**
     * Detects the {@link Brand} from manufacturer, brand, and model strings.
     *
     * @param manufacturer Manufacturer string.
     * @param brandName    Brand string.
     * @param model        Device model string.
     * @return Matched {@link Brand}.
     */
    public static Brand detectBrand(String manufacturer, String brandName, String model) {
        String combined = (manufacturer + " " + brandName + " " + model).toLowerCase(Locale.ROOT);
        if (combined.contains("amazon") || combined.contains("aft")) {
            return Brand.AMAZON;
        }
        if (combined.contains("xiaomi") || combined.contains("mitv") || combined.contains("mibox")
                || combined.contains("formovie") || combined.contains("fengmi")) {
            return Brand.XIAOMI;
        }
        if (combined.contains("tcl") || combined.contains("ffalcon") || combined.contains("thomson")) {
            return Brand.TCL;
        }
        if (combined.contains("philips") || combined.contains("tpv") || combined.contains("droidtv")) {
            return Brand.PHILIPS;
        }
        if (combined.contains("skyworth") || combined.contains("jvc") || combined.contains("coocaa")
                || combined.contains("metz")) {
            return Brand.SKYWORTH;
        }
        if (combined.contains("vestel") || combined.contains("hitachi")) {
            return Brand.VESTEL;
        }
        if (combined.contains("hisense") || combined.contains("toshiba")) {
            return Brand.HISENSE;
        }
        if (combined.contains("sony") || combined.contains("bravia")) {
            return Brand.SONY;
        }
        if (combined.contains("chromecast") || combined.contains("sabrina")
                || combined.contains("google tv streamer")) {
            return Brand.CHROMECAST;
        }
        if (ONN_WORD.matcher(combined).find()) {
            return Brand.ONN;
        }
        return Brand.GENERIC;
    }

    private static List<String> resolveLiveTvPackages(Brand brand) {
        switch (brand) {
            case XIAOMI:
                return Collections.unmodifiableList(Arrays.asList(
                        "com.xiaomi.mitv.tvplayer",
                        "com.google.android.tv",
                        "com.mediatek.wwtv.tvcenter"
                ));
            case TCL:
                return Collections.unmodifiableList(Arrays.asList(
                        "com.tcl.tv",
                        "com.google.android.tv"
                ));
            case SONY:
                return Collections.unmodifiableList(Arrays.asList(
                        "com.sony.dtv.tvx",
                        "com.google.android.tv"
                ));
            case PHILIPS:
                return Collections.unmodifiableList(Arrays.asList(
                        "org.droidtv.playtv",
                        "com.mediatek.wwtv.tvcenter",
                        "com.google.android.tv"
                ));
            case SKYWORTH:
                return Collections.unmodifiableList(Arrays.asList(
                        "com.smartdevice.livetv",
                        "com.mediatek.wwtv.tvcenter",
                        "com.google.android.tv"
                ));
            case VESTEL:
                return Collections.unmodifiableList(Arrays.asList(
                        "com.vestel.livetv",
                        "com.mediatek.wwtv.tvcenter",
                        "com.google.android.tv"
                ));
            case HISENSE:
                return Collections.unmodifiableList(Arrays.asList(
                        "com.hisense.tv.livetv",
                        "com.mediatek.wwtv.tvcenter",
                        "com.google.android.tv"
                ));
            case AMAZON:
                return Collections.unmodifiableList(Arrays.asList(
                        "com.amazon.tv.livetv",
                        "com.amazon.mediatek.livetv"
                ));
            default:
                return Collections.unmodifiableList(Arrays.asList(
                        "com.tcl.tv",
                        "com.google.android.tv",
                        "com.mediatek.wwtv.tvcenter",
                        "com.sony.dtv.tvx",
                        "org.droidtv.playtv",
                        "com.smartdevice.livetv",
                        "com.mediatek.tv.oneworld.tvcenter",
                        "com.softwinner.awlivetv",
                        "com.xiaomi.mitv.tvplayer"
                ));
        }
    }

    private static List<String> resolveVendorOverlays(Brand brand) {
        switch (brand) {
            case XIAOMI:
                return Collections.unmodifiableList(Arrays.asList(
                        "com.mitv.tvhome",
                        "com.xiaomi.mitv.smartshare"
                ));
            default:
                return Collections.emptyList();
        }
    }

    /** @return The detected {@link Brand}. */
    public Brand getBrand() {
        return brand;
    }

    /** @return The raw device manufacturer string. */
    public String getManufacturer() {
        return manufacturer;
    }

    /** @return The raw device model string (e.g., {@code "SmartTV-4K"}). */
    public String getModel() {
        return model;
    }

    /** @return The Android SDK integer version. */
    public int getSdkInt() {
        return sdkInt;
    }

    /** @return Ordered list of Live TV package names prioritized for this TV brand. */
    public List<String> getLiveTvPackages() {
        return liveTvPackages;
    }

    /** @return Optional secondary vendor launcher/overlay packages present on this brand. */
    public List<String> getVendorOverlayPackages() {
        return vendorOverlayPackages;
    }

    /**
     * @return {@code true} if the firmware marks the Home Activity translucent on resume (e.g., TCL Android 11+),
     * requiring {@code Activity.setTranslucent(false)}.
     */
    public boolean requiresOpaqueResumeFix() {
        return requiresOpaqueResumeFix;
    }

    /**
     * @return {@code true} if the remote's gear button sends {@code KEYCODE_NOTIFICATION} managed by {@code RemoteKeys}.
     */
    public boolean interceptsNotificationAsSettings() {
        return interceptsNotificationAsSettings;
    }
}
