package io.mytv;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Encapsulates native in-app Bluetooth controller and remote discovery, pairing, HID profile
 * connection, and unpairing directly inside MyTV.
 *
 * <p><b>Why:</b> Dropping the user out of MyTV into Android TV's system Settings UI to pair a
 * Bluetooth gamepad breaks immersion and loses context before first-time color/name setup.
 * This service scans nearby Bluetooth controllers inside MyTV (filtering out uncategorized BLE
 * appliances/scales/TVs by default just like Android TV's {@code InputDeviceCriteria}), initiates
 * {@code createBond()}, stamps {@link BluetoothPairingAssist} for hands-free SSP confirmation,
 * connects the {@code HidHost} input profile ({@code BluetoothProfile} ID {@code 4}) immediately
 * upon bonding, and triggers MyTV's First-Time Controller Setup flow automatically.</p>
 */
public final class BluetoothControllerPairer {

    /** Android hidden {@code BluetoothProfile.HID_HOST} constant ({@code 4}). */
    private static final int PROFILE_HID_HOST = 4;

    private static final String PERM_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    private static final String PERM_BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN";

    /** Maximum automatic BR/EDR inquiry cycles per scan session so BLE TV remotes are never radio-starved. */
    public static final int MAX_AUTO_DISCOVERY_CYCLES = 2;
    /** Coexistence breathing window (ms) between BR/EDR inquiry cycles on shared 2.4 GHz Wi-Fi/BT combo chips. */
    public static final long DISCOVERY_BLE_BREATHING_DELAY_MS = 2500L;

    /** Bluetooth Major Device Class constants for pure-JVM unit test compatibility. */
    public static final int MAJOR_MISC = 0x0000;
    public static final int MAJOR_COMPUTER = 0x0100;
    public static final int MAJOR_PHONE = 0x0200;
    public static final int MAJOR_NETWORKING = 0x0300;
    public static final int MAJOR_AUDIO_VIDEO = 0x0400;
    public static final int MAJOR_PERIPHERAL = 0x0500;
    public static final int MAJOR_IMAGING = 0x0600;
    public static final int MAJOR_WEARABLE = 0x0700;
    public static final int MAJOR_TOY = 0x0800;
    public static final int MAJOR_HEALTH = 0x0900;
    public static final int MAJOR_UNCATEGORIZED = 0x1F00;

    /**
     * Immutable snapshot of a discovered Bluetooth controller or accessory candidate.
     */
    public static final class DiscoveredDevice {
        public final String address;
        public final String name;
        public final int majorClass;
        public final int rssi;
        public final boolean bonded;
        public final boolean gamepadHint;
        public final boolean hasHidUuid;

        public DiscoveredDevice(
                String address,
                String name,
                int majorClass,
                int rssi,
                boolean bonded,
                boolean gamepadHint
        ) {
            this(address, name, majorClass, rssi, bonded, gamepadHint, false);
        }

        public DiscoveredDevice(
                String address,
                String name,
                int majorClass,
                int rssi,
                boolean bonded,
                boolean gamepadHint,
                boolean hasHidUuid
        ) {
            this.address = address != null ? address.trim() : "";
            this.name = name != null ? name.trim() : "";
            this.majorClass = majorClass;
            this.rssi = rssi;
            this.bonded = bonded;
            this.gamepadHint = gamepadHint;
            this.hasHidUuid = hasHidUuid;
        }
    }

    /**
     * Callback interface notifying the UI of live scan updates and bonding results.
     */
    public interface Listener {
        void onDevicesUpdated(List<DiscoveredDevice> devices, boolean scanning, String statusMessage);
        void onDevicePaired(String address, String name);
        void onPairingFailed(String address, String name, String reason);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, DiscoveredDevice> rawDiscoveredByMac = new LinkedHashMap<>();
    private Listener listener;
    private boolean receiverRegistered = false;
    private boolean activeSession = false;
    private boolean discoveryPaused = false;
    private boolean showAllDevices = false;
    private String bondingAddress = null;
    private String bondingName = null;
    private int completedDiscoveryCycles = 0;

    /**
     * Creates a new in-app Bluetooth controller scanner/pairer bound to the application context.
     */
    public BluetoothControllerPairer(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    /**
     * Returns true when a Bluetooth device name strongly indicates a game controller or TV remote.
     */
    public static boolean isLikelyGamepadOrRemoteName(String name) {
        if (name == null) return false;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return false;
        return lower.contains("controller")
                || lower.contains("gamepad")
                || lower.contains("joystick")
                || lower.contains("joy-con")
                || lower.contains("joycon")
                || lower.contains("dualshock")
                || lower.contains("dualsense")
                || lower.contains("xbox")
                || lower.contains("gamesir")
                || lower.contains("nova")
                || lower.contains("8bitdo")
                || lower.contains("stadia")
                || lower.contains("luna")
                || lower.contains("flydigi")
                || lower.contains("gulikit")
                || lower.contains("machenike")
                || lower.contains("easysmx")
                || lower.contains("thunderobot")
                || lower.contains("razer")
                || lower.contains("steelseries")
                || lower.contains("hori")
                || lower.contains("powera")
                || lower.contains("backbone")
                || lower.contains("remote");
    }

    private static boolean looksLikeMacAddress(String s) {
        if (s == null || s.length() != 17) return false;
        char sep = s.charAt(2);
        if (sep != ':' && sep != '-') return false;
        for (int i = 0; i < 17; i++) {
            if (i % 3 == 2) {
                if (s.charAt(i) != sep) return false;
            } else {
                char c = s.charAt(i);
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex) return false;
            }
        }
        return true;
    }

    private static boolean isKnownNonControllerApplianceName(String trimmedName) {
        String lower = trimmedName.toLowerCase(Locale.ROOT);
        // Never block devices explicitly declaring controller / gamepad / remote
        if (lower.contains("controller") || lower.contains("gamepad") || lower.contains("joystick") || lower.contains("remote")) {
            return false;
        }
        return lower.contains("scale")
                || lower.startsWith("tv -")
                || lower.startsWith("tv ")
                || lower.contains("[tv]")
                || lower.contains("bravia")
                || lower.contains("mitv")
                || lower.contains("webos")
                || lower.contains("wiz")
                || lower.contains("sensibo")
                || lower.contains("switcher")
                || lower.contains("tuya")
                || lower.contains("yeelight")
                || lower.contains("govee")
                || lower.contains("airpods")
                || lower.contains("earbuds")
                || lower.contains("galaxy buds")
                || lower.contains("soundbar")
                || lower.contains("printer")
                || lower.contains("mi band")
                || lower.contains("apple watch")
                || lower.contains("galaxy watch");
    }

    /**
     * Pure filter deciding whether a discovered Bluetooth device should be surfaced in the
     * default in-app controller pairing list (matching native Android TV's peripheral/HID filter).
     */
    public static boolean shouldIncludeDiscoveredDevice(String name, String address, int majorClass) {
        return shouldIncludeDiscoveredDevice(name, address, majorClass, false, false);
    }

    /**
     * Extended pure filter supporting HID/HOGP service UUID advertisement hints and an optional
     * {@code showAllDevices} mode if the user chooses to inspect all named nearby Bluetooth devices.
     */
    public static boolean shouldIncludeDiscoveredDevice(
            String name,
            String address,
            int majorClass,
            boolean hasHidUuid,
            boolean showAllDevices
    ) {
        if (address == null || address.trim().isEmpty()) return false;
        if (name == null || name.trim().isEmpty()) return false;
        String trimmed = name.trim();
        if (trimmed.equalsIgnoreCase(address.trim()) || looksLikeMacAddress(trimmed)) {
            return false;
        }

        if (showAllDevices) {
            return true;
        }

        if (isKnownNonControllerApplianceName(trimmed)) {
            return false;
        }

        if (isLikelyGamepadOrRemoteName(trimmed) || hasHidUuid) {
            return true;
        }

        // Exclude phones, computers, networking, audio/video, imaging, wearables, health, and uncategorized/misc BLE beacons
        if (majorClass == MAJOR_PHONE
                || majorClass == MAJOR_COMPUTER
                || majorClass == MAJOR_NETWORKING
                || majorClass == MAJOR_AUDIO_VIDEO
                || majorClass == MAJOR_IMAGING
                || majorClass == MAJOR_WEARABLE
                || majorClass == MAJOR_HEALTH
                || majorClass == MAJOR_UNCATEGORIZED
                || majorClass == MAJOR_MISC) {
            return false;
        }

        return majorClass == MAJOR_PERIPHERAL;
    }

    /**
     * Sorts discovered candidates with unbonded gamepads/peripherals at the top, ordered by signal strength.
     */
    public static void sortDiscoveredDevices(List<DiscoveredDevice> list) {
        if (list == null || list.size() <= 1) return;
        Collections.sort(list, (a, b) -> {
            if (a.bonded != b.bonded) {
                return a.bonded ? 1 : -1;
            }
            if (a.gamepadHint != b.gamepadHint) {
                return a.gamepadHint ? -1 : 1;
            }
            boolean aPeriph = (a.majorClass == MAJOR_PERIPHERAL || a.hasHidUuid);
            boolean bPeriph = (b.majorClass == MAJOR_PERIPHERAL || b.hasHidUuid);
            if (aPeriph != bPeriph) {
                return aPeriph ? -1 : 1;
            }
            int rssiCmp = Integer.compare(b.rssi, a.rssi);
            if (rssiCmp != 0) return rssiCmp;
            return a.name.compareToIgnoreCase(b.name);
        });
    }

    /**
     * Returns whether all named Bluetooth devices are shown instead of only controllers & remotes.
     */
    public boolean isShowAllDevices() {
        return showAllDevices;
    }

    /**
     * Toggles between strict Controllers & Remotes filtering (default) and showing all named Bluetooth devices.
     */
    public void setShowAllDevices(boolean showAll) {
        this.showAllDevices = showAll;
        notifyUpdate(!discoveryPaused, showAll
                ? "Showing all nearby Bluetooth devices — select a device to pair:"
                : "Showing controllers & remotes — put your controller in pairing mode:");
    }

    /**
     * Returns true when a {@code createBond()} operation is currently in flight.
     */
    public boolean isBondingInProgress() {
        return bondingAddress != null;
    }

    /**
     * Ensures {@code BLUETOOTH_CONNECT} and {@code BLUETOOTH_SCAN} runtime permissions are granted on Android 12+,
     * self-granting silently via MyTV's Zero-PC loopback ADB session when available.
     */
    public static void ensureScanAndConnectPermissions(final Activity activity, final Runnable onReady) {
        if (activity == null) {
            if (onReady != null) onReady.run();
            return;
        }
        if (Build.VERSION.SDK_INT < 31) {
            if (onReady != null) onReady.run();
            return;
        }
        boolean hasConnect = activity.checkSelfPermission(PERM_BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        boolean hasScan = activity.checkSelfPermission(PERM_BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        if (hasConnect && hasScan) {
            if (onReady != null) onReady.run();
            return;
        }

        final Context appCtx = activity.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                Adb adb = Adb.connectSignedOnly(appCtx);
                if (adb != null) {
                    try {
                        String pkg = appCtx.getPackageName();
                        adb.shell("pm grant " + pkg + " " + PERM_BLUETOOTH_CONNECT);
                        adb.shell("pm grant " + pkg + " " + PERM_BLUETOOTH_SCAN);
                    } finally {
                        adb.close();
                    }
                }
            } catch (Throwable ignored) {
            }
            main.post(() -> {
                boolean nowConnect = activity.checkSelfPermission(PERM_BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
                boolean nowScan = activity.checkSelfPermission(PERM_BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
                if (!nowConnect || !nowScan) {
                    try {
                        activity.requestPermissions(new String[] {PERM_BLUETOOTH_CONNECT, PERM_BLUETOOTH_SCAN}, 8421);
                    } catch (Throwable ignored) {
                    }
                }
                if (onReady != null) onReady.run();
            });
        }, "bt-perm-grant").start();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!activeSession || intent == null) return;
            String action = intent.getAction();
            if (action == null) return;

            if (BluetoothDevice.ACTION_FOUND.equals(action) || BluetoothDevice.ACTION_NAME_CHANGED.equals(action)) {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) -75);
                handleDiscoveredBluetoothDevice(dev, rssi);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                notifyUpdate(true, "Put your controller in pairing mode — scanning nearby controllers…");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                completedDiscoveryCycles++;
                if (activeSession && !discoveryPaused && bondingAddress == null) {
                    if (completedDiscoveryCycles < MAX_AUTO_DISCOVERY_CYCLES) {
                        mainHandler.postDelayed(() -> {
                            if (activeSession && !discoveryPaused && bondingAddress == null) {
                                BluetoothAdapter adapter = getAdapter(appContext);
                                if (adapter != null && adapter.isEnabled()) {
                                    try {
                                        adapter.startDiscovery();
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }, DISCOVERY_BLE_BREATHING_DELAY_MS);
                        notifyUpdate(false, "Scan cycle " + completedDiscoveryCycles + "/" + MAX_AUTO_DISCOVERY_CYCLES + " finished — refreshing…");
                    } else {
                        discoveryPaused = true;
                        notifyUpdate(false, "Scan complete — select a controller or choose Rescan");
                    }
                } else if (!discoveryPaused && bondingAddress != null) {
                    notifyUpdate(false, "Pairing with " + (bondingName != null ? bondingName : bondingAddress) + "…");
                }
            } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (dev != null && bondingAddress != null && safeAddress(dev).equalsIgnoreCase(bondingAddress)) {
                    try {
                        dev.setPairingConfirmation(true);
                    } catch (Throwable ignored) {
                        // Normal on unprivileged apps; BluetoothPairingAssist in :keys auto-clicks Pair!
                    }
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                int prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (dev == null) return;
                String addr = safeAddress(dev);
                String name = safeName(dev);
                if (name.isEmpty() && bondingName != null) name = bondingName;
                if (name.isEmpty()) name = "Wireless Controller";

                if (state == BluetoothDevice.BOND_BONDING) {
                    notifyUpdate(false, "Confirming Bluetooth pairing with " + name + "…");
                } else if (state == BluetoothDevice.BOND_BONDED) {
                    BluetoothPairingAssist.clearPendingPairRequest(appContext);
                    connectHidHostProfile(appContext, dev);
                    if (bondingAddress == null || addr.equalsIgnoreCase(bondingAddress)) {
                        bondingAddress = null;
                        bondingName = null;
                        if (listener != null) {
                            listener.onDevicePaired(addr, ControllerManager.cleanDeviceName(name));
                        }
                    }
                } else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                    BluetoothPairingAssist.clearPendingPairRequest(appContext);
                    if (bondingAddress != null && addr.equalsIgnoreCase(bondingAddress)) {
                        bondingAddress = null;
                        bondingName = null;
                        if (listener != null) {
                            listener.onPairingFailed(addr, name, "Pairing was declined or timed out. Keep the controller blinking fast in pairing mode and try again.");
                        }
                        if (!discoveryPaused) {
                            restartDiscovery();
                        }
                    }
                }
            }
        }
    };

    /**
     * Starts live Bluetooth discovery and streams updates to {@code listener}.
     */
    public void start(Activity activity, Listener listener) {
        this.listener = listener;
        this.activeSession = true;
        this.discoveryPaused = false;
        this.bondingAddress = null;
        this.bondingName = null;
        this.rawDiscoveredByMac.clear();

        ensureScanAndConnectPermissions(activity, () -> {
            if (!activeSession || appContext == null) return;
            registerReceiverIfNeeded();
            BluetoothAdapter adapter = getAdapter(appContext);
            if (adapter == null) {
                notifyUpdate(false, "Bluetooth hardware is unavailable on this TV.");
                return;
            }
            try {
                if (!adapter.isEnabled()) {
                    adapter.enable();
                }
            } catch (Throwable ignored) {}

            restartDiscovery();
        });
    }

    /**
     * Restarts Bluetooth inquiry scan immediately.
     */
    public void restartDiscovery() {
        if (!activeSession || appContext == null) return;
        completedDiscoveryCycles = 0;
        discoveryPaused = false;
        BluetoothAdapter adapter = getAdapter(appContext);
        if (adapter == null) return;
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
            boolean started = adapter.startDiscovery();
            notifyUpdate(started, started
                    ? "Put your controller in pairing mode — scanning nearby controllers…"
                    : "Waiting for Bluetooth scanner permission…");
        } catch (Throwable t) {
            notifyUpdate(false, "Bluetooth scan error: " + t.getMessage());
        }
    }

    /**
     * Pauses background inquiry scanning when {@code HomeActivity} loses foreground focus without tearing down
     * active bond-state listeners.
     */
    public void pauseDiscoveryOnly() {
        discoveryPaused = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (appContext != null) {
            BluetoothAdapter adapter = getAdapter(appContext);
            if (adapter != null) {
                try {
                    if (adapter.isDiscovering()) {
                        adapter.cancelDiscovery();
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Initiates bonding ({@code createBond()}) with the selected discovered controller.
     * Stamps {@link BluetoothPairingAssist} so the system confirmation dialog auto-confirms hands-free.
     */
    public void pairDevice(DiscoveredDevice target) {
        if (target == null || target.address.isEmpty() || appContext == null) return;
        BluetoothAdapter adapter = getAdapter(appContext);
        if (adapter == null) return;
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
            BluetoothDevice raw = adapter.getRemoteDevice(target.address.toUpperCase(Locale.ROOT));
            if (raw == null) return;
            if (raw.getBondState() == BluetoothDevice.BOND_BONDED) {
                connectHidHostProfile(appContext, raw);
                if (listener != null) {
                    listener.onDevicePaired(target.address, ControllerManager.cleanDeviceName(target.name));
                }
                return;
            }
            bondingAddress = target.address;
            bondingName = target.name;
            BluetoothPairingAssist.recordPendingPairRequest(appContext, target.address, target.name);
            notifyUpdate(false, "Pairing with " + target.name + "…");
            boolean initiated = raw.createBond();
            if (!initiated) {
                BluetoothPairingAssist.clearPendingPairRequest(appContext);
                bondingAddress = null;
                bondingName = null;
                if (listener != null) {
                    listener.onPairingFailed(target.address, target.name, "Could not start Bluetooth pairing.");
                }
            }
        } catch (Throwable t) {
            BluetoothPairingAssist.clearPendingPairRequest(appContext);
            bondingAddress = null;
            bondingName = null;
            if (listener != null) {
                listener.onPairingFailed(target.address, target.name, "Bluetooth pairing failed: " + t.getMessage());
            }
        }
    }

    /**
     * Stops discovery and unregisters broadcast receivers cleanly when the dialog closes.
     */
    public void stop() {
        activeSession = false;
        discoveryPaused = true;
        bondingAddress = null;
        bondingName = null;
        mainHandler.removeCallbacksAndMessages(null);
        if (appContext != null) {
            BluetoothPairingAssist.clearPendingPairRequest(appContext);
            BluetoothAdapter adapter = getAdapter(appContext);
            if (adapter != null) {
                try {
                    if (adapter.isDiscovering()) {
                        adapter.cancelDiscovery();
                    }
                } catch (Throwable ignored) {}
            }
            if (receiverRegistered) {
                try {
                    appContext.unregisterReceiver(receiver);
                } catch (Throwable ignored) {}
                receiverRegistered = false;
            }
        }
    }

    /**
     * Connects Android's {@code BluetoothHidHost} ({@code BluetoothProfile} {@code 4}) to a newly bonded
     * or sleeping controller so its {@code InputDevice} activates immediately without manual intervention.
     */
    public static void connectHidHostProfile(Context context, final BluetoothDevice device) {
        if (context == null || device == null) return;
        final Context appCtx = context.getApplicationContext();
        final BluetoothAdapter adapter = getAdapter(appCtx);
        if (adapter == null) return;
        try {
            adapter.getProfileProxy(appCtx, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        Method mConnect = proxy.getClass().getMethod("connect", BluetoothDevice.class);
                        mConnect.setAccessible(true);
                        mConnect.invoke(proxy, device);
                    } catch (Throwable ignored) {
                    } finally {
                        try {
                            adapter.closeProfileProxy(profile, proxy);
                        } catch (Throwable ignored) {}
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {}
            }, PROFILE_HID_HOST);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Unpairs ({@code removeBond}) a bonded Bluetooth controller identified by MAC or {@code "bt:<mac>"} descriptor.
     */
    public static boolean unpairDevice(Context context, String macOrDescriptor) {
        if (context == null || macOrDescriptor == null) return false;
        String clean = macOrDescriptor.trim();
        if (clean.toLowerCase(Locale.ROOT).startsWith("bt:")) {
            clean = clean.substring(3).trim();
        }
        if (clean.isEmpty()) return false;
        BluetoothAdapter adapter = getAdapter(context);
        if (adapter == null) return false;
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(clean.toUpperCase(Locale.ROOT));
            if (dev == null) return false;
            Method mRemoveBond = dev.getClass().getMethod("removeBond");
            mRemoveBond.setAccessible(true);
            Object res = mRemoveBond.invoke(dev);
            return Boolean.TRUE.equals(res);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void registerReceiverIfNeeded() {
        if (receiverRegistered || appContext == null) return;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(receiver, f);
            }
            receiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasHidServiceUuid(BluetoothDevice dev) {
        if (dev == null) return false;
        try {
            ParcelUuid[] uuids = dev.getUuids();
            if (uuids != null) {
                for (ParcelUuid u : uuids) {
                    if (u == null || u.getUuid() == null) continue;
                    String s = u.getUuid().toString().toLowerCase(Locale.ROOT);
                    // Classic Bluetooth HID (0x1124) or BLE HID-over-GATT HOGP (0x1812)
                    if (s.startsWith("00001124-") || s.startsWith("00001812-")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void handleDiscoveredBluetoothDevice(BluetoothDevice dev, int rssi) {
        if (dev == null) return;
        String addr = safeAddress(dev);
        String name = safeName(dev);
        int major = MAJOR_UNCATEGORIZED;
        try {
            BluetoothClass bc = dev.getBluetoothClass();
            if (bc != null) {
                major = bc.getMajorDeviceClass();
            }
        } catch (Throwable ignored) {}

        boolean bonded = false;
        try {
            bonded = (dev.getBondState() == BluetoothDevice.BOND_BONDED);
        } catch (Throwable ignored) {}
        if (bonded) return;

        if (addr.isEmpty() || name.isEmpty() || name.equalsIgnoreCase(addr) || looksLikeMacAddress(name)) {
            return;
        }

        boolean hidUuid = hasHidServiceUuid(dev);
        boolean hint = isLikelyGamepadOrRemoteName(name) || major == MAJOR_PERIPHERAL || hidUuid;
        String key = addr.toUpperCase(Locale.ROOT);
        DiscoveredDevice prev = rawDiscoveredByMac.get(key);
        int mergedRssi = (rssi != Short.MIN_VALUE) ? rssi : (prev != null ? prev.rssi : -75);
        boolean mergedHid = hidUuid || (prev != null && prev.hasHidUuid);
        rawDiscoveredByMac.put(key, new DiscoveredDevice(
                key, ControllerManager.cleanDeviceName(name), major, mergedRssi, false, hint, mergedHid));

        if (shouldIncludeDiscoveredDevice(name, addr, major, mergedHid, showAllDevices)) {
            notifyUpdate(true, "Select a controller below to pair:");
        }
    }

    private void notifyUpdate(boolean scanning, String message) {
        if (listener == null) return;
        List<DiscoveredDevice> snapshot = new ArrayList<>();
        for (DiscoveredDevice d : rawDiscoveredByMac.values()) {
            if (shouldIncludeDiscoveredDevice(d.name, d.address, d.majorClass, d.hasHidUuid, showAllDevices)) {
                snapshot.add(d);
            }
        }
        sortDiscoveredDevices(snapshot);
        listener.onDevicesUpdated(snapshot, scanning, message);
    }

    private static BluetoothAdapter getAdapter(Context context) {
        if (context == null) return null;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                if (bm != null && bm.getAdapter() != null) return bm.getAdapter();
            } catch (Throwable ignored) {}
        }
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeAddress(BluetoothDevice dev) {
        try {
            String a = dev.getAddress();
            return a != null ? a.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeName(BluetoothDevice dev) {
        try {
            String n = dev.getName();
            return n != null ? n.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}
