# MyTV — Automated Installation & User-Onboarding Playbook (For AI Agents)

Follow this structured runbook when helping a human user install, configure, or update **MyTV**. Read [`../../AGENTS.md`](../../AGENTS.md) first for the mandatory **Smart TV Safety Protocol**.

---

## 1. Pre-Flight Human Intake (Launch Setup Studio Automatically!)

1. **Open MyTV Setup Studio for the User**:
   - If `MyTV-launcher/.setup_handoff.json` does not exist yet (or the user wants to change choices), launch:
     ```bash
     python setup_wizard.py --wait-for-save
     ```
   - This opens the TV-styled GUI in the user's browser so they can select their TV OS, TV IP, city, optional **Smart Home** & **PC Gaming** tabs, standalone game folders (via native OS folder explorer), custom Smart-Home brands, and free API keys (with built-in `?` real screenshot guides).
   - Wait for the user to click **Save & Continue Setup**. The server saves secrets privately to git-ignored `MyTV-launcher/config.properties` & `.env`, writes the non-secret summary **`MyTV-launcher/.setup_handoff.json`**, and exits cleanly (`0`).
2. **Read `MyTV-launcher/.setup_handoff.json`**:
   - Inspect `tv_platform`, `values` (`tv_ip`, `city`, `enable_smarthome`, `enable_gaming`, `games_dirs`), `configured_keys` booleans, and `custom_smarthome`.
   - Ask the user in chat **only** for anything still missing.
   - If `custom_smarthome` contains custom brands (e.g. `Philips Hue`, `Home Assistant`, `Govee`, `SwitchBot`), inspect `SmartHomeManager.java` and add modular support for them using any saved `custom_smarthome_key_<slug>` secret (never print secrets in chat).

---

## 2. Guiding the Human User Through Free API Keys

- **Never print, echo, or commit secret values** (`config.properties`, `.env`, `keys.txt`, and `*.keystore` are git-ignored).
- Recommend launching the visual **MyTV Setup Studio** (`Setup-MyTV.bat` on Windows or `./setup-mytv.sh` on macOS/Linux) so the user can paste keys directly into local files without exposing them in chat — or write values directly into `MyTV-launcher/config.properties` if the user provides them.
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
1. Copy `MyTV-launcher/config.example.properties` to `MyTV-launcher/config.properties` and `.env.example` to `.env` if absent.
2. Set `enable_gaming=true` or `enable_gaming=false` and `enable_smarthome=true` or `enable_smarthome=false` explicitly to match the user's answer in Step 1.
3. Fill in `city`, `latitude`, `longitude`, `tmdb_region`, and `temperature` for the user's city.

### 3c. Inspect TV Brand, Build, Install & Sync Encrypted Secrets (Android TV / Google TV / Fire TV)
1. Connect over ADB (`adb connect <TV_IP>:5555` — on Chromecast Android 14, pair via *Wireless debugging* `adb pair <ip:port> <code>` then `adb tcpip 5555`). Tell the user to accept *Allow debugging* on the TV screen.
2. Run read-only brand checks (see [`tv-brand-adaptation.md`](tv-brand-adaptation.md)) and immediately run `adb disconnect`.
3. Build and run verification:
   ```bash
   cd MyTV-remote && npm ci && npm run build && npm test
   cd ../MyTV-launcher && ./build.sh
   ```
4. Install and push hardware-encrypted secrets:
   ```bash
   adb connect <TV_IP>:5555
   adb install -r MyTV-launcher/MyTV.apk
   python3 MyTV-launcher/tools/send_key.py <TV_IP> --sync-config
   ```
   - **Play Protect prompt on Android 14**: Tell the user to choose *More details -> Install anyway*.
   - **TCL Google TV**: Also run `adb shell "appops set io.mytv AUTO_START allow || appops set io.mytv APP_AUTO_START allow"` so TCL's `TclAppBoot` allows `ModeJob`.
5. **CRITICAL**: Run `adb disconnect` and verify `adb devices` is empty **before** the user switches Launcher Mode on the TV (so Android 8–10 TVs do not route local `127.0.0.1:5555` loopback ADB to the computer).
6. Guide the user on the TV: open **MyTV -> Settings** to switch Launcher Mode (`Original -> MyTV`), pair **Trakt / Stremio**, and scan the **Companion Remote** QR code (`http://<TV_IP>:8484`).

### 3d. LG webOS & Samsung Tizen TVs
- **LG webOS**:
  ```bash
  cd MyTV-launcher && python3 webos/build.py
  ```
  Have the user run `python3 webos/install.py <LG_TV_IP>` in their own terminal the first time to enter the LG Developer Mode passphrase.
- **Samsung Tizen**:
  ```bash
  cd MyTV-launcher && python3 tizen/build.py --sign <TIZEN_CERT_PROFILE>
  python3 tizen/install.py <SAMSUNG_TV_IP>
  ```

---

## 4. Phase B — Optional Windows PC Gaming Setup (`enable_gaming=true`)

Skip this entire section if the user opted out of PC Gaming (`enable_gaming=false`).

1. **Install Sunshine**: Guide the user to install [Sunshine](https://github.com/LizardByte/Sunshine/releases) on their Windows PC and set local Web UI credentials at `https://localhost:47990` (stored in git-ignored `keys.txt`).
2. **Configure Game Folders & Non-TV Desk Monitors (`.env`)**:
   - Set `MYTV_GAMES_DIRS=<semicolon-separated standalone game folders>` in `.env`.
   - Run `python MyTV-launcher/tools/gaming/laptop_gaming_agent.py --list-monitors` on the Windows PC. Ask which listed external displays are desk monitors (not the TV) and set `MYTV_NON_TV_MONITORS=<comma-separated monitor names/EDIDs>` in `.env`.
3. **One-Time Administrator Power & Firewall Setup (`[HUMAN]`)**:
   Have the user run once in an elevated **Administrator PowerShell**:
   ```powershell
   python "MyTV-launcher\tools\gaming\laptop_gaming_agent.py" --enable-sunshine-power-control --manual-start
   ```
   *(Optional flags: `--enable-wifi-wake` and `--install-unlock-helper`.)*
4. **Start & Pair**: Run `python MyTV-launcher/tools/gaming/laptop_gaming_agent.py` (port `8485`) and pair once on the TV via **Gaming -> Pair Gaming PC (6-Digit Code)** (`~/.mytv_gaming_agent_pair_code.txt`).

---

## 5. Late Addition: Enabling Gaming or Smart Home Later

Because optional tabs are modularly controlled by [`FeatureTabs.java`](../../MyTV-launcher/app/src/io/mytv/FeatureTabs.java), enabling **Gaming** or **Smart Home** later requires **zero refactoring**:

1. Set `enable_gaming=true` and/or `enable_smarthome=true` in `MyTV-launcher/config.properties` (and `.env`), plus any newly added Smart-Home keys or `MYTV_GAMES_DIRS` / `MYTV_NON_TV_MONITORS`.
2. Push the updated flags & encrypted keys live to the TV over ADB (even without rebuilding the APK!):
   ```bash
   python3 MyTV-launcher/tools/send_key.py <TV_IP> --sync-config
   ```
   *(Or rebuild and install with `cd MyTV-launcher && ./build.sh && adb install -r MyTV.apk && adb disconnect` if build-time properties like `gaming_pc_hdmi` were also updated.)*
3. Both the TV Top-Bar, TV Settings categories, and the Phone Companion Remote automatically surface the newly enabled tab immediately.
