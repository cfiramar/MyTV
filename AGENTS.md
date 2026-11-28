# SoarTV — Guide, Safety Protocol & Adaptation Rules for AI Setup Assistants

Welcome! This file (`AGENTS.md`, mirrored for Claude Code via [`CLAUDE.md`](CLAUDE.md)) gives AI coding agents everything needed to help a human user configure, customize, and safely install **SoarTV** (`io.soartv`).

For step-by-step terminal commands, also read:
- **[`docs/agents/install-playbook.md`](docs/agents/install-playbook.md)** — End-to-end build, install, secret-sync, LG/Samsung packaging, and PC gaming runbook.
- **[`docs/agents/tv-brand-adaptation.md`](docs/agents/tv-brand-adaptation.md)** — Inspecting unlisted TV models and extending remote/input adapters safely.
- **[`docs/supabase-schema.sql`](docs/supabase-schema.sql)** — Optional PostgreSQL Dual-Path RLS schema for Multi-User Profiles & Encrypted Cloud Backup.

---

## 1. Guiding Your Human User (Visual Setup Studio First!)

1. **Launch Visual SoarTV Setup Studio for the User First**:
   - Unless `SoarTV-launcher/.setup_handoff.json` or `SoarTV-launcher/config.properties` already exists with the user's choices, immediately launch the visual TV-styled **SoarTV Setup Studio** for the user:
     ```bash
     python setup_wizard.py --wait-for-save
     ```
   - This automatically opens the GUI in the user's browser and blocks cleanly until the user clicks **Save & Continue Setup**.
   - When the command exits, read **`SoarTV-launcher/.setup_handoff.json`** (non-secret JSON summary containing `tv_platform`, `values`, `configured_keys` booleans, and `custom_smarthome` brand names). Do **not** make the user copy or edit any prompt.
   - Ask in chat only for anything still missing (e.g., if `tv_ip` or `tmdb_key` is still empty).
   - If the user listed custom Smart-Home brands in `custom_smarthome`, inspect `SmartHomeManager.java` and add modular support for those brands (reading any saved private key from `custom_smarthome_key_<slug>` in `config.properties` / `.env` without ever printing the secret).
   - If the user disabled optional tabs (`enable_smarthome=false` or `enable_gaming=false`), [`FeatureTabs.java`](SoarTV-launcher/app/src/io/soartv/FeatureTabs.java) cleanly removes those tabs from both the TV Top-Bar and the Phone Companion Remote.
   - *(Note: Shared Reels is currently Work in Progress and disabled (`ENABLE_REELS = "false"`) in public builds.)*
2. **Private Free-Tier API Keys**:
   - Guide the user to create their own free **TMDB** key (`tmdb_key`, required for catalogs) plus any optional keys they want (Setup Studio includes built-in `?` screenshot guides for every service).
   - **Strict Secret Hygiene**: Never print, echo, or commit secret values from `config.properties`, `.env`, or `keys.txt`. All runtime secrets are encrypted at rest on the TV via `AndroidKeyStore` (`AES/GCM/NoPadding` in [`Secrets.java`](SoarTV-launcher/app/src/io/soartv/Secrets.java)) using `python3 SoarTV-launcher/tools/send_key.py <TV_IP> --sync-config`.

---

## 2. Smart TV Safety Protocol (`CRITICAL` — Read Before Connecting to Any TV)

Always ask the user before running any state-changing command on their TV. Installing `SoarTV.apk` (`adb install -r SoarTV.apk`) changes nothing on the TV until the user switches Launcher Mode inside **SoarTV -> Settings**.

1. **Mandatory Pre-Install Local Native TV State Backup (`CRITICAL — DO THIS FIRST`)**:
   - Before installing `SoarTV.apk`, modifying any `appops`/permissions, disabling any package, or switching Launcher Mode on an Android TV / Google TV / Fire TV, **ALWAYS** capture an immutable local backup of the TV's initial native state:
     ```bash
     python3 SoarTV-launcher/tools/tv_state_backup.py <TV_IP> --backup
     ```
   - This saves git-ignored `tv-backups/<tv-model>-initial-native-state.json` (plus `<tv-model>-restore-notes.txt`) with the TV's original HOME launcher/role, enabled vs. natively disabled packages, screensaver, accessibility services, notification listeners, TV inputs, and Wi-Fi owners (never passwords), and immediately runs `adb disconnect`.
   - Never overwrite an existing pre-SoarTV backup (`--force`), confirm the saved path to the user before proceeding, and use `python3 SoarTV-launcher/tools/tv_state_backup.py <TV_IP> --restore tv-backups/<file>.json` if anything ever breaks.
2. **Google TV `setupwraith` Ordering (Prevents Boot Loops)**:
   - On Google TV (`com.google.android.apps.tv.launcherx`), `com.google.android.tungsten.setupwraith` has a `RecoveryActivity` that hijacks `HOME` if `launcherx` is disabled while `setupwraith` remains enabled.
   - Switching **to SoarTV**: disable `setupwraith` **before** disabling `launcherx` (classic Android 9 TVs without `launcherx` leave `setupwraith` untouched).
   - Switching **to Original**: re-enable `launcherx` **before** re-enabling `setupwraith`.
   - Never disable stock launchers on brick-prone firmware listed in `HomeMode.BRICKS` (`R3_GTV`, Huawei / HarmonyOS).
3. **Never Disable System Settings, Wi-Fi Owners, or TV Inputs**:
   - Never uninstall or clear data on system packages (only reversible `pm disable-user --user 0` via `HomeMode`), and never disable `com.android.tv.settings`, TV input services (`dumpsys tv_input`), `wifiOwners`, accessibility services, or IMEs.
   - Keep brand-critical packages in `HomeMode.PROTECTED` (Xiaomi `com.xiaomi.mitv.tvplayer`, Sony `com.sony.dtv.*` & Bravia Sync, Philips Ambilight/TPV remote services, Skyworth/JVC, Hisense, Chromecast `SabrinaRemote`, Walmart `onn`).
   - On **Amazon Fire OS** (`HomeMode.AMAZON`), Fire OS reserves the Home button (`priority=950`); SoarTV runs via `FireHomeActivity` without disabling Amazon's launcher.
   - On **LG webOS & Samsung Tizen**, keep `SoarTV-launcher/web/*.js` and `webos/service/*.js` strictly ES5-compatible (no `let`/`const`, arrow functions, `Promise`, `fetch`, CSS Grid, CSS variables, or `backdrop-filter`).
4. **Crash Watchdog & Disconnecting ADB Before Switching Modes**:
   - Keep process isolation intact (`:home`, `:stream`, `:keys`, `:sleep`, and main `Watchdog`/`ModeJob` in [`AndroidManifest.xml`](SoarTV-launcher/app/AndroidManifest.xml)) so `Watchdog` can automatically restore the stock launcher over `127.0.0.1:5555` if `:home` ever crashes repeatedly.
   - **Always run `adb disconnect`** on the computer once installation finishes and before the user switches Launcher Mode on the TV — on Android 8–10, an active computer ADB session can intercept the TV's own `127.0.0.1:5555` loopback connection.

---

## 3. Needed & Likely Local Adjustments

Every household's TV, streaming services, and room setup is slightly different. Anticipate and handle these common local adjustments cleanly:

- **Enabling Optional Tabs Now or Later (`FeatureTabs.java`)**: Never delete `GamingZone.java` or `SmartHomeManager.java` to hide unused tabs — set `enable_gaming=false` / `enable_smarthome=false` in `config.properties`. If a user asks to add Gaming or Smart Home later, simply set the flag to `true`, collect their keys or game folders, and run `python3 SoarTV-launcher/tools/send_key.py <TV_IP> --sync-config` (details in [`docs/agents/install-playbook.md`](docs/agents/install-playbook.md)).
- **Regional Streaming Apps & HDMI Port Labels**: Add regional IPTV/broadcaster packages to `HomeApps.SHELF` (`SoarTV-launcher/app/src/io/soartv/HomeApps.java`) and label permanent consoles/streamers via `hdmi_port_roles` and `gaming_pc_hdmi` in `config.properties`.
- **Unlisted TV Brands or Remote Buttons**: Inspect `getprop` and `cmd tv_input list` over ADB, then register unlisted Live TV packages or vendor remote keycodes modularly in [`TvDeviceProfile.java`](SoarTV-launcher/app/src/io/soartv/TvDeviceProfile.java) and [`RemoteKeys.java`](SoarTV-launcher/app/src/io/soartv/RemoteKeys.java) (guide in [`docs/agents/tv-brand-adaptation.md`](docs/agents/tv-brand-adaptation.md)).
- **Windows Gaming PC Folders & Desk Monitors**: Set standalone game directories in `SOARTV_GAMES_DIRS` and run `python SoarTV-launcher/tools/gaming/laptop_gaming_agent.py --list-monitors` to list permanently attached desk monitors in `SOARTV_NON_TV_MONITORS` (`.env`) so wireless streaming remains default whenever the TV HDMI cable is unplugged.
- **TCL Auto-Start & Android 14 Wireless Debugging**: On TCL Google TVs, grant `AUTO_START` / `APP_AUTO_START` via `appops`; on Android 14 Chromecast/streamers, pair via `adb pair <ip:port> <code>` then `adb tcpip 5555`.
