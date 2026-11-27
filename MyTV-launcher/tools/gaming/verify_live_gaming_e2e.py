"""
Live Host & TV Gaming E2E Verification Harness (MyTV).

Why: Provides an on-demand diagnostic & verification CLI covering both host-side
services (Sunshine :47989/:47990, Laptop Gaming Agent :8485 UDP/TCP, Windows Firewall
Public+Private profiles, sunshine.conf & sunshine.log) and TV-side ADB telemetry
(MyTV logcat, Wi-Fi/load/meminfo/GC, multi-setting GameStream telemetry).

Safety:
- Named `verify_live_gaming_e2e.py` (never `test_*.py`) so `unittest discover` never
  triggers live ADB connections.
- `--check-host` is 100% Zero-ADB.
- `--check-tv` / `--all` unconditionally releases ADB inside `try ... finally:` via
  `adb disconnect <ip>:<port>` and `adb disconnect` and verifies empty `adb devices`.
- Never prints or emits secret tokens from `.env` or `config.properties`.
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
from typing import Any, Dict, List, Optional, Sequence, Tuple
import xml.etree.ElementTree as ET


DEFAULT_ADB_PORT = 5555
DEFAULT_AGENT_PORT = 8485
DEFAULT_SUNSHINE_HTTP_PORT = 47989
SECRET_KEY_NAMES = {
    "GAMING_PC_TOKEN",
    "gaming_pc_token",
    "HOUSEHOLD_KEY",
    "household_key",
    "TMDB_API_KEY",
    "TMDB_READ_ACCESS_TOKEN",
    "GEMINI_API_KEY",
    "gemini_api_key",
    "GITHUB_TOKEN",
    "github_token",
    "SUPABASE_SERVICE_ROLE_KEY",
    "SUPABASE_SECRET_KEY",
    "SENSIBO_API_KEY",
    "sensibo_api_key",
    "TUYA_ACCESS_SECRET",
    "tuya_access_secret",
    "STREMIO_AUTH_KEY",
    "stremio_auth_key",
    "STREMIO_PASSWORD",
    "stremio_password",
    "REAL_DEBRID_API_TOKEN",
    "TORBOX_API_KEY",
}


def resolve_adb_executable(env: Optional[Dict[str, str]] = None) -> Optional[Path]:
    """Finds adb executable via PATH, LOCALAPPDATA, ANDROID_HOME, or ANDROID_SDK_ROOT."""
    env_map = env if env is not None else os.environ
    which_adb = shutil.which("adb")
    if which_adb:
        p = Path(which_adb)
        if p.is_file():
            return p

    candidates: List[Path] = []
    local_app = env_map.get("LOCALAPPDATA", "")
    if local_app:
        candidates.append(Path(local_app) / "Android" / "Sdk" / "platform-tools" / "adb.exe")
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = env_map.get(var, "")
        if root:
            candidates.append(Path(root) / "platform-tools" / ("adb.exe" if sys.platform == "win32" else "adb"))
    for c in candidates:
        if c.is_file():
            return c
    return None


def resolve_repo_root(start_path: Optional[Path] = None) -> Path:
    """Resolves git repository/worktree root starting from this script or start_path."""
    anchor = (start_path or Path(__file__)).resolve()
    base = anchor if anchor.is_dir() else anchor.parent
    for parent in [base] + list(base.parents):
        if (parent / ".git").exists() or (parent / "MyTV-launcher").is_dir():
            return parent
    return base


def resolve_env_file(
    repo_root: Optional[Path] = None,
    fallback_paths: Optional[Sequence[Path]] = None,
) -> Optional[Path]:
    """
    Resolves `.env` across git worktrees without modifying main repo files:
    1. `<repo_root>/.env`
    2. Parent of `git rev-parse --git-common-dir` (`<common_dir>/../.env`)
    3. Optional explicit fallback paths.
    """
    root = repo_root or resolve_repo_root()
    local_env = root / ".env"
    if local_env.is_file():
        return local_env

    try:
        proc = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "--git-common-dir"],
            capture_output=True,
            text=True,
            timeout=5,
        )
        if proc.returncode == 0 and proc.stdout.strip():
            common_dir = Path(proc.stdout.strip())
            if not common_dir.is_absolute():
                common_dir = (root / common_dir).resolve()
            common_env = common_dir.parent / ".env"
            if common_env.is_file():
                return common_env
    except (OSError, subprocess.SubprocessError):
        pass

    fallbacks = list(fallback_paths) if fallback_paths is not None else []
    for fb in fallbacks:
        if fb.is_file():
            return fb
    return None


def parse_env_file(path: Optional[Path]) -> Dict[str, str]:
    """Parses key=value pairs from `.env` or `config.properties` safely (UTF-8, ignores comments)."""
    out: Dict[str, str] = {}
    if path is None or not path.is_file():
        return out
    try:
        raw = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return out
    for line in raw.splitlines():
        clean = line.strip()
        if not clean or clean.startswith("#") or clean.startswith("//") or "=" not in clean:
            continue
        k, _, v = clean.partition("=")
        k_clean = k.strip()
        v_clean = v.strip().strip('"').strip("'")
        if k_clean:
            out[k_clean] = v_clean
    return out


def redact_secrets(data: Any, secret_values: Sequence[str] = ()) -> Any:
    """Recursively redacts known secret keys or raw token values from diagnostic output."""
    non_empty_secrets = [s for s in secret_values if s and len(s) >= 6]
    if isinstance(data, dict):
        cleaned: Dict[str, Any] = {}
        for k, v in data.items():
            k_lower = str(k).lower()
            if (
                str(k) in SECRET_KEY_NAMES
                or "token" in k_lower
                or "secret" in k_lower
                or "password" in k_lower
                or "apikey" in k_lower
                or "api_key" in k_lower
            ):
                cleaned[k] = "***REDACTED***" if v else ""
            else:
                cleaned[k] = redact_secrets(v, non_empty_secrets)
        return cleaned
    if isinstance(data, list):
        return [redact_secrets(item, non_empty_secrets) for item in data]
    if isinstance(data, str):
        out = data
        for sec in non_empty_secrets:
            if sec in out:
                out = out.replace(sec, "***REDACTED***")
        return out
    return data


def probe_sunshine_serverinfo(
    host: str = "127.0.0.1",
    port: int = DEFAULT_SUNSHINE_HTTP_PORT,
    timeout: float = 2.0,
) -> Dict[str, Any]:
    """Queries Sunshine HTTP :47989/serverinfo XML without touching ADB."""
    result: Dict[str, Any] = {
        "reachable": False,
        "host": host,
        "port": port,
        "hostname": "",
        "uniqueid": "",
        "pairStatus": "",
        "serverCodecModeSupport": "",
        "currentgame": "",
        "error": "",
    }
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        conn.request("GET", "/serverinfo?uniqueid=0123456789abcdef")
        resp = conn.getresponse()
        body = resp.read().decode("utf-8", errors="ignore")
        result["statusCode"] = resp.status
        if 200 <= resp.status < 300 and "<root" in body:
            result["reachable"] = True
            root = ET.fromstring(body)
            result["hostname"] = (root.findtext("hostname") or "").strip()
            result["uniqueid"] = (root.findtext("uniqueid") or "").strip()
            result["pairStatus"] = (root.findtext("PairStatus") or "").strip()
            result["serverCodecModeSupport"] = (root.findtext("ServerCodecModeSupport") or "").strip()
            result["currentgame"] = (root.findtext("currentgame") or "").strip()
        else:
            result["error"] = f"HTTP {resp.status}"
    except Exception as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        conn.close()
    return result


def probe_agent_udp_discovery(
    host: str = "127.0.0.1",
    port: int = DEFAULT_AGENT_PORT,
    timeout: float = 1.0,
) -> Dict[str, Any]:
    """Sends MYTV_GAMING_DISCOVER_V1 over UDP and validates MYTV_GAMING_HERE_V1 reply."""
    out: Dict[str, Any] = {"reachable": False, "host": host, "port": port, "reply": {}, "error": ""}
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(timeout)
    try:
        sock.sendto(b'{"op":"MYTV_GAMING_DISCOVER_V1"}', (host, port))
        raw, addr = sock.recvfrom(4096)
        payload = json.loads(raw.decode("utf-8", errors="ignore"))
        out["replyFrom"] = addr[0]
        out["reply"] = payload
        out["reachable"] = (
            isinstance(payload, dict)
            and payload.get("op") == "MYTV_GAMING_HERE_V1"
            and payload.get("agentService") == "mytv-laptop-gaming-agent"
        )
    except Exception as exc:
        out["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        sock.close()
    return out


def probe_agent_http_status(
    host: str = "127.0.0.1",
    port: int = DEFAULT_AGENT_PORT,
    token: str = "",
    timeout: float = 2.0,
) -> Dict[str, Any]:
    """Queries Laptop Gaming Agent GET /api/gaming/status (unauth + optional auth without leaking token)."""
    out: Dict[str, Any] = {"reachable": False, "host": host, "port": port, "unauthenticated": {}, "authenticated": {}, "error": ""}
    for label, hdrs in (("unauthenticated", {}), ("authenticated", {"X-MyTV-Token": token} if token else None)):
        if hdrs is None:
            continue
        conn = http.client.HTTPConnection(host, port, timeout=timeout)
        try:
            conn.request("GET", "/api/gaming/status", headers=hdrs)
            resp = conn.getresponse()
            data = json.loads(resp.read().decode("utf-8", errors="ignore") or "{}")
            out["reachable"] = out["reachable"] or (200 <= resp.status < 300)
            out[label] = {
                "statusCode": resp.status,
                "agentService": data.get("agentService", ""),
                "authenticated": bool(data.get("authenticated", False)),
                "paired": bool(data.get("paired", False)),
                "sunshineRunning": bool(data.get("sunshineRunning", False)),
                "sunshineHostUuid": data.get("sunshineHostUuid", ""),
                "sunshineHostName": data.get("sunshineHostName", ""),
                "hdmiConnected": bool(data.get("hdmiConnected", False)),
                "connectionMode": data.get("connectionMode", ""),
                "gameCount": len(data.get("games", []) or []),
            }
        except Exception as exc:
            out[label] = {"error": f"{type(exc).__name__}: {exc}"}
            if not out["error"]:
                out["error"] = out[label]["error"]
        finally:
            conn.close()
    return out


def parse_firewall_python_profiles(ps_json_or_text: str) -> Dict[str, Any]:
    """
    Parses PowerShell Get-NetFirewallRule JSON output (or netsh text) for Python inbound Allow rules
    and determines whether BOTH Private and Public profiles are covered.
    """
    rules: List[Dict[str, Any]] = []
    text = (ps_json_or_text or "").strip()
    if text.startswith("[") or text.startswith("{"):
        try:
            parsed = json.loads(text)
            if isinstance(parsed, dict):
                parsed = [parsed]
            if isinstance(parsed, list):
                for item in parsed:
                    if isinstance(item, dict):
                        rules.append(item)
        except json.JSONDecodeError:
            pass

    allowed_private = False
    allowed_public = False
    blocked_private = False
    blocked_public = False
    matched_rules: List[Dict[str, Any]] = []
    block_rules: List[Dict[str, Any]] = []
    for r in rules:
        enabled = str(r.get("Enabled", "")).lower() in ("true", "1", "yes")
        action = str(r.get("Action", "")).lower()
        direction = str(r.get("Direction", "")).lower()
        if not enabled or direction not in ("inbound", "1"):
            continue
        raw_profile = str(r.get("Profile", ""))
        tokens = {t.strip().lower() for t in re.split(r"[,;\s]+", raw_profile) if t.strip()}
        rule_info = {
            "displayName": r.get("DisplayName", ""),
            "profile": raw_profile,
            "program": r.get("Program", ""),
        }
        if action in ("allow", "2"):
            if "any" in tokens or "0" in tokens:
                allowed_private = True
                allowed_public = True
            if "private" in tokens or "2" in tokens:
                allowed_private = True
            if "public" in tokens or "4" in tokens:
                allowed_public = True
            matched_rules.append(rule_info)
        elif action in ("block", "4"):
            if "any" in tokens or "0" in tokens:
                blocked_private = True
                blocked_public = True
            if "private" in tokens or "2" in tokens:
                blocked_private = True
            if "public" in tokens or "4" in tokens:
                blocked_public = True
            block_rules.append(rule_info)

    covers_private = allowed_private and not blocked_private
    covers_public = allowed_public and not blocked_public

    return {
        "rulesChecked": len(rules),
        "inboundAllowRules": matched_rules,
        "inboundBlockRules": block_rules,
        "blockedPrivate": blocked_private,
        "blockedPublic": blocked_public,
        "coversPrivate": covers_private,
        "coversPublic": covers_public,
        "coversBothPrivateAndPublic": covers_private and covers_public,
    }


def inspect_windows_firewall_python() -> Dict[str, Any]:
    """Queries Windows Firewall rules for python.exe/pythonw.exe without modifying firewall state."""
    if sys.platform != "win32":
        return {"skipped": True, "reason": "non-windows", "coversBothPrivateAndPublic": True}
    ps_cmd = (
        "Get-NetFirewallApplicationFilter | Where-Object { $_.Program -like '*python*' } | "
        "ForEach-Object { $r = $_ | Get-NetFirewallRule; "
        "[PSCustomObject]@{ DisplayName=$r.DisplayName; Enabled=$r.Enabled.ToString(); "
        "Direction=$r.Direction.ToString(); Action=$r.Action.ToString(); "
        "Profile=$r.Profile.ToString(); Program=$_.Program } } | ConvertTo-Json -Compress"
    )
    try:
        proc = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps_cmd],
            capture_output=True,
            text=True,
            timeout=45,
        )
        if proc.returncode != 0:
            return {
                "error": proc.stderr.strip() or f"powershell exit {proc.returncode}",
                "coversPrivate": False,
                "coversPublic": False,
                "coversBothPrivateAndPublic": False,
            }
        return parse_firewall_python_profiles(proc.stdout)
    except (OSError, subprocess.SubprocessError) as exc:
        return {
            "error": f"{type(exc).__name__}: {exc}",
            "coversPrivate": False,
            "coversPublic": False,
            "coversBothPrivateAndPublic": False,
        }


def parse_sunshine_log_telemetry(log_text: str) -> Dict[str, Any]:
    """Parses sunshine.log for encoder probes, refresh rates, WSASendMsg errors, and client connect counts."""
    lines = (log_text or "").splitlines()
    tried_encoders: List[str] = []
    found_encoders: List[str] = []
    fatal_errors: List[str] = []
    wsa_send_errors: List[str] = []
    refresh_rates: List[str] = []
    requested_fps: List[str] = []
    client_connected = 0
    client_disconnected = 0

    for line in lines:
        if "CLIENT CONNECTED" in line:
            client_connected += 1
        if "CLIENT DISCONNECTED" in line:
            client_disconnected += 1
        m_try = re.search(r"Trying encoder\s*\[([^\]]+)\]", line)
        if m_try:
            tried_encoders.append(m_try.group(1).strip())
        m_found = re.search(r"Found\s+([A-Za-z0-9._-]+)\s+encoder:\s*(.+)$", line)
        if m_found:
            found_encoders.append(f"{m_found.group(1).strip()}: {m_found.group(2).strip()}")
        m_rr = re.search(r"Display refresh rate\s*\[([^\]]+)\]", line)
        if m_rr:
            refresh_rates.append(m_rr.group(1).strip())
        m_fps = re.search(r"Requested frame rate\s*\[([^\]]+)\]", line)
        if m_fps:
            requested_fps.append(m_fps.group(1).strip())
        if "WSASendMsg" in line:
            wsa_send_errors.append(line.strip())
        if "Fatal:" in line or "CRITICAL:" in line:
            fatal_errors.append(line.strip())

    return {
        "clientConnectedCount": client_connected,
        "clientDisconnectedCount": client_disconnected,
        "triedEncoders": tried_encoders[-10:],
        "foundEncoders": found_encoders[-10:],
        "displayRefreshRates": refresh_rates[-10:],
        "requestedFrameRates": requested_fps[-10:],
        "wsaSendMsgErrorCount": len(wsa_send_errors),
        "fatalErrorCount": len(fatal_errors),
        "fatalErrors": fatal_errors[-10:],
    }


def parse_sunshine_conf_file(path: Optional[Path] = None) -> Dict[str, str]:
    """Reads sunshine.conf key-value entries from standard host location."""
    candidates = [path] if path is not None else [
        Path(r"C:\Program Files\Sunshine\config\sunshine.conf"),
        Path(os.environ.get("ProgramData", r"C:\ProgramData")) / "Sunshine" / "config" / "sunshine.conf",
    ]
    for p in candidates:
        if p is not None and p.is_file():
            return parse_env_file(p)
    return {}


def parse_logcat_telemetry(logcat_text: str) -> Dict[str, Any]:
    """Extracts GamingZone and GameStreamActivity telemetry/error events from `MyTV` logcat."""
    discovery_events: List[str] = []
    launch_events: List[str] = []
    terminate_events: List[str] = []
    stream_telemetry: List[str] = []
    stream_errors: List[str] = []
    gc_pauses: List[str] = []

    for raw_line in (logcat_text or "").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if "GamingZone:" in line:
            if "Discovered Gaming PC" in line or "Adopted verified PC" in line or "probe" in line.lower():
                discovery_events.append(line)
            if "launch" in line.lower():
                launch_events.append(line)
            if "terminat" in line.lower():
                terminate_events.append(line)
        if (
            "GameStreamActivity" in line
            or "GameStream telemetry:" in line
            or "GameStream decoder configured:" in line
        ):
            if any(tok in line.lower() for tok in ("error", "failed", "drsetup", "exception")):
                stream_errors.append(line)
            if any(tok in line.lower() for tok in ("fps", "drop", "decode", "rtt", "bitrate", "codec", "telemetry")):
                stream_telemetry.append(line)
        if "HeapTaskDaemon" in line or "GC " in line or "concurrent copying" in line:
            gc_pauses.append(line)

    return {
        "discoveryEvents": discovery_events[-20:],
        "launchEvents": launch_events[-20:],
        "terminateEvents": terminate_events[-20:],
        "streamTelemetry": stream_telemetry[-30:],
        "streamErrors": stream_errors[-20:],
        "gcPauses": gc_pauses[-20:],
    }


def parse_adb_devices_output(devices_stdout: str) -> List[str]:
    """Returns active device serials/endpoints listed by `adb devices`."""
    devices: List[str] = []
    for line in (devices_stdout or "").splitlines():
        clean = line.strip()
        if not clean or clean.startswith("*") or clean.lower().startswith("list of devices"):
            continue
        parts = clean.split()
        if len(parts) >= 2:
            devices.append(parts[0])
    return devices


def run_host_checks(env_map: Dict[str, str], config_map: Dict[str, str]) -> Dict[str, Any]:
    """Executes all host-side checks without invoking ADB."""
    pc_ip = env_map.get("GAMING_PC_IP") or config_map.get("gaming_pc_ip") or "127.0.0.1"
    token = (
        env_map.get("GAMING_PC_TOKEN")
        or env_map.get("gaming_pc_token")
        or config_map.get("gaming_pc_token")
        or ""
    )

    sunshine_probe = probe_sunshine_serverinfo("127.0.0.1", DEFAULT_SUNSHINE_HTTP_PORT)
    udp_probe = probe_agent_udp_discovery("127.0.0.1", DEFAULT_AGENT_PORT)
    http_probe = probe_agent_http_status("127.0.0.1", DEFAULT_AGENT_PORT, token=token)
    firewall = inspect_windows_firewall_python()

    sunshine_log_path: Optional[Path] = None
    for cand in (
        Path(r"C:\Program Files\Sunshine\config\sunshine.log"),
        Path(os.environ.get("ProgramData", r"C:\ProgramData")) / "Sunshine" / "config" / "sunshine.log",
    ):
        if cand.is_file():
            sunshine_log_path = cand
            break
    log_text = sunshine_log_path.read_text(encoding="utf-8", errors="ignore") if sunshine_log_path else ""
    sunshine_log = parse_sunshine_log_telemetry(log_text)
    sunshine_conf = parse_sunshine_conf_file()

    return redact_secrets(
        {
            "hostProbeTarget": pc_ip,
            "sunshineServerInfo": sunshine_probe,
            "agentUdpDiscovery": udp_probe,
            "agentHttpStatus": http_probe,
            "windowsFirewallPython": firewall,
            "sunshineConf": {
                "encoder": sunshine_conf.get("encoder", ""),
                "hevc_mode": sunshine_conf.get("hevc_mode", ""),
                "qsv_coder": sunshine_conf.get("qsv_coder", ""),
                "minimum_fps_target": sunshine_conf.get("minimum_fps_target", ""),
                "dd_configuration_option": sunshine_conf.get("dd_configuration_option", ""),
                "dd_refresh_rate_option": sunshine_conf.get("dd_refresh_rate_option", ""),
                "fec_percentage": sunshine_conf.get("fec_percentage", ""),
            },
            "sunshineLog": sunshine_log,
        },
        secret_values=[token],
    )


def _run_adb(adb_exe: Path, args: Sequence[str], timeout: int = 10) -> Tuple[int, str, str]:
    proc = subprocess.run(
        [str(adb_exe)] + list(args),
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return proc.returncode, proc.stdout or "", proc.stderr or ""


def run_tv_checks(env_map: Dict[str, str]) -> Dict[str, Any]:
    """
    Connects briefly to TV via ADB inside `try ... finally:` and unconditionally releases ADB
    with `adb disconnect <ip>:<port>` and `adb disconnect`, verifying empty `adb devices`.
    """
    adb_exe = resolve_adb_executable()
    if adb_exe is None:
        return {"ok": False, "error": "adb_executable_not_found"}

    tv_ip = env_map.get("TV_IP", "").strip()
    tv_port_raw = env_map.get("TV_ADB_PORT", str(DEFAULT_ADB_PORT)).strip()
    tv_port = int(tv_port_raw) if tv_port_raw.isdigit() else DEFAULT_ADB_PORT
    if not tv_ip:
        return {"ok": False, "error": "TV_IP_not_configured_in_env"}

    target = f"{tv_ip}:{tv_port}"
    report: Dict[str, Any] = {"ok": False, "target": target, "adbReleased": False}

    try:
        rc_conn, out_conn, err_conn = _run_adb(adb_exe, ["connect", target], timeout=12)
        report["connectOutput"] = (out_conn + "\n" + err_conn).strip()
        conn_lower = report["connectOutput"].lower()
        if rc_conn != 0 or any(
            marker in conn_lower
            for marker in ("cannot connect", "failed to connect", "unable to connect", "cannot resolve", "error:")
        ):
            report["error"] = f"adb connect failed ({rc_conn}): {report['connectOutput']}"
            return report

        rc_state, state_out, state_err = _run_adb(adb_exe, ["-s", target, "get-state"], timeout=8)
        if rc_state != 0 or state_out.strip().lower() != "device":
            report["error"] = f"adb get-state failed ({rc_state}): {(state_out + ' ' + state_err).strip()}"
            return report

        _, logcat_out, _ = _run_adb(adb_exe, ["-s", target, "logcat", "-d", "-s", "MyTV"], timeout=12)
        rc_load, load_out, _ = _run_adb(adb_exe, ["-s", target, "shell", "cat /proc/loadavg"], timeout=8)
        rc_mem, mem_out, _ = _run_adb(adb_exe, ["-s", target, "shell", "dumpsys meminfo io.mytv"], timeout=10)
        rc_wifi, wifi_out, _ = _run_adb(adb_exe, ["-s", target, "shell", "ip route"], timeout=8)
        rc_ps, ps_out, _ = _run_adb(adb_exe, ["-s", target, "shell", "ps -A -o NAME,PID,RSS"], timeout=8)
        if rc_load != 0 and rc_mem != 0 and rc_ps != 0:
            report["error"] = "adb shell probes failed"
            return report

        mytv_procs = [ln.strip() for ln in ps_out.splitlines() if "io.mytv" in ln]
        report.update({
            "ok": True,
            "logcatTelemetry": parse_logcat_telemetry(logcat_out),
            "tvLoadAvg": load_out.strip(),
            "tvIpRoute": wifi_out.strip(),
            "mytvProcesses": mytv_procs,
            "meminfoSummary": [
                ln.strip() for ln in mem_out.splitlines()
                if any(k in ln for k in ("TOTAL", "Native Heap", "Dalvik Heap", "Swap", "MEMINFO in pid"))
            ][:20],
        })
        return report
    finally:
        # R4 Permanent Rule: ALWAYS release ADB priority immediately when done.
        try:
            _run_adb(adb_exe, ["disconnect", target], timeout=6)
        except Exception:
            pass
        try:
            _run_adb(adb_exe, ["disconnect"], timeout=6)
        except Exception:
            pass
        try:
            _, dev_out, _ = _run_adb(adb_exe, ["devices"], timeout=6)
            remaining = parse_adb_devices_output(dev_out)
            report["remainingAdbDevices"] = remaining
            report["adbReleased"] = len(remaining) == 0
        except Exception:
            report["adbReleased"] = False


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="MyTV Live Gaming E2E Verification Harness")
    parser.add_argument("--check-host", action="store_true", help="Run host-only Sunshine/Agent/Firewall checks (Zero ADB)")
    parser.add_argument("--check-tv", action="store_true", help="Run live TV ADB telemetry checks (always runs adb disconnect)")
    parser.add_argument("--all", action="store_true", help="Run both host and live TV checks")
    parser.add_argument("--json", action="store_true", help="Output structured JSON")
    args = parser.parse_args(list(argv) if argv is not None else None)

    if not (args.check_host or args.check_tv or args.all):
        args.check_host = True

    repo_root = resolve_repo_root()
    env_path = resolve_env_file(repo_root=repo_root)
    env_map = parse_env_file(env_path)
    cfg_path = repo_root / "MyTV-launcher" / "config.properties"
    config_map = parse_env_file(cfg_path if cfg_path.is_file() else None)

    result: Dict[str, Any] = {
        "repoRoot": str(repo_root),
        "envFileFound": env_path is not None,
    }
    if args.check_host or args.all:
        result["host"] = run_host_checks(env_map, config_map)
    if args.check_tv or args.all:
        result["tv"] = run_tv_checks(env_map)

    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print("=== MyTV Live Gaming E2E Verification ===")
        print(f"Repo root: {result['repoRoot']} | .env located: {result['envFileFound']}")
        if "host" in result:
            h = result["host"]
            print(f"[Host] Sunshine :47989 reachable={h['sunshineServerInfo']['reachable']} "
                  f"hostname={h['sunshineServerInfo']['hostname']} pairStatus={h['sunshineServerInfo']['pairStatus']}")
            print(f"[Host] Agent UDP :8485 reachable={h['agentUdpDiscovery']['reachable']} | "
                  f"HTTP reachable={h['agentHttpStatus']['reachable']}")
            fw = h["windowsFirewallPython"]
            print(f"[Host] Firewall Python Private={fw.get('coversPrivate')} Public={fw.get('coversPublic')} "
                  f"Both={fw.get('coversBothPrivateAndPublic')}")
            slog = h["sunshineLog"]
            print(f"[Host] sunshine.log: connected={slog['clientConnectedCount']} disconnected={slog['clientDisconnectedCount']} "
                  f"wsaErrors={slog['wsaSendMsgErrorCount']} fatal={slog['fatalErrorCount']}")
        if "tv" in result:
            t = result["tv"]
            print(f"[TV] ok={t.get('ok')} target={t.get('target')} adbReleased={t.get('adbReleased')} "
                  f"remaining={t.get('remainingAdbDevices')}")
    if "tv" in result:
        tv_rep = result["tv"]
        if not tv_rep.get("ok") or not tv_rep.get("adbReleased"):
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
