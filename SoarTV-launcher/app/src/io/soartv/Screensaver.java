package io.soartv;

import android.service.dreams.DreamService;
import android.view.KeyEvent;

/**
 * The SoarTV screensaver (enabled in SoarTV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits; with Watch from the screensaver on (Home Settings), OK first opens the
 * title on screen in its app.
 */
public class Screensaver extends DreamService {
    private DreamView view;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Prefs.reload();
        Ui.request4kDisplayMode(getWindow());
        Watchdog.install(this);
        Ui.init(this);
        ScreensaverCatalog.ensureStartersExtracted(this, null);
        setInteractive(true);   // interactive: the keys come here instead of waking the TV
        setFullscreen(true);
        setScreenBright(true);
        view = new DreamView(this);
        setContentView(view);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isInteractive()) return super.dispatchKeyEvent(event);
        if (view != null && view.handleKeyEvent(event)) return true;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean backOrHome = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_HOME;
        if (ok || backOrHome) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (ok && Prefs.saverWatch() && view != null) view.watch();
                finish();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        Prefs.reload();
        if (view != null) view.start();
    }

    @Override public void onDreamingStopped() {
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
