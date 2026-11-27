package io.mytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TDD Unit Suite for the redesigned MyTV Settings Screen (UX Information Architecture,
 * Native Android TV Parity, 38%/62% Two-Panel Layout &amp; Focus Math, Dynamic Settings Search,
 * and Interactive Up/Down Sleep Timer Modal).
 */
public final class SettingsRedesignTests {

    private static int passed = 0;

    private static void assertEquals(Object expected, Object actual, String message) {
        if ((expected == null && actual != null) || (expected != null && !expected.equals(actual))) {
            throw new AssertionError(message + " | expected=" + expected + ", actual=" + actual);
        }
        passed++;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        passed++;
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
        passed++;
    }

    public static void main(String[] args) {
        runAllTests();
        System.out.println("ALL " + passed + " SETTINGS REDESIGN ASSERTIONS PASSED.");
    }

    public static void runAllTests() {
        testSleepTimerSmartDurationLadderAndExtension();
        testSleepTimerModalStateInteractions();
        testRedesignedRootHierarchyAndConditionalInitialSetup();
        testClutterConsolidationAndZeroConflatedDuplicates();
        testNativeAndroidTvParityAndDynamicTelemetry();
        testTwoPanelLayoutAndSectionHeaderSkipping();
        testSettingsSearchAndUniversalIconEmojiAudit();
    }

    private static void testSleepTimerSmartDurationLadderAndExtension() {
        // Curated likely TV sleep durations: 0 -> 10 -> 15 -> 20 -> 25 -> 30 -> 45 -> 60 -> 75 -> 90 -> 120
        int[] expectedUp = {10, 15, 20, 25, 30, 45, 60, 75, 90, 120, 135, 150, 165, 180};
        int cur = 0;
        for (int exp : expectedUp) {
            cur = SleepTimer.nextDurationUp(cur);
            assertEquals(exp, cur, "Step up reaches " + exp + " min");
        }

        // Continuing past 2 hours keeps extending in +15m intervals up to MAX_MINUTES (480)
        assertEquals(480, SleepTimer.nextDurationUp(465), "Extends to 480 min max");
        assertEquals(480, SleepTimer.nextDurationUp(480), "Clamps at 480 min max");

        // Down stepping mirrors cleanly back to Off (0)
        assertEquals(165, SleepTimer.nextDurationDown(180), "180 -> 165");
        assertEquals(120, SleepTimer.nextDurationDown(135), "135 -> 120");
        assertEquals(90, SleepTimer.nextDurationDown(120), "120 -> 90");
        assertEquals(75, SleepTimer.nextDurationDown(90), "90 -> 75");
        assertEquals(60, SleepTimer.nextDurationDown(75), "75 -> 60");
        assertEquals(45, SleepTimer.nextDurationDown(60), "60 -> 45");
        assertEquals(30, SleepTimer.nextDurationDown(45), "45 -> 30");
        assertEquals(25, SleepTimer.nextDurationDown(30), "30 -> 25");
        assertEquals(10, SleepTimer.nextDurationDown(15), "15 -> 10");
        assertEquals(0, SleepTimer.nextDurationDown(10), "10 -> 0 (Off)");
        assertEquals(0, SleepTimer.nextDurationDown(0), "0 stays 0");

        // Arbitrary in-progress running timer values (e.g., 18 min remaining) step cleanly and support +5 min add
        assertEquals(20, SleepTimer.nextDurationUp(18), "Running timer 18m steps up to 20m");
        assertEquals(15, SleepTimer.nextDurationDown(18), "Running timer 18m steps down to 15m");
        assertEquals(23, SleepTimer.addMinutes(18, 5), "Quick +5m adds 5 minutes directly to 18m -> 23m");
        assertEquals(125, SleepTimer.addMinutes(120, 5), "Quick +5m works even after top preset 120m -> 125m");
        assertEquals(480, SleepTimer.addMinutes(478, 5), "addMinutes clamps at MAX_MINUTES");
        assertEquals(0, SleepTimer.addMinutes(2, -5), "addMinutes clamps at 0");

        // Pure formatting helpers (safe on headless JVM)
        assertEquals("Off", SleepTimer.formatDurationLabel(0), "0 formats as Off");
        assertEquals("15 min", SleepTimer.formatDurationLabel(15), "15 formats as 15 min");
        assertEquals("1 h", SleepTimer.formatDurationLabel(60), "60 formats as 1 h");
        assertEquals("1 h 30 min", SleepTimer.formatDurationLabel(90), "90 formats as 1 h 30 min");
        assertEquals("2 h 15 min", SleepTimer.formatDurationLabel(135), "135 formats as 2 h 15 min");
        assertEquals("", SleepTimer.formatSleepAtClock(0, 1_700_000_000_000L, true), "Off has empty sleep-at clock");
        assertFalse(SleepTimer.formatSleepAtClock(30, 1_700_000_000_000L, true).isEmpty(), "Active timer formats sleep-at clock");
    }

    private static void testSleepTimerModalStateInteractions() {
        // 1. Opening when Off starts focused on Duration Wheel with 0 min
        ModalOverlayRenderer.SleepTimerModalState st = new ModalOverlayRenderer.SleepTimerModalState(0);
        assertEquals(ModalOverlayRenderer.SleepTimerModalState.SLOT_DURATION, st.getFocusSlot(),
                "Modal opens focused on Duration Wheel");
        assertFalse(st.isEnabled(), "Initially Off");
        assertEquals(0, st.getMinutes(), "Initially 0 minutes");

        // Pressing Up on Duration Wheel immediately enables timer and steps through intervals
        st.moveFocus(0, -1);
        assertTrue(st.isEnabled(), "Pressing Up from Off turns timer On");
        assertEquals(10, st.getMinutes(), "First Up from Off sets 10 min");
        st.moveFocus(0, -1);
        assertEquals(15, st.getMinutes(), "Second Up sets 15 min");
        st.moveFocus(0, -1);
        assertEquals(20, st.getMinutes(), "Third Up sets 20 min");
        st.moveFocus(0, -1);
        assertEquals(25, st.getMinutes(), "Fourth Up sets 25 min");

        // Pressing Left moves to Power Toggle; toggling Off remembers last duration (25m) and toggling On restores it
        st.moveFocus(-1, 0);
        assertEquals(ModalOverlayRenderer.SleepTimerModalState.SLOT_TOGGLE, st.getFocusSlot(), "Left moves to On/Off toggle");
        st.toggleEnabled();
        assertFalse(st.isEnabled(), "Toggling Off disables timer");
        assertEquals(0, st.getMinutes(), "Minutes become 0 when toggled Off");
        st.toggleEnabled();
        assertTrue(st.isEnabled(), "Toggling back On restores previous duration");
        assertEquals(25, st.getMinutes(), "Restored previous duration 25 min");

        // Pressing Right -> Duration -> Right -> +5 min quick-add button
        st.moveFocus(1, 0);
        assertEquals(ModalOverlayRenderer.SleepTimerModalState.SLOT_DURATION, st.getFocusSlot(), "Right returns to Duration");
        st.moveFocus(1, 0);
        assertEquals(ModalOverlayRenderer.SleepTimerModalState.SLOT_ADD_FIVE, st.getFocusSlot(), "Right moves to +5 min");
        st.addFiveMinutes();
        assertEquals(30, st.getMinutes(), "+5 min increases 25 -> 30");
        st.addFiveMinutes();
        assertEquals(35, st.getMinutes(), "+5 min increases 30 -> 35");

        // Down from +5 min moves to bottom Cancel/Save row, Up returns to Duration
        st.moveFocus(0, 1);
        assertEquals(ModalOverlayRenderer.SleepTimerModalState.SLOT_CANCEL, st.getFocusSlot(), "Down from +5m goes to Cancel");
        st.moveFocus(-1, 0);
        assertEquals(ModalOverlayRenderer.SleepTimerModalState.SLOT_SAVE, st.getFocusSlot(), "Left from Cancel goes to Save");
        st.moveFocus(0, -1);
        assertEquals(ModalOverlayRenderer.SleepTimerModalState.SLOT_DURATION, st.getFocusSlot(), "Up from Save returns to Duration");

        // 2. Opening when timer already has 42 minutes running preserves live remaining minutes
        ModalOverlayRenderer.SleepTimerModalState running = new ModalOverlayRenderer.SleepTimerModalState(42);
        assertTrue(running.isEnabled(), "Running modal starts enabled");
        assertEquals(42, running.getMinutes(), "Running modal starts at 42 min");
        running.moveFocus(0, -1);
        assertEquals(45, running.getMinutes(), "Up from 42 min snaps up to 45 min");
        running.addFiveMinutes();
        assertEquals(50, running.getMinutes(), "+5m adds to 50 min even after preset");
    }

    private static void testRedesignedRootHierarchyAndConditionalInitialSetup() {
        // Case A: Initial setup COMPLETED -> Root starts directly with TV & Connectivity (zero setup clutter at top)
        SettingsTree.StateSnapshot doneSnap = new SettingsTree.StateSnapshot();
        doneSnap.initialSetupDone = true;
        SettingsTree.Node doneRoot = SettingsTree.buildRoot(doneSnap);

        List<SettingsTree.Node> selectableRoot = new ArrayList<>();
        List<SettingsTree.Node> sectionHeaders = new ArrayList<>();
        for (SettingsTree.Node child : doneRoot.children) {
            if (child.isSectionHeader()) sectionHeaders.add(child);
            else selectableRoot.add(child);
        }

        assertEquals(3, sectionHeaders.size(), "Completed root has 3 clean section headers");
        assertEquals(10, selectableRoot.size(), "Completed root has exactly 10 compact top-level categories (fits without scroll)");
        assertEquals("network_internet", selectableRoot.get(0).id, "Category 1: Network & Internet");
        assertEquals("display_sound", selectableRoot.get(1).id, "Category 2: Display & Sound");
        assertEquals("channels_inputs", selectableRoot.get(2).id, "Category 3: Channels & Inputs");
        assertEquals("remotes_accessories", selectableRoot.get(3).id, "Category 4: Remotes & Accessories");
        assertEquals("home_appearance", selectableRoot.get(4).id, "Category 5: Home & Appearance");
        assertEquals("gaming_settings", selectableRoot.get(5).id, "Category 6: Gaming");
        assertEquals("smarthome_settings", selectableRoot.get(6).id, "Category 7: Smart-Home");
        assertEquals("users", selectableRoot.get(7).id, "Category 8: Accounts & Profiles");
        assertEquals("apps_privacy", selectableRoot.get(8).id, "Category 9: Apps & Privacy");
        assertEquals("system_settings", selectableRoot.get(9).id, "Category 10: System");

        // Initial Setup & Cloud is still reachable inside System when completed
        SettingsTree.Node sysNode = doneRoot.findById("system_settings");
        assertTrue(sysNode != null && sysNode.findById("initial_setup") != null,
                "Initial Setup & Cloud remains accessible under System after completion");

        // Case B: Initial setup INCOMPLETE -> Promoted at the very top of root with WARNING badge
        SettingsTree.StateSnapshot freshSnap = new SettingsTree.StateSnapshot();
        freshSnap.initialSetupDone = false;
        freshSnap.traktConnected = false;
        SettingsTree.Node freshRoot = SettingsTree.buildRoot(freshSnap);

        int firstIdx = SettingsScreenRenderer.firstSelectableIndex(freshRoot.children);
        assertTrue(firstIdx >= 0, "Fresh root has selectable items");
        SettingsTree.Node firstSelectable = freshRoot.children.get(firstIdx);
        assertEquals("initial_setup", firstSelectable.id, "Incomplete Initial Setup is promoted to top of root");
        assertEquals(SettingsTree.StatusTone.WARNING, firstSelectable.statusTone,
                "Incomplete Initial Setup uses WARNING status tone");
    }

    private static void testClutterConsolidationAndZeroConflatedDuplicates() {
        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        snap.initialSetupDone = true;
        snap.traktConnected = true;
        snap.traktUser = "alex_tv";
        snap.stremioConnected = true;
        snap.stremioEmail = "user@example.com";
        SettingsTree.Node root = SettingsTree.buildRoot(snap);

        // 1. Gaming is consolidated from 12 flat rows into 5 crisp groups/items, preserving all existing IDs
        SettingsTree.Node gaming = root.findById("gaming_settings");
        assertTrue(gaming != null, "Gaming category exists");
        assertEquals(5, gaming.children.size(), "Gaming has 5 clean inner items instead of 12 flat rows");
        assertEquals("gaming_stream_quality", gaming.children.get(0).id, "Gaming 1: Stream Quality & Video");
        assertEquals("gaming_stream_audio_controls", gaming.children.get(1).id, "Gaming 2: Stream Audio & Controls");
        assertEquals("gaming_pc_group", gaming.children.get(2).id, "Gaming 3: Gaming PC group");
        assertEquals("gaming_controllers_group", gaming.children.get(3).id, "Gaming 4: Game Controllers group");
        assertEquals("shg_hdmi_ports", gaming.children.get(4).id, "Gaming 5: HDMI Gaming Ports");

        // All 4 Gaming PC items live inside gaming_pc_group
        SettingsTree.Node pcGroup = gaming.findById("gaming_pc_group");
        assertTrue(pcGroup.findById("shg_pc_sync") != null, "Gaming PC has Sync Library");
        assertTrue(pcGroup.findById("shg_pc_wake") != null, "Gaming PC has Wake Gaming PC");
        assertTrue(pcGroup.findById("shg_pc_pair") != null, "Gaming PC has Pair Gaming PC");
        assertTrue(pcGroup.findById("shg_pc_ip") != null, "Gaming PC has Gaming PC IP");

        // All 4 Controller items live inside gaming_controllers_group
        SettingsTree.Node ctrlGroup = gaming.findById("gaming_controllers_group");
        assertTrue(ctrlGroup.findById("gaming_bt_pair") != null, "Game Controllers has Pair Bluetooth");
        assertTrue(ctrlGroup.findById("gaming_controllers") != null, "Game Controllers has Controllers list");
        assertTrue(ctrlGroup.findById("gaming_ctrl_wizard") != null, "Game Controllers has Multi-Controller Wizard");
        assertTrue(ctrlGroup.findById("gaming_colors_rainbow") != null, "Game Controllers has Rainbow Colors");

        // TV Remote Buttons belongs in Remotes & Accessories, NOT Gaming
        assertEquals(null, gaming.findById("rem_buttons"), "TV Remote Buttons moved out of Gaming");
        SettingsTree.Node remotes = root.findById("remotes_accessories");
        assertTrue(remotes != null, "Remotes & Accessories category exists");
        assertTrue(remotes.findById("rem_buttons") != null, "TV Remote Buttons lives in Remotes & Accessories");
        assertTrue(remotes.findById("bt_sys_accessories") != null, "System Bluetooth Audio/Accessories lives in Remotes & Accessories");
        assertTrue(remotes.findById("paired_remotes") != null, "Companion Phone Remotes lives in Remotes & Accessories");

        // 2. Sleep Timer is consolidated into ONE interactive modal row + overlay options (no 6 inline duration rows)
        SettingsTree.Node power = root.findById("sys_power");
        assertTrue(power != null, "Power & Sleep Timer exists");
        assertTrue(power.findById("sys_sleep_modal") != null, "Single Sleep Timer modal row exists");
        assertEquals(null, power.findById("sys_sleep:15"), "Old flat inline 15-min row removed");
        assertEquals(null, power.findById("sys_sleep:60"), "Old flat inline 60-min row removed");
        assertTrue(power.findById("sys_sleep_show") != null, "Show Time Left toggle exposed");
        assertTrue(power.findById("sys_sleep_place") != null, "Timer Badge Position exposed");
        assertTrue(power.findById("sys_power_on") != null, "At Power-On behavior exposed");
        assertTrue(power.findById("sys_restart") != null, "Restart TV exposed");
        assertEquals(SettingsTree.RowType.ACTION, power.findById("sys_restart").rowType, "Restart TV triggers direct confirmation modal");

        // 3. Intentional cross-category guide links: Accounts has Trakt & Stremio directly AND Initial Setup still guides them
        SettingsTree.Node accounts = root.findById("users");
        SettingsTree.Node initialSetup = root.findById("initial_setup");
        assertTrue(accounts.findById("trakt_auth") != null && initialSetup.findById("trakt_auth") != null,
                "Trakt Account available directly under Accounts & Profiles AND inside Initial Setup guide");
        assertTrue(accounts.findById("stremio_auth") != null && initialSetup.findById("stremio_auth") != null,
                "Stremio Account available directly under Accounts & Profiles AND inside Initial Setup guide");

        // Smart-Home has TV Room, WiZ scan, AND Integrations directly AND Initial Setup still guides them
        SettingsTree.Node smarthome = root.findById("smarthome_settings");
        assertTrue(smarthome.findById("shg_tv_room") != null, "Smart-Home has TV Room");
        assertTrue(smarthome.findById("shg_wiz_scan") != null, "Smart-Home has WiZ Bulb Scan");
        assertTrue(smarthome.findById("setup_key_sensibo") != null, "Smart-Home has Sensibo API Key");
        assertTrue(smarthome.findById("setup_key_tuya") != null, "Smart-Home has Tuya Cloud Keys");

        // 4. Three distinct 'Location' concepts are strictly separate and unambiguously named (never conflated!)
        SettingsTree.Node weatherCity = root.findById("theme_sun_location");
        SettingsTree.Node sysLocation = root.findById("priv_sys_location");
        SettingsTree.Node saverLocationCaption = root.findById("ss_toggle_location");
        assertTrue(weatherCity != null && sysLocation != null && saverLocationCaption != null,
                "All 3 distinct Location-related settings exist independently");
        assertFalse(weatherCity.title.equalsIgnoreCase(sysLocation.title),
                "Weather/Sunrise City and System App Location Access have distinct titles");
        assertFalse(sysLocation.title.equalsIgnoreCase(saverLocationCaption.title),
                "System App Location Access and Screensaver Location Caption have distinct titles");
        assertEquals(SettingsTree.RowType.EXTERNAL, sysLocation.rowType,
                "System Location Access is clearly marked EXTERNAL");
        assertEquals(SettingsTree.RowType.TOGGLE, saverLocationCaption.rowType,
                "Screensaver Location Caption is clearly marked TOGGLE");
    }

    private static void testNativeAndroidTvParityAndDynamicTelemetry() {
        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        snap.initialSetupDone = true;
        snap.networkConnected = true;
        snap.networkType = "Wi-Fi";
        snap.wifiSsid = "StudioMesh-5G";
        snap.tvIpAddress = "192.168.50.42";
        snap.displaySummary = "3840x2160 @ 60Hz";
        snap.deviceName = "Living Room TV";
        snap.deviceModel = "BRAVIA-4K-VH22";
        snap.androidVersion = "13";
        snap.securityPatch = "2026-08-05";
        snap.appVersion = "1.4";
        snap.storageSummary = "6.1 GB free of 16.0 GB";
        snap.developerOptionsEnabled = true;
        snap.externalInputs = Arrays.asList(
                new String[] {"input_hdmi1", "HDMI 1 (PS5)", "1"},
                new String[] {"input_hdmi2", "HDMI 2 (Apple TV)", "1"},
                new String[] {"input_hdmi3", "HDMI 3", "0"}
        );
        SettingsTree.Node root = SettingsTree.buildRoot(snap);

        // Zero hardcoded personal or single-TV strings anywhere in the entire tree!
        List<SettingsTree.Node> all = new ArrayList<>();
        collectAllNodes(root, all);
        for (SettingsTree.Node n : all) {
            assertFalse(n.title.contains("PrivateHomeWiFi") || n.subtitle.contains("PrivateHomeWiFi") || n.value.contains("PrivateHomeWiFi"),
                    "Zero hardcoded 'PrivateHomeWiFi' SSID anywhere in SettingsTree: " + n.id);
            assertFalse(n.title.contains("SmartTV-4K") || n.subtitle.contains("SmartTV-4K") || n.value.contains("SmartTV-4K"),
                    "Zero hardcoded 'SmartTV-4K' model anywhere in SettingsTree: " + n.id);
        }

        // Dynamic live telemetry verification
        assertEquals("StudioMesh-5G", root.findById("network_internet").value, "Network reflects live Wi-Fi SSID");
        assertTrue(root.findById("net_details").subtitle.contains("192.168.50.42"), "Network details displays live IPv4");
        assertEquals("3840x2160 @ 60Hz", root.findById("display_sound").value, "Display & Sound reflects live Display.Mode");
        assertEquals("6.1 GB free", root.findById("sys_storage").value, "Storage shows concise free space badge on rail");
        assertTrue(root.findById("sys_storage").subtitle.contains("6.1 GB free of 16.0 GB"), "Storage subtitle shows full live StatFs readout");
        assertEquals("BRAVIA-4K-VH22", root.findById("sys_about_device").value, "About reflects real Build.MODEL");
        assertTrue(root.findById("sys_dev_options") != null, "Developer Options visible when enabled on TV");

        // Dynamic External Inputs from TvInputManager
        SettingsTree.Node extInputs = root.findById("ci_external_inputs");
        assertTrue(extInputs != null && extInputs.children.size() == 3, "External Inputs reflects live TV inputs");
        assertEquals("HDMI 1 (PS5)", extInputs.children.get(0).title, "Live input 1 label preserved");
        assertEquals("Connected", extInputs.children.get(0).value, "Live input 1 shows Connected");
        assertEquals("", extInputs.children.get(2).value, "Disconnected input shows empty status");

        // Missing native TV features added
        assertTrue(root.findById("ci_cast_open") != null, "Google Cast built-in present");
        assertTrue(root.findById("ss_sys_dream") != null, "System Screensaver & Idle Timer present");
        assertTrue(root.findById("priv_assistant_open") != null, "Voice & Assistant settings present");
        assertTrue(root.findById("ui_clock_fmt") != null, "Clock Format (12h/24h/Auto) exposed in Home & Appearance");
        assertTrue(root.findById("ui_temp_unit") != null, "Temperature Unit (°C/°F/Auto) exposed in Home & Appearance");
        assertTrue(root.findById("ui_recents") != null, "Recent Apps Switcher exposed in Home & Appearance");
        assertTrue(root.findById("sys_mytv_lang") != null, "MyTV Display Language exposed in System -> Language & Keyboard");
        assertTrue(root.findById("system_settings").children.stream().anyMatch(n -> "atv_open_full".equals(n.id)),
                "Open Native TV Settings is directly reachable at bottom of System");

        // Developer Options hidden when disabled
        snap.developerOptionsEnabled = false;
        SettingsTree.Node rootNoDev = SettingsTree.buildRoot(snap);
        assertEquals(null, rootNoDev.findById("sys_dev_options"), "Developer Options hidden when disabled on TV");
    }

    private static void testTwoPanelLayoutAndSectionHeaderSkipping() {
        // 38% Left Rail / 62% Right Pane proportions + 42%/58% Live Preview proportions + 100% full-width leaf
        assertTrue(Math.abs(SettingsScreenRenderer.computeLeftColumnWidth(1760f, 28f, false) - (1732f * 0.38f)) < 0.01f,
                "Two-panel left rail uses 38% of available width");
        assertTrue(Math.abs(SettingsScreenRenderer.computeLeftColumnWidth(1760f, 28f, true) - 1760f) < 0.01f,
                "Full-width leaf uses 100% width");
        assertTrue(Math.abs(SettingsScreenRenderer.computePreviewLeftWidth(1760f, 28f) - (1732f * 0.42f)) < 0.01f,
                "Screensaver preview left column uses 42% leaving 58% for 4K preview");

        // Section header focus skipping
        SettingsTree.Node h1 = SettingsTree.sectionHeader("h1", "TV & CONNECTIVITY");
        SettingsTree.Node a = new SettingsTree.Node("a", "Network", "", "", "wifi", false, null);
        SettingsTree.Node b = new SettingsTree.Node("b", "Display", "", "", "tv", false, null);
        SettingsTree.Node h2 = SettingsTree.sectionHeader("h2", "MYTV");
        SettingsTree.Node c = new SettingsTree.Node("c", "Gaming", "", "", "gamepad", false, null);
        List<SettingsTree.Node> list = Arrays.asList(h1, a, b, h2, c);

        assertEquals(1, SettingsScreenRenderer.firstSelectableIndex(list), "firstSelectableIndex skips leading section header");
        assertEquals(2, SettingsScreenRenderer.nextSelectableIndex(list, 1, 1), "Down from a -> b");
        assertEquals(4, SettingsScreenRenderer.nextSelectableIndex(list, 2, 1), "Down from b skips h2 -> c");
        assertEquals(4, SettingsScreenRenderer.nextSelectableIndex(list, 4, 1), "Down at bottom clamps at c");
        assertEquals(2, SettingsScreenRenderer.nextSelectableIndex(list, 4, -1), "Up from c skips h2 -> b");
        assertEquals(-1, SettingsScreenRenderer.nextSelectableIndex(list, 1, -1),
                "Up from first selectable item signals -1 to focus Top-Bar");
    }

    private static void testSettingsSearchAndUniversalIconEmojiAudit() {
        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        snap.initialSetupDone = true;
        SettingsTree.Node root = SettingsTree.buildRoot(snap);

        // Search finds deep settings with valid pathIds and deduplicates cross-category links
        List<SettingsTree.SearchMatch> bitrateHits = SettingsTree.searchNodes(root, "bitrate");
        assertTrue(!bitrateHits.isEmpty(), "Search 'bitrate' returns Video Bitrate");
        assertEquals("gaming_stream_bitrate", bitrateHits.get(0).node.id, "Top match for 'bitrate' is gaming_stream_bitrate");
        assertTrue(bitrateHits.get(0).pathIds.contains("gaming_settings"), "Bitrate path includes gaming_settings");

        List<SettingsTree.SearchMatch> sleepHits = SettingsTree.searchNodes(root, "sleep timer");
        assertTrue(!sleepHits.isEmpty(), "Search 'sleep timer' finds Sleep Timer modal");
        assertTrue(sleepHits.stream().anyMatch(m -> "sys_sleep_modal".equals(m.node.id)),
                "Sleep Timer search hits sys_sleep_modal");

        List<SettingsTree.SearchMatch> traktHits = SettingsTree.searchNodes(root, "trakt");
        Set<String> seenIds = new HashSet<>();
        for (SettingsTree.SearchMatch m : traktHits) {
            assertFalse(m.node.isSectionHeader(), "Search never returns section headers");
            assertTrue(seenIds.add(m.node.id), "Search deduplicates cross-category node IDs: " + m.node.id);
        }

        // Universal audit: every single node in BOTH fresh and completed trees has valid Symbols icon & zero emojis
        for (boolean done : new boolean[] {false, true}) {
            snap.initialSetupDone = done;
            snap.developerOptionsEnabled = true;
            SettingsTree.Node tree = SettingsTree.buildRoot(snap);
            List<SettingsTree.Node> allNodes = new ArrayList<>();
            collectAllNodes(tree, allNodes);
            assertTrue(allNodes.size() > 80, "Redesigned SettingsTree has comprehensive node hierarchy");
            for (SettingsTree.Node n : allNodes) {
                if (!n.isSectionHeader()) {
                    assertTrue(Symbols.isSupportedIcon(n.icon),
                            "Supported Symbols icon required (node=" + n.id + ", icon=" + n.icon + ")");
                }
                assertFalse(containsEmoji(n.title) || containsEmoji(n.subtitle) || containsEmoji(n.value),
                        "Zero emojis permitted (node=" + n.id + ")");
            }
        }

        // Entering category/group panes must focus row 0 (never jump to a checked GROUP/TOGGLE status row),
        // while entering single-choice radio lists (RowType.CHECK) still focuses the active checked choice.
        SettingsTree.StateSnapshot focusSnap = new SettingsTree.StateSnapshot();
        focusSnap.initialSetupDone = true;
        focusSnap.sleepShown = true;
        focusSnap.powerOnMode = "home";
        SettingsTree.Node focusRoot = SettingsTree.buildRoot(focusSnap);
        SettingsTree.Node sys = focusRoot.findById("system_settings");
        assertEquals(0, SettingsScreenRenderer.findCheckedOrFirstIndex(sys.children),
                "Entering System focuses row 0 (About & Device Info), not completed Initial Setup");
        SettingsTree.Node pwr = focusRoot.findById("sys_power");
        assertEquals(0, SettingsScreenRenderer.findCheckedOrFirstIndex(pwr.children),
                "Entering Power & Sleep Timer focuses row 0 (Sleep Timer), not active toggle row");
        SettingsTree.Node pwrOn = focusRoot.findById("sys_power_on");
        assertEquals(1, SettingsScreenRenderer.findCheckedOrFirstIndex(pwrOn.children),
                "Entering At Power-On radio list still focuses checked RowType.CHECK choice (MyTV Home Screen)");
    }

    private static void collectAllNodes(SettingsTree.Node node, List<SettingsTree.Node> out) {
        if (node == null) return;
        if (!"root".equals(node.id)) out.add(node);
        for (SettingsTree.Node child : node.children) {
            collectAllNodes(child, out);
        }
    }

    private static boolean containsEmoji(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 0x1F300 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x26FF) || (cp >= 0x2700 && cp <= 0x27BF)) {
                return true;
            }
        }
        return false;
    }
}
