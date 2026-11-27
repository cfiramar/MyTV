"""
4-Tier Opaque-Box E2E Test Suite for MyTV Gaming Discovery, Stream Startup,
Performance Telemetry, Settings Isolation (R3), and ADB Release Rule (R4).

Run directly via:
    `python -m unittest -v MyTV-launcher/tools/gaming/test_e2e_gaming_suite.py`

Gating:
- Default mode: runs all Tier 1-4 protocol, sandboxed HTTP/UDP, config, isolation,
  and baseline Java requirement tests (must pass 100% pre-fix and post-fix).
- Post-fix gate (`MYTV_E2E_POST_FIX=1` with `python -m unittest ...`, or `--post-fix`
  only when invoking this script directly via `python .../test_e2e_gaming_suite.py --post-fix`):
  enables M1/M2/M3 post-fix assertions (R4 docs, TvBeaconNotifier loopback filter,
  Firewall Private+Public, M3 Sunshine encoder/coder/refresh alignment, and Java `--strict` mode).
"""

from __future__ import annotations

import ast
import atexit
from http.server import BaseHTTPRequestHandler, HTTPServer
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from typing import Any, Dict, List, Optional, Tuple
import unittest
from unittest import mock

_TOOLS_GAMING_DIR = Path(__file__).resolve().parent
_LAUNCHER_DIR = _TOOLS_GAMING_DIR.parents[1]
_REPO_ROOT = _TOOLS_GAMING_DIR.parents[2]
if str(_TOOLS_GAMING_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_GAMING_DIR))

import laptop_gaming_agent as agent_mod
from laptop_gaming_agent import (
    AGENT_SERVICE_ID,
    AgentAuthManager,
    CursorPrivacyController,
    DisplayModeController,
    DisplayRefreshRateController,
    GameCatalogScanner,
    GameEntry,
    GameProcessController,
    LaptopGamingAgentApp,
    PCPrivacySafetyNet,
    PhysicalEscAbortListener,
    PrivacyCurtainManager,
    SteamPreparationManager,
    SunshineConfigManager,
    TvBeaconNotifier,
    UdpDiscoveryResponder,
    WindowsSessionController,
    create_http_server,
)
import test_sunshine_config as sun_cfg_mod
from test_sunshine_config import (
    MANDATORY_M2_CONFIG,
    MoonlightClientPacingSpec,
    SunshineConfigParser,
    SunshineConfigValidator,
    SunshineLogTelemetryParser,
)
import verify_live_gaming_e2e as live_harness


_POST_FIX_ENABLED: bool = (
    os.environ.get("MYTV_E2E_POST_FIX") == "1" or "--post-fix" in sys.argv
)
_PRE_SEEDED_TOKEN: str = "a" * 64
_TEST_HOST_UUID: str = "14FEDE7C-47F1-1D07-0185-22930C564015"


_OS_HARDWARE_GUARD_PATCHES: List[Any] = []


def _forbid_os_call(name: str) -> Any:
    def _blocked(*_args: Any, **_kwargs: Any) -> Any:
        raise AssertionError(
            f"Unmocked OS hardware call '{name}' is forbidden in test_e2e_gaming_suite.py"
        )
    return _blocked


def _install_os_hardware_guards() -> None:
    """Patches all real Windows audio, cursor, curtain, display, and launch seams fail-closed."""
    if _OS_HARDWARE_GUARD_PATCHES:
        return
    targets = [
        (PCPrivacySafetyNet, "_native_mute_audio", _forbid_os_call("PCPrivacySafetyNet._native_mute_audio"), None),
        (PCPrivacySafetyNet, "_native_unmute_audio", _forbid_os_call("PCPrivacySafetyNet._native_unmute_audio"), None),
        (PCPrivacySafetyNet, "_native_minimize_open_windows", _forbid_os_call("PCPrivacySafetyNet._native_minimize_open_windows"), None),
        (PCPrivacySafetyNet, "_native_restore_minimized_windows", _forbid_os_call("PCPrivacySafetyNet._native_restore_minimized_windows"), None),
        (PCPrivacySafetyNet, "_native_hide_desktop", _forbid_os_call("PCPrivacySafetyNet._native_hide_desktop"), None),
        (PCPrivacySafetyNet, "_native_restore_desktop", _forbid_os_call("PCPrivacySafetyNet._native_restore_desktop"), None),
        (DisplayRefreshRateController, "_native_get_primary_refresh_hz", _forbid_os_call("DisplayRefreshRateController._native_get_primary_refresh_hz"), None),
        (DisplayRefreshRateController, "_native_set_primary_refresh_hz", _forbid_os_call("DisplayRefreshRateController._native_set_primary_refresh_hz"), None),
        (CursorPrivacyController, "_default_get_cursor_pos", _forbid_os_call("CursorPrivacyController._default_get_cursor_pos"), None),
        (CursorPrivacyController, "_default_set_cursor_pos", _forbid_os_call("CursorPrivacyController._default_set_cursor_pos"), None),
        (DisplayModeController, "apply_display_mode", _forbid_os_call("DisplayModeController.apply_display_mode"), None),
        (WindowsSessionController, "wake_from_standby", None, True),
        (WindowsSessionController, "unlock_with_pin", _forbid_os_call("WindowsSessionController.unlock_with_pin"), None),
        (WindowsSessionController, "launch_game", _forbid_os_call("WindowsSessionController.launch_game"), None),
        (SteamPreparationManager, "prepare_for_stream", _forbid_os_call("SteamPreparationManager.prepare_for_stream"), None),
        (PrivacyCurtainManager, "_run_native_curtain_ui_loop", _forbid_os_call("PrivacyCurtainManager._run_native_curtain_ui_loop"), None),
        (GameProcessController, "_default_post_message", _forbid_os_call("GameProcessController._default_post_message"), None),
        (GameProcessController, "_default_terminate_process", _forbid_os_call("GameProcessController._default_terminate_process"), None),
        (SunshineConfigManager, "_sunshine_api_request", _forbid_os_call("SunshineConfigManager._sunshine_api_request"), None),
    ]
    for cls, attr, side_eff, ret_val in targets:
        if side_eff is not None:
            p = mock.patch.object(cls, attr, side_effect=side_eff)
        else:
            p = mock.patch.object(cls, attr, return_value=ret_val)
        p.start()
        _OS_HARDWARE_GUARD_PATCHES.append(p)


def _remove_os_hardware_guards() -> None:
    while _OS_HARDWARE_GUARD_PATCHES:
        p = _OS_HARDWARE_GUARD_PATCHES.pop()
        try:
            p.stop()
        except RuntimeError:
            pass


def setUpModule() -> None:
    _install_os_hardware_guards()


def tearDownModule() -> None:
    _remove_os_hardware_guards()


def _http_json(
    port: int,
    method: str,
    path: str,
    body: Optional[Dict[str, Any]] = None,
    headers: Optional[Dict[str, str]] = None,
    host: str = "127.0.0.1",
) -> Tuple[int, Dict[str, Any], Dict[str, str]]:
    conn = http.client.HTTPConnection(host, port, timeout=4.0)
    try:
        req_headers: Dict[str, str] = {}
        if body is not None:
            req_headers["Content-Type"] = "application/json"
        if headers:
            req_headers.update(headers)
        raw = json.dumps(body).encode("utf-8") if body is not None else None
        conn.request(method, path, body=raw, headers=req_headers)
        resp = conn.getresponse()
        raw_resp = resp.read().decode("utf-8", errors="ignore")
        parsed = json.loads(raw_resp) if raw_resp else {}
        resp_hdrs = {k.lower(): v for k, v in resp.getheaders()}
        return resp.status, parsed, resp_hdrs
    finally:
        conn.close()


def _create_sandbox_agent(
    tmp_dir: Path,
    seed_token: str = _PRE_SEEDED_TOKEN,
    find_game_hwnd_fn: Optional[Any] = None,
    sunshine_log_path: Optional[Path] = None,
    default_tv_port: int = 8484,
) -> Tuple[LaptopGamingAgentApp, Any, int, List[str]]:
    games_dir = tmp_dir / "Games"
    steam_dir = tmp_dir / "Steam"
    steam_apps = steam_dir / "steamapps"
    steam_apps.mkdir(parents=True, exist_ok=True)
    (games_dir / "Hades").mkdir(parents=True, exist_ok=True)
    (games_dir / "Hades" / "Hades.exe").write_bytes(b"MZ")
    (steam_apps / "appmanifest_728880.acf").write_text(
        '"AppState"\n{\n\t"appid"\t\t"728880"\n\t"name"\t\t"Overcooked! 2"\n\t"StateFlags"\t\t"4"\n\t"installdir"\t\t"Overcooked! 2"\n}\n',
        encoding="utf-8",
    )

    cfg_file = tmp_dir / "config.properties"
    if seed_token:
        cfg_file.write_text(f"gaming_pc_token={seed_token}\n", encoding="utf-8")

    state_dir = tmp_dir / "state"
    auth_mgr = AgentAuthManager(
        auth_path=state_dir / "auth.json",
        pair_code_path=state_dir / "pair_code.txt",
        config_properties_path=cfg_file,
    )
    beacon = TvBeaconNotifier(
        endpoint_store_path=state_dir / "last_tv.json",
        default_tv_port=default_tv_port,
    )
    app = LaptopGamingAgentApp(
        games_root=games_dir,
        steam_root=steam_dir,
        state_dir=state_dir,
        auth_manager=auth_mgr,
        tv_beacon=beacon,
    )

    hz_state = {"hz": 59}
    safety = PCPrivacySafetyNet(
        mute_audio_fn=lambda: (True, False),
        unmute_audio_fn=lambda was_muted: True,
        minimize_windows_fn=lambda: [101],
        restore_windows_fn=lambda hwnds: None,
        hide_desktop_fn=lambda: (True, 202),
        restore_desktop_fn=lambda was_vis, defview: None,
    )
    safety._refresh_controller = DisplayRefreshRateController(
        get_primary_refresh_hz_fn=lambda: hz_state["hz"],
        set_primary_refresh_hz_fn=lambda target: (hz_state.__setitem__("hz", target) is None or True),
    )
    curtain = PrivacyCurtainManager(
        sunshine_log_path=sunshine_log_path or (tmp_dir / "sunshine.log"),
        cursor_controller=CursorPrivacyController(
            get_cursor_pos_fn=lambda: (500, 500),
            set_cursor_pos_fn=lambda _x, _y: True,
            get_virtual_bounds_fn=lambda: (0, 0, 1920, 1080),
        ),
        find_game_hwnd_fn=find_game_hwnd_fn or (lambda _g: 0),
        apply_curtain_z_order_fn=lambda _m, _h: None,
        fallback_launch_fn=lambda _g: True,
        create_native_window=False,
        poll_interval_sec=0.03,
        safety_net=safety,
        esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
        close_sunshine_app_fn=lambda: True,
    )
    proc_ctrl = GameProcessController(
        safety_net=safety,
        privacy_curtain=curtain,
        post_message_fn=lambda *_a: True,
        terminate_process_fn=lambda _pid: True,
        is_pid_alive_fn=lambda _pid: False,
        snapshot_procs_fn=lambda: [],
        find_hwnds_fn=lambda _pids: [],
        close_sunshine_fn=lambda: True,
    )
    curtain.process_controller = proc_ctrl
    app.safety_net = safety
    app.privacy_curtain = curtain
    app.esc_listener = curtain.esc_listener
    app.process_controller = proc_ctrl

    app.refresh_and_sync(include_hidden=True, sync_sunshine=False)
    unlock_pins: List[str] = []
    server = create_http_server(
        app=app,
        host="127.0.0.1",
        port=0,
        unlock_handler=lambda pin: (unlock_pins.append(pin) is None or True),
    )
    port = server.server_address[1]
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    return app, server, port, unlock_pins


class _MockSunshineHandler(BaseHTTPRequestHandler):
    state: Dict[str, Any] = {}

    def _read_json(self) -> Dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length).decode("utf-8", errors="ignore") if length > 0 else ""
        return json.loads(raw) if raw else {}

    def _send_json(self, code: int, obj: Dict[str, Any]) -> None:
        raw = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self) -> None:  # noqa: N802
        st = self.__class__.state
        if self.path == "/api/pin":
            self._send_json(200, {"pairings": [{"id": st.get("pairing_id", "0123456789abcdef0123456789abcdef")}]})
            return
        if self.path == "/api/config":
            self._send_json(200, dict(st.get("config", {})))
            return
        if self.path == "/api/apps":
            self._send_json(200, {"apps": list(st.get("apps", []))})
            return
        self._send_json(404, {"status": False})

    def do_POST(self) -> None:  # noqa: N802
        st = self.__class__.state
        body = self._read_json()
        if self.path == "/api/pin":
            st["submitted_pins"].append(body)
            accept = st.get("accept_pin", True)
            self._send_json(200, {"status": bool(accept)})
            return
        if self.path == "/api/config":
            st["config"].update(body)
            st["config_posts"].append(dict(body))
            self._send_json(200, {"status": True})
            return
        if self.path == "/api/apps":
            st["apps_posts"].append(dict(body))
            idx = int(body.get("index", -1))
            apps = st["apps"]
            if 0 <= idx < len(apps):
                apps[idx] = body
            else:
                apps.append(body)
            self._send_json(200, {"status": True})
            return
        if self.path == "/api/apps/close":
            st["close_calls"] += 1
            self._send_json(200, {"status": True})
            return
        self._send_json(404, {"status": False})

    def log_message(self, fmt: str, *args: Any) -> None:
        return


def _start_mock_sunshine_api(accept_pin: bool = True) -> Tuple[HTTPServer, Dict[str, Any], Any]:
    state: Dict[str, Any] = {
        "pairing_id": "abcdef0123456789abcdef0123456789",
        "accept_pin": accept_pin,
        "submitted_pins": [],
        "config": {"minimum_fps_target": 30},
        "config_posts": [],
        "apps": [{"name": "Desktop", "output": ""}, {"name": "Overcooked! 2", "cmd": "steam://rungameid/728880"}],
        "apps_posts": [],
        "close_calls": 0,
    }

    class ScopedHandler(_MockSunshineHandler):
        pass

    ScopedHandler.state = state
    srv = HTTPServer(("127.0.0.1", 0), ScopedHandler)
    port = srv.server_address[1]
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()

    def api_forwarder(method: str, path: str, payload: Optional[Dict[str, Any]] = None) -> Optional[Dict[str, Any]]:
        status, parsed, _ = _http_json(port, method, path, body=payload)
        return parsed if 200 <= status < 300 else None

    return srv, state, api_forwarder


# =============================================================================
# Standalone Java Compilation Helper (Shared for Tier 1 & Tier 2 Java Tests)
# =============================================================================

_JAVA_BUILD_CACHE: Dict[str, Any] = {}


def _find_android_sdk_jars() -> Tuple[Path, Path]:
    sdk_candidates: List[Path] = []
    for env_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        val = os.environ.get(env_var, "").strip()
        if val:
            sdk_candidates.append(Path(val))
    local_app = os.environ.get("LOCALAPPDATA", "").strip()
    if local_app:
        sdk_candidates.append(Path(local_app) / "Android" / "Sdk")

    for sdk in sdk_candidates:
        if not sdk.is_dir():
            continue
        platforms = sorted((sdk / "platforms").glob("android-*"), reverse=True)
        build_tools = sorted((sdk / "build-tools").glob("*"), reverse=True)
        android_jar = next((p / "android.jar" for p in platforms if (p / "android.jar").is_file()), None)
        lambda_jar = next((b / "core-lambda-stubs.jar" for b in build_tools if (b / "core-lambda-stubs.jar").is_file()), None)
        if android_jar and lambda_jar:
            return android_jar, lambda_jar
    raise RuntimeError("Android SDK android.jar / core-lambda-stubs.jar not found")


def _ensure_java_e2e_compiled() -> Tuple[Path, Path, str, str]:
    if "out_dir" in _JAVA_BUILD_CACHE:
        return (
            _JAVA_BUILD_CACHE["out_dir"],
            _JAVA_BUILD_CACHE["android_jar"],
            _JAVA_BUILD_CACHE["java_bin"],
            _JAVA_BUILD_CACHE["cp_sep"],
        )

    javac_bin = shutil.which("javac")
    java_bin = shutil.which("java")
    if not javac_bin or not java_bin:
        java_home = os.environ.get("JAVA_HOME", "")
        if java_home:
            javac_bin = str(Path(java_home) / "bin" / ("javac.exe" if sys.platform == "win32" else "javac"))
            java_bin = str(Path(java_home) / "bin" / ("java.exe" if sys.platform == "win32" else "java"))
    if not javac_bin or not java_bin:
        raise RuntimeError("javac/java executable not found on PATH or JAVA_HOME")

    android_jar, lambda_jar = _find_android_sdk_jars()
    cp_sep = ";" if sys.platform == "win32" else ":"

    tmp_obj = tempfile.TemporaryDirectory(prefix="mytv_java_e2e_")
    atexit.register(tmp_obj.cleanup)
    tmp_root = Path(tmp_obj.name)
    stub_src = tmp_root / "stub_src" / "io" / "mytv"
    stub_src.mkdir(parents=True, exist_ok=True)
    classes_dir = tmp_root / "classes"
    classes_dir.mkdir(parents=True, exist_ok=True)

    (stub_src / "Config.java").write_text(
        """package io.mytv;
final class Config {
    private Config() {}
    static final String TMDB_KEY = "";
    static final String OMDB_KEY = "";
    static final String MDBLIST_KEY = "";
    static final String TRAKT_CLIENT_ID = "";
    static final String TRAKT_USERNAME = "";
    static final String STREMIO_AUTH_KEY = "";
    static final String STREMIO_EMAIL = "";
    static final String STREMIO_PASSWORD = "";
    static final String AIOSTREAMS_MANIFEST_URL = "";
    static final String CINEMETA_URL = "https://v3-cinemeta.strem.io";
    static final String SUPABASE_URL = "";
    static final String SUPABASE_ANON_KEY = "";
    static final String GITHUB_REPO = "";
    static final String GITHUB_TOKEN = "";
    static final String GEMINI_API_KEY = "";
    static final String SENSIBO_API_KEY = "";
    static final String SENSIBO_DEVICE_ID = "";
    static final String WIZ_LIGHT_IPS = "";
    static final String SWITCHER_DEVICE_IP = "";
    static final String SWITCHER_DEVICE_ID = "";
    static final String TUYA_DEVICE_ID = "";
    static final String TUYA_ACCESS_ID = "";
    static final String TUYA_ACCESS_SECRET = "";
    static final String GAMING_PC_MAC = "";
    static final String GAMING_PC_IP = "";
    static final String GAMING_PC_HDMI = "HDMI 3";
    static final String HDMI_PORT_ROLES = "";
    static final String ADMIN_EMAILS = "";
    static final int REMOTE_SERVER_PORT = 8484;
    static final String TMDB_REGION = "US";
    static final String LATITUDE = "";
    static final String LONGITUDE = "";
    static final String CITY = "";
    static final String LANGUAGE = "auto";
    static final String CURRENCY = "USD";
    static final String TEMPERATURE = "auto";
    static final String HIDE_SERVICES = "";
    static final String THEME = "auto";
    static final boolean DISABLE_ASSISTANT = false;
    static final boolean HIDE_ANIMATION = false;
    static final boolean SCREENSAVER = true;
    static final boolean GENERIC = true;
    static final boolean OPEN_IN_STREMIO = false;
    static final String ENABLE_GAMING = "auto";
    static final String ENABLE_SMARTHOME = "auto";
    static final String ENABLE_REELS = "false";
}
""",
        encoding="utf-8",
    )
    (stub_src / "R.java").write_text(
        """package io.mytv;
public final class R {
    public static final class drawable {
        public static final int coin_btc = 1;
        public static final int coin_eth = 2;
        public static final int coin_sol = 3;
        public static final int coin_usdt = 4;
        public static final int icon = 5;
    }
}
""",
        encoding="utf-8",
    )

    java_files = [
        str(p) for p in sorted((_LAUNCHER_DIR / "app" / "src").rglob("*.java"))
    ] + [
        str(stub_src / "Config.java"),
        str(stub_src / "R.java"),
        str(_LAUNCHER_DIR / "tests" / "GamingE2ERequirementsTests.java"),
    ]
    sources_txt = tmp_root / "sources.txt"
    sources_txt.write_text("\n".join(f'"{f}"' if " " in f else f for f in java_files), encoding="utf-8")

    cmd = [
        javac_bin,
        "-nowarn",
        "-Xlint:-options",
        "-source",
        "8",
        "-target",
        "8",
        "-bootclasspath",
        f"{android_jar}{cp_sep}{lambda_jar}",
        "-classpath",
        str(android_jar),
        "-encoding",
        "UTF-8",
        "-d",
        str(classes_dir),
        f"@{sources_txt}",
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    if proc.returncode != 0:
        raise AssertionError(f"Java E2E compile failed:\n{proc.stdout}\n{proc.stderr}")

    _JAVA_BUILD_CACHE.update({
        "tmp_obj": tmp_obj,
        "out_dir": classes_dir,
        "android_jar": android_jar,
        "java_bin": java_bin,
        "cp_sep": cp_sep,
    })
    return classes_dir, android_jar, java_bin, cp_sep


# =============================================================================
# Feature 1: Discovery & PC Announce (Tier 1 >= 5, Tier 2 >= 5)
# =============================================================================


class TestFeature1DiscoveryAndAnnounce(unittest.TestCase):
    """Tier 1 & Tier 2 opaque-box tests for UDP discovery, HTTP auth, TvBeaconNotifier & Firewall."""

    def test_tier1_udp_discovery_returns_here_schema(self) -> None:
        resp = UdpDiscoveryResponder(
            host="127.0.0.1",
            port=0,
            tcp_port=8485,
            uuid_provider=lambda: _TEST_HOST_UUID,
            hostname_provider=lambda: "Gaming-Laptop",
        )
        self.assertTrue(resp.start())
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as cli:
                cli.settimeout(1.5)
                cli.sendto(b'{"op":"MYTV_GAMING_DISCOVER_V1"}', ("127.0.0.1", resp.bound_port))
                raw, _ = cli.recvfrom(4096)
                data = json.loads(raw.decode("utf-8"))
                self.assertEqual(data.get("op"), "MYTV_GAMING_HERE_V1")
                self.assertEqual(data.get("agentService"), AGENT_SERVICE_ID)
                self.assertEqual(data.get("port"), 8485)
                self.assertEqual(data.get("sunshineHostUuid"), _TEST_HOST_UUID)
                self.assertEqual(data.get("sunshineHostName"), "Gaming-Laptop")
        finally:
            resp.stop()

    def test_tier1_udp_discovery_accepts_plain_op_string(self) -> None:
        resp = UdpDiscoveryResponder(
            host="127.0.0.1",
            port=0,
            tcp_port=9485,
            uuid_provider=lambda: _TEST_HOST_UUID,
            hostname_provider=lambda: "Gaming-Laptop",
        )
        self.assertTrue(resp.start())
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as cli:
                cli.settimeout(1.5)
                cli.sendto(b"MYTV_GAMING_DISCOVER_V1", ("127.0.0.1", resp.bound_port))
                raw, _ = cli.recvfrom(4096)
                data = json.loads(raw.decode("utf-8"))
                self.assertEqual(data.get("op"), "MYTV_GAMING_HERE_V1")
                self.assertEqual(data.get("port"), 9485)
        finally:
            resp.stop()

    def test_tier1_status_unauthenticated_omits_sensitive_lan_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                code, data, _ = _http_json(port, "GET", "/api/gaming/status")
                self.assertEqual(code, 200)
                self.assertEqual(data.get("agentService"), AGENT_SERVICE_ID)
                self.assertFalse(data.get("authenticated"))
                self.assertFalse(data.get("paired"))
                self.assertTrue(data.get("pairingRequired"))
                self.assertEqual(data.get("games"), [])
                self.assertNotIn("macAddresses", data)
                self.assertNotIn("lanIp", data)
            finally:
                srv.shutdown()
                srv.server_close()

    def test_tier1_status_authenticated_via_x_mytv_token(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                code, data, _ = _http_json(
                    port,
                    "GET",
                    "/api/gaming/status",
                    headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                )
                self.assertEqual(code, 200)
                self.assertTrue(data.get("authenticated"))
                self.assertTrue(data.get("paired"))
                self.assertFalse(data.get("pairingRequired"))
                self.assertIn("macAddresses", data)
                self.assertGreaterEqual(len(data.get("games", [])), 2)
            finally:
                srv.shutdown()
                srv.server_close()

    def test_tier1_status_authenticated_via_bearer_and_pc_announce(self) -> None:
        announced: List[Dict[str, Any]] = []

        class TvHandler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                ln = int(self.headers.get("Content-Length", "0") or "0")
                raw = self.rfile.read(ln).decode("utf-8", errors="ignore")
                if self.path == "/api/gaming/pc-announce":
                    announced.append(json.loads(raw))
                self.send_response(200)
                self.send_header("Content-Length", "11")
                self.end_headers()
                self.wfile.write(b'{"ok":true}')

            def log_message(self, fmt: str, *args: Any) -> None:
                return

        tv_srv = HTTPServer(("127.0.0.1", 0), TvHandler)
        tv_port = tv_srv.server_address[1]
        threading.Thread(target=tv_srv.serve_forever, daemon=True).start()

        with tempfile.TemporaryDirectory() as tmp:
            app, srv, port, _ = _create_sandbox_agent(Path(tmp), default_tv_port=tv_port)
            try:
                code, data, _ = _http_json(
                    port,
                    "GET",
                    "/api/gaming/status",
                    headers={"Authorization": f"Bearer {_PRE_SEEDED_TOKEN}", "X-MyTV-Tv-Port": str(tv_port)},
                )
                self.assertEqual(code, 200)
                self.assertTrue(data.get("authenticated"))
                app.tv_beacon.record_authenticated_tv("127.0.0.1", str(tv_port))
                self.assertTrue(app.tv_beacon.notify_ip_changed("192.168.1.99", agent_port=port))
                self.assertEqual(len(announced), 1)
                self.assertEqual(announced[0].get("agentService"), AGENT_SERVICE_ID)
                self.assertEqual(announced[0].get("pcIp"), "192.168.1.99")
            finally:
                srv.shutdown()
                srv.server_close()
                tv_srv.shutdown()
                tv_srv.server_close()

    def test_tier2_udp_ignores_malformed_wrong_op_and_oversized_datagrams(self) -> None:
        resp = UdpDiscoveryResponder(host="127.0.0.1", port=0, tcp_port=8485)
        self.assertTrue(resp.start())
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as cli:
                cli.settimeout(0.25)
                for bad in (b"{bad-json", b'{"op":"WRONG_OP"}', b"X" * 2049, b""):
                    cli.sendto(bad, ("127.0.0.1", resp.bound_port))
                    with self.assertRaises(socket.timeout):
                        cli.recvfrom(4096)
        finally:
            resp.stop()

    def test_tier2_token_mismatch_returns_unauthenticated_distinct_from_unreachable(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            _, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                code, data, _ = _http_json(
                    port,
                    "GET",
                    "/api/gaming/status",
                    headers={"X-MyTV-Token": "f" * 64},
                )
                self.assertEqual(code, 200)
                self.assertEqual(data.get("agentService"), AGENT_SERVICE_ID)
                self.assertFalse(data.get("authenticated"))
                self.assertTrue(data.get("pairingRequired"))
            finally:
                srv.shutdown()
                srv.server_close()

            with self.assertRaises(OSError):
                _http_json(port, "GET", "/api/gaming/status")

    def test_tier2_cross_origin_and_sec_fetch_site_rejected_403(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            _, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                c1, d1, h1 = _http_json(
                    port, "GET", "/api/gaming/status", headers={"Origin": "https://evil.example"}
                )
                self.assertEqual(c1, 403)
                self.assertEqual(d1.get("error"), "cross_origin_forbidden")
                self.assertNotEqual(h1.get("access-control-allow-origin"), "*")

                c2, d2, _ = _http_json(
                    port, "POST", "/api/gaming/pair/request", body=None, headers={"Sec-Fetch-Site": "cross-site"}
                )
                self.assertEqual(c2, 403)
                self.assertEqual(d2.get("error"), "cross_origin_forbidden")

                c3, d3, _ = _http_json(
                    port, "GET", "/api/gaming/status", headers={"Sec-Fetch-Site": "cross-site"}
                )
                self.assertEqual(c3, 403)
                self.assertEqual(d3.get("error"), "cross_origin_forbidden")
            finally:
                srv.shutdown()
                srv.server_close()

    @unittest.skipUnless(_POST_FIX_ENABLED, "Requires M1/M2/M3 fixes (run with MYTV_E2E_POST_FIX=1)")
    def test_tier2_post_fix_tv_beacon_ignores_loopback_ips(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store = Path(tmp) / "last_tv.json"
            beacon = TvBeaconNotifier(endpoint_store_path=store, default_tv_port=8484)
            beacon.record_authenticated_tv("192.168.1.27", "8484")
            self.assertEqual(beacon.load_saved_tv_endpoint(), ("192.168.1.27", 8484))

            for loopback in ("127.0.0.1", "127.0.1.1", "::1"):
                beacon.record_authenticated_tv(loopback, "8484")
                self.assertEqual(
                    beacon.load_saved_tv_endpoint(),
                    ("192.168.1.27", 8484),
                    f"TvBeaconNotifier must ignore loopback client IP '{loopback}'",
                )

        with tempfile.TemporaryDirectory() as tmp2:
            app, srv, port, _ = _create_sandbox_agent(Path(tmp2))
            try:
                store_path = Path(tmp2) / "state" / "last_tv.json"
                self.assertFalse(store_path.exists())
                code, data, _ = _http_json(
                    port,
                    "GET",
                    "/api/gaming/status",
                    headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN, "X-MyTV-Tv-Port": "8484"},
                )
                self.assertEqual(code, 200)
                self.assertTrue(data.get("authenticated"))
                self.assertFalse(
                    store_path.exists(),
                    "Authenticated HTTP /status over 127.0.0.1 must NOT populate empty last_tv.json",
                )
                self.assertIsNone(app.tv_beacon.load_saved_tv_endpoint())
            finally:
                srv.shutdown()
                srv.server_close()

    @unittest.skipUnless(_POST_FIX_ENABLED, "Requires M1/M2/M3 fixes (run with MYTV_E2E_POST_FIX=1)")
    def test_tier2_post_fix_windows_firewall_python_allows_private_and_public(self) -> None:
        if sys.platform != "win32":
            self.skipTest("Windows Firewall profile check applies only to Windows host")
        fw = live_harness.inspect_windows_firewall_python()
        self.assertTrue(
            fw.get("coversBothPrivateAndPublic"),
            f"Windows Firewall Python inbound Allow rule must cover BOTH Private and Public profiles: {fw}",
        )


# =============================================================================
# Feature 2: Stream Startup, Pairing & Lifecycle (Tier 1 >= 5, Tier 2 >= 5)
# =============================================================================


class TestFeature2StartupPairingAndLifecycle(unittest.TestCase):
    """Tier 1 & Tier 2 opaque-box tests for pairing, Sunshine PIN, launch prelaunch, and terminate."""

    def test_tier1_pair_request_and_verify_issues_64_hex_token(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app, srv, port, _ = _create_sandbox_agent(Path(tmp), seed_token="")
            try:
                c1, d1, _ = _http_json(port, "POST", "/api/gaming/pair/request", body={})
                self.assertEqual(c1, 200)
                self.assertTrue(d1.get("ok"))
                self.assertNotIn("code", d1)
                pair_code = app.auth_manager.pair_code_path.read_text(encoding="utf-8").strip()
                self.assertRegex(pair_code, r"^\d{6}$")

                c2, d2, _ = _http_json(
                    port, "POST", "/api/gaming/pair/verify", body={"code": pair_code, "deviceName": "MyTV"}
                )
                self.assertEqual(c2, 200)
                self.assertTrue(d2.get("paired"))
                tok = d2.get("token", "")
                self.assertRegex(tok, r"^[0-9a-f]{64}$")

                c3, d3, _ = _http_json(port, "GET", "/api/gaming/status", headers={"X-MyTV-Token": tok})
                self.assertEqual(c3, 200)
                self.assertTrue(d3.get("authenticated"))
            finally:
                srv.shutdown()
                srv.server_close()

    def test_tier1_sunshine_pair_valid_pin_via_mock_sunshine_api(self) -> None:
        sun_srv, sun_state, api_fn = _start_mock_sunshine_api(accept_pin=True)
        with tempfile.TemporaryDirectory() as tmp:
            _, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                with mock.patch.object(SunshineConfigManager, "_sunshine_api_request", side_effect=api_fn):
                    code, data, _ = _http_json(
                        port,
                        "POST",
                        "/api/gaming/sunshine-pair",
                        body={"pin": "4821", "deviceName": "MyTV"},
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                self.assertEqual(code, 200)
                self.assertTrue(data.get("ok"))
                self.assertTrue(data.get("paired"))
                self.assertEqual(sun_state["submitted_pins"][0]["pin"], "4821")
                self.assertEqual(sun_state["submitted_pins"][0]["pairing_id"], sun_state["pairing_id"])
            finally:
                srv.shutdown()
                srv.server_close()
                sun_srv.shutdown()
                sun_srv.server_close()

    def test_tier1_launch_with_user_approved_and_prelaunch_engages_curtain(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                with mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                        mock.patch.object(WindowsSessionController, "is_session_locked", return_value=False), \
                        mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                        mock.patch.object(WindowsSessionController, "launch_game", return_value=True), \
                        mock.patch.object(SteamPreparationManager, "prepare_for_stream", return_value=True), \
                        mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True):
                    code, data, _ = _http_json(
                        port,
                        "POST",
                        "/api/gaming/launch",
                        body={
                            "gameId": "standalone_hades",
                            "connectionMode": "auto",
                            "userApproved": True,
                            "prelaunchBeforeStream": True,
                            "delegateLaunchToMoonlight": False,
                        },
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                self.assertEqual(code, 200)
                self.assertTrue(data.get("launched"))
                self.assertTrue(data.get("curtainEngaged"))
                self.assertTrue(data.get("userApproved"))
                self.assertEqual(data.get("activeMode"), "wifi")
            finally:
                app.privacy_curtain.stop()
                srv.shutdown()
                srv.server_close()

    def test_tier1_launch_status_transitions_window_ready(self) -> None:
        hwnd_holder = {"hwnd": 0}
        with tempfile.TemporaryDirectory() as tmp:
            app, srv, port, _ = _create_sandbox_agent(
                Path(tmp), find_game_hwnd_fn=lambda _g: hwnd_holder["hwnd"]
            )
            try:
                with mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                        mock.patch.object(WindowsSessionController, "is_session_locked", return_value=False), \
                        mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                        mock.patch.object(WindowsSessionController, "launch_game", return_value=True), \
                        mock.patch.object(SteamPreparationManager, "prepare_for_stream", return_value=True), \
                        mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True):
                    code, _, _ = _http_json(
                        port,
                        "POST",
                        "/api/gaming/launch",
                        body={
                            "gameId": "standalone_hades",
                            "connectionMode": "wifi",
                            "userApproved": True,
                            "prelaunchBeforeStream": True,
                        },
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                self.assertEqual(code, 200)

                c1, st1, _ = _http_json(
                    port, "GET", "/api/gaming/launch-status", headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN}
                )
                self.assertEqual(c1, 200)
                self.assertTrue(st1.get("active"))
                self.assertFalse(st1.get("gameWindowReady"))

                hwnd_holder["hwnd"] = 987654
                ready = False
                for _ in range(30):
                    _, st2, _ = _http_json(
                        port, "GET", "/api/gaming/launch-status", headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN}
                    )
                    if st2.get("gameWindowReady"):
                        ready = True
                        break
                    time.sleep(0.04)
                self.assertTrue(ready, "launch-status must transition gameWindowReady False -> True")
            finally:
                app.privacy_curtain.stop()
                srv.shutdown()
                srv.server_close()

    def test_tier1_terminate_restores_pc_state(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                with mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                        mock.patch.object(WindowsSessionController, "is_session_locked", return_value=False), \
                        mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                        mock.patch.object(WindowsSessionController, "launch_game", return_value=True), \
                        mock.patch.object(SteamPreparationManager, "prepare_for_stream", return_value=True), \
                        mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True):
                    _http_json(
                        port,
                        "POST",
                        "/api/gaming/launch",
                        body={"gameId": "standalone_hades", "userApproved": True, "prelaunchBeforeStream": True},
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                self.assertTrue(app.privacy_curtain.is_active())
                code, res, _ = _http_json(
                    port,
                    "POST",
                    "/api/gaming/terminate",
                    body={"gameId": "standalone_hades", "reason": "tv_remote_back"},
                    headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                )
                self.assertEqual(code, 200)
                self.assertEqual(res.get("status"), "ok")
                self.assertTrue(res.get("pcRestored"))
                self.assertFalse(app.privacy_curtain.is_active())
            finally:
                app.privacy_curtain.stop()
                srv.shutdown()
                srv.server_close()

    def test_tier2_unauthenticated_mutating_endpoints_return_401(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            _, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                for method, path, body in (
                    ("GET", "/api/gaming/launch-status", None),
                    ("POST", "/api/gaming/sunshine-pair", {"pin": "1234"}),
                    ("POST", "/api/gaming/launch", {"gameId": "desktop", "userApproved": True}),
                    ("POST", "/api/gaming/terminate", {"gameId": "desktop"}),
                    ("POST", "/api/gaming/hide", {"gameId": "standalone_hades", "hidden": True}),
                ):
                    code, data, _ = _http_json(port, method, path, body=body)
                    self.assertEqual(code, 401, f"{method} {path} must reject unauthenticated with 401")
                    self.assertEqual(data.get("error"), "unauthorized")
            finally:
                srv.shutdown()
                srv.server_close()

    def test_tier2_launch_rejects_missing_or_false_user_approval_400(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            _, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                for payload in (
                    {"gameId": "standalone_hades"},
                    {"gameId": "standalone_hades", "userApproved": False},
                    {"gameId": "standalone_hades", "userApproved": "false"},
                ):
                    code, data, _ = _http_json(
                        port,
                        "POST",
                        "/api/gaming/launch",
                        body=payload,
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                    self.assertEqual(code, 400)
                    self.assertEqual(data.get("error"), "explicit_user_approval_required")
            finally:
                srv.shutdown()
                srv.server_close()

    def test_tier2_sunshine_pair_rejects_invalid_pin_400(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            _, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                for bad_pin in ("abc", "123", "12345", ""):
                    code, data, _ = _http_json(
                        port,
                        "POST",
                        "/api/gaming/sunshine-pair",
                        body={"pin": bad_pin},
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                    self.assertEqual(code, 400)
                    self.assertEqual(data.get("error"), "invalid_pin")
            finally:
                srv.shutdown()
                srv.server_close()

    def test_tier2_sunshine_pair_no_match_returns_409(self) -> None:
        sun_srv, _, api_fn = _start_mock_sunshine_api(accept_pin=False)
        with tempfile.TemporaryDirectory() as tmp:
            _, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                with mock.patch.object(SunshineConfigManager, "_sunshine_api_request", side_effect=api_fn), \
                        mock.patch.object(
                            SunshineConfigManager,
                            "submit_pairing_pin_with_retry",
                            return_value=False,
                        ):
                    code, data, _ = _http_json(
                        port,
                        "POST",
                        "/api/gaming/sunshine-pair",
                        body={"pin": "9999"},
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                self.assertEqual(code, 409)
                self.assertFalse(data.get("paired"))
            finally:
                srv.shutdown()
                srv.server_close()
                sun_srv.shutdown()
                sun_srv.server_close()

    def test_tier2_pair_verify_rate_limit_429_and_idempotent_terminate(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            _, srv, port, _ = _create_sandbox_agent(Path(tmp), seed_token="")
            try:
                _http_json(port, "POST", "/api/gaming/pair/request", body={})
                for _ in range(3):
                    c, _, _ = _http_json(port, "POST", "/api/gaming/pair/verify", body={"code": "000000"})
                    self.assertEqual(c, 401)
                c4, d4, _ = _http_json(port, "POST", "/api/gaming/pair/verify", body={"code": "000000"})
                self.assertEqual(c4, 429)
                self.assertEqual(d4.get("error"), "rate_limited")

                ct, dt, _ = _http_json(
                    port,
                    "POST",
                    "/api/gaming/terminate",
                    body={"gameId": ""},
                    headers={"X-MyTV-Token": "nope"},
                )
                self.assertEqual(ct, 401)
            finally:
                srv.shutdown()
                srv.server_close()

        with tempfile.TemporaryDirectory() as tmp:
            _, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                c_idle, d_idle, _ = _http_json(
                    port,
                    "POST",
                    "/api/gaming/terminate",
                    body={"gameId": "nonexistent"},
                    headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                )
                self.assertEqual(c_idle, 200)
                self.assertEqual(d_idle.get("status"), "ok")
            finally:
                srv.shutdown()
                srv.server_close()


# =============================================================================
# Feature 3: Performance Config, Refresh Rate & Volume Safety
# =============================================================================


class TestFeature3PerformanceConfigRefreshAndSafety(unittest.TestCase):
    """Tier 1 & Tier 2 tests for Sunshine config, telemetry parsing, 59->60Hz, and volume safety."""

    def test_tier1_sunshine_config_parser_parses_conf(self) -> None:
        raw = "# header\nencoder = quicksync\nhevc_mode = 1\nfec_percentage = 20\n"
        parsed = SunshineConfigParser.parse_text(raw)
        self.assertEqual(parsed, {"encoder": "quicksync", "hevc_mode": "1", "fec_percentage": "20"})

    def test_tier1_sunshine_config_validator_accepts_optimal_config(self) -> None:
        self.assertEqual(SunshineConfigValidator.validate_config(MANDATORY_M2_CONFIG), [])

    def test_tier1_display_refresh_rate_59_to_60_engage_and_restore(self) -> None:
        hz = {"cur": 59}
        calls: List[int] = []
        ctrl = DisplayRefreshRateController(
            get_primary_refresh_hz_fn=lambda: hz["cur"],
            set_primary_refresh_hz_fn=lambda target: (calls.append(target) or hz.__setitem__("cur", target) is None or True),
        )
        self.assertTrue(ctrl.engage_60hz())
        self.assertEqual(hz["cur"], 60)
        self.assertTrue(ctrl.restore())
        self.assertEqual(hz["cur"], 59)
        self.assertEqual(calls, [60, 59])

    def test_tier1_sunshine_log_telemetry_parser_extracts_encoders_and_config(self) -> None:
        sample = (
            "Sunshine version: v2026.1.0\n"
            "Info: config: 'encoder' = quicksync\n"
            "Info: Trying encoder [quicksync]\n"
            "Info: Found h264_qsv encoder: Intel(R) UHD Graphics\n"
        )
        parsed = SunshineLogTelemetryParser.parse_log(sample)
        self.assertEqual(parsed["version"], "v2026.1.0")
        self.assertEqual(parsed["tried_encoders"], ["quicksync"])
        self.assertEqual(len(parsed["found_encoders"]), 1)
        self.assertEqual(parsed["fatal_errors"], [])

    def test_tier1_ensure_optimal_sunshine_conf_merges_and_posts(self) -> None:
        sun_srv, sun_state, api_fn = _start_mock_sunshine_api()
        try:
            with mock.patch.object(SunshineConfigManager, "_sunshine_api_request", side_effect=api_fn):
                ok = SunshineConfigManager.ensure_optimal_sunshine_conf()
            self.assertTrue(ok)
            self.assertEqual(len(sun_state["config_posts"]), 1)
            for k, v in SunshineConfigManager.OPTIMAL_OPTIMUS_SUNSHINE_CONFIG.items():
                self.assertEqual(str(sun_state["config"].get(k)), str(v))
        finally:
            sun_srv.shutdown()
            sun_srv.server_close()

    def test_tier2_invalid_fec_percentage_rejected(self) -> None:
        for bad in ("-1", "2", "101", "not_int"):
            errs = SunshineConfigValidator.validate_config(dict(MANDATORY_M2_CONFIG, fec_percentage=bad))
            self.assertTrue(any("fec_percentage" in e for e in errs), f"fec_percentage={bad} must fail")

    def test_tier2_invalid_nvenc_and_dd_options_rejected(self) -> None:
        for key, bad_val in (
            ("nvenc_preset", "9"),
            ("dd_configuration_option", "bogus"),
            ("qsv_coder", "invalid"),
        ):
            errs = SunshineConfigValidator.validate_config(dict(MANDATORY_M2_CONFIG, **{key: bad_val}))
            self.assertTrue(any(key in e for e in errs), f"{key}={bad_val} must fail")

    def test_tier2_display_refresh_rate_60hz_is_noop_and_restore_idempotent(self) -> None:
        set_calls: List[int] = []
        ctrl60 = DisplayRefreshRateController(
            get_primary_refresh_hz_fn=lambda: 60,
            set_primary_refresh_hz_fn=lambda t: (set_calls.append(t) is None or True),
        )
        self.assertTrue(ctrl60.engage_60hz())
        self.assertEqual(set_calls, [])

        hz = {"cur": 59}
        ctrl59 = DisplayRefreshRateController(
            get_primary_refresh_hz_fn=lambda: hz["cur"],
            set_primary_refresh_hz_fn=lambda t: (set_calls.append(t) or hz.__setitem__("cur", t) is None or True),
        )
        ctrl59.engage_60hz()
        ctrl59.restore()
        ctrl59.restore()
        self.assertEqual(set_calls, [60, 59])

    def test_tier2_privacy_safety_net_preserves_mute_and_forbids_vk_volume_up(self) -> None:
        unmute_args: List[bool] = []
        net = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, True),
            unmute_audio_fn=lambda was_muted: (unmute_args.append(was_muted) is None or True),
            minimize_windows_fn=lambda: [],
            restore_windows_fn=lambda _h: None,
            hide_desktop_fn=lambda: (False, 0),
            restore_desktop_fn=lambda _v, _d: None,
        )
        net._refresh_controller = DisplayRefreshRateController(
            get_primary_refresh_hz_fn=lambda: 60,
            set_primary_refresh_hz_fn=lambda _t: True,
        )
        self.assertTrue(net.engage())
        self.assertTrue(net.restore())
        # When PC was already muted (was_muted=True), restore() must NOT unmute it
        self.assertEqual(unmute_args, [])

        # When PC was NOT muted (was_muted=False), restore() restores unmuted state (was_muted=False)
        net2 = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, False),
            unmute_audio_fn=lambda was_muted: (unmute_args.append(was_muted) is None or True),
            minimize_windows_fn=lambda: [],
            restore_windows_fn=lambda _h: None,
            hide_desktop_fn=lambda: (False, 0),
            restore_desktop_fn=lambda _v, _d: None,
        )
        net2._refresh_controller = DisplayRefreshRateController(
            get_primary_refresh_hz_fn=lambda: 60,
            set_primary_refresh_hz_fn=lambda _t: True,
        )
        self.assertTrue(net2.engage())
        self.assertTrue(net2.restore())
        self.assertEqual(unmute_args, [False])

        agent_src = (_TOOLS_GAMING_DIR / "laptop_gaming_agent.py").read_text(encoding="utf-8", errors="ignore")
        self.assertNotIn("VK_VOLUME_UP", agent_src)

    def test_tier2_e2e_suite_os_hardware_guards_block_real_audio_and_display_calls(self) -> None:
        with self.assertRaises(AssertionError):
            PCPrivacySafetyNet._native_mute_audio()
        with self.assertRaises(AssertionError):
            PCPrivacySafetyNet._native_unmute_audio(False)
        with self.assertRaises(AssertionError):
            CursorPrivacyController().park_cursor()
        with self.assertRaises(AssertionError):
            DisplayRefreshRateController._native_set_primary_refresh_hz(60)
        with self.assertRaises(AssertionError):
            DisplayModeController.apply_display_mode(True)
        with self.assertRaises(AssertionError):
            WindowsSessionController.unlock_with_pin("1234")
        with self.assertRaises(AssertionError):
            SunshineConfigManager._sunshine_api_request("GET", "/api/apps")

        unguarded_safety = PCPrivacySafetyNet()
        self.assertFalse(unguarded_safety.engage())
        self.assertFalse(unguarded_safety.is_engaged())

    @unittest.skipUnless(_POST_FIX_ENABLED, "Requires M1/M2/M3 fixes (run with MYTV_E2E_POST_FIX=1)")
    def test_tier2_post_fix_m3_sunshine_encoder_coder_and_refresh_alignment(self) -> None:
        opt = SunshineConfigManager.OPTIMAL_OPTIMUS_SUNSHINE_CONFIG
        self.assertNotEqual(
            str(opt.get("qsv_coder", "")).lower(),
            "cavlc",
            "M3 requires upgrading qsv_coder off slow CAVLC",
        )
        self.assertGreaterEqual(int(opt.get("minimum_fps_target", 0)), 60)
        self.assertNotEqual(
            str(opt.get("dd_refresh_rate_option", "disabled")).lower(),
            "disabled",
            "M3 requires active refresh rate alignment (dd_refresh_rate_option != disabled) or aligned encoder config",
        )


# =============================================================================
# Feature 4: R3 Isolation, R4 ADB Rule & Java E2E Integration
# =============================================================================


class TestFeature4IsolationAdbRuleAndJavaE2E(unittest.TestCase):
    """Tier 1 & Tier 2 checks for R3 Settings isolation, R4 ADB release rule, and Java E2E suite."""

    def test_tier1_r3_settings_tree_and_renderer_isolated_vs_main(self) -> None:
        # SettingsScreenRenderer stays strictly isolated; SettingsTree Gaming tips were explicitly
        # upgraded to be dynamic (no hardcoded GPU/monitor tips) while preserving redesign structure.
        renderer_path = _LAUNCHER_DIR / "app" / "src" / "io" / "mytv" / "SettingsScreenRenderer.java"
        self.assertTrue(renderer_path.is_file(), "SettingsScreenRenderer.java must exist in io.mytv")
        renderer_text = renderer_path.read_text(encoding="utf-8")
        self.assertIn("class SettingsScreenRenderer", renderer_text)
        self.assertNotIn("moonlight", renderer_text.lower())

        st_src = (_LAUNCHER_DIR / "app" / "src" / "io" / "mytv" / "SettingsTree.java").read_text(encoding="utf-8")
        self.assertIn("buildGamingNode", st_src)
        idx = st_src.index("buildGamingNode")
        gaming_section = st_src[idx : idx + 15000]
        self.assertNotIn("moonlight", gaming_section.lower())
        self.assertNotIn("hybrid laptop", gaming_section.lower())
        self.assertNotIn("desktop rtx", gaming_section.lower())

    def test_tier1_r3_branch_valid_and_live_harness_not_test_prefixed(self) -> None:
        proc = subprocess.run(
            ["git", "-C", str(_REPO_ROOT), "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(proc.returncode, 0)
        branch = proc.stdout.strip()
        self.assertNotEqual(branch, "")
        self.assertNotEqual(branch, "HEAD")

        harness_path = _TOOLS_GAMING_DIR / "verify_live_gaming_e2e.py"
        self.assertTrue(harness_path.is_file())
        self.assertFalse(harness_path.name.startswith("test_"))

    def test_tier1_verify_live_harness_ast_enforces_finally_adb_disconnect(self) -> None:
        harness_src = (_TOOLS_GAMING_DIR / "verify_live_gaming_e2e.py").read_text(encoding="utf-8")
        tree = ast.parse(harness_src)
        run_tv_fn = next(
            (n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "run_tv_checks"),
            None,
        )
        self.assertIsNotNone(run_tv_fn)
        assert run_tv_fn is not None
        finally_blocks = [
            stmt.finalbody
            for stmt in ast.walk(run_tv_fn)
            if isinstance(stmt, ast.Try) and stmt.finalbody
        ]
        self.assertTrue(finally_blocks, "run_tv_checks must wrap ADB commands in try ... finally:")
        finally_src = "\n".join(ast.get_source_segment(harness_src, node) or "" for blk in finally_blocks for node in blk)
        self.assertIn("disconnect", finally_src)

    def test_tier1_java_e2e_requirements_compile_and_run_default_mode(self) -> None:
        out_dir, android_jar, java_bin, cp_sep = _ensure_java_e2e_compiled()
        proc = subprocess.run(
            [java_bin, "-cp", f"{out_dir}{cp_sep}{android_jar}", "io.mytv.GamingE2ERequirementsTests"],
            cwd=str(_LAUNCHER_DIR),
            capture_output=True,
            text=True,
            timeout=120,
        )
        self.assertEqual(proc.returncode, 0, f"Java default mode failed:\n{proc.stdout}\n{proc.stderr}")
        self.assertIn("mode=BASELINE", proc.stdout)

    def test_tier1_verify_live_harness_check_host_never_invokes_adb(self) -> None:
        harness_src = (_TOOLS_GAMING_DIR / "verify_live_gaming_e2e.py").read_text(encoding="utf-8")
        tree = ast.parse(harness_src)
        host_fn = next(
            (n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "run_host_checks"),
            None,
        )
        self.assertIsNotNone(host_fn)
        assert host_fn is not None
        host_segment = ast.get_source_segment(harness_src, host_fn) or ""
        self.assertNotIn("_run_adb", host_segment)
        self.assertNotIn("resolve_adb_executable", host_segment)

    def test_tier2_r3_settings_renderer_contains_zero_moonlight_ui(self) -> None:
        renderer_src = (_LAUNCHER_DIR / "app" / "src" / "io" / "mytv" / "SettingsScreenRenderer.java").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("moonlight", renderer_src.lower())

    def test_tier2_verify_live_harness_env_resolution_and_secret_redaction(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake_repo = Path(tmp) / "worktree"
            fake_repo.mkdir()
            fb_env = Path(tmp) / "main.env"
            fb_env.write_text('TV_IP=192.168.1.27\nGAMING_PC_TOKEN="supersecret_12345678"\n', encoding="utf-8")
            resolved = live_harness.resolve_env_file(repo_root=fake_repo, fallback_paths=[fb_env])
            self.assertEqual(resolved, fb_env)
            parsed = live_harness.parse_env_file(resolved)
            self.assertEqual(parsed.get("TV_IP"), "192.168.1.27")

            redacted = live_harness.redact_secrets(
                {"TV_IP": "192.168.1.27", "GAMING_PC_TOKEN": "supersecret_12345678", "msg": "tok=supersecret_12345678"},
                secret_values=["supersecret_12345678"],
            )
            self.assertEqual(redacted["GAMING_PC_TOKEN"], "***REDACTED***")
            self.assertNotIn("supersecret_12345678", json.dumps(redacted))

    def test_tier2_verify_live_harness_logcat_and_sunshine_log_parsers(self) -> None:
        logcat = (
            "GamingZone: Discovered Gaming PC via UDP broadcast at 192.168.1.12\n"
            "GamingZone: Dispatched remote termination for steam_728880\n"
            "11-25 17:34:54.367  5327  5327 I MyTV: GameStream decoder configured: "
            "codec=c2.mtk.avc.decoder mime=video/avc res=1920x1080 fps=60 lowLatency=true "
            "accepted=[low-latency(feat=true), priority=0, operating-rate=120, vendor.mtk.vdec.lowlatency=1] skipped=[]\n"
            "11-25 17:34:56.370  5327  5327 I MyTV: GameStream telemetry: "
            "codec=c2.mtk.avc.decoder(H.264) lowLatency=true res=1920x1080 pacing=balanced reqFps=60 "
            "inFps=59.8 outFps=59.5 decodeMs=3.25 maxDecodeMs=6.10 dropIn=0 dropOut=0 idr=0 "
            "bitrateMbps=12.40 targetMbps=30 rttMs=4\n"
            "11-25 17:34:57.000  5327  5327 W MyTV: GameStreamActivity drSetup failed: test\n"
            "HeapTaskDaemon: Background concurrent copying GC freed 12MB paused 112ms\n"
        )
        lc = live_harness.parse_logcat_telemetry(logcat)
        self.assertEqual(len(lc["discoveryEvents"]), 1)
        self.assertEqual(len(lc["terminateEvents"]), 1)
        self.assertEqual(len(lc["streamTelemetry"]), 2)
        self.assertIn("GameStream decoder configured:", lc["streamTelemetry"][0])
        self.assertIn("GameStream telemetry:", lc["streamTelemetry"][1])
        self.assertEqual(len(lc["streamErrors"]), 1)
        self.assertEqual(len(lc["gcPauses"]), 1)

        slog = (
            "Info: CLIENT CONNECTED\n"
            "Info: Display refresh rate [59.9401Hz]\n"
            "Info: Requested frame rate [60/1]\n"
            "Error: WSASendMsg() failed: 10022\n"
        )
        sl = live_harness.parse_sunshine_log_telemetry(slog)
        self.assertEqual(sl["clientConnectedCount"], 1)
        self.assertEqual(sl["displayRefreshRates"], ["59.9401Hz"])
        self.assertEqual(sl["requestedFrameRates"], ["60/1"])
        self.assertEqual(sl["wsaSendMsgErrorCount"], 1)

    def test_tier2_verify_live_harness_never_calls_launch_or_mute(self) -> None:
        harness_src = (_TOOLS_GAMING_DIR / "verify_live_gaming_e2e.py").read_text(encoding="utf-8")
        for forbidden in (
            "/api/gaming/launch",
            "/api/gaming/terminate",
            "/api/gaming/wake",
            "/api/gaming/unlock",
            "PCPrivacySafetyNet",
            "PrivacyCurtainManager",
            "_native_mute_audio",
            "SetMute",
            "DisplaySwitch",
        ):
            self.assertNotIn(
                forbidden,
                harness_src,
                f"verify_live_gaming_e2e.py must remain read-only and never invoke '{forbidden}'",
            )

    @unittest.skipUnless(_POST_FIX_ENABLED, "Requires M1/M2/M3 fixes (run with MYTV_E2E_POST_FIX=1)")
    def test_tier2_post_fix_r4_permanent_adb_release_rule_in_docs(self) -> None:
        rule_pattern = re.compile(r"(adb\s+disconnect|free\s+(the\s+)?(tv|adb)|release\s+adb)", re.IGNORECASE)
        priority_pattern = re.compile(r"(priority|whenever\s+not\s+actively|do\s+not\s+hold)", re.IGNORECASE)
        for doc_rel in (
            "AGENTS.md",
            "MyTV-launcher/CLAUDE.md",
            "memory/common_issues_and_lessons.md",
        ):
            doc_path = _REPO_ROOT / doc_rel
            self.assertTrue(doc_path.is_file(), f"Missing doc: {doc_rel}")
            txt = doc_path.read_text(encoding="utf-8", errors="ignore")
            self.assertRegex(txt, rule_pattern, f"R4 permanent ADB disconnect/release rule missing from {doc_rel}")
            self.assertRegex(txt, priority_pattern, f"R4 ADB priority-release rule wording missing from {doc_rel}")

    @unittest.skipUnless(_POST_FIX_ENABLED, "Requires M1/M2/M3 fixes (run with MYTV_E2E_POST_FIX=1)")
    def test_tier2_post_fix_java_e2e_requirements_strict_mode(self) -> None:
        out_dir, android_jar, java_bin, cp_sep = _ensure_java_e2e_compiled()
        proc = subprocess.run(
            [
                java_bin,
                "-Dmytv.e2e.strict=true",
                "-cp",
                f"{out_dir}{cp_sep}{android_jar}",
                "io.mytv.GamingE2ERequirementsTests",
                "--strict",
            ],
            cwd=str(_LAUNCHER_DIR),
            capture_output=True,
            text=True,
            timeout=120,
        )
        self.assertEqual(proc.returncode, 0, f"Java --strict mode failed:\n{proc.stdout}\n{proc.stderr}")


# =============================================================================
# Tier 3: Cross-Feature Combinations (>= 5 tests)
# =============================================================================


class TestTier3CrossFeatureCombinations(unittest.TestCase):
    """Tier 3 pairwise and multi-feature E2E combinations across real loopback servers."""

    def test_tier3_discovery_auth_launch_status_refresh_terminate_restore(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            hwnd_box = {"hwnd": 0}
            app, srv, port, _ = _create_sandbox_agent(
                Path(tmp), find_game_hwnd_fn=lambda _g: hwnd_box["hwnd"]
            )
            udp = UdpDiscoveryResponder(
                host="127.0.0.1",
                port=0,
                tcp_port=port,
                uuid_provider=lambda: _TEST_HOST_UUID,
                hostname_provider=lambda: "Gaming-Laptop",
            )
            udp.start()
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as cli:
                    cli.settimeout(1.5)
                    cli.sendto(b'{"op":"MYTV_GAMING_DISCOVER_V1"}', ("127.0.0.1", udp.bound_port))
                    raw, _ = cli.recvfrom(4096)
                    disc = json.loads(raw.decode("utf-8"))
                discovered_port = disc["port"]

                c_st, d_st, _ = _http_json(
                    discovered_port, "GET", "/api/gaming/status", headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN}
                )
                self.assertEqual(c_st, 200)
                self.assertTrue(d_st["authenticated"])

                with mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                        mock.patch.object(WindowsSessionController, "is_session_locked", return_value=False), \
                        mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                        mock.patch.object(WindowsSessionController, "launch_game", return_value=True), \
                        mock.patch.object(SteamPreparationManager, "prepare_for_stream", return_value=True), \
                        mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True):
                    c_ln, d_ln, _ = _http_json(
                        discovered_port,
                        "POST",
                        "/api/gaming/launch",
                        body={"gameId": "standalone_hades", "userApproved": True, "prelaunchBeforeStream": True},
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                    self.assertEqual(c_ln, 200)
                    self.assertTrue(d_ln["curtainEngaged"])
                    self.assertEqual(app.safety_net._refresh_controller._saved_hz, 59)

                    hwnd_box["hwnd"] = 445566
                    time.sleep(0.08)
                    c_ls, d_ls, _ = _http_json(
                        discovered_port, "GET", "/api/gaming/launch-status", headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN}
                    )
                    self.assertEqual(c_ls, 200)
                    self.assertTrue(d_ls["gameWindowReady"])

                    c_tm, d_tm, _ = _http_json(
                        discovered_port,
                        "POST",
                        "/api/gaming/terminate",
                        body={"gameId": "standalone_hades", "reason": "user_exit"},
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                    self.assertEqual(c_tm, 200)
                    self.assertTrue(d_tm["pcRestored"])
                    self.assertFalse(app.safety_net.is_engaged())
                    self.assertEqual(app.safety_net._refresh_controller._changed_by_us, False)
            finally:
                app.privacy_curtain.stop()
                udp.stop()
                srv.shutdown()
                srv.server_close()

    def test_tier3_auth_tv_port_ip_change_pc_announce_reconnect(self) -> None:
        announces: List[Dict[str, Any]] = []

        class TvMock(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                ln = int(self.headers.get("Content-Length", "0") or "0")
                announces.append(json.loads(self.rfile.read(ln).decode("utf-8")))
                self.send_response(200)
                self.send_header("Content-Length", "11")
                self.end_headers()
                self.wfile.write(b'{"ok":true}')

            def log_message(self, fmt: str, *args: Any) -> None:
                return

        tv_srv = HTTPServer(("127.0.0.1", 0), TvMock)
        tv_port = tv_srv.server_address[1]
        threading.Thread(target=tv_srv.serve_forever, daemon=True).start()

        with tempfile.TemporaryDirectory() as tmp:
            app, srv, port, _ = _create_sandbox_agent(Path(tmp), default_tv_port=tv_port)
            try:
                app.tv_beacon.record_authenticated_tv("127.0.0.1", str(tv_port))
                with mock.patch.object(
                    agent_mod.LocalNetworkInspector,
                    "get_primary_lan_ipv4",
                    side_effect=["192.168.1.12", "192.168.1.88"],
                ):
                    app.tv_beacon.check_and_notify_if_ip_changed(agent_port=port)
                    app.tv_beacon.check_and_notify_if_ip_changed(agent_port=port)
                self.assertEqual([a["pcIp"] for a in announces], ["192.168.1.12", "192.168.1.88"])

                c, d, _ = _http_json(
                    port, "GET", "/api/gaming/status", headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN}
                )
                self.assertEqual(c, 200)
                self.assertTrue(d["authenticated"])
            finally:
                srv.shutdown()
                srv.server_close()
                tv_srv.shutdown()
                tv_srv.server_close()

    def test_tier3_connection_mode_matrix_hdmi_vs_wifi_with_curtain_and_refresh(self) -> None:
        self.assertEqual(LaptopGamingAgentApp.resolve_connection_mode("auto", True), "hdmi")
        self.assertEqual(LaptopGamingAgentApp.resolve_connection_mode("auto", False), "wifi")
        self.assertEqual(LaptopGamingAgentApp.resolve_connection_mode("wifi", True), "wifi")
        self.assertEqual(LaptopGamingAgentApp.resolve_connection_mode("hdmi", False), "wifi")
        self.assertEqual(LaptopGamingAgentApp.resolve_connection_mode("hdmi", True), "hdmi")

        with tempfile.TemporaryDirectory() as tmp:
            app, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                with mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=True), \
                        mock.patch.object(WindowsSessionController, "is_session_locked", return_value=False), \
                        mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                        mock.patch.object(WindowsSessionController, "launch_game", return_value=True):
                    code, data, _ = _http_json(
                        port,
                        "POST",
                        "/api/gaming/launch",
                        body={"gameId": "standalone_hades", "connectionMode": "auto", "userApproved": True},
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                self.assertEqual(code, 200)
                self.assertEqual(data.get("activeMode"), "hdmi")
                self.assertFalse(data.get("curtainEngaged"))
            finally:
                app.privacy_curtain.stop()
                srv.shutdown()
                srv.server_close()

    def test_tier3_sunshine_api_config_apps_pin_close_lifecycle(self) -> None:
        sun_srv, sun_state, api_fn = _start_mock_sunshine_api(accept_pin=True)
        with tempfile.TemporaryDirectory() as tmp:
            games = GameCatalogScanner(
                games_root=Path(tmp) / "Games", steam_root=Path(tmp) / "Steam"
            )
            (Path(tmp) / "Games" / "Celeste").mkdir(parents=True, exist_ok=True)
            (Path(tmp) / "Games" / "Celeste" / "Celeste.exe").write_bytes(b"MZ")
            discovered = games.discover_games()

            try:
                with mock.patch.object(SunshineConfigManager, "_sunshine_api_request", side_effect=api_fn):
                    self.assertTrue(SunshineConfigManager.ensure_optimal_sunshine_conf())
                    changed = SunshineConfigManager.sync_via_api(discovered, steam_root=Path(tmp) / "Steam")
                    self.assertGreaterEqual(changed, 1)
                    self.assertTrue(SunshineConfigManager.submit_pairing_pin_with_retry("1357"))
                    self.assertTrue(SunshineConfigManager.close_active_app())
                self.assertEqual(sun_state["close_calls"], 1)
                self.assertTrue(any(a.get("name") == "Celeste" for a in sun_state["apps"]))
            finally:
                sun_srv.shutdown()
                sun_srv.server_close()

    def test_tier3_prelaunch_failure_and_desktop_mode_resilience(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app, srv, port, _ = _create_sandbox_agent(Path(tmp))
            try:
                with mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                        mock.patch.object(WindowsSessionController, "is_session_locked", return_value=False), \
                        mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                        mock.patch.object(WindowsSessionController, "launch_game", return_value=True), \
                        mock.patch.object(SteamPreparationManager, "prepare_for_stream", return_value=True), \
                        mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True):
                    c_desk, d_desk, _ = _http_json(
                        port,
                        "POST",
                        "/api/gaming/launch",
                        body={"gameId": "desktop", "userApproved": True, "prelaunchBeforeStream": True},
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                    self.assertEqual(c_desk, 200)
                    self.assertFalse(d_desk["curtainEngaged"])
                    self.assertEqual(d_desk["sunshineAppName"], "Desktop")
            finally:
                app.privacy_curtain.stop()
                srv.shutdown()
                srv.server_close()


# =============================================================================
# Tier 4: Real-World End-to-End Application Scenarios (>= 2 tests)
# =============================================================================


class TestTier4RealWorldScenarios(unittest.TestCase):
    """Tier 4 full simulated end-to-end gaming session & multi-setting validation."""

    def test_tier4_full_end_to_end_gaming_workflow_and_grace_period_cleanup(self) -> None:
        sun_srv, sun_state, api_fn = _start_mock_sunshine_api(accept_pin=True)
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            sun_log = tmp_dir / "sunshine.log"
            sun_log.write_text("Sunshine version: v2026.1.0\n", encoding="utf-8")
            hwnd_box = {"hwnd": 0}
            app, srv, port, _ = _create_sandbox_agent(
                tmp_dir,
                find_game_hwnd_fn=lambda _g: hwnd_box["hwnd"],
                sunshine_log_path=sun_log,
            )
            udp = UdpDiscoveryResponder(
                host="127.0.0.1",
                port=0,
                tcp_port=port,
                uuid_provider=lambda: _TEST_HOST_UUID,
                hostname_provider=lambda: "Gaming-Laptop",
            )
            udp.start()
            try:
                # 1. Discover via UDP
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as cli:
                    cli.settimeout(1.5)
                    cli.sendto(b'{"op":"MYTV_GAMING_DISCOVER_V1"}', ("127.0.0.1", udp.bound_port))
                    disc = json.loads(cli.recvfrom(4096)[0].decode("utf-8"))
                self.assertEqual(disc["op"], "MYTV_GAMING_HERE_V1")

                # 2. Check authenticated /status & verify Steam + standalone catalog
                c_st, d_st, _ = _http_json(
                    port, "GET", "/api/gaming/status", headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN}
                )
                self.assertEqual(c_st, 200)
                titles = {g["title"] for g in d_st["games"]}
                self.assertIn("Overcooked! 2", titles)
                self.assertIn("Hades", titles)

                # 3. Headless Sunshine PIN pairing
                with mock.patch.object(SunshineConfigManager, "_sunshine_api_request", side_effect=api_fn):
                    c_sp, d_sp, _ = _http_json(
                        port,
                        "POST",
                        "/api/gaming/sunshine-pair",
                        body={"pin": "2468", "deviceName": "MyTV"},
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                self.assertEqual(c_sp, 200)
                self.assertTrue(d_sp["paired"])

                # 4. Prelaunch behind curtain, transition HWND ready, simulate Sunshine CLIENT CONNECTED -> DISCONNECTED -> terminate
                with mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                        mock.patch.object(WindowsSessionController, "is_session_locked", return_value=False), \
                        mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                        mock.patch.object(WindowsSessionController, "launch_game", return_value=True), \
                        mock.patch.object(SteamPreparationManager, "prepare_for_stream", return_value=True), \
                        mock.patch.object(SunshineConfigManager, "_sunshine_api_request", side_effect=api_fn), \
                        mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True):
                    c_ln, d_ln, _ = _http_json(
                        port,
                        "POST",
                        "/api/gaming/launch",
                        body={"gameId": "steam_728880", "userApproved": True, "prelaunchBeforeStream": True},
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                    self.assertEqual(c_ln, 200)
                    self.assertTrue(d_ln["curtainEngaged"])

                    hwnd_box["hwnd"] = 112233
                    time.sleep(0.08)
                    with sun_log.open("a", encoding="utf-8") as fh:
                        fh.write("Info: CLIENT CONNECTED\nInfo: CLIENT DISCONNECTED\n")
                    time.sleep(0.06)
                    # Within disconnect grace period curtain stays active until explicit /terminate
                    self.assertTrue(app.privacy_curtain.is_active())

                    c_tm, d_tm, _ = _http_json(
                        port,
                        "POST",
                        "/api/gaming/terminate",
                        body={"gameId": "steam_728880", "reason": "tv_remote_home"},
                        headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                    )
                    self.assertEqual(c_tm, 200)
                    self.assertTrue(d_tm["pcRestored"])
                    self.assertFalse(app.privacy_curtain.is_active())
                    self.assertFalse(app.safety_net.is_engaged())
            finally:
                app.privacy_curtain.stop()
                udp.stop()
                srv.shutdown()
                srv.server_close()
                sun_srv.shutdown()
                sun_srv.server_close()

    def test_tier4_multi_setting_sunshine_and_client_matrix_validation(self) -> None:
        # Stage A: Real SunshineConfigManager remediation -> INI round-trip -> SunshineConfigValidator + negative rejections
        sun_srv, sun_state, api_fn = _start_mock_sunshine_api(accept_pin=True)
        sun_state["config"] = {
            "encoder": "nvenc",
            "hevc_mode": 0,
            "minimum_fps_target": 30,
            "fec_percentage": 5,
            "dd_configuration_option": "ensure_only_display",
        }
        try:
            with mock.patch.object(SunshineConfigManager, "_sunshine_api_request", side_effect=api_fn):
                self.assertTrue(SunshineConfigManager.ensure_optimal_sunshine_conf())
            self.assertEqual(len(sun_state["config_posts"]), 1)
            ini_text = "\n".join(f"{k} = {v}" for k, v in sun_state["config"].items())
            parsed_cfg = SunshineConfigParser.parse_text(ini_text)
            self.assertEqual(SunshineConfigValidator.validate_config(parsed_cfg), [])

            for bad_key, bad_val in (
                ("fec_percentage", "5"),
                ("nvenc_preset", "9"),
                ("dd_configuration_option", "bogus"),
            ):
                bad_errs = SunshineConfigValidator.validate_config(dict(parsed_cfg, **{bad_key: bad_val}))
                self.assertTrue(
                    any(bad_key in e for e in bad_errs),
                    f"SunshineConfigValidator must reject {bad_key}={bad_val}: {bad_errs}",
                )
        finally:
            sun_srv.shutdown()
            sun_srv.server_close()

        # Stage B: Real sandboxed LaptopGamingAgentApp + HTTP launch/status/terminate matrix across 4 profiles
        # Isolate each profile with its own fresh sandbox agent & HTTP server so a dying
        # PrivacyCurtainManager.monitor_loop thread from profile N cannot race profile N+1.
        profiles = (
            ("720p", 30, 10, "wifi", False, "standalone_hades", "wifi", True),
            ("1080p", 60, 30, "auto", False, "steam_728880", "wifi", True),
            ("1440p", 90, 60, "hdmi", False, "standalone_hades", "wifi", True),
            ("4k", 120, 100, "auto", True, "steam_728880", "hdmi", False),
        )
        for res, fps, mbps, req_mode, hdmi_conn, game_id, exp_mode, exp_curtain in profiles:
            self.assertEqual(
                LaptopGamingAgentApp.resolve_connection_mode(req_mode, hdmi_conn),
                exp_mode,
            )
            with tempfile.TemporaryDirectory() as tmp:
                app, srv, port, _ = _create_sandbox_agent(
                    Path(tmp), find_game_hwnd_fn=lambda _g: 556677
                )
                try:
                    with mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=hdmi_conn), \
                            mock.patch.object(WindowsSessionController, "is_session_locked", return_value=False), \
                            mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                            mock.patch.object(WindowsSessionController, "launch_game", return_value=True), \
                            mock.patch.object(SteamPreparationManager, "prepare_for_stream", return_value=True), \
                            mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True):
                        c_ln, d_ln, _ = _http_json(
                            port,
                            "POST",
                            "/api/gaming/launch",
                            body={
                                "gameId": game_id,
                                "connectionMode": req_mode,
                                "userApproved": True,
                                "prelaunchBeforeStream": True,
                                "delegateLaunchToMoonlight": False,
                            },
                            headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                        )
                        self.assertEqual(c_ln, 200, f"Launch failed for {res}/{fps}: {d_ln}")
                        self.assertEqual(d_ln.get("activeMode"), exp_mode)
                        self.assertIs(d_ln.get("curtainEngaged"), exp_curtain)

                        if exp_curtain:
                            self.assertTrue(app.safety_net.is_engaged())
                            self.assertEqual(app.safety_net._refresh_controller._saved_hz, 59)
                            self.assertTrue(app.safety_net._refresh_controller._changed_by_us)
                            self.assertEqual(app.safety_net._refresh_controller._custom_get_fn(), 60)

                            c_ls, d_ls, _ = _http_json(
                                port,
                                "GET",
                                "/api/gaming/launch-status",
                                headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                            )
                            self.assertEqual(c_ls, 200)
                            self.assertTrue(d_ls.get("gameWindowReady"))
                        else:
                            self.assertFalse(app.safety_net.is_engaged())

                        c_tm, d_tm, _ = _http_json(
                            port,
                            "POST",
                            "/api/gaming/terminate",
                            body={"gameId": game_id, "reason": "matrix_profile_done"},
                            headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                        )
                        self.assertEqual(c_tm, 200)
                        self.assertTrue(d_tm.get("pcRestored"))
                        self.assertFalse(app.safety_net.is_engaged())
                        self.assertFalse(app.safety_net._refresh_controller._changed_by_us)
                        self.assertEqual(app.safety_net._refresh_controller._custom_get_fn(), 59)
                finally:
                    app.privacy_curtain.stop()
                    srv.shutdown()
                    srv.server_close()

            client_cfg = {
                "video_codec": "HEVC",
                "frame_pacing": "Balanced",
                "target_fps": fps,
                "resolution": res,
                "bitrate_mbps": mbps,
            }
            devs = MoonlightClientPacingSpec.validate_client_settings(client_cfg)
            if fps == 60:
                self.assertEqual(devs, [])
            else:
                self.assertTrue(any("60" in d for d in devs), f"Expected 60fps deviation for fps={fps}: {devs}")

        # Negative HTTP & pacing rejections in an isolated sandbox
        with tempfile.TemporaryDirectory() as tmp_neg:
            app_neg, srv_neg, port_neg, _ = _create_sandbox_agent(Path(tmp_neg))
            try:
                c_no_app, d_no_app, _ = _http_json(
                    port_neg,
                    "POST",
                    "/api/gaming/launch",
                    body={"gameId": "standalone_hades", "userApproved": False},
                    headers={"X-MyTV-Token": _PRE_SEEDED_TOKEN},
                )
                self.assertEqual(c_no_app, 400)
                self.assertEqual(d_no_app.get("error"), "explicit_user_approval_required")

                c_no_tok, d_no_tok, _ = _http_json(
                    port_neg,
                    "POST",
                    "/api/gaming/launch",
                    body={"gameId": "standalone_hades", "userApproved": True},
                )
                self.assertEqual(c_no_tok, 401)
                self.assertEqual(d_no_tok.get("error"), "unauthorized")
            finally:
                app_neg.privacy_curtain.stop()
                srv_neg.shutdown()
                srv_neg.server_close()

        self.assertTrue(
            MoonlightClientPacingSpec.validate_client_settings(
                dict(MoonlightClientPacingSpec.RECOMMENDED_SETTINGS, video_codec="H.264")
            )
        )
        self.assertTrue(
            MoonlightClientPacingSpec.validate_client_settings(
                dict(MoonlightClientPacingSpec.RECOMMENDED_SETTINGS, frame_pacing="Lowest Latency")
            )
        )

        # Stage C: Cross-layer Java production verification (Prefs + parseResolution + startSunshineSession matrix)
        out_dir, android_jar, java_bin, cp_sep = _ensure_java_e2e_compiled()
        proc = subprocess.run(
            [java_bin, "-cp", f"{out_dir}{cp_sep}{android_jar}", "io.mytv.GamingE2ERequirementsTests"],
            cwd=str(_LAUNCHER_DIR),
            capture_output=True,
            text=True,
            timeout=120,
        )
        self.assertEqual(proc.returncode, 0, f"Java matrix suite failed:\n{proc.stdout}\n{proc.stderr}")
        m_pass = re.search(r"alwaysPassed=(\d+)", proc.stdout)
        self.assertIsNotNone(m_pass, f"Missing alwaysPassed summary in Java output:\n{proc.stdout}")
        assert m_pass is not None
        self.assertGreaterEqual(int(m_pass.group(1)), 195)


if __name__ == "__main__":
    clean_argv = [a for a in sys.argv if a != "--post-fix"]
    unittest.main(argv=clean_argv)
