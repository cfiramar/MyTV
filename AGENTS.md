# MyTV — Guide, Safety Protocol & Adaptation Rules for AI Setup Assistants

Welcome! This file (`AGENTS.md`, mirrored for Claude Code via [`CLAUDE.md`](CLAUDE.md)) gives AI coding agents everything needed to help a human user configure, customize, and safely install **MyTV** (`io.mytv`).

For step-by-step terminal commands, also read:
- **[`docs/agents/install-playbook.md`](docs/agents/install-playbook.md)** — End-to-end build, install, secret-sync, LG/Samsung packaging, and PC gaming runbook.
- **[`docs/agents/tv-brand-adaptation.md`](docs/agents/tv-brand-adaptation.md)** — Inspecting unlisted TV models and extending remote/input adapters safely.
- **[`docs/supabase-schema.sql`](docs/supabase-schema.sql)** — Optional PostgreSQL Dual-Path RLS schema for Multi-User Profiles & Encrypted Cloud Backup.

---

## 1. Guiding Your Human User (Intake & Optional Tabs)

1. **Always Start with a Brief Human Intake**:
   - Check whether the user already saved choices in `MyTV-launcher/config.properties` or `.env` (for example via the visual **MyTV Setup Studio**: `Setup-MyTV.bat` / `./setup-mytv.sh` / `python setup_wizard.py`).
   - Ask the user for any missing basics:
     1. Their **TV OS & Wi-Fi IP address** (`Android TV / Google TV`, `Amazon Fire TV`, `LG webOS`, or `Samsung Tizen`).
     2. Their **city** (for Open-Meteo weather and sunrise/sunset themes).
     3. Whether they want the optional **Smart Home** tab (`enable_smarthome=true` vs `enable_smarthome=false`) and optional **PC Gaming** tab (`enable_gaming=true` vs `enable_gaming=false`). If a user does not use smart-home devices or PC game streaming on their TV, set the corresponding flag to `false` (or leave `auto` with no keys) so [`FeatureTabs.java`](MyTV-launcher/app/src/io/mytv/FeatureTabs.java) cleanly removes those tabs from both the TV Top-Bar and the Phone Companion Remote.
   - *(Note: Shared Reels is currently Work in Progress and disabled (`ENABLE_REELS = "false"`) in public builds.)*
2. **Private Free-Tier API Keys**:
   - Guide the user to create their own free **TMDB** key (`tmdb_key`, required for catalogs) plus any optional keys they want (see [`README.md`](README.md) or open Setup Studio).
   - **Strict Secret Hygiene**: Never print, echo, or commit secret values from `config.properties`, `.env`, or `keys.txt`. All runtime secrets are encrypted at rest on the TV via `AndroidKeyStore` (`AES/GCM/NoPadding` in [`Secrets.java`](MyTV-launcher/app/src/io/mytv/Secrets.java)) using `python3 MyTV-launcher/tools/send_key.py <TV_IP> --sync-config`.

---

## 2. Smart TV Safety Protocol (`CRITICAL` — Read Before Connecting to Any TV)

Always ask the user before running any state-changing command on their TV. Installing `MyTV.apk` (`adb install -r MyTV.apk`) changes nothing on the TV until the user switches Launcher Mode inside **MyTV -> Settings**.

1. **Google TV `setupwraith` Ordering (Prevents Boot Loops)**:
   - On Google TV (`com.google.android.apps.tv.launcherx`), `com.google.android.tungsten.setupwraith` has a `RecoveryActivity` that hijacks `HOME` if `launcherx` is disabled while `setupwraith` remains enabled.
   - Switching **to MyTV**: disable `setupwraith` **before** disabling `launcherx` (classic Android 9 TVs without `launcherx` leave `setupwraith` untouched).
   - Switching **to Original**: re-enable `launcherx` **before** re-enabling `setupwraith`.
   - Never disable stock launchers on brick-prone firmware listed in `HomeMode.BRICKS` (`R3_GTV`, Huawei / HarmonyOS).
2. **Never Disable System Settings, Wi-Fi Owners, or TV Inputs**:
   - Never uninstall or clear data on system packages (only reversible `pm disable-user --user 0` via `HomeMode`), and never disable `com.android.tv.settings`, TV input services (`dumpsys tv_input`), `wifiOwners`, accessibility services, or IMEs.
   - Keep brand-critical packages in `HomeMode.PROTECTED` (Xiaomi `com.xiaomi.mitv.tvplayer`, Sony `com.sony.dtv.*` & Bravia Sync, Philips Ambilight/TPV remote services, Skyworth/JVC, Hisense, Chromecast `SabrinaRemote`, Walmart `onn`).
   - On **Amazon Fire OS** (`HomeMode.AMAZON`), Fire OS reserves the Home button (`priority=950`); MyTV runs via `FireHomeActivity` without disabling Amazon's launcher.
   - On **LG webOS & Samsung Tizen**, keep `MyTV-launcher/web/*.js` and `webos/service/*.js` strictly ES5-compatible (no `let`/`const`, arrow functions, `Promise`, `fetch`, CSS Grid, CSS variables, or `backdrop-filter`).
3. **Crash Watchdog & Disconnecting ADB Before Switching Modes**:
   - Keep process isolation intact (`:home`, `:stream`, `:keys`, `:sleep`, and main `Watchdog`/`ModeJob` in [`AndroidManifest.xml`](MyTV-launcher/app/AndroidManifest.xml)) so `Watchdog` can automatically restore the stock launcher over `127.0.0.1:5555` if `:home` ever crashes repeatedly.
   - **Always run `adb disconnect`** on the computer once installation finishes and before the user switches Launcher Mode on the TV — on Android 8–10, an active computer ADB session can intercept the TV's own `127.0.0.1:5555` loopback connection.

---

## 3. Needed & Likely Local Adjustments

Every household's TV, streaming services, and room setup is slightly different. Anticipate and handle these common local adjustments cleanly:

- **Enabling Optional Tabs Now or Later (`FeatureTabs.java`)**: Never delete `GamingZone.java` or `SmartHomeManager.java` to hide unused tabs — set `enable_gaming=false` / `enable_smarthome=false` in `config.properties`. If a user asks to add Gaming or Smart Home later, simply set the flag to `true`, collect their keys or game folders, and run `python3 MyTV-launcher/tools/send_key.py <TV_IP> --sync-config` (details in [`docs/agents/install-playbook.md`](docs/agents/install-playbook.md)).
- **Regional Streaming Apps & HDMI Port Labels**: Add regional IPTV/broadcaster packages to `HomeApps.SHELF` (`MyTV-launcher/app/src/io/mytv/HomeApps.java`) and label permanent consoles/streamers via `hdmi_port_roles` and `gaming_pc_hdmi` in `config.properties`.
- **Unlisted TV Brands or Remote Buttons**: Inspect `getprop` and `cmd tv_input list` over ADB, then register unlisted Live TV packages or vendor remote keycodes modularly in [`TvDeviceProfile.java`](MyTV-launcher/app/src/io/mytv/TvDeviceProfile.java) and [`RemoteKeys.java`](MyTV-launcher/app/src/io/mytv/RemoteKeys.java) (guide in [`docs/agents/tv-brand-adaptation.md`](docs/agents/tv-brand-adaptation.md)).
- **Windows Gaming PC Folders & Desk Monitors**: Set standalone game directories in `MYTV_GAMES_DIRS` and run `python MyTV-launcher/tools/gaming/laptop_gaming_agent.py --list-monitors` to list permanently attached desk monitors in `MYTV_NON_TV_MONITORS` (`.env`) so wireless streaming remains default whenever the TV HDMI cable is unplugged.
- **TCL Auto-Start & Android 14 Wireless Debugging**: On TCL Google TVs, grant `AUTO_START` / `APP_AUTO_START` via `appops`; on Android 14 Chromecast/streamers, pair via `adb pair <ip:port> <code>` then `adb tcpip 5555`.
