# SoarTV — Automated Installation & User-Onboarding Playbook (For AI Agents)

Follow this structured runbook when helping a human user install, configure, or update **SoarTV**. Read [`../../AGENTS.md`](../../AGENTS.md) first for the mandatory **Smart TV Safety Protocol**.

---

## 1. Pre-Flight Human Intake (Launch Setup Studio Automatically!)

1. **Open SoarTV Setup Studio for the User**:
   - If `SoarTV-launcher/.setup_handoff.json` does not exist yet (or the user wants to change choices), launch:
     ```bash
     python setup_wizard.py --wait-for-save
     ```
   - This opens the TV-styled GUI in the user's browser so they can select their TV OS, TV IP, city, optional **Smart Home** & **PC Gaming** tabs, standalone game folders (via native OS folder explorer), custom Smart-Home brands, and free API keys (with built-in `?` real screenshot guides).
   - Wait for the user to click **Save & Continue Setup**. The server saves secrets privately to git-ignored `SoarTV-launcher/config.properties` & `.env`, writes the non-secret summary **`SoarTV-launcher/.setup_handoff.json`**, and exits cleanly (`0`).
2. **Read `SoarTV-launcher/.setup_handoff.json`**:
   - Inspect `tv_platform`, `values` (`tv_ip`, `city`, `enable_smarthome`, `enable_gaming`, `games_dirs`), `configured_keys` booleans, and `custom_smarthome`.
   - Ask the user in chat **only** for anything still missing.
   - If `custom_smarthome` contains custom brands (e.g. `Philips Hue`, `Home Assistant`, `Govee`, `SwitchBot`), inspect `SmartHomeManager.java` and add modular support for them using any saved `custom_smarthome_key_<slug>` secret (never print secrets in chat).

---

## 2. Guiding the Human User Through Free API Keys

- **Never print, echo, or commit secret values** (`config.properties`, `.env`, `keys.txt`, and `*.keystore` are git-ignored).
- Launch the visual **SoarTV Setup Studio** (`python setup_wizard.py --wait-for-save`) so the user can paste keys directly into local files without exposing them in chat — or write values directly into `SoarTV-launcher/config.properties` if the user provides them.
- **Key Priority Guide**:
  - **TMDB (`tmdb_key`) — Required for catalogs**: Free at `https://www.themoviedb.org/settings/api`.
  - **Trakt (`trakt_client_id`) & Stremio (`stremio_email`) — Recommended**: Trakt app Redirect URI must be `urn:ietf:wg:oauth:2.0:oob`; user pairs via QR code on the TV after install.
  - **OMDb (`omdb_key`), MDBList (`mdblist_key`), Gemini AI (`gemini_api_key`) — Optional**: Remind OMDb users to click the activation link emailed by OMDb; remind MDBList users to sign in directly at `https://mdblist.com/preferences/` (not via Trakt login).
  - **Supabase (`supabase_url`, `supabase_anon_key`, `household_key`, `admin_emails`) — Optional**: Have the user run [`../supabase-schema.sql`](../supabase-schema.sql) in Supabase SQL Editor, generate a 64-hex `household_key` (`python3 -c "import secrets; print(secrets.token_hex(32))"`), and store only its `SHA-256` hash in `private.household_auth_config`.

---

## 3. Phase A — Building & Installing on the TV

### 3a. Check / Install Build Tools
- Required for Android TV / Google TV / Fire TV: **JDK 17+**, **Android SDK** (`build-tools;34.0.0`, `platforms;android-34`), **ADB** (`platform-tools`), **Node.js 20+**, and **Python 3.10+**.
- Check `java -version`, `$ANDROID_HOME`, `adb version`, `node -v` first and install only missing tools.

### 3b. Configure `config.properties` & Optional Tabs
1. Copy `SoarTV-launcher/config.example.properties` to `SoarTV-launcher/config.properties` and `.env.example` to `.env` if absent.
2. Set `enable_gaming=true` or `enable_gaming=false` and `enable_smarthome=true` or `enable_smarthome=false` explicitly to match the user's answer in Step 1.
3. Fill in `city`, `latitude`, `longitude`, `tmdb_region`, and `temperature` for the user's city.

### 3c. Back Up Initial Native TV State (`MANDATORY`), Inspect Brand, Build, Install & Sync Secrets (Android TV / Google TV / Fire TV)
1. **Save an Immutable Local Backup of the TV's Initial Native State (`MANDATORY — BEFORE ANY INSTALL OR CHANGE`)**:
   - Before installing `SoarTV.apk`, changing any `appops`/permissions, disabling any package, or switching Launcher Mode, connect once over ADB (on Chromecast / Android 14 streamers, pair via *Wireless debugging* `adb pair <ip:port> <code>` then `adb tcpip 5555`; tell the user to accept *Allow debugging* on the TV screen) and run:
     ```bash
     python3 SoarTV-launcher/tools/tv_state_backup.py <TV_IP> --backup
     ```
   - This runs **read-only** queries and writes an immutable local snapshot to git-ignored `tv-backups/<tv-model>-initial-native-state.json` (plus `<tv-model>-restore-notes.txt`), recording the TV's exact pre-SoarTV HOME launcher/role, enabled vs. natively disabled packages, screensaver settings, accessibility services, notification listeners, TV inputs, and Wi-Fi owner packages (never passwords), and immediately runs `adb disconnect`.
   - **Never overwrite an existing initial backup** (do not pass `--force` if a pre-SoarTV backup for this TV already exists, and never treat a TV already running `io.soartv` as HOME as a clean initial snapshot).
   - Verify the backup JSON file exists on disk and tell the user its saved local path before proceeding.
2. Run any additional read-only brand checks (see [`tv-brand-adaptation.md`](tv-brand-adaptation.md)) and verify `adb devices` is empty (`adb disconnect`).
3. Build (or download the signed GitHub Release APK for automatic OTA updates):
   - **Automatic OTA Self-Updates (Recommended when no custom Java/Smart-Home edits are needed)**: If the user has no custom Smart-Home brands or Java edits to compile in and a signed `SoarTV.apk` is published on GitHub Releases, you may install that release `SoarTV.apk` directly and push all private keys & tab flags with `send_key.py --sync-config` so the TV automatically receives future OTA releases (`Settings -> System -> Updates`).
   - **Local Build (Required if adding custom Smart-Home brands or local Java adjustments)**:
   ```bash
   cd SoarTV-remote && npm ci && npm run build && npm test
   cd ../SoarTV-launcher && bash ./build.sh
   ```
4. Install and push hardware-encrypted secrets:
   ```bash
   adb connect <TV_IP>:5555
   adb install -r SoarTV-launcher/SoarTV.apk
   python3 SoarTV-launcher/tools/send_key.py <TV_IP> --sync-config
   ```
   - **Play Protect prompt on Android 14**: Tell the user to choose *More details -> Install anyway*.
   - **TCL Google TV**: Also run `adb shell "appops set io.soartv AUTO_START allow || appops set io.soartv APP_AUTO_START allow"` so TCL's `TclAppBoot` allows `ModeJob`.
5. **CRITICAL**: Run `adb disconnect` and verify `adb devices` is empty **before** the user switches Launcher Mode on the TV (so Android 8–10 TVs do not route local `127.0.0.1:5555` loopback ADB to the computer).
6. Guide the user on the TV: open **SoarTV -> Settings** to switch Launcher Mode (`Original -> SoarTV`), pair **Trakt / Stremio**, and scan the **Companion Remote** QR code (`http://<TV_IP>:8484`).

### 3d. LG webOS & Samsung Tizen TVs
- *Platform scope*: LG webOS and Samsung Tizen run the lightweight ES5 web launcher (`SoarTV-launcher/web/`: App Shelf, TMDB Movies/Shows/Explore picks, HDMI & Live TV switching, Open-Meteo weather, sunrise/sunset themes, and screensaver). Companion Remote (`port 8484`), Smart-Home Hub, PC Game Streaming, Gemini AI Search, and Trakt/Stremio sync are native Android TV (`SoarTV.apk`) features only.
- *Native-state safety*: On LG webOS and Samsung Tizen, SoarTV installs as a standard third-party app without modifying or disabling system launchers or OS settings (uninstalling the app reverts completely; on LG webOS, `webos/apps.py` also saves a local `webos-apps.local.json` inventory).
- **LG webOS** (webOS 3.0–24+; Python 3 + OpenSSH only, no LG CLI needed):
  ```bash
  cd SoarTV-launcher && python3 webos/build.py
  ```
  Have the user run `python3 webos/install.py <LG_TV_IP>` in their own terminal the first time to enter the LG Developer Mode passphrase (the script auto-scales to `1280x720` on non-4K LG sets). On older **webOS 3** sets (or if the TV blocks in-app `listLaunchPoints`), run `python3 webos/apps.py <LG_TV_IP>` first to pull installed apps & icons from the TV, then re-run `python3 webos/build.py && python3 webos/install.py <LG_TV_IP>`.
- **Samsung Tizen** (Tizen 2.3–9+):
  ```bash
  cd SoarTV-launcher && python3 tizen/build.py --sign <TIZEN_CERT_PROFILE>
  python3 tizen/install.py <SAMSUNG_TV_IP>
  ```

---

## 4. Phase B — Optional Windows PC Gaming Setup (`enable_gaming=true`)

Skip this entire section if the user opted out of PC Gaming (`enable_gaming=false`).

1. **Install Sunshine**: Guide the user to install [Sunshine](https://github.com/LizardByte/Sunshine/releases) on their Windows PC and set local Web UI credentials at `https://localhost:47990` (stored in git-ignored `keys.txt`).
2. **Configure Game Folders & Non-TV Desk Monitors (`.env`)**:
   - Set `SOARTV_GAMES_DIRS=<semicolon-separated standalone game folders>` in `.env`.
   - Run `python SoarTV-launcher/tools/gaming/laptop_gaming_agent.py --list-monitors` on the Windows PC. Ask which listed external displays are desk monitors (not the TV) and set `SOARTV_NON_TV_MONITORS=<comma-separated monitor names/EDIDs>` in `.env`.
3. **One-Time Administrator Power & Firewall Setup (`[HUMAN]`)**:
   Have the user run once in an elevated **Administrator PowerShell**:
   ```powershell
   python "SoarTV-launcher\tools\gaming\laptop_gaming_agent.py" --enable-sunshine-power-control --manual-start
   ```
   *(Optional flags: `--enable-wifi-wake` and `--install-unlock-helper`.)*
4. **Start & Pair**: Run `python SoarTV-launcher/tools/gaming/laptop_gaming_agent.py` (port `8485`) and pair once on the TV via **Gaming -> Pair Gaming PC (6-Digit Code)** (`~/.soartv_gaming_agent_pair_code.txt`).

---

## 5. Late Addition: Enabling Gaming or Smart Home Later

Because optional tabs are modularly controlled by [`FeatureTabs.java`](../../SoarTV-launcher/app/src/io/soartv/FeatureTabs.java), enabling **Gaming** or **Smart Home** later requires **zero refactoring**:

1. Set `enable_gaming=true` and/or `enable_smarthome=true` in `SoarTV-launcher/config.properties` (and `.env`), plus any newly added Smart-Home keys or `SOARTV_GAMES_DIRS` / `SOARTV_NON_TV_MONITORS`.
2. Push the updated flags & encrypted keys live to the TV over ADB (even without rebuilding the APK!):
   ```bash
   python3 SoarTV-launcher/tools/send_key.py <TV_IP> --sync-config
   ```
   *(Or rebuild and install with `cd SoarTV-launcher && ./build.sh && adb install -r SoarTV.apk && adb disconnect` if build-time properties like `gaming_pc_hdmi` were also updated.)*
3. Both the TV Top-Bar, TV Settings categories, and the Phone Companion Remote automatically surface the newly enabled tab immediately.

---

## 6. Emergency Recovery — Restoring the TV's Initial Native State

If anything breaks or behaves unexpectedly after installing or switching to SoarTV on an Android TV / Google TV / Fire TV, restore the TV directly from the local pre-install snapshot saved in Step 3c (`tv-backups/<tv-model>-initial-native-state.json`):

1. **Preview the exact restore plan (read-only)**:
   ```bash
   python3 SoarTV-launcher/tools/tv_state_backup.py <TV_IP> --restore tv-backups/<tv-model>-initial-native-state.json --dry-run
   ```
2. **Restore the TV to its recorded initial native state** (re-enables only packages that were enabled originally, strictly re-enables `launcherx` / original HOME **before** `com.google.android.tungsten.setupwraith`, restores screensaver, accessibility, and notification listeners, launches the native HOME screen, and disconnects ADB):
   ```bash
   python3 SoarTV-launcher/tools/tv_state_backup.py <TV_IP> --restore tv-backups/<tv-model>-initial-native-state.json
   ```
   *(Append `--uninstall-soartv` if the user also wants to remove `io.soartv` automatically after the original home screen is restored.)*
3. **Verify ADB is disconnected**: Run `adb devices` and confirm no session remains held.
