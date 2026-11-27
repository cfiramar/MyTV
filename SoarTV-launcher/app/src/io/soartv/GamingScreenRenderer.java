package io.soartv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Encapsulates Gaming Screen row construction, controller/PC status banner rendering, and game browser card
 * rendering for {@link HomeView}.
 *
 * <p><b>Why:</b> Isolates Gaming Hub UI composition (connected physical gamepads with colored swatches,
 * battery levels &amp; charging bolts, Moonlight/Sunshine PC battery &amp; HDMI status pills, and Steam/PC game
 * artwork cards) into a single-responsibility collaborator while keeping zero per-frame allocations and 100%
 * headless Desktop JVM compatibility.
 *
 * <p><b>What:</b> Provides pure game filtering, subtitle formatting, and pill-wrapping helpers alongside
 * {@link #buildGamingScreenRows}, {@link #drawGamingStatusBanner}, and {@link #drawGameBrowserCard}.
 */
public final class GamingScreenRenderer {

    /**
     * Reusable zero-allocation metadata carrier populated by {@link #drawGameBrowserCard}.
     */
    static final class CardMeta {
        String title = "";
        String subtitle = "";
        boolean grayedOut = false;
    }

    /** Callback used by {@link #drawGameBrowserCard} to resolve cached gradients and cover-cropped bitmaps. */
    interface CardDrawCallbacks {
        LinearGradient cachedGradient(int colorTop, int colorBottom, RectF cell);
        void coverImage(Canvas c, Bitmap bmp, BitmapShader shader, RectF cell, float radius, float alpha);
        void onGameArtworkDecoded();
    }

    private final CardMeta reusableMeta = new CardMeta();

    /**
     * Creates a new {@link GamingScreenRenderer} without allocating Android graphics stubs in the constructor.
     */
    public GamingScreenRenderer() {
    }

    /**
     * Filters a raw list of PC/Steam games to exclude inline-hidden items, permanently hidden IDs, and
     * internal {@code "system"} pseudo-entries.
     *
     * @param rawGames      Source game catalog list.
     * @param permHiddenIds Set of permanently hidden game IDs.
     * @return Filtered list of visible games.
     */
    public static List<GamingZone.GameItem> filterVisibleGames(
            List<GamingZone.GameItem> rawGames,
            Set<String> permHiddenIds) {
        if (rawGames == null || rawGames.isEmpty()) return new ArrayList<>();
        Set<String> perm = permHiddenIds != null ? permHiddenIds : Collections.emptySet();
        List<GamingZone.GameItem> visible = new ArrayList<>(rawGames.size());
        for (GamingZone.GameItem g : rawGames) {
            if (g != null && !g.hidden && !perm.contains(g.id) && !"system".equals(g.category)) {
                visible.add(g);
            }
        }
        return visible;
    }

    /**
     * Formats the subtitle shown underneath a game card on the Gaming screen.
     *
     * @param g            Game item.
     * @param hiddenLabel  Localized {@code "Hidden"} label.
     * @param recentLabel  Localized {@code "Recently Played"} label.
     * @return Formatted subtitle string with zero emojis.
     */
    public static String formatGameSubtitle(GamingZone.GameItem g, String hiddenLabel, String recentLabel) {
        if (g == null) return "";
        String base;
        if ("steam".equalsIgnoreCase(g.category)) {
            base = "Steam";
        } else if ("heroic".equalsIgnoreCase(g.category)) {
            base = "Heroic Games";
        } else if ("epic".equalsIgnoreCase(g.category) || "legendary".equalsIgnoreCase(g.category)) {
            base = "Epic Games";
        } else {
            base = "PC Game";
        }
        if (g.hidden) {
            return base + " \u00B7 " + (hiddenLabel != null ? hiddenLabel : "Hidden");
        }
        if (g.lastPlayedAt > 0) {
            return base + " \u00B7 " + (recentLabel != null ? recentLabel : "Recently Played");
        }
        return base;
    }

    /**
     * Determines whether a controller or PC status pill should wrap onto the next banner line.
     *
     * @param curX       Current horizontal cursor X.
     * @param pillW      Width of the next status pill.
     * @param maxRight   Right content boundary X.
     * @param leftMargin Left content margin X.
     * @return {@code true} if the pill overflows {@code maxRight} and is not the first pill on the line.
     */
    public static boolean shouldWrapPillToNextLine(float curX, float pillW, float maxRight, float leftMargin) {
        return curX + pillW > maxRight && curX > leftMargin;
    }

    /**
     * Builds the rows for {@code SCREEN_GAMING} (Continue Playing, HDMI Consoles, Games Library, and
     * Streaming &amp; PC Controls).
     *
     * @param c                     Android context.
     * @param gamingStatus          Cached or live laptop status.
     * @param showHiddenGamesInline Whether temporarily hidden games are currently expanded inline.
     * @param outRows               Destination row list.
     * @return Updated {@code showHiddenGamesInline} state (auto-reset to {@code false} if no hidden games remain).
     */
    boolean buildGamingScreenRows(
            Context c,
            GamingZone.LaptopStatus gamingStatus,
            boolean showHiddenGamesInline,
            List<HomeView.BrowserRow> outRows) {
        Set<String> permHiddenGames = GamingZone.getPermanentlyHiddenGameIds(c);
        List<GamingZone.GameItem> allGames = filterVisibleGames(
                gamingStatus != null ? gamingStatus.games : Collections.emptyList(),
                permHiddenGames);
        if (allGames.isEmpty()) {
            allGames = filterVisibleGames(GamingZone.defaultGameCatalog(), permHiddenGames);
        }
        boolean nextShowHidden = showHiddenGamesInline;
        List<GamingZone.GameItem> tempHiddenGames = GamingZone.getTemporarilyHiddenGames(c);
        if (!tempHiddenGames.isEmpty()) {
            allGames.add(new GamingZone.GameItem(
                    GamingZone.HIDDEN_TILE_ID,
                    Ui.tr("Hidden") + " (" + tempHiddenGames.size() + ")",
                    "hidden_tile", "", false));
            if (nextShowHidden) {
                for (GamingZone.GameItem hg : tempHiddenGames) {
                    allGames.add(hg.withHidden(true));
                }
            }
        } else {
            nextShowHidden = false;
        }

        List<GamingZone.GameItem> continueGames = GamingZone.getContinuePlayingGames(c);
        if (!continueGames.isEmpty()) {
            outRows.add(new HomeView.BrowserRow(HomeView.ROW_GAMES, Ui.tr("Continue Playing"), null,
                    null, null, continueGames, null, null));
        }

        List<HomeApps.Item> consoleInputs = GamingZone.getConnectedConsoles(c);
        if (!consoleInputs.isEmpty()) {
            List<HomeView.ActionCard> consoleCards = new ArrayList<>(consoleInputs.size());
            for (HomeApps.Item cin : consoleInputs) {
                String sig = cin.connected
                        ? Ui.tr("HDMI Connected \u00B7 Press OK to switch input")
                        : Ui.tr("Standby \u00B7 Press OK to switch input");
                consoleCards.add(new HomeView.ActionCard(
                        "gaming_console:" + cin.key,
                        cin.label,
                        sig,
                        "gamepad",
                        cin.connected ? 0xFF2563EB : 0xFF334155,
                        cin.connected ? 0xFF1D4ED8 : 0xFF0F172A,
                        cin));
            }
            outRows.add(new HomeView.BrowserRow(HomeView.ROW_ACTIONS, Ui.tr("Gaming Consoles (HDMI)"), null,
                    null, null, null, consoleCards, null));
        }

        outRows.add(new HomeView.BrowserRow(HomeView.ROW_GAMES, Ui.tr("Games Library"), null,
                null, null, allGames, null, null));

        List<HomeView.ActionCard> actions = new ArrayList<>(7);
        actions.add(new HomeView.ActionCard("gaming_wake", Ui.tr("Wake Gaming PC (Wireless / Auto HDMI)"),
                Ui.tr("Uses HDMI cable if plugged in, otherwise streams wirelessly"), "gamepad",
                0xFF2563EB, 0xFF1D4ED8, null));
        actions.add(new HomeView.ActionCard("gaming_launch:steam_bigpicture", Ui.tr("Steam Big Picture"),
                Ui.tr("Console-style Steam UI for TV controller"), "gamepad",
                0xFF1E3A8A, 0xFF172554, null));
        actions.add(new HomeView.ActionCard("gaming_launch:desktop", Ui.tr("Windows Desktop"),
                Ui.tr("Stream full PC desktop wirelessly to TV"), "tv",
                0xFF0F766E, 0xFF134E4A, null));
        actions.add(new HomeView.ActionCard("gaming_hdmi_ports", Ui.tr("HDMI Ports Setup"),
                Ui.tr("Configure PlayStation, Xbox, Switch, Wii, Streamer or Laptop HDMI"), "hdmi",
                0xFF7C3AED, 0xFF4C1D95, null));
        actions.add(new HomeView.ActionCard("gaming_sync", Ui.tr("Sync Library with PC Now"),
                Ui.tr("Refreshes local game library from Gaming PC"), "sparkles",
                0xFF059669, 0xFF047857, null));
        actions.add(new HomeView.ActionCard("gaming_hub", Ui.tr("Manage Games & Windows PIN"),
                Ui.tr("Unlock Windows Hello or hide/unhide games"), "settings",
                0xFF4F46E5, 0xFF3730A3, null));
        String streamSummary = ModalOverlayRenderer.StreamQualityModalState.formatCompactSummary(
                Prefs.gamingResolution(), Prefs.gamingFps(), Prefs.gamingBitrateMbps());
        actions.add(new HomeView.ActionCard("gaming_stream_settings", Ui.tr("Stream Quality & Settings"),
                streamSummary + " \u00B7 " + Ui.tr("Quick tune resolution, FPS & bitrate"), "sliders",
                0xFF334155, 0xFF0F172A, null));
        outRows.add(new HomeView.BrowserRow(HomeView.ROW_ACTIONS, Ui.tr("Streaming & PC Controls"), null,
                null, null, null, actions, null));

        return nextShowHidden;
    }

    /**
     * Renders the Gaming status banner (connected controller pills with rainbow color circles and charging
     * lightning bolts + Gaming PC online/battery/charging pill) with zero per-frame object allocations.
     *
     * @return Next vertical Y coordinate below the banner.
     */
    float drawGamingStatusBanner(
            Context ctx,
            Canvas c,
            float y,
            int w,
            float k,
            float marginVirtual,
            GamingZone.LaptopStatus gamingStatus,
            boolean isDark,
            int lDimColor,
            Paint fill,
            Paint border,
            Paint lSubTabOn,
            RectF tmp) {
        float left = marginVirtual * k;
        List<ControllerManager.ControllerInfo> controllers = ControllerManager.getConnectedPhysicalControllers(ctx);
        boolean ctrlConnected = !controllers.isEmpty();
        boolean pcOnline = gamingStatus != null && gamingStatus.awake;
        boolean pcHdmi = gamingStatus != null && gamingStatus.hdmiConnected;
        int pcBattery = gamingStatus != null ? gamingStatus.batteryPercent : -1;
        boolean pcCharging = gamingStatus != null && gamingStatus.charging;
        boolean pcFull = gamingStatus != null && (gamingStatus.batteryFull || (pcCharging && pcBattery >= 100));
        boolean pcSunshineRunning = gamingStatus == null || gamingStatus.sunshineRunning;
        boolean pcSunshineCtrlReady = gamingStatus != null && gamingStatus.sunshinePowerControlReady;
        String pcText = Ui.tr(GamingZone.formatPcConnectionStatus(
                pcOnline, pcHdmi, pcBattery, pcCharging, pcFull, pcSunshineRunning, pcSunshineCtrlReady));

        float pillH = 44 * k;
        float padX = 18 * k;
        float curX = left;
        float maxRight = w - marginVirtual * k;

        if (!ctrlConnected) {
            String ctrlSummary = Ui.tr(GamingZone.formatControllerCountSummary(0));
            float ctrlW = lSubTabOn.measureText(ctrlSummary) + 46 * k + padX * 2;
            tmp.set(curX, y, curX + ctrlW, y + pillH);
            fill.setShader(null);
            fill.setColor(isDark ? 0x26FFFFFF : 0x18000000);
            c.drawRoundRect(tmp, pillH / 2f, pillH / 2f, fill);
            int ctrlInk = isDark ? 0xFF98989D : lDimColor;
            Symbols.draw(ctx, c, "gamepad", tmp.left + padX + 12 * k, tmp.centerY(), 22 * k, ctrlInk);
            int prev = lSubTabOn.getColor();
            lSubTabOn.setColor(ctrlInk);
            c.drawText(ctrlSummary, tmp.left + padX + 34 * k, tmp.centerY() + Ui.halfCap(lSubTabOn), lSubTabOn);
            lSubTabOn.setColor(prev);
            curX += ctrlW + 16 * k;
        } else {
            for (ControllerManager.ControllerInfo ci : controllers) {
                String ctrlLabel = ControllerManager.formatControllerPillLabel(ci);
                float labelW = lSubTabOn.measureText(ctrlLabel);
                float circleDiameter = 22 * k;
                float circleRadius = circleDiameter / 2f;
                float ctrlBoltSize = 18 * k;
                float ctrlBoltGap = 6 * k;
                float pillW = padX + circleDiameter + 10 * k + labelW
                        + (ci.charging ? (ctrlBoltGap + ctrlBoltSize) : 0f) + padX;

                if (shouldWrapPillToNextLine(curX, pillW, maxRight, left)) {
                    curX = left;
                    y += pillH + 10 * k;
                }

                tmp.set(curX, y, curX + pillW, y + pillH);
                fill.setShader(null);
                fill.setColor(isDark ? ControllerManager.CONNECTED_PILL_BG_DARK : ControllerManager.CONNECTED_PILL_BG_LIGHT);
                c.drawRoundRect(tmp, pillH / 2f, pillH / 2f, fill);
                border.setColor(isDark ? ControllerManager.CONNECTED_PILL_BORDER_DARK : ControllerManager.CONNECTED_PILL_BORDER_LIGHT);
                c.drawRoundRect(tmp, pillH / 2f, pillH / 2f, border);

                float circleCx = tmp.left + padX + circleRadius;
                float circleCy = tmp.centerY();
                fill.setColor(ci.color);
                c.drawCircle(circleCx, circleCy, circleRadius, fill);
                border.setColor(isDark ? 0xCCFFFFFF : 0x55000000);
                c.drawCircle(circleCx, circleCy, circleRadius, border);

                if (ci.charging) {
                    int boltInk = ControllerManager.calculateContrastInk(ci.color);
                    ControllerManager.drawLightningBolt(c, circleCx, circleCy, circleRadius * 0.72f, boltInk);
                }

                int prev = lSubTabOn.getColor();
                int textInk = isDark ? ControllerManager.CONNECTED_PILL_TEXT_DARK : ControllerManager.CONNECTED_PILL_TEXT_LIGHT;
                float labelStartX = tmp.left + padX + circleDiameter + 10 * k;
                lSubTabOn.setColor(textInk);
                c.drawText(ctrlLabel, labelStartX, tmp.centerY() + Ui.halfCap(lSubTabOn), lSubTabOn);
                lSubTabOn.setColor(prev);
                if (ci.charging) {
                    Symbols.draw(ctx, c, "bolt",
                            labelStartX + labelW + ctrlBoltGap + ctrlBoltSize / 2f,
                            tmp.centerY(), ctrlBoltSize, textInk);
                }

                curX += pillW + 14 * k;
            }
        }

        boolean showPcChargingBolt = GamingZone.isPcBatteryChargingIndicatorNeeded(pcOnline, pcBattery, pcCharging, pcFull);
        float boltSize = 18 * k;
        float boltGap = 6 * k;
        float pcTextW = lSubTabOn.measureText(pcText);
        float pcW = pcTextW + (showPcChargingBolt ? (boltGap + boltSize) : 0f) + padX * 2;
        if (shouldWrapPillToNextLine(curX, pcW, maxRight, left)) {
            curX = left;
            y += pillH + 10 * k;
        }
        tmp.set(curX, y, curX + pcW, y + pillH);
        fill.setShader(null);
        fill.setColor(pcOnline
                ? (isDark ? ControllerManager.CONNECTED_PILL_BG_DARK : ControllerManager.CONNECTED_PILL_BG_LIGHT)
                : (isDark ? 0x26FFFFFF : 0x18000000));
        c.drawRoundRect(tmp, pillH / 2f, pillH / 2f, fill);
        if (pcOnline) {
            border.setColor(isDark ? ControllerManager.CONNECTED_PILL_BORDER_DARK : ControllerManager.CONNECTED_PILL_BORDER_LIGHT);
            c.drawRoundRect(tmp, pillH / 2f, pillH / 2f, border);
        }
        int prev = lSubTabOn.getColor();
        int pcInk = pcOnline
                ? (isDark ? ControllerManager.CONNECTED_PILL_TEXT_DARK : ControllerManager.CONNECTED_PILL_TEXT_LIGHT)
                : (isDark ? 0xFF98989D : lDimColor);
        lSubTabOn.setColor(pcInk);
        c.drawText(pcText, tmp.left + padX, tmp.centerY() + Ui.halfCap(lSubTabOn), lSubTabOn);
        lSubTabOn.setColor(prev);
        if (showPcChargingBolt) {
            float boltCx = tmp.left + padX + pcTextW + boltGap + boltSize / 2f;
            Symbols.draw(ctx, c, "bolt", boltCx, tmp.centerY(), boltSize, pcInk);
        }
        return y + pillH + 22 * k;
    }

    /**
     * Renders a single {@code ROW_GAMES} card (either the inline Hidden Games toggle tile or a Steam/PC game
     * artwork card) and returns reusable metadata without per-frame heap allocations.
     */
    CardMeta drawGameBrowserCard(
            Context ctx,
            Canvas c,
            GamingZone.GameItem g,
            RectF cell,
            float radius,
            float k,
            float scale,
            boolean showHiddenGamesInline,
            Paint fill,
            Paint imageFill,
            Paint veil,
            ColorMatrixColorFilter grayscaleDimFilter,
            CardDrawCallbacks callbacks) {
        reusableMeta.title = "";
        reusableMeta.subtitle = "";
        reusableMeta.grayedOut = false;
        if (g == null) return reusableMeta;

        if (GamingZone.HIDDEN_TILE_ID.equals(g.id)) {
            fill.setShader(null);
            fill.setColor(0xFF1E293B);
            c.drawRoundRect(cell, radius, radius, fill);
            fill.setShader(callbacks.cachedGradient(0xFF334155, 0xFF0F172A, cell));
            fill.setAlpha(255);
            c.drawRoundRect(cell, radius, radius, fill);
            fill.setShader(null);
            Symbols.draw(ctx, c, showHiddenGamesInline ? "eye_off" : "eye",
                    cell.centerX(), cell.centerY(), 46 * k * scale, 0xE6FFFFFF);
            reusableMeta.title = g.title;
            reusableMeta.subtitle = showHiddenGamesInline
                    ? Ui.tr("Press OK to hide hidden games")
                    : Ui.tr("Press OK to show hidden games");
            return reusableMeta;
        }

        reusableMeta.grayedOut = g.hidden;
        Bitmap bmp = GamingZone.getGameBitmap(ctx, g, callbacks::onGameArtworkDecoded);
        BitmapShader sh = GamingZone.getGameShader(g.id);
        if (bmp != null && sh != null) {
            if (g.hidden && grayscaleDimFilter != null) imageFill.setColorFilter(grayscaleDimFilter);
            callbacks.coverImage(c, bmp, sh, cell, radius, 1f);
            if (g.hidden) imageFill.setColorFilter(null);
        } else {
            fill.setShader(null);
            fill.setColor(0xFF1E293B);
            c.drawRoundRect(cell, radius, radius, fill);
        }
        if (g.hidden) {
            veil.setColor(0x66000000);
            c.drawRoundRect(cell, radius, radius, veil);
        }
        reusableMeta.title = g.title;
        reusableMeta.subtitle = formatGameSubtitle(g, Ui.tr("Hidden"), Ui.tr("Recently Played"));
        return reusableMeta;
    }
}
