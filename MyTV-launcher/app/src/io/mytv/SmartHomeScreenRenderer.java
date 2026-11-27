package io.mytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates Smart-Home Screen row construction, Wi-Fi status banner rendering, Smart-Home browser card
 * rendering, and Hold-OK Smart-Home Device Control Card state/rendering for {@link HomeView}.
 *
 * <p><b>Why:</b> Separates multi-protocol Smart-Home UI logic (WiZ LAN light rooms/bulbs/Kelvin/brightness,
 * Sensibo &amp; Tuya AC mode/temperature/fan, and Switcher water heater timers) from {@link HomeView} so all
 * device-control rules and Canvas rendering live in a cohesive, Desktop JVM unit-testable collaborator.
 *
 * <p><b>What:</b> Provides pure Kelvin/brightness/boiler/room helpers alongside {@link #buildSmartHomeScreenRows},
 * {@link #populateSmartDeviceControlRows}, {@link #adjustSmartDeviceValue}, {@link #drawSmartHomeBanner},
 * {@link #drawSmartDeviceControlRow}, and {@link #drawSmartHomeBrowserCard}.
 */
public final class SmartHomeScreenRenderer {

    /**
     * Reusable zero-allocation metadata carrier populated by {@link #drawSmartHomeBrowserCard}.
     */
    static final class CardMeta {
        String title = "";
        String subtitle = "";
    }

    /**
     * Mutable session state for the interactive Smart-Home Device Control Card opened on Hold-OK.
     */
    static final class ControlCardSession {
        SmartHomeManager.Device device;
        int roomIndex = 0;
        int bulbIndex = 0;
        int rowCount = 0;
    }

    private final CardMeta reusableMeta = new CardMeta();

    /**
     * Creates a new {@link SmartHomeScreenRenderer} without allocating Android graphics stubs in the constructor.
     */
    public SmartHomeScreenRenderer() {
    }

    /**
     * Formats a WiZ bulb color temperature in Kelvin into its human-readable label.
     *
     * @param kelvin    Color temperature in Kelvin (e.g. {@code 2700}..{@code 6500}).
     * @param warm      Localized {@code "Warm"} label.
     * @param softWhite Localized {@code "Soft White"} label.
     * @param neutral   Localized {@code "Neutral"} label.
     * @param daylight  Localized {@code "Daylight"} label.
     * @param cool      Localized {@code "Cool"} label.
     * @return Formatted label such as {@code "Warm (2700K)"}.
     */
    public static String formatKelvinLabel(
            int kelvin,
            String warm,
            String softWhite,
            String neutral,
            String daylight,
            String cool) {
        if (kelvin <= 2800) return warm + " (2700K)";
        if (kelvin <= 3400) return softWhite + " (3000K)";
        if (kelvin <= 4500) return neutral + " (4000K)";
        if (kelvin <= 5600) return daylight + " (5000K)";
        return cool + " (6500K)";
    }

    /**
     * Enforces Smart-Home Rule 1: hide the individual {@code "Bulbs"} selector row when Room is {@code "All"}
     * or when the selected room contains fewer than 2 bulbs.
     *
     * @param selectedRoom Selected room name.
     * @param bulbCount    Number of bulbs discovered in {@code selectedRoom}.
     * @return {@code true} if the {@code "Bulbs"} selector row should be displayed.
     */
    public static boolean shouldShowWizBulbsRow(String selectedRoom, int bulbCount) {
        return selectedRoom != null && !"All".equalsIgnoreCase(selectedRoom) && bulbCount > 1;
    }

    /**
     * Steps light brightness by 10% increments clamped to {@code [10, 100]}.
     *
     * @param currentPct Current brightness percentage.
     * @param dir        Direction ({@code -1} or {@code +1}).
     * @return Clamped brightness percentage.
     */
    public static int stepBrightness(int currentPct, int dir) {
        return Math.max(10, Math.min(100, currentPct + dir * 10));
    }

    /**
     * Steps Switcher water-boiler auto-off timer by 5-minute increments clamped to {@code [5, 120]}.
     *
     * @param currentMins Current timer duration in minutes.
     * @param dir         Direction ({@code -1} or {@code +1}).
     * @return Clamped timer duration in minutes.
     */
    public static int stepBoilerMinutes(int currentMins, int dir) {
        return Math.max(5, Math.min(120, currentMins + dir * 5));
    }

    /**
     * Builds the rows for {@code SCREEN_SMARTHOME} (Smart-Home Devices, Advanced Controls &amp; Routines,
     * and TV Inputs &amp; Sources).
     */
    void buildSmartHomeScreenRows(
            Context c,
            List<HomeApps.Item> drawerItems,
            List<HomeView.BrowserRow> outRows) {
        List<HomeApps.Item> smartDevices = new ArrayList<>();
        List<HomeApps.Item> inputs = new ArrayList<>();
        if (drawerItems != null) {
            for (HomeApps.Item it : drawerItems) {
                if (it.kind == HomeApps.SMART_DEVICE) {
                    smartDevices.add(it);
                } else if (it.kind == HomeApps.INPUT || it.kind == HomeApps.LIVE_TV) {
                    inputs.add(it);
                }
            }
        }
        if (smartDevices.isEmpty()) {
            smartDevices.addAll(SmartHomeManager.get(c).drawerItems());
        }
        outRows.add(new HomeView.BrowserRow(HomeView.ROW_SMARTHOME, Ui.tr("Smart-Home Devices"), null,
                null, null, null, null, smartDevices));

        List<HomeView.ActionCard> shActions = new ArrayList<>(8);
        shActions.add(new HomeView.ActionCard("sh_reorder_devices", Ui.tr("Reorder Devices"),
                Ui.tr("Rearrange Smart-Home devices order (\u2190 \u2192)"), "reorder",
                0xFF4F46E5, 0xFF3730A3, null));
        shActions.add(new HomeView.ActionCard("sh_ac_controls", Ui.tr("AC Mode, Temperature & Fan"),
                Ui.tr("Cool / Heat \u00B7 16\u00B0C\u201330\u00B0C \u00B7 Fan Speed"), "ac",
                0xFF0284C7, 0xFF0369A1, null));
        shActions.add(new HomeView.ActionCard("sh_lights_dim", Ui.tr("Dim Lights & Color Temp"),
                Ui.tr("10%\u2013100% Brightness \u00B7 Warm / Daylight"), "lightbulb",
                0xFFD97706, 0xFF92400E, null));
        shActions.add(new HomeView.ActionCard("sh_boiler_timer", Ui.tr("Water Heater Timer"),
                Ui.tr("On with 15m / 30m / 45m / 60m Auto-Off"), "shower",
                0xFFDC2626, 0xFF991B1B, null));
        shActions.add(new HomeView.ActionCard("sh_routine_movie", Ui.tr("Routine: Movie Time"),
                Ui.tr("Warm 20% lights + Living Room AC 22\u00B0C"), "sparkles",
                0xFF7C3AED, 0xFF4C1D95, null));
        shActions.add(new HomeView.ActionCard("sh_routine_night", Ui.tr("Routine: Good Night"),
                Ui.tr("Turn off all lights, AC & water heater"), "sparkles",
                0xFF1E293B, 0xFF0F172A, null));
        shActions.add(new HomeView.ActionCard("sh_google_home", Ui.tr("Google Home Assistant"),
                Ui.tr("Voice command or routine for Google Home"), "mic",
                0xFF059669, 0xFF065F46, null));
        shActions.add(new HomeView.ActionCard("sh_wiz_scan", Ui.tr("Scan Wi-Fi for New Bulbs"),
                Ui.tr("Auto-discovers WiZ bulbs on any Wi-Fi"), "wifi",
                0xFF2563EB, 0xFF1E3A8A, null));
        outRows.add(new HomeView.BrowserRow(HomeView.ROW_ACTIONS, Ui.tr("Advanced Controls & Routines"), null,
                null, null, null, shActions, null));

        if (!inputs.isEmpty()) {
            outRows.add(new HomeView.BrowserRow(HomeView.ROW_SMARTHOME, Ui.tr("TV Inputs & Sources"), null,
                    null, null, null, null, inputs));
        }
    }

    /**
     * Populates the interactive Smart-Home Device Control Card rows ({@code labels}, {@code values}, {@code icons})
     * for the active {@link ControlCardSession}.
     *
     * @return Number of populated control rows.
     */
    int populateSmartDeviceControlRows(
            Context ctx,
            ControlCardSession session,
            String[] labels,
            String[] values,
            String[] icons) {
        if (session == null || session.device == null) {
            if (session != null) session.rowCount = 0;
            return 0;
        }
        SmartHomeManager mgr = SmartHomeManager.get(ctx);
        SmartHomeManager.Device dev = session.device;
        SmartHomeManager.Protocol p = dev.getProtocol();
        if (p == SmartHomeManager.Protocol.SENSIBO_AC || p == SmartHomeManager.Protocol.TUYA_AC) {
            String mode = dev.getAcMode();
            String modeLabel = mode.isEmpty() ? "Cool" : (Character.toUpperCase(mode.charAt(0)) + mode.substring(1));
            String fan = dev.getFanSpeed();
            String fanLabel = fan.isEmpty() ? "Auto" : (Character.toUpperCase(fan.charAt(0)) + fan.substring(1));
            labels[0] = Ui.tr("Mode");
            values[0] = Ui.tr(modeLabel);
            labels[1] = Ui.tr("Temp");
            values[1] = dev.getTargetTempC() + "\u00B0C";
            labels[2] = Ui.tr("Fan Strength");
            values[2] = Ui.tr(fanLabel);
            icons[0] = "sliders";
            icons[1] = "thermometer";
            icons[2] = "fan";
            session.rowCount = 3;
        } else if (p == SmartHomeManager.Protocol.WIZ_LIGHT) {
            List<String> rooms = mgr.getLightRooms();
            session.roomIndex = Math.max(0, Math.min(session.roomIndex, rooms.size() - 1));
            String selectedRoom = rooms.get(session.roomIndex);
            List<SmartHomeManager.Device> bulbs = mgr.getLightBulbsForRoom(selectedRoom);
            boolean showBulbsRow = shouldShowWizBulbsRow(selectedRoom, bulbs.size());
            if (showBulbsRow) {
                session.bulbIndex = Math.max(0, Math.min(session.bulbIndex, bulbs.size()));
            } else {
                session.bulbIndex = 0;
            }
            int rowIdx = 0;
            labels[rowIdx] = Ui.tr("Room");
            values[rowIdx] = "All".equalsIgnoreCase(selectedRoom) ? Ui.tr("All") : selectedRoom;
            icons[rowIdx] = "home";
            rowIdx++;

            boolean roomOn = mgr.isRoomLightsOn(selectedRoom, session.bulbIndex);
            labels[rowIdx] = Ui.tr("Turn On / Off");
            values[rowIdx] = roomOn ? Ui.tr("ON") : Ui.tr("OFF");
            icons[rowIdx] = "power";
            rowIdx++;

            if (showBulbsRow) {
                String bulbLabel = (session.bulbIndex == 0)
                        ? (Ui.tr("All") + " (" + bulbs.size() + ")")
                        : bulbs.get(session.bulbIndex - 1).getName();
                labels[rowIdx] = Ui.tr("Bulbs");
                values[rowIdx] = bulbLabel;
                icons[rowIdx] = "lightbulb";
                rowIdx++;
            }
            labels[rowIdx] = Ui.tr("Strength");
            values[rowIdx] = dev.getBrightnessPct() + "%";
            icons[rowIdx] = "sunny";
            rowIdx++;

            labels[rowIdx] = Ui.tr("Temperature");
            values[rowIdx] = formatKelvinLabel(
                    dev.getColorTempK(),
                    Ui.tr("Warm"),
                    Ui.tr("Soft White"),
                    Ui.tr("Neutral"),
                    Ui.tr("Daylight"),
                    Ui.tr("Cool"));
            icons[rowIdx] = "thermometer";
            rowIdx++;
            session.rowCount = rowIdx;
        } else if (p == SmartHomeManager.Protocol.SWITCHER_BOILER) {
            labels[0] = Ui.tr("Timer");
            values[0] = dev.getTimerMinutes() + " " + Ui.tr("min");
            icons[0] = "shower";
            session.rowCount = 1;
        } else {
            labels[0] = Ui.tr("Power");
            values[0] = dev.isOn() ? Ui.tr("ON") : Ui.tr("OFF");
            icons[0] = "power";
            session.rowCount = 1;
        }
        return session.rowCount;
    }

    /**
     * Adjusts the currently focused Smart-Home Device Control Card parameter when pressing Left/Right/OK.
     */
    void adjustSmartDeviceValue(
            Context ctx,
            ControlCardSession session,
            int menuIndex,
            int dir,
            SmartHomeManager.Callback onRemoteUpdated) {
        if (session == null || session.device == null || dir == 0) return;
        SmartHomeManager mgr = SmartHomeManager.get(ctx);
        SmartHomeManager.Device dev = session.device;
        SmartHomeManager.Protocol p = dev.getProtocol();
        if (p == SmartHomeManager.Protocol.SENSIBO_AC || p == SmartHomeManager.Protocol.TUYA_AC) {
            String[] modes = {"cool", "heat", "fan", "dry", "auto"};
            String[] fans = {"auto", "low", "medium", "high"};
            String mode = dev.getAcMode();
            int temp = dev.getTargetTempC();
            String fan = dev.getFanSpeed();
            if (menuIndex == 0) {
                int idx = 0;
                for (int i = 0; i < modes.length; i++) {
                    if (modes[i].equalsIgnoreCase(mode)) {
                        idx = i;
                        break;
                    }
                }
                mode = modes[(idx + dir + modes.length) % modes.length];
            } else if (menuIndex == 1) {
                temp = Math.max(16, Math.min(30, temp + dir));
            } else if (menuIndex == 2) {
                int idx = 0;
                for (int i = 0; i < fans.length; i++) {
                    if (fans[i].equalsIgnoreCase(fan)) {
                        idx = i;
                        break;
                    }
                }
                fan = fans[(idx + dir + fans.length) % fans.length];
            }
            mgr.setAcState(dev.getId(), true, mode, temp, fan, onRemoteUpdated);
        } else if (p == SmartHomeManager.Protocol.WIZ_LIGHT) {
            List<String> rooms = mgr.getLightRooms();
            String selectedRoom = rooms.get(Math.max(0, Math.min(session.roomIndex, rooms.size() - 1)));
            List<SmartHomeManager.Device> bulbs = mgr.getLightBulbsForRoom(selectedRoom);
            boolean hasBulbRow = shouldShowWizBulbsRow(selectedRoom, bulbs.size());
            int powerRow = 1;
            int bulbRow = hasBulbRow ? 2 : -1;
            int strengthRow = hasBulbRow ? 3 : 2;
            int tempRow = hasBulbRow ? 4 : 3;
            if (menuIndex == 0) {
                session.roomIndex = (session.roomIndex + dir + rooms.size()) % rooms.size();
                session.bulbIndex = 0;
            } else if (menuIndex == powerRow) {
                boolean nextOn = !mgr.isRoomLightsOn(selectedRoom, session.bulbIndex);
                mgr.setRoomLightsPower(selectedRoom, session.bulbIndex, nextOn, onRemoteUpdated);
            } else if (hasBulbRow && menuIndex == bulbRow) {
                int totalOpts = bulbs.size() + 1;
                session.bulbIndex = (session.bulbIndex + dir + totalOpts) % totalOpts;
            } else if (menuIndex == strengthRow || menuIndex == tempRow) {
                int bright = dev.getBrightnessPct();
                int kelvin = dev.getColorTempK();
                int[] kelvins = {2700, 3000, 4000, 5000, 6500};
                if (menuIndex == strengthRow) {
                    bright = stepBrightness(bright, dir);
                } else {
                    int kIdx = 0;
                    int bestDiff = Integer.MAX_VALUE;
                    for (int i = 0; i < kelvins.length; i++) {
                        int d = Math.abs(kelvins[i] - kelvin);
                        if (d < bestDiff) {
                            bestDiff = d;
                            kIdx = i;
                        }
                    }
                    kelvin = kelvins[(kIdx + dir + kelvins.length) % kelvins.length];
                }
                String targetDevId = dev.getId();
                if (hasBulbRow && session.bulbIndex > 0 && session.bulbIndex - 1 < bulbs.size()) {
                    targetDevId = bulbs.get(session.bulbIndex - 1).getId();
                } else if (!"All".equalsIgnoreCase(selectedRoom) && bulbs.size() == 1) {
                    targetDevId = bulbs.get(0).getId();
                }
                dev.setLightState(true, bright, kelvin);
                mgr.setLightParameters(targetDevId, bright, kelvin, onRemoteUpdated);
            }
        } else if (p == SmartHomeManager.Protocol.SWITCHER_BOILER) {
            int mins = stepBoilerMinutes(dev.getTimerMinutes(), dir);
            dev.setBoilerTimerMinutes(mins);
            mgr.setBoilerTimer(dev.getId(), mins, onRemoteUpdated);
        }
    }

    /** Generic network connectivity hint shown on the Smart-Home screen (zero hardcoded SSIDs). */
    static final String SMART_HOME_BANNER_INFO =
            "Local devices (WiZ/Switcher) use your home Wi-Fi \u00B7 Cloud APIs (Sensibo/Tuya) work over any internet connection";

    /**
     * Renders the Smart-Home Wi-Fi &amp; Cloud connectivity info pill above the device rows.
     */
    float drawSmartHomeBanner(
            Canvas c,
            float y,
            float k,
            float marginVirtual,
            float contentRightVirtual,
            boolean isDark,
            Paint fill,
            Paint lSubTab,
            TextPaint fitPaint,
            RectF tmp) {
        float left = marginVirtual * k;
        float pillH = 44 * k;
        float padX = 20 * k;
        String info = Ui.tr(SMART_HOME_BANNER_INFO);
        fitPaint.set(lSubTab);
        String clipped = TextUtils.ellipsize(info, fitPaint, (contentRightVirtual - marginVirtual - 40) * k, TextUtils.TruncateAt.END).toString();
        float wPill = fitPaint.measureText(clipped) + padX * 2;
        tmp.set(left, y, left + wPill, y + pillH);
        fill.setShader(null);
        fill.setColor(isDark ? 0x24FFFFFF : 0x16000000);
        c.drawRoundRect(tmp, pillH / 2f, pillH / 2f, fill);
        c.drawText(clipped, tmp.left + padX, tmp.centerY() + Ui.halfCap(lSubTab), lSubTab);
        return y + pillH + 22 * k;
    }

    /**
     * Renders a single interactive Smart-Home Device Control Card parameter row (label + {@code chevron_left}
     * + value + {@code chevron_right}).
     */
    void drawSmartDeviceControlRow(
            Context ctx,
            Canvas c,
            RectF rowRect,
            float textX,
            float k,
            String label,
            String value,
            int optColor,
            Paint menuOptionPaint) {
        String lbl = label == null ? "" : label;
        String val = value == null ? "" : value;
        c.drawText(lbl, textX, rowRect.centerY() + Ui.halfCap(menuOptionPaint), menuOptionPaint);
        float valW = menuOptionPaint.measureText(val);
        float rightEdge = rowRect.right - 28 * k;
        Symbols.draw(ctx, c, "chevron_right", rightEdge - 10 * k, rowRect.centerY(), 18 * k, optColor);
        c.drawText(val, rightEdge - 28 * k - valW, rowRect.centerY() + Ui.halfCap(menuOptionPaint), menuOptionPaint);
        Symbols.draw(ctx, c, "chevron_left", rightEdge - 46 * k - valW, rowRect.centerY(), 18 * k, optColor);
    }

    /**
     * Renders a single {@code ROW_SMARTHOME} card (Smart-Home device tile or TV Input source tile) and
     * returns reusable title/subtitle metadata without per-frame heap allocations.
     */
    CardMeta drawSmartHomeBrowserCard(
            Context ctx,
            Canvas c,
            HomeApps.Item it,
            RectF cell,
            float radius,
            float k,
            float scale,
            float darkMix,
            int lInkColor,
            Paint fill) {
        reusableMeta.title = "";
        reusableMeta.subtitle = "";
        if (it == null) return reusableMeta;

        boolean isDark = darkMix > 0.5f;
        boolean on = (it.kind == HomeApps.SMART_DEVICE && it.smartDevice != null && it.smartDevice.isOn())
                || (it.kind == HomeApps.INPUT && it.connected);
        fill.setShader(null);
        fill.setColor(on ? (isDark ? 0xFF2563EB : 0xFFFFFFFF) : (isDark ? 0xFF2C2C2E : 0xFFE5E5EA));
        c.drawRoundRect(cell, radius, radius, fill);
        int iconInk = on ? (isDark ? 0xFFFFFFFF : 0xFF1D4ED8) : (isDark ? 0xCCFFFFFF : lInkColor);
        Symbols.draw(ctx, c, it.symbol, cell.centerX(), cell.centerY(), 40 * k * scale, iconInk);
        reusableMeta.title = it.smartDevice != null ? it.smartDevice.getName() : it.label;
        reusableMeta.subtitle = it.smartDevice != null
                ? it.smartDevice.getStatusText()
                : (on ? Ui.tr("Active") : Ui.tr("Standby"));
        return reusableMeta;
    }
}
