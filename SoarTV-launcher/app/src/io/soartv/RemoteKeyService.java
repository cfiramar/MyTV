package io.soartv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 * Also assists BluetoothPairingDialog on Android 14 / Xiaomi TVs when dialog buttons lack initial D-pad focus.
 */
public class RemoteKeyService extends AccessibilityService {
    private final BluetoothPairingAssist pairingAssist = new BluetoothPairingAssist();
    private final TvInputFieldSync inputFieldSync = new TvInputFieldSync();

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        inputFieldSync.attach(this);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (pairingAssist.onKeyEvent(this, event)) {
            return true;
        }
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        pairingAssist.onAccessibilityEvent(this, event);
        inputFieldSync.onAccessibilityEvent(this, event);
    }

    @Override public void onInterrupt() {
        pairingAssist.onInterrupt();
        inputFieldSync.onInterrupt();
    }

    @Override public void onDestroy() {
        inputFieldSync.detach(this);
        super.onDestroy();
    }
}
