package io.soartv;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure D-pad, grid, Back-stack, Row-scroll memory, and Pairing-modal navigation collaborator for {@link HomeView}.
 *
 * <p><b>Why:</b> Extracts deterministic focus routing, per-row horizontal scroll memory ({@code INC-05}),
 * hierarchical screen Back-stack transitions ({@code INC-06}), and 6-digit pairing prompt navigation out of
 * {@link HomeView} so all navigation state invariants are isolated in a pure-Java collaborator with zero
 * Android graphics dependencies (100% Desktop JVM unit-testable).
 *
 * <p><b>What:</b> Owns {@link RowScrollMemory}, {@link BackNavigationStack}, {@link #buildRowMemoryKey},
 * {@link #moveGridIndex}, {@link #formatSixDigitCode}, and {@link #movePairingModalFocus}.
 */
public final class HomeInputRouter {

    /** Number of selectable buttons on the on-TV "New Remote Detected" Liquid Glass platter. */
    public static final int PAIRING_BUTTON_COUNT = 4;

    /** Standard Android KeyEvent.KEYCODE_DPAD_* values (pure-Java constants for headless JVM tests). */
    public static final int KEYCODE_DPAD_UP = 19;
    public static final int KEYCODE_DPAD_DOWN = 20;
    public static final int KEYCODE_DPAD_LEFT = 21;
    public static final int KEYCODE_DPAD_RIGHT = 22;

    /**
     * Resolves a Companion Remote touchpad swipe delta {@code (dx, dy)} into a standard D-Pad keycode.
     *
     * <p><b>Why:</b> Routing touchpad vertical swipes into {@code wheel()} clamped focus at row {@code 0}
     * and prevented reaching the Top-Bar tabs ({@code Z_TOP}) or navigating Settings/Modals. Mapping swipes
     * directly to standard D-Pad keycodes guarantees 100% parity with the physical TV remote across every
     * screen, grid, Settings rail, Hold-OK menu, and modal.
     *
     * @param dx Horizontal swipe step delta (negative = left, positive = right).
     * @param dy Vertical swipe step delta (negative = up, positive = down).
     * @return Matching {@code KEYCODE_DPAD_*} constant, or {@code 0} if both deltas are zero.
     */
    public static int resolveRemoteScrollKeyCode(int dx, int dy) {
        if (Math.abs(dy) >= Math.abs(dx) && dy != 0) {
            return dy > 0 ? KEYCODE_DPAD_DOWN : KEYCODE_DPAD_UP;
        }
        if (dx != 0) {
            return dx > 0 ? KEYCODE_DPAD_RIGHT : KEYCODE_DPAD_LEFT;
        }
        return 0;
    }

    private final RowScrollMemory rowScrollMemory = new RowScrollMemory();
    private final BackNavigationStack backStack = new BackNavigationStack();

    /**
     * Creates a new {@link HomeInputRouter} instance safe for both Android TV runtime and headless JVM tests.
     */
    public HomeInputRouter() {
    }

    /**
     * Returns the bounded LRU row scroll and column focus memory ({@code INC-05}).
     *
     * @return Active {@link RowScrollMemory} instance.
     */
    public RowScrollMemory rowScrollMemory() {
        return rowScrollMemory;
    }

    /**
     * Returns the bounded hierarchical Back-navigation screen stack ({@code INC-06}).
     *
     * @return Active {@link BackNavigationStack} instance.
     */
    public BackNavigationStack backStack() {
        return backStack;
    }

    /**
     * Builds a deterministic row identity key so vertical Up/Down navigation restores each row's prior
     * column and horizontal scroll offset.
     *
     * @param screen   Active top-bar screen index.
     * @param subTab   Active media sub-tab ({@code 0} = TV Shows, {@code 1} = Movies).
     * @param rowKind  Row kind constant ({@code ROW_*}).
     * @param pickKind Optional recommendation row kind.
     * @param genreKey Optional genre key.
     * @param title    Row display title fallback.
     * @return Canonical row memory key.
     */
    public static String buildRowMemoryKey(
            int screen,
            int subTab,
            int rowKind,
            String pickKind,
            String genreKey,
            String title) {
        int scopedSubTab = (screen == HomeView.SCREEN_HOME && rowKind == HomeView.ROW_CONTINUE) ? 0 : subTab;
        String suffix = (genreKey != null && !genreKey.isEmpty())
                ? ("g:" + genreKey)
                : ((pickKind != null && !pickKind.isEmpty())
                        ? ("p:" + pickKind)
                        : ("t:" + (title != null ? title : "")));
        return screen + ":" + scopedSubTab + ":" + rowKind + ":" + suffix;
    }

    /**
     * Computes the next focused cell index inside a multi-column grid (such as All Apps), returning {@code -1}
     * when pressing Up from row 0 so the caller can promote focus to the Top-Bar.
     *
     * @param currentIdx Current focused index in {@code [0, totalItems - 1]}.
     * @param dx         Horizontal step ({@code -1}, {@code 0}, or {@code +1}).
     * @param dy         Vertical step ({@code -1}, {@code 0}, or {@code +1}).
     * @param columns    Number of columns per row.
     * @param totalItems Total items in the grid.
     * @return Next grid index, or {@code -1} when navigating Up out of row 0.
     */
    public static int moveGridIndex(int currentIdx, int dx, int dy, int columns, int totalItems) {
        if (totalItems <= 0 || columns <= 0) return 0;
        int clamped = Math.max(0, Math.min(totalItems - 1, currentIdx));
        int r = clamped / columns;
        int col = clamped % columns;
        int rows = (totalItems + columns - 1) / columns;
        if (dx != 0) {
            int candidate = clamped + dx;
            if (candidate >= 0 && candidate < totalItems && candidate / columns == r) {
                return candidate;
            }
            return clamped;
        }
        if (dy != 0) {
            int targetRow = r + dy;
            if (targetRow < 0) {
                return -1;
            }
            if (targetRow < rows) {
                return Math.min(totalItems - 1, targetRow * columns + col);
            }
        }
        return clamped;
    }

    /**
     * Formats a 6-digit pairing code with a clean middle space (e.g. {@code "482917"} -&gt; {@code "482 917"})
     * for effortless legibility from across the living room.
     *
     * @param raw Raw 6-digit pairing code string.
     * @return Formatted code string preserving leading zeros.
     */
    public static String formatSixDigitCode(String raw) {
        if (raw == null) return "--- ---";
        String clean = raw.trim();
        if (clean.length() == 6) {
            return clean.substring(0, 3) + " " + clean.substring(3);
        }
        return clean.isEmpty() ? "--- ---" : clean;
    }

    /**
     * Moves D-pad focus across the 4 action buttons of the on-TV {@code "New Remote Detected"} Liquid Glass modal
     * ({@code 0}: Guest 30d, {@code 1}: Household, {@code 2}: Admin, {@code 3}: Dismiss).
     *
     * @param currentFocus Current focused button index ({@code 0..3}).
     * @param dx           Horizontal D-pad delta.
     * @param dy           Vertical D-pad delta.
     * @return Clamped next button index in {@code [0, 3]}.
     */
    public static int movePairingModalFocus(int currentFocus, int dx, int dy) {
        int clamped = Math.max(0, Math.min(PAIRING_BUTTON_COUNT - 1, currentFocus));
        int step = dy != 0 ? (dy > 0 ? 1 : -1) : (dx != 0 ? (dx > 0 ? 1 : -1) : 0);
        if (step == 0) return clamped;
        return Math.max(0, Math.min(PAIRING_BUTTON_COUNT - 1, clamped + step));
    }

    /**
     * Resolves the initial D-pad focus slot on the on-TV Pairing Platter:
     * slot {@code 2} ({@code Make Admin}) for first-time Owner setup or explicit Admin invites so pressing
     * OK never accidentally pairs the owner's phone as a 30-day Guest, slot {@code 1} for Household invites,
     * and slot {@code 0} ({@code Allow Guest}) for subsequent general pairing requests.
     */
    public static int initialPairingModalFocus(RemoteAuthManager.Role presetRole, boolean bootstrapAdmin) {
        if (presetRole == RemoteAuthManager.Role.HOUSEHOLD) return 1;
        if (presetRole == RemoteAuthManager.Role.ADMIN || bootstrapAdmin) return 2;
        return 0;
    }

    /**
     * Bounded LRU per-row scroll and column memory ({@code INC-05}) so moving Up/Down between rows
     * restores each row's horizontal scroll offset and focused card instead of snapping back to column 0.
     */
    public static class RowScrollMemory {
        public static final int MAX_ENTRIES = 64;

        private static final class RowScrollState {
            final int col;
            final float scrollX;

            RowScrollState(int col, float scrollX) {
                this.col = Math.max(0, col);
                this.scrollX = Math.max(0f, scrollX);
            }
        }

        private final LinkedHashMap<String, RowScrollState> entries =
                new LinkedHashMap<String, RowScrollState>(32, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, RowScrollState> eldest) {
                        return size() > MAX_ENTRIES;
                    }
                };

        public RowScrollMemory() {
        }

        public void save(String rowKey, int col, float scrollX) {
            if (rowKey == null || rowKey.isEmpty()) return;
            entries.put(rowKey, new RowScrollState(col, scrollX));
        }

        public boolean contains(String rowKey) {
            return rowKey != null && entries.containsKey(rowKey);
        }

        public int restoreCol(String rowKey, int maxCount, int fallbackCol) {
            if (maxCount <= 0) return 0;
            RowScrollState e = rowKey != null ? entries.get(rowKey) : null;
            int raw = e != null ? e.col : fallbackCol;
            return Math.max(0, Math.min(maxCount - 1, raw));
        }

        public float restoreScrollX(String rowKey, float fallbackScrollX) {
            RowScrollState e = rowKey != null ? entries.get(rowKey) : null;
            return e != null ? e.scrollX : fallbackScrollX;
        }

        public void clear() {
            entries.clear();
        }
    }

    /**
     * Computes the right-column preview highlight index when stepping back out of a nested Settings column.
     *
     * <p><b>Why:</b> Keeps the right preview pane pointed at the exact child the user just returned from,
     * instead of snapping the preview highlight back to index {@code 0}.
     */
    public static int computeBackSettingsRightIndex(int[] depthIndices, int poppedDepth, int childCount) {
        if (depthIndices == null || childCount <= 0 || poppedDepth < 0 || poppedDepth >= depthIndices.length) {
            return 0;
        }
        return Math.max(0, Math.min(childCount - 1, depthIndices[poppedDepth]));
    }

    /**
     * Immutable screen cursor position saved on the Back stack so pressing Return restores the exact
     * prior screen and row/column/grid focus.
     */
    public static final class ScreenCursorState {
        public final int screen;
        public final int subTab;
        public final int homeRow;
        public final int homeCol;
        public final int gridIndex;

        public ScreenCursorState(int screen, int subTab, int homeRow, int homeCol, int gridIndex) {
            this.screen = screen;
            this.subTab = Math.max(0, subTab);
            this.homeRow = Math.max(0, homeRow);
            this.homeCol = Math.max(0, homeCol);
            this.gridIndex = Math.max(0, gridIndex);
        }
    }

    /**
     * Immutable full navigation snapshot preserving active screen, cursor focus, Back history, and
     * nested Settings column navigation across Android TV Settings returns and Activity recreations.
     */
    public static final class NavigationSnapshot {
        public final int activeScreen;
        public final int topIndex;
        public final int zone;
        public final int subTab;
        public final int homeRow;
        public final int homeCol;
        public final int gridIndex;
        public final List<String> settingsPathIds;
        public final int[] settingsDepthIndices;
        public final boolean settingsFocusRight;
        public final int settingsRightIndex;
        public final List<ScreenCursorState> backHistory;

        public NavigationSnapshot(
                int activeScreen,
                int topIndex,
                int zone,
                int subTab,
                int homeRow,
                int homeCol,
                int gridIndex,
                List<String> settingsPathIds,
                int[] settingsDepthIndices,
                boolean settingsFocusRight,
                int settingsRightIndex,
                List<ScreenCursorState> backHistory) {
            this.activeScreen = activeScreen;
            this.topIndex = topIndex;
            this.zone = zone;
            this.subTab = Math.max(0, subTab);
            this.homeRow = Math.max(0, homeRow);
            this.homeCol = Math.max(0, homeCol);
            this.gridIndex = Math.max(0, gridIndex);
            this.settingsPathIds = settingsPathIds == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(settingsPathIds));
            this.settingsDepthIndices = settingsDepthIndices == null
                    ? new int[16]
                    : Arrays.copyOf(settingsDepthIndices, settingsDepthIndices.length);
            this.settingsFocusRight = settingsFocusRight;
            this.settingsRightIndex = Math.max(0, settingsRightIndex);
            this.backHistory = backHistory == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(backHistory));
        }
    }

    /**
     * Bounded hierarchical screen Back-navigation stack ({@code INC-06}) preserving exact per-screen cursor state.
     */
    public static class BackNavigationStack {
        public static final int MAX_ENTRIES = 12;
        private final ArrayDeque<ScreenCursorState> stack = new ArrayDeque<>();

        public BackNavigationStack() {
        }

        public void pushScreen(int fromScreen, int toScreen) {
            pushScreenState(new ScreenCursorState(fromScreen, 0, 0, 0, 0), toScreen);
        }

        /**
         * Pushes the full cursor state of {@code fromState} when navigating to {@code toScreen}.
         */
        public void pushScreenState(ScreenCursorState fromState, int toScreen) {
            if (fromState == null || fromState.screen == toScreen) return;
            if (!stack.isEmpty() && stack.peekLast() != null && stack.peekLast().screen == fromState.screen) {
                stack.removeLast();
            }
            if (stack.size() >= MAX_ENTRIES) {
                stack.removeFirst();
            }
            stack.addLast(fromState);
        }

        public boolean isEmpty() {
            return stack.isEmpty();
        }

        public int size() {
            return stack.size();
        }

        public int popPrevious(int currentScreen, int fallbackScreen) {
            ScreenCursorState prev = popPreviousState(currentScreen, fallbackScreen);
            return prev.screen;
        }

        /**
         * Pops the most recent distinct screen cursor state, or returns a clean fallback cursor on {@code fallbackScreen}.
         */
        public ScreenCursorState popPreviousState(int currentScreen, int fallbackScreen) {
            while (!stack.isEmpty()) {
                ScreenCursorState prev = stack.removeLast();
                if (prev != null && prev.screen != currentScreen) {
                    return prev;
                }
            }
            return new ScreenCursorState(fallbackScreen, 0, 0, 0, 0);
        }

        public List<ScreenCursorState> snapshotStates() {
            return new ArrayList<>(stack);
        }

        public void restoreStates(List<ScreenCursorState> states) {
            stack.clear();
            if (states == null) return;
            for (ScreenCursorState st : states) {
                if (st == null) continue;
                if (stack.size() >= MAX_ENTRIES) stack.removeFirst();
                stack.addLast(st);
            }
        }

        public void clear() {
            stack.clear();
        }
    }
}
