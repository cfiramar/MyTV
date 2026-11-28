#!/usr/bin/env python3
"""
Pre-Install Initial Native TV State Backup & Emergency Restore Utility (`tv_state_backup.py`).

Why:
  Before installing SoarTV or modifying any TV launcher, screensaver, accessibility, or package
  state, AI setup agents MUST capture an immutable local snapshot of the TV's original native
  state so the TV can be restored cleanly if anything breaks.

What:
  - `--backup`: Connects over ADB, runs read-only queries to record the TV's original HOME
    launcher/role, enabled & disabled packages, screensaver settings, accessibility services,
    notification listeners, TV inputs, and Wi-Fi owner packages (never passwords), writes an
    immutable JSON backup + human-readable restore notes into `tv-backups/`, and immediately
    disconnects ADB.
  - `--restore <backup.json>`: Safely restores the TV to the exact recorded native state while
    enforcing Google TV `setupwraith` ordering (`launcherx` / original HOME enabled and set as
    HOME strictly BEFORE `com.google.android.tungsten.setupwraith` is re-enabled), preserving
    packages that were already disabled natively, and disconnecting ADB when done.

Usage:
  python3 SoarTV-launcher/tools/tv_state_backup.py <TV_IP> --backup
  python3 SoarTV-launcher/tools/tv_state_backup.py <TV_IP> --restore tv-backups/<file>.json --dry-run
  python3 SoarTV-launcher/tools/tv_state_backup.py <TV_IP> --restore tv-backups/<file>.json
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import shutil
import subprocess
import sys
from abc import ABC, abstractmethod
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

SCHEMA_VERSION = 1
DEFAULT_ADB_PORT = 5555
SOARTV_PKG = "io.soartv"
SOARTV_NOW_PLAYING_LISTENER = f"{SOARTV_PKG}/{SOARTV_PKG}.NowPlaying"
GOOGLE_SETUP_PKG = "com.google.android.tungsten.setupwraith"
GOOGLE_HOME_COMPONENT = "com.google.android.apps.tv.launcherx/.home.HomeActivity"
JM_HOME_PROP = "persist.sys.home.selectpkg"

_SAFE_PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_.]*$")
_SAFE_COMPONENT_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+$")
_SAFE_COMPONENT_LIST_RE = re.compile(r"^[A-Za-z0-9_./$:,]+$")
_SAFE_SCALAR_RE = re.compile(r"^[A-Za-z0-9_.:-]+$")
_WIFI_OWNER_RE = re.compile(r"cname=([A-Za-z0-9_.]+)")
_SLUG_RE = re.compile(r"[^a-z0-9]+")

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_BACKUP_DIR = REPO_ROOT / "tv-backups"


class AdbCommandError(RuntimeError):
    """Raised when an ADB connection or shell command fails."""


class AdbExecutor(ABC):
    """Abstract transport interface for executing ADB commands against a target TV."""

    @abstractmethod
    def connect(self, target: str) -> None:
        """Connects to the target `<ip>:<port>` over ADB."""

    @abstractmethod
    def disconnect(self, target: Optional[str] = None) -> None:
        """Disconnects from the target TV (or all ADB targets) to release debugging priority."""

    @abstractmethod
    def shell(self, target: str, command: str) -> str:
        """Runs a single `adb -s <target> shell <command>` and returns stdout."""

    @abstractmethod
    def uninstall(self, target: str, package_name: str) -> str:
        """Uninstalls a package from the target TV via `adb -s <target> uninstall <pkg>`."""


class SubprocessAdbExecutor(AdbExecutor):
    """Concrete ADB transport backed by the local `adb` CLI binary."""

    def __init__(self, timeout_seconds: int = 20) -> None:
        self._timeout = timeout_seconds

    def _run(self, args: List[str]) -> subprocess.CompletedProcess[str]:
        if not shutil.which("adb"):
            raise AdbCommandError("`adb` was not found in PATH. Install Android platform-tools first.")
        return subprocess.run(
            ["adb", *args],
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=self._timeout,
        )

    def connect(self, target: str) -> None:
        proc = self._run(["connect", target])
        combined = f"{proc.stdout}\n{proc.stderr}".strip()
        if proc.returncode != 0 or "connected" not in combined.lower():
            raise AdbCommandError(f"Could not connect to {target}: {combined}")

    def disconnect(self, target: Optional[str] = None) -> None:
        args = ["disconnect", target] if target else ["disconnect"]
        try:
            self._run(args)
        except Exception:
            # Best-effort cleanup; never hide the original exception.
            pass

    def shell(self, target: str, command: str) -> str:
        proc = self._run(["-s", target, "shell", command])
        if proc.returncode != 0:
            detail = (proc.stderr or proc.stdout).strip()
            raise AdbCommandError(f"ADB shell failed (`{command}`): {detail}")
        return proc.stdout

    def uninstall(self, target: str, package_name: str) -> str:
        if not _is_safe_package(package_name):
            raise ValueError(f"Refusing to uninstall invalid package name: {package_name!r}")
        proc = self._run(["-s", target, "uninstall", package_name])
        return f"{proc.stdout}\n{proc.stderr}".strip()


@dataclass
class TvNativeSnapshot:
    """Immutable snapshot of a TV's native pre-SoarTV state for emergency recovery."""

    schema_version: int
    captured_at_utc: str
    target: str
    device: Dict[str, str]
    home: Dict[str, Any]
    packages: Dict[str, Any]
    screensaver: Dict[str, str]
    services: Dict[str, str]
    diagnostics: Dict[str, Any]
    already_running_soartv_home: bool

    def to_dict(self) -> Dict[str, Any]:
        """Serializes the snapshot to a JSON-compatible dictionary."""
        return asdict(self)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "TvNativeSnapshot":
        """Deserializes and validates a snapshot dictionary loaded from JSON."""
        if not isinstance(data, dict) or data.get("schema_version") != SCHEMA_VERSION:
            raise ValueError(f"Unsupported or invalid TV backup schema: {data.get('schema_version')!r}")
        return cls(
            schema_version=int(data["schema_version"]),
            captured_at_utc=str(data.get("captured_at_utc", "")),
            target=str(data.get("target", "")),
            device=dict(data.get("device") or {}),
            home=dict(data.get("home") or {}),
            packages=dict(data.get("packages") or {}),
            screensaver=dict(data.get("screensaver") or {}),
            services=dict(data.get("services") or {}),
            diagnostics=dict(data.get("diagnostics") or {}),
            already_running_soartv_home=bool(data.get("already_running_soartv_home", False)),
        )


@dataclass
class RestoreResult:
    """Structured outcome returned by `TvStateBackupManager.restore()`."""

    target: str
    backup_path: Path
    dry_run: bool
    planned_commands: List[str]
    executed_commands: List[str]
    uninstall_performed: bool


def normalize_target(raw_target: str) -> str:
    """Normalizes `<ip>` into `<ip>:5555` while preserving explicit `<ip>:<port>` targets."""
    cleaned = raw_target.strip()
    if not cleaned:
        raise ValueError("TV IP address (or ip:port) is required.")
    return cleaned if ":" in cleaned else f"{cleaned}:{DEFAULT_ADB_PORT}"


def _clean_setting(raw: str) -> str:
    value = raw.strip()
    return "" if value.lower() == "null" else value


def _is_safe_package(pkg: str) -> bool:
    return bool(_SAFE_PACKAGE_RE.match(pkg))


def _is_safe_component(component: str) -> bool:
    return bool(_SAFE_COMPONENT_RE.match(component))


def _is_safe_component_list(value: str) -> bool:
    return bool(value and _SAFE_COMPONENT_LIST_RE.match(value))


def _is_safe_scalar(value: str) -> bool:
    return bool(value and _SAFE_SCALAR_RE.match(value))


def _parse_package_list(raw: str) -> List[str]:
    packages: Set[str] = set()
    for line in raw.splitlines():
        item = line.strip()
        if item.startswith("package:"):
            pkg = item[len("package:") :].strip()
            if _is_safe_package(pkg):
                packages.add(pkg)
    return sorted(packages)


def _parse_resolved_activity(raw: str) -> str:
    lines = [ln.strip() for ln in raw.splitlines() if ln.strip()]
    if not lines:
        return ""
    candidate = lines[-1]
    return candidate if _is_safe_component(candidate) else ""


def _parse_home_activities(raw: str) -> List[str]:
    activities: List[str] = []
    for line in raw.splitlines():
        item = line.strip()
        if _is_safe_component(item) and item not in activities:
            activities.append(item)
    return activities


def _parse_wifi_owners(raw: str) -> List[str]:
    owners = {m.group(1) for m in _WIFI_OWNER_RE.finditer(raw) if _is_safe_package(m.group(1))}
    return sorted(owners)


def _slugify(value: str) -> str:
    slug = _SLUG_RE.sub("-", value.lower()).strip("-")
    return slug or "tv"


class TvStateBackupManager:
    """
    Captures, stores, and safely restores an Android TV / Google TV's initial native state.

    Why:
      Encapsulates all read-only inspection queries, immutability guards, and Google TV
      `setupwraith`-safe restoration sequencing in one testable domain service.
    """

    def __init__(self, adb: AdbExecutor) -> None:
        self._adb = adb

    def _safe_shell(self, target: str, command: str) -> str:
        """Runs an optional diagnostic query without aborting capture if unsupported on older Android."""
        try:
            return self._adb.shell(target, command)
        except AdbCommandError:
            return ""

    def capture(self, raw_target: str) -> TvNativeSnapshot:
        """
        Connects to the TV, records its complete read-only native state, and ALWAYS disconnects ADB.

        Raises:
          AdbCommandError: If ADB cannot connect or critical package/home queries fail.
        """
        target = normalize_target(raw_target)
        self._adb.connect(target)
        try:
            device = {
                "manufacturer": self._safe_shell(target, "getprop ro.product.manufacturer").strip(),
                "brand": self._safe_shell(target, "getprop ro.product.brand").strip(),
                "model": self._safe_shell(target, "getprop ro.product.model").strip(),
                "device": self._safe_shell(target, "getprop ro.product.device").strip(),
                "android_release": self._safe_shell(target, "getprop ro.build.version.release").strip(),
                "sdk_int": self._safe_shell(target, "getprop ro.build.version.sdk").strip(),
                "fingerprint": self._safe_shell(target, "getprop ro.build.fingerprint").strip(),
            }

            role_holders = self._safe_shell(
                target, "cmd role get-role-holders --user 0 android.app.role.HOME"
            ).strip()
            resolved_home = _parse_resolved_activity(
                self._adb.shell(
                    target,
                    "cmd package resolve-activity --brief --user 0 "
                    "-a android.intent.action.MAIN -c android.intent.category.HOME",
                )
            )
            home_activities = _parse_home_activities(
                self._safe_shell(
                    target,
                    "cmd package query-activities --brief --user 0 "
                    "-a android.intent.action.MAIN -c android.intent.category.HOME",
                )
            )
            jm_home_prop = self._safe_shell(target, f"getprop {JM_HOME_PROP}").strip()

            enabled_pkgs = _parse_package_list(self._adb.shell(target, "pm list packages -e"))
            disabled_pkgs = _parse_package_list(self._adb.shell(target, "pm list packages -d"))
            soartv_listed = self._safe_shell(target, f"pm list packages {SOARTV_PKG}")
            soartv_installed = f"package:{SOARTV_PKG}" in soartv_listed.split()
            soartv_disabled_record = _clean_setting(
                self._safe_shell(target, "settings get global soartv_disabled")
            )

            screensaver = {
                "components": _clean_setting(
                    self._safe_shell(target, "settings get secure screensaver_components")
                ),
                "default_component": _clean_setting(
                    self._safe_shell(target, "settings get secure screensaver_default_component")
                ),
                "enabled": _clean_setting(
                    self._safe_shell(target, "settings get secure screensaver_enabled")
                ),
                "activate_on_sleep": _clean_setting(
                    self._safe_shell(target, "settings get secure screensaver_activate_on_sleep")
                ),
                "activate_on_dock": _clean_setting(
                    self._safe_shell(target, "settings get secure screensaver_activate_on_dock")
                ),
                "screen_off_timeout": _clean_setting(
                    self._safe_shell(target, "settings get system screen_off_timeout")
                ),
            }

            services = {
                "enabled_accessibility_services": _clean_setting(
                    self._safe_shell(target, "settings get secure enabled_accessibility_services")
                ),
                "accessibility_enabled": _clean_setting(
                    self._safe_shell(target, "settings get secure accessibility_enabled")
                ),
                "enabled_notification_listeners": _clean_setting(
                    self._safe_shell(target, "settings get secure enabled_notification_listeners")
                ),
            }

            tv_inputs_raw = self._safe_shell(target, "cmd tv_input list")
            tv_inputs = [ln.strip() for ln in tv_inputs_raw.splitlines() if ln.strip()]
            wifi_raw = self._safe_shell(
                target, "dumpsys wifi | grep -o -E 'cname=[^ ]*|^Wi-Fi is [a-z]+'"
            )
            wifi_owners = _parse_wifi_owners(wifi_raw)

            already_soartv_home = (
                resolved_home.startswith(f"{SOARTV_PKG}/")
                or SOARTV_PKG in role_holders.split(";")
            )

            captured_at = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
            return TvNativeSnapshot(
                schema_version=SCHEMA_VERSION,
                captured_at_utc=captured_at,
                target=target,
                device=device,
                home={
                    "role_holders": role_holders,
                    "resolved_home_activity": resolved_home,
                    "home_activities": home_activities,
                    "jm_home_prop": jm_home_prop,
                },
                packages={
                    "enabled": enabled_pkgs,
                    "disabled": disabled_pkgs,
                    "soartv_installed": soartv_installed,
                    "soartv_disabled_record": soartv_disabled_record,
                },
                screensaver=screensaver,
                services=services,
                diagnostics={
                    "tv_inputs": tv_inputs,
                    "wifi_owners": wifi_owners,
                },
                already_running_soartv_home=already_soartv_home,
            )
        finally:
            self._adb.disconnect(target)

    def default_backup_path(self, snapshot: TvNativeSnapshot, backup_dir: Path = DEFAULT_BACKUP_DIR) -> Path:
        """Builds a deterministic, human-readable filename inside `tv-backups/` for this TV."""
        parts = [
            snapshot.device.get("manufacturer", ""),
            snapshot.device.get("model", "") or snapshot.device.get("device", ""),
        ]
        slug = _slugify("-".join(p for p in parts if p))
        return backup_dir / f"{slug}-initial-native-state.json"

    def save_backup(
        self,
        snapshot: TvNativeSnapshot,
        output_path: Optional[Path] = None,
        backup_dir: Path = DEFAULT_BACKUP_DIR,
        force: bool = False,
    ) -> Path:
        """
        Writes the snapshot JSON and a companion human-readable restore notes file.

        Why:
          Refuses to overwrite an existing initial native state backup unless `force=True`,
          preventing later runs (after SoarTV is already installed) from destroying the pristine
          pre-SoarTV backup.
        """
        target_path = output_path or self.default_backup_path(snapshot, backup_dir=backup_dir)
        target_path.parent.mkdir(parents=True, exist_ok=True)
        if target_path.exists() and not force:
            raise FileExistsError(
                f"Initial TV backup already exists at {target_path}. "
                "Keep this pristine pre-SoarTV backup intact (pass --force only if you truly want to overwrite it)."
            )

        target_path.write_text(
            json.dumps(snapshot.to_dict(), indent=2, sort_keys=False) + "\n",
            encoding="utf-8",
        )

        notes_name = (
            target_path.stem.replace("-initial-native-state", "-restore-notes") + ".txt"
            if "-initial-native-state" in target_path.stem
            else f"{target_path.stem}-restore-notes.txt"
        )
        notes_path = target_path.with_name(notes_name)
        notes_path.write_text(self._render_restore_notes(snapshot, target_path), encoding="utf-8")
        return target_path

    def load_backup(self, backup_path: Path) -> TvNativeSnapshot:
        """Loads and validates a saved `TvNativeSnapshot` JSON file from disk."""
        raw = json.loads(backup_path.read_text(encoding="utf-8"))
        return TvNativeSnapshot.from_dict(raw)

    def _resolve_target_home_component(self, snapshot: TvNativeSnapshot) -> str:
        resolved = str(snapshot.home.get("resolved_home_activity", "")).strip()
        if _is_safe_component(resolved) and not resolved.startswith(f"{SOARTV_PKG}/"):
            if resolved.startswith(f"{GOOGLE_SETUP_PKG}/"):
                return GOOGLE_HOME_COMPONENT
            return resolved

        for candidate in snapshot.home.get("home_activities") or []:
            cand = str(candidate).strip()
            if (
                _is_safe_component(cand)
                and not cand.startswith(f"{SOARTV_PKG}/")
                and not cand.startswith(f"{GOOGLE_SETUP_PKG}/")
                and not cand.startswith("android/")
            ):
                return cand
        return ""

    def build_restore_plan(
        self,
        snapshot: TvNativeSnapshot,
        currently_disabled: Set[str],
        uninstall_soartv: bool = False,
    ) -> List[str]:
        """
        Builds an ordered list of ADB shell commands to restore the TV's recorded native state.

        Safety invariants:
          1. Never re-enables packages that were already disabled in the initial native snapshot.
          2. Re-enables all previously-enabled packages EXCEPT `setupwraith` first, sets the
             original stock HOME launcher, and ONLY THEN re-enables `setupwraith` (preventing
             Google TV `RecoveryActivity` boot loops).
          3. Rejects any identifier containing shell metacharacters.
        """
        commands: List[str] = []
        initially_enabled = {
            p for p in (snapshot.packages.get("enabled") or []) if isinstance(p, str) and _is_safe_package(p)
        }
        initially_disabled = {
            p for p in (snapshot.packages.get("disabled") or []) if isinstance(p, str) and _is_safe_package(p)
        }

        to_enable = sorted(
            pkg
            for pkg in currently_disabled
            if pkg in initially_enabled and pkg not in initially_disabled and pkg != SOARTV_PKG
        )

        enable_setupwraith_later = GOOGLE_SETUP_PKG in to_enable
        for pkg in to_enable:
            if pkg == GOOGLE_SETUP_PKG:
                continue
            commands.append(f"pm enable {pkg}")

        original_home = self._resolve_target_home_component(snapshot)
        if original_home:
            commands.append(f"cmd package set-home-activity {original_home}")

        jm_prop = str(snapshot.home.get("jm_home_prop", "")).strip()
        if jm_prop and _is_safe_package(jm_prop):
            commands.append(f"setprop {JM_HOME_PROP} '{jm_prop}'")

        if enable_setupwraith_later:
            commands.append(f"pm enable {GOOGLE_SETUP_PKG}")
            if original_home:
                commands.append(f"cmd package set-home-activity {original_home}")

        # Screensaver settings
        self._append_setting_restore(
            commands, "secure", "screensaver_components", snapshot.screensaver.get("components", ""), allow_list=True
        )
        self._append_setting_restore(
            commands, "secure", "screensaver_enabled", snapshot.screensaver.get("enabled", "")
        )
        self._append_setting_restore(
            commands, "secure", "screensaver_activate_on_sleep", snapshot.screensaver.get("activate_on_sleep", "")
        )
        self._append_setting_restore(
            commands, "secure", "screensaver_activate_on_dock", snapshot.screensaver.get("activate_on_dock", "")
        )
        self._append_setting_restore(
            commands, "system", "screen_off_timeout", snapshot.screensaver.get("screen_off_timeout", "")
        )

        # Accessibility & notification listeners
        self._append_setting_restore(
            commands,
            "secure",
            "enabled_accessibility_services",
            snapshot.services.get("enabled_accessibility_services", ""),
            allow_list=True,
        )
        self._append_setting_restore(
            commands,
            "secure",
            "accessibility_enabled",
            snapshot.services.get("accessibility_enabled", ""),
        )
        commands.append(f"cmd notification disallow_listener {SOARTV_NOW_PLAYING_LISTENER}")
        self._append_setting_restore(
            commands,
            "secure",
            "enabled_notification_listeners",
            snapshot.services.get("enabled_notification_listeners", ""),
            allow_list=True,
        )

        # Clear or restore SoarTV's disabled-package record
        self._append_setting_restore(
            commands,
            "global",
            "soartv_disabled",
            str(snapshot.packages.get("soartv_disabled_record", "")),
            allow_list=True,
        )

        if uninstall_soartv:
            commands.append(f"# uninstall {SOARTV_PKG} via `adb uninstall {SOARTV_PKG}`")

        commands.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
        return commands

    @staticmethod
    def _append_setting_restore(
        commands: List[str],
        namespace: str,
        key: str,
        raw_value: str,
        allow_list: bool = False,
    ) -> None:
        value = _clean_setting(raw_value or "")
        if not value:
            commands.append(f"settings delete {namespace} {key}")
            return
        valid = _is_safe_component_list(value) if allow_list else _is_safe_scalar(value)
        if valid:
            commands.append(f"settings put {namespace} {key} '{value}'")

    def restore(
        self,
        raw_target: str,
        backup_path: Path,
        dry_run: bool = False,
        uninstall_soartv: bool = False,
    ) -> RestoreResult:
        """
        Restores the TV from a saved initial native state JSON backup and ALWAYS disconnects ADB.
        """
        snapshot = self.load_backup(backup_path)
        target = normalize_target(raw_target)
        self._adb.connect(target)
        executed: List[str] = []
        uninstall_performed = False
        try:
            currently_disabled = set(_parse_package_list(self._adb.shell(target, "pm list packages -d")))
            plan = self.build_restore_plan(
                snapshot,
                currently_disabled=currently_disabled,
                uninstall_soartv=uninstall_soartv,
            )
            if not dry_run:
                for cmd in plan:
                    if cmd.startswith("# uninstall "):
                        self._adb.uninstall(target, SOARTV_PKG)
                        uninstall_performed = True
                        executed.append(f"adb uninstall {SOARTV_PKG}")
                    else:
                        self._adb.shell(target, cmd)
                        executed.append(cmd)
            return RestoreResult(
                target=target,
                backup_path=backup_path,
                dry_run=dry_run,
                planned_commands=plan,
                executed_commands=executed,
                uninstall_performed=uninstall_performed,
            )
        finally:
            self._adb.disconnect(target)

    def _render_restore_notes(self, snapshot: TvNativeSnapshot, backup_path: Path) -> str:
        home = self._resolve_target_home_component(snapshot) or "(unknown)"
        model = f"{snapshot.device.get('manufacturer', '')} {snapshot.device.get('model', '')}".strip()
        lines = [
            "SoarTV — Initial Native TV State Backup",
            "========================================",
            f"Captured (UTC):      {snapshot.captured_at_utc}",
            f"TV Target:           {snapshot.target}",
            f"TV Model:            {model or 'Unknown'} (Android {snapshot.device.get('android_release', '?')}, SDK {snapshot.device.get('sdk_int', '?')})",
            f"Original HOME:       {home}",
            f"Original Screensaver:{snapshot.screensaver.get('components') or '(default/unset)'}",
            f"Enabled Packages:    {len(snapshot.packages.get('enabled') or [])}",
            f"Disabled Packages:   {len(snapshot.packages.get('disabled') or [])}",
            f"Backup JSON:         {backup_path}",
            "",
            "Emergency Restore (Preview First):",
            f"  python3 SoarTV-launcher/tools/tv_state_backup.py {snapshot.target} --restore \"{backup_path}\" --dry-run",
            "",
            "Emergency Restore (Execute):",
            f"  python3 SoarTV-launcher/tools/tv_state_backup.py {snapshot.target} --restore \"{backup_path}\"",
            "",
            "Emergency Restore + Uninstall SoarTV:",
            f"  python3 SoarTV-launcher/tools/tv_state_backup.py {snapshot.target} --restore \"{backup_path}\" --uninstall-soartv",
        ]
        return "\n".join(lines) + "\n"


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Save an immutable local backup of a TV's initial native state and restore it safely if needed."
    )
    parser.add_argument("target", help="TV IP address or `<ip>:<port>` (defaults to port 5555)")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--backup",
        action="store_true",
        help="Capture and save the TV's initial native state into tv-backups/ (never overwrites unless --force).",
    )
    mode.add_argument(
        "--restore",
        metavar="BACKUP_JSON",
        help="Restore the TV from a saved initial native state JSON backup (enforces setupwraith ordering).",
    )
    parser.add_argument(
        "--out",
        metavar="PATH",
        help="Optional explicit output path for --backup (defaults to tv-backups/<tv-model>-initial-native-state.json).",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Allow overwriting an existing initial backup file (use with caution).",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="With --restore, print the exact safe restore commands without mutating the TV.",
    )
    parser.add_argument(
        "--uninstall-soartv",
        action="store_true",
        help="With --restore, also uninstall io.soartv AFTER the native stock launcher is restored.",
    )
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    manager = TvStateBackupManager(SubprocessAdbExecutor())

    try:
        if args.backup:
            snapshot = manager.capture(args.target)
            if snapshot.already_running_soartv_home:
                print(
                    "WARNING: This TV is already using SoarTV (io.soartv) as its HOME launcher. "
                    "Switch back to Original TV Home first if you need a clean pre-SoarTV native snapshot.",
                    file=sys.stderr,
                )
            out_path = Path(args.out) if args.out else None
            saved = manager.save_backup(snapshot, output_path=out_path, force=args.force)
            print(f"Saved initial native TV state backup: {saved}")
            print(
                f"  Device: {snapshot.device.get('manufacturer', '?')} {snapshot.device.get('model', '?')} "
                f"(Android {snapshot.device.get('android_release', '?')})"
            )
            print(f"  Original HOME: {snapshot.home.get('resolved_home_activity') or '(none resolved)'}")
            print(
                f"  Packages recorded: {len(snapshot.packages.get('enabled') or [])} enabled, "
                f"{len(snapshot.packages.get('disabled') or [])} already disabled"
            )
            print("ADB disconnected cleanly.")
            return 0

        backup_path = Path(args.restore)
        if not backup_path.is_file():
            print(f"Backup file not found: {backup_path}", file=sys.stderr)
            return 1
        result = manager.restore(
            args.target,
            backup_path,
            dry_run=args.dry_run,
            uninstall_soartv=args.uninstall_soartv,
        )
        if result.dry_run:
            print(f"[DRY RUN] Restore plan for {result.target} from {result.backup_path}:")
            for cmd in result.planned_commands:
                print(f"  {cmd}")
        else:
            print(f"Restored native TV state on {result.target} from {result.backup_path} ({len(result.executed_commands)} step(s)).")
        print("ADB disconnected cleanly.")
        return 0
    except FileExistsError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except (AdbCommandError, ValueError, OSError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
