# MyTV — TV Brand Inspection & Local Adaptation Guide (For AI Agents)

While MyTV supports Xiaomi, TCL, Sony BRAVIA, Philips, Hisense, Skyworth/JVC, Vestel, KTC, Chromecast with Google TV, Google TV Streamer, Walmart `onn.`, Amazon Fire TV, LG webOS, and Samsung Tizen out of the box, individual households and regional TV models sometimes benefit from targeted local adjustments.

---

## 1. Read-Only TV Inspection Commands

Before modifying code for a new or unlisted Android TV / Google TV model, connect briefly over ADB, run these read-only commands, and **disconnect immediately** (`adb disconnect`):

```bash
adb connect <TV_IP>:5555
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.brand
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd tv_input list
adb disconnect
```

---

## 2. Modular Extension Points (Where to Make Local Adjustments)

Keep all hardware, regional, and household customizations isolated in their dedicated adapter classes:

1. **Optional Feature Tabs (`FeatureTabs.java` & `config.properties`)**:
   - Controlled via `enable_gaming=auto|true|false` and `enable_smarthome=auto|true|false` in `MyTV-launcher/config.properties` (and runtime overrides in `FeatureTabs.java`).
   - Never delete `GamingZone.java` or `SmartHomeManager.java` to hide a tab — use `enable_gaming=false` / `enable_smarthome=false` so the user can enable them effortlessly later.
2. **TV Brand Detection & Live TV Apps (`TvDeviceProfile.java` & `HomeApps.LIVE_TV_APPS`)**:
   - If the user's TV uses an unlisted vendor Live TV package (found via `cmd tv_input list` or `pm list packages -s`), register the brand/package in [`TvDeviceProfile.java`](../../MyTV-launcher/app/src/io/mytv/TvDeviceProfile.java) and `HomeApps.LIVE_TV_APPS` with a unit test in `MyTV-launcher/tests/MyTvCoreTests.java`.
3. **Protected vs. Promo System Packages (`HomeMode.java`)**:
   - **Safety Rule (`When in doubt, protect it`)**: Add any vendor HDMI input switch, Bluetooth remote pairing service, CEC service, picture/sound menu, or Wi-Fi settings owner to `HomeMode.PROTECTED`.
   - Only add verified promotional/banner/demo overlays to `HomeMode.DEBLOAT` (never uninstall system apps; `HomeMode` uses reversible `pm disable-user --user 0` so **Original** restores everything).
4. **Physical TV Remote Shortcut Buttons (`RemoteKeys.java` & `Prefs.KEYS`)**:
   - If the user wants a vendor app button on their TV remote remapped (e.g., a regional streaming button intercepted as a global key in `adb shell dumpsys window policy`), add the brand guard and keycode in [`RemoteKeys.java`](../../MyTV-launcher/app/src/io/mytv/RemoteKeys.java) and [`Prefs.java`](../../MyTV-launcher/app/src/io/mytv/Prefs.java).
5. **Regional Streaming Apps & Default Side-Shelf (`HomeApps.SHELF`)**:
   - If the user relies on regional TV streaming apps (e.g., local IPTV or national broadcasters), add their `{key, label, packageName, hexColor, iconMode, scale}` row to `HomeApps.SHELF` (and optional PNG logo in `MyTV-launcher/app/assets/logos/<key>.png`).
6. **HDMI Port Labels & Gaming PC Display Detection (`config.properties` & `.env`)**:
   - Map permanent consoles, streaming boxes, and gaming laptops via `hdmi_port_roles=1:console:PS5,3:laptop:Gaming PC` and `gaming_pc_hdmi=HDMI 3` in `config.properties`.
   - Exclude non-TV desk monitors on a Windows Gaming PC via `MYTV_NON_TV_MONITORS` in `.env` (using `python MyTV-launcher/tools/gaming/laptop_gaming_agent.py --list-monitors`).

---

## 3. Verifying Local Adjustments

After any Java, TypeScript, Web, or Python adjustment, run the automated verification suites locally before installing on the user's TV:

```bash
cd MyTV-remote && npm run build && npm test
cd ../MyTV-launcher && bash tools/check.sh
```
