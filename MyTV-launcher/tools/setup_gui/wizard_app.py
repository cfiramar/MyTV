#!/usr/bin/env python3
"""
MyTV Liquid Glass Desktop Setup Studio (`wizard_app.py`).

Why:
  Gives non-technical users a friendly, zero-dependency visual setup assistant matching
  MyTV's Liquid Glass design language — generating tailored AI-agent prompts, guiding
  free API key creation with live validation, writing git-ignored `config.properties`
  and `.env` safely, and answering common TV setup & troubleshooting questions.

What:
  Encapsulates configuration persistence (`SetupConfigStore`), local environment & ADB
  diagnostics (`EnvironmentInspector`), tailored prompt generation (`AgentPromptBuilder`),
  and a loopback-only CSRF-protected HTTP server (`create_setup_http_server`).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

SECRET_CONFIG_KEYS = frozenset(
    {
        "tmdb_key",
        "omdb_key",
        "mdblist_key",
        "gemini_api_key",
        "trakt_client_id",
        "supabase_anon_key",
        "household_key",
        "sensibo_api_key",
        "tuya_access_secret",
        "github_token",
        "stremio_auth_key",
    }
)

CONFIG_TO_DOTENV_MAP: Dict[str, str] = {
    "tmdb_key": "TMDB_API_KEY",
    "omdb_key": "OMDB_API_KEY",
    "mdblist_key": "MDBLIST_API_KEY",
    "gemini_api_key": "GEMINI_API_KEY",
    "trakt_client_id": "TRAKT_CLIENT_ID",
    "trakt_username": "TRAKT_USERNAME",
    "supabase_url": "SUPABASE_URL",
    "supabase_anon_key": "SUPABASE_ANON_KEY",
    "household_key": "HOUSEHOLD_KEY",
    "admin_emails": "ADMIN_EMAILS",
    "sensibo_api_key": "SENSIBO_API_KEY",
    "wiz_light_ips": "WIZ_LIGHT_IPS",
    "tuya_access_id": "TUYA_ACCESS_ID",
    "tuya_access_secret": "TUYA_ACCESS_SECRET",
    "tuya_device_id": "TUYA_DEVICE_ID",
    "switcher_device_ip": "SWITCHER_DEVICE_IP",
    "switcher_device_id": "SWITCHER_DEVICE_ID",
    "gaming_pc_ip": "GAMING_PC_IP",
    "gaming_pc_mac": "GAMING_PC_MAC",
    "enable_gaming": "ENABLE_GAMING",
    "enable_smarthome": "ENABLE_SMARTHOME",
    "city": "CITY",
    "latitude": "LATITUDE",
    "longitude": "LONGITUDE",
    "tmdb_region": "REGION",
    "temperature": "TEMPERATURE_UNIT",
    "tv_ip": "TV_IP",
    "games_dirs": "MYTV_GAMES_DIRS",
    "non_tv_monitors": "MYTV_NON_TV_MONITORS",
}


class SetupConfigStore:
    """Manages safe UTF-8 reading and updating of git-ignored `config.properties` and `.env`."""

    def __init__(self, repo_root: Path) -> None:
        self.repo_root = repo_root.resolve()
        self.launcher_dir = self.repo_root / "MyTV-launcher"
        self.config_path = self.launcher_dir / "config.properties"
        self.config_example = self.launcher_dir / "config.example.properties"
        self.dotenv_path = self.repo_root / ".env"
        self.dotenv_example = self.repo_root / ".env.example"

    def ensure_files_exist(self) -> None:
        """Creates `config.properties` and `.env` from example templates if missing."""
        if not self.config_path.exists() and self.config_example.exists():
            shutil.copyfile(self.config_example, self.config_path)
        elif not self.config_path.exists():
            self.config_path.write_text("# MyTV local settings\n", encoding="utf-8")

        if not self.dotenv_path.exists() and self.dotenv_example.exists():
            shutil.copyfile(self.dotenv_example, self.dotenv_path)
        elif not self.dotenv_path.exists():
            self.dotenv_path.write_text("# MyTV local environment\n", encoding="utf-8")

    @staticmethod
    def _parse_kv(path: Path) -> Dict[str, str]:
        result: Dict[str, str] = {}
        if not path.is_file():
            return result
        for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            result[k.strip()] = v.strip().strip('"').strip("'")
        return result

    @staticmethod
    def _upsert_kv_file(path: Path, updates: Dict[str, str]) -> None:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines() if path.is_file() else []
        remaining = dict(updates)
        out_lines: List[str] = []
        for line in lines:
            m = re.match(r"^(\s*)([A-Za-z0-9_]+)(\s*=\s*)(.*)$", line)
            if m and m.group(2) in remaining:
                key = m.group(2)
                out_lines.append(f"{m.group(1)}{key}={remaining.pop(key)}")
            else:
                out_lines.append(line)
        for key, val in remaining.items():
            out_lines.append(f"{key}={val}")
        path.write_text("\n".join(out_lines) + "\n", encoding="utf-8", newline="\n")

    def load_masked_state(self) -> Dict[str, Any]:
        """Returns non-secret config values and boolean presence flags for secret keys (never raw secrets)."""
        cfg = self._parse_kv(self.config_path)
        env = self._parse_kv(self.dotenv_path)

        def get_val(cfg_key: str, default: str = "") -> str:
            if cfg.get(cfg_key):
                return cfg[cfg_key]
            env_key = CONFIG_TO_DOTENV_MAP.get(cfg_key, "")
            if env_key and env.get(env_key):
                return env[env_key]
            return default

        configured_keys = {k: bool(get_val(k)) for k in SECRET_CONFIG_KEYS}
        values = {
            "tv_ip": env.get("TV_IP", ""),
            "city": get_val("city", ""),
            "latitude": get_val("latitude", ""),
            "longitude": get_val("longitude", ""),
            "tmdb_region": get_val("tmdb_region", "US"),
            "temperature": get_val("temperature", "auto"),
            "enable_gaming": get_val("enable_gaming", "auto"),
            "enable_smarthome": get_val("enable_smarthome", "auto"),
            "trakt_username": get_val("trakt_username", ""),
            "stremio_email": cfg.get("stremio_email", ""),
            "supabase_url": get_val("supabase_url", ""),
            "admin_emails": get_val("admin_emails", ""),
            "wiz_light_ips": get_val("wiz_light_ips", ""),
            "sensibo_device_id": cfg.get("sensibo_device_id", ""),
            "tuya_access_id": get_val("tuya_access_id", ""),
            "tuya_device_id": get_val("tuya_device_id", ""),
            "switcher_device_ip": get_val("switcher_device_ip", ""),
            "gaming_pc_hdmi": cfg.get("gaming_pc_hdmi", "HDMI 3"),
            "games_dirs": env.get("MYTV_GAMES_DIRS", ""),
            "non_tv_monitors": env.get("MYTV_NON_TV_MONITORS", ""),
        }
        return {"values": values, "configured_keys": configured_keys}

    def save_settings(self, payload: Dict[str, Any]) -> None:
        """Writes non-empty secrets and updated settings into `config.properties` and `.env`."""
        self.ensure_files_exist()
        cfg_updates: Dict[str, str] = {}
        env_updates: Dict[str, str] = {}

        dotenv_only = {"tv_ip", "games_dirs", "non_tv_monitors"}

        for raw_key, raw_val in payload.items():
            if raw_val is None:
                continue
            val = str(raw_val).strip().replace("\r", "").replace("\n", "")
            # Never overwrite an existing secret with an empty string unless explicitly requested
            if raw_key in SECRET_CONFIG_KEYS and val == "":
                continue
            if raw_key not in dotenv_only:
                cfg_updates[raw_key] = val
            env_name = CONFIG_TO_DOTENV_MAP.get(raw_key)
            if env_name:
                env_updates[env_name] = val

        if cfg_updates:
            self._upsert_kv_file(self.config_path, cfg_updates)
        if env_updates:
            self._upsert_kv_file(self.dotenv_path, env_updates)


class AgentPromptBuilder:
    """Synthesizes a concise, copy-pasteable AI-agent setup prompt from the user's preferences."""

    PLATFORM_LABELS = {
        "android_tv": "Android TV / Google TV",
        "fire_tv": "Amazon Fire TV",
        "lg_webos": "LG webOS TV",
        "samsung_tizen": "Samsung Tizen TV",
    }

    @classmethod
    def build_prompt(
        cls,
        tv_platform: str,
        tv_ip: str,
        city: str,
        enable_smarthome: bool,
        smarthome_devices: List[str],
        enable_gaming: bool,
        games_dirs: str,
    ) -> str:
        """Builds a clear, human-readable instruction prompt for Claude Code, Antigravity, Cursor, or Codex."""
        plat = cls.PLATFORM_LABELS.get(tv_platform, "Smart TV")
        ip_part = f" at IP `{tv_ip.strip()}`" if tv_ip.strip() else " (ask me for my TV's Wi-Fi IP)"
        city_part = f" My city is `{city.strip()}`." if city.strip() else ""

        if enable_smarthome:
            devs = ", ".join(smarthome_devices) if smarthome_devices else "my smart devices"
            sh_line = f"- Smart Home Tab: ENABLE — please enable Smart Home ({devs}) and guide me if any key is missing."
        else:
            sh_line = "- Smart Home Tab: DISABLE — I do not need Smart Home controls right now, so hide the Smart Home tab (`enable_smarthome=false`)."

        if enable_gaming:
            folders = f" (standalone game folders: `{games_dirs.strip()}`)" if games_dirs.strip() else ""
            gm_line = f"- PC Gaming Tab: ENABLE — set up 4K Windows PC Game Streaming{folders}, detect my non-TV desk monitors, and walk me through Sunshine pairing."
        else:
            gm_line = "- PC Gaming Tab: DISABLE — I will not stream PC games to this TV right now, so hide the Gaming tab (`enable_gaming=false`)."

        return (
            f"Read `AGENTS.md` and `docs/agents/install-playbook.md` and help me install and configure MyTV on my {plat}{ip_part}.{city_part}\n"
            f"{sh_line}\n"
            f"{gm_line}\n"
            "Check any keys I already saved in `MyTV-launcher/config.properties` / `.env` (never print secret values in chat), "
            "guide me step-by-step for anything missing, verify my TV brand compatibility safely, build, and install MyTV."
        )


class EnvironmentInspector:
    """Performs read-only local toolchain checks, live key/city helpers, and bounded ADB probes."""

    @staticmethod
    def check_local_tools() -> Dict[str, Any]:
        """Checks whether JDK 17+, Android SDK, ADB, Node.js, and Python are installed."""
        sdk_candidates = [
            os.environ.get("ANDROID_HOME", ""),
            os.environ.get("ANDROID_SDK_ROOT", ""),
            str(Path.home() / "AppData/Local/Android/Sdk"),
            str(Path.home() / "Library/Android/sdk"),
            str(Path.home() / "Android/Sdk"),
        ]
        sdk_found = next((p for p in sdk_candidates if p and (Path(p) / "build-tools").is_dir()), "")
        return {
            "python": sys.version.split()[0],
            "java_installed": bool(shutil.which("javac") or Path(r"C:\Program Files\Java").is_dir()),
            "android_sdk": bool(sdk_found),
            "adb_installed": bool(shutil.which("adb") or (sdk_found and (Path(sdk_found) / "platform-tools").is_dir())),
            "node_installed": bool(shutil.which("node")),
        }

    @staticmethod
    def generate_household_credentials(admin_email: str = "") -> Dict[str, str]:
        """Generates a random 256-bit `household_key` and its ready-to-run Supabase SHA-256 SQL snippet."""
        key = secrets.token_hex(32)
        digest = hashlib.sha256(key.encode("utf-8")).hexdigest()
        clean_email = admin_email.strip().lower() or "you@gmail.com"
        sql = (
            "UPDATE private.household_auth_config\n"
            f"   SET household_key_sha256 = '{digest}',\n"
            f"       allowed_emails = ARRAY['{clean_email}'],\n"
            "       updated_at = now()\n"
            " WHERE id = 1;"
        )
        return {"household_key": key, "sha256": digest, "sql": sql}

    @staticmethod
    def validate_tmdb_key(raw_key: str) -> Tuple[bool, str]:
        """Validates a TMDB v3 API key or v4 Read Access Token against `api.tmdb.org`."""
        clean = raw_key.strip()
        if not clean:
            return False, "Please paste your TMDB API key first."
        url = "https://api.tmdb.org/3/configuration"
        headers = {"Accept": "application/json"}
        if len(clean) > 60 and "." in clean:
            headers["Authorization"] = f"Bearer {clean}"
        else:
            url = f"{url}?api_key={urllib.parse.quote(clean)}"
        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=6) as resp:
                if resp.status == 200:
                    return True, "TMDB API key verified successfully."
        except urllib.error.HTTPError as exc:
            if exc.code == 401:
                return False, "TMDB rejected this key (HTTP 401). Double-check the key from themoviedb.org/settings/api."
            return False, f"TMDB returned HTTP {exc.code}."
        except Exception as exc:
            return False, f"Could not reach api.tmdb.org: {exc}"
        return False, "Unexpected response from TMDB."

    @staticmethod
    def lookup_city(city_query: str) -> Dict[str, Any]:
        """Resolves city name to latitude, longitude, and ISO country code via Open-Meteo geocoding."""
        q = city_query.strip()
        if not q:
            return {"ok": False, "error": "Enter a city name first."}
        url = f"https://geocoding-api.open-meteo.com/v1/search?name={urllib.parse.quote(q)}&count=1&language=en&format=json"
        try:
            with urllib.request.urlopen(url, timeout=6) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
            results = payload.get("results") or []
            if not results:
                return {"ok": False, "error": f"No city found matching '{q}'."}
            top = results[0]
            return {
                "ok": True,
                "city": top.get("name", q),
                "latitude": str(round(float(top.get("latitude", 0.0)), 4)),
                "longitude": str(round(float(top.get("longitude", 0.0)), 4)),
                "country_code": str(top.get("country_code", "US")).upper(),
            }
        except Exception as exc:
            return {"ok": False, "error": f"City lookup failed: {exc}"}

    @staticmethod
    def detect_windows_monitors(repo_root: Path) -> Dict[str, Any]:
        """Runs `laptop_gaming_agent.py --list-monitors` on Windows to list connected displays."""
        agent_script = repo_root / "MyTV-launcher" / "tools" / "gaming" / "laptop_gaming_agent.py"
        if sys.platform != "win32" or not agent_script.is_file():
            return {"ok": False, "error": "Monitor auto-detection is only needed on Windows Gaming PCs."}
        try:
            proc = subprocess.run(
                [sys.executable, str(agent_script), "--list-monitors"],
                capture_output=True,
                text=True,
                timeout=8,
                check=False,
            )
            return {"ok": proc.returncode == 0, "output": (proc.stdout or proc.stderr).strip()}
        except Exception as exc:
            return {"ok": False, "error": str(exc)}

    @staticmethod
    def probe_tv_adb(tv_ip: str) -> Dict[str, Any]:
        """Briefly tests ADB connection to `<tv_ip>:5555`, reads brand/model, and immediately disconnects."""
        clean_ip = tv_ip.strip()
        if not re.match(r"^[0-9a-zA-Z._-]+$", clean_ip):
            return {"ok": False, "error": "Enter a valid TV IPv4 address (e.g. 192.168.1.50)."}
        adb_bin = shutil.which("adb")
        if not adb_bin:
            for cand in [
                Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe",
                Path.home() / "Library/Android/sdk/platform-tools/adb",
                Path.home() / "Android/Sdk/platform-tools/adb",
            ]:
                if cand.is_file():
                    adb_bin = str(cand)
                    break
        if not adb_bin:
            return {"ok": False, "error": "ADB is not installed yet — your AI agent can install Android platform-tools for you."}
        target = clean_ip if ":" in clean_ip else f"{clean_ip}:5555"
        try:
            subprocess.run([adb_bin, "connect", target], capture_output=True, text=True, timeout=6, check=False)
            brand = subprocess.run(
                [adb_bin, "-s", target, "shell", "getprop", "ro.product.brand"],
                capture_output=True,
                text=True,
                timeout=5,
                check=False,
            ).stdout.strip()
            model = subprocess.run(
                [adb_bin, "-s", target, "shell", "getprop", "ro.product.model"],
                capture_output=True,
                text=True,
                timeout=5,
                check=False,
            ).stdout.strip()
            if not brand and not model:
                return {
                    "ok": False,
                    "error": "Connected or waiting for approval — check your TV screen and tap 'Allow USB/Wireless Debugging', then try again.",
                }
            return {"ok": True, "brand": brand, "model": model}
        except Exception as exc:
            return {"ok": False, "error": str(exc)}
        finally:
            subprocess.run([adb_bin, "disconnect"], capture_output=True, text=True, timeout=4, check=False)


def create_setup_http_server(
    repo_root: Path,
    port: int = 8765,
    sandbox: bool = False,
) -> Tuple[ThreadingHTTPServer, str]:
    """Creates a loopback-only (`127.0.0.1`) HTTP server with per-session CSRF token protection.

    When `sandbox=True`, all config reads/writes go to an isolated temporary directory and TV ADB
    probing is simulated so the user can test every button safely without touching their TV or real files.
    """
    session_token = secrets.token_urlsafe(24)
    effective_root = repo_root
    if sandbox:
        tmp_dir = Path(tempfile.mkdtemp(prefix="mytv_setup_sandbox_"))
        (tmp_dir / "MyTV-launcher").mkdir(parents=True, exist_ok=True)
        for rel in ("MyTV-launcher/config.example.properties", ".env.example"):
            src = repo_root / rel
            if src.is_file():
                shutil.copyfile(src, tmp_dir / rel)
        effective_root = tmp_dir

    store = SetupConfigStore(effective_root)
    static_html_path = Path(__file__).resolve().parent / "static" / "index.html"

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt: str, *args: Any) -> None:
            return  # Keep terminal clean and never log request query/body data

        def _send_json(self, status: int, data: Dict[str, Any]) -> None:
            body = json.dumps(data).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _check_csrf(self) -> bool:
            supplied = self.headers.get("X-MyTV-Setup-Token", "")
            if not secrets.compare_digest(supplied, session_token):
                self._send_json(403, {"ok": False, "error": "Invalid session token"})
                return False
            return True

        def do_GET(self) -> None:
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path in ("/", "/index.html"):
                html = static_html_path.read_text(encoding="utf-8") if static_html_path.is_file() else "<h1>MyTV Setup</h1>"
                html = html.replace("__MYTV_SETUP_TOKEN__", session_token)
                body = html.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return

            if parsed.path == "/api/state":
                if not self._check_csrf():
                    return
                state = store.load_masked_state()
                tools = EnvironmentInspector.check_local_tools()
                self._send_json(200, {"ok": True, "sandbox": sandbox, "state": state, "tools": tools})
                return

            self._send_json(404, {"ok": False, "error": "Not found"})

        def do_POST(self) -> None:
            if not self._check_csrf():
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > 65536:
                self._send_json(413, {"ok": False, "error": "Payload too large"})
                return
            raw = self.rfile.read(length) if length > 0 else b"{}"
            try:
                body = json.loads(raw.decode("utf-8"))
            except json.JSONDecodeError:
                self._send_json(400, {"ok": False, "error": "Invalid JSON"})
                return

            parsed = urllib.parse.urlparse(self.path)
            if parsed.path == "/api/save":
                store.save_settings(body.get("settings") or {})
                self._send_json(200, {"ok": True, "state": store.load_masked_state()})
                return
            if parsed.path == "/api/prompt":
                prompt = AgentPromptBuilder.build_prompt(
                    tv_platform=str(body.get("tv_platform", "android_tv")),
                    tv_ip=str(body.get("tv_ip", "")),
                    city=str(body.get("city", "")),
                    enable_smarthome=bool(body.get("enable_smarthome", False)),
                    smarthome_devices=[str(x) for x in (body.get("smarthome_devices") or [])],
                    enable_gaming=bool(body.get("enable_gaming", False)),
                    games_dirs=str(body.get("games_dirs", "")),
                )
                self._send_json(200, {"ok": True, "prompt": prompt})
                return
            if parsed.path == "/api/validate-tmdb":
                ok, msg = EnvironmentInspector.validate_tmdb_key(str(body.get("tmdb_key", "")))
                self._send_json(200, {"ok": ok, "message": msg})
                return
            if parsed.path == "/api/lookup-city":
                self._send_json(200, EnvironmentInspector.lookup_city(str(body.get("city", ""))))
                return
            if parsed.path == "/api/generate-household-key":
                creds = EnvironmentInspector.generate_household_credentials(str(body.get("admin_email", "")))
                self._send_json(200, {"ok": True, **creds})
                return
            if parsed.path == "/api/list-monitors":
                self._send_json(200, EnvironmentInspector.detect_windows_monitors(repo_root))
                return
            if parsed.path == "/api/probe-tv":
                if sandbox:
                    ip = str(body.get("tv_ip", "")).strip() or "192.168.1.50"
                    self._send_json(
                        200,
                        {
                            "ok": True,
                            "brand": "Sandbox Preview",
                            "model": f"(Simulated TV at {ip} — ADB skipped in Sandbox Mode)",
                        },
                    )
                    return
                self._send_json(200, EnvironmentInspector.probe_tv_adb(str(body.get("tv_ip", ""))))
                return

            self._send_json(404, {"ok": False, "error": "Unknown endpoint"})

    try:
        httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    except OSError:
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    return httpd, session_token


def main() -> None:
    parser = argparse.ArgumentParser(description="Launch the MyTV Liquid Glass Desktop Setup Studio.")
    parser.add_argument("--port", type=int, default=8765, help="Local port on 127.0.0.1 (default: 8765)")
    parser.add_argument("--no-browser", action="store_true", help="Do not open web browser automatically")
    parser.add_argument("--sandbox", action="store_true", help="Run in isolated test mode (never touches real config files or TV)")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[3]
    server, _ = create_setup_http_server(repo_root, port=args.port, sandbox=args.sandbox)
    actual_port = server.server_address[1]
    url = f"http://127.0.0.1:{actual_port}/"
    print(f"MyTV Setup Studio running locally at {url}")
    print("Press Ctrl+C to close when you are done.")
    if not args.no_browser:
        threading.Timer(0.35, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nClosing MyTV Setup Studio.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
