"""
Unit tests for `tv_state_backup.py` (Pre-Install Initial Native TV State Backup & Emergency Restore).

Covers:
  1. Full read-only native state capture across device identity, HOME role/activities,
     enabled/disabled packages, screensaver, accessibility, notification listeners, and diagnostics.
  2. Immutability guard: never silently overwrites an existing initial native state backup
     unless `force=True` is explicitly provided.
  3. Detection of already-active SoarTV HOME state so agents are warned before saving.
  4. Strict Google TV `setupwraith` restore ordering (`launcherx` / original home enabled and
     set as HOME strictly before `com.google.android.tungsten.setupwraith` is re-enabled).
  5. Preservation of natively disabled packages (never enables packages that were already off).
  6. Guaranteed ADB disconnect (`adb disconnect`) even when a shell command fails.
  7. Safe identifier validation (blocks shell metacharacters in untrusted output).
"""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

from tv_state_backup import (
    GOOGLE_SETUP_PKG,
    AdbCommandError,
    AdbExecutor,
    TvNativeSnapshot,
    TvStateBackupManager,
)


class FakeAdbExecutor(AdbExecutor):
    """In-memory fake ADB transport recording every command for deterministic unit tests."""

    def __init__(
        self,
        responses: Dict[str, str] | None = None,
        fail_on_substring: str | None = None,
    ) -> None:
        self.responses = dict(responses or {})
        self.fail_on_substring = fail_on_substring
        self.connected_targets: List[str] = []
        self.disconnected_targets: List[str] = []
        self.shell_calls: List[str] = []

    def connect(self, target: str) -> None:
        self.connected_targets.append(target)

    def disconnect(self, target: str | None = None) -> None:
        self.disconnected_targets.append(target or "<all>")

    def shell(self, target: str, command: str) -> str:
        self.shell_calls.append(command)
        if self.fail_on_substring and self.fail_on_substring in command:
            raise AdbCommandError(f"Simulated ADB failure on: {command}")
        if command in self.responses:
            return self.responses[command]
        return ""

    def uninstall(self, target: str, package_name: str) -> str:
        self.shell_calls.append(f"__uninstall__ {package_name}")
        return "Success"


def _sample_native_responses() -> Dict[str, str]:
    """Returns realistic read-only ADB shell outputs for a stock Google TV."""
    return {
        "getprop ro.product.manufacturer": "Xiaomi",
        "getprop ro.product.brand": "Xiaomi",
        "getprop ro.product.model": "TV Box S 2nd Gen",
        "getprop ro.product.device": "sample_gtv",
        "getprop ro.build.version.release": "14",
        "getprop ro.build.version.sdk": "34",
        "getprop ro.build.fingerprint": "Xiaomi/sample_gtv/sample_gtv:14/TEST/1:user/release-keys",
        "cmd role get-role-holders --user 0 android.app.role.HOME": "com.google.android.apps.tv.launcherx",
        "cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME": (
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n"
            "com.google.android.apps.tv.launcherx/.home.HomeActivity"
        ),
        "cmd package query-activities --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME": (
            "com.google.android.apps.tv.launcherx/.home.HomeActivity\n"
            "com.google.android.tungsten.setupwraith/.RecoveryActivity\n"
        ),
        "getprop persist.sys.home.selectpkg": "",
        "pm list packages -e": (
            "package:com.google.android.apps.tv.launcherx\n"
            "package:com.google.android.tungsten.setupwraith\n"
            "package:com.google.android.backdrop\n"
            "package:com.google.android.youtube.tvmusic\n"
        ),
        "pm list packages -d": "package:com.vendor.already.disabled\n",
        "pm list packages io.soartv": "",
        "settings get global soartv_disabled": "null",
        "settings get secure screensaver_components": "com.google.android.backdrop/.Backdrop",
        "settings get secure screensaver_default_component": "com.google.android.backdrop/.Backdrop",
        "settings get secure screensaver_enabled": "1",
        "settings get secure screensaver_activate_on_sleep": "1",
        "settings get secure screensaver_activate_on_dock": "1",
        "settings get system screen_off_timeout": "300000",
        "settings get secure enabled_accessibility_services": "null",
        "settings get secure accessibility_enabled": "0",
        "settings get secure enabled_notification_listeners": "com.google.android.tv.remote.service/.Listener",
        "cmd tv_input list": "HDMI 1\nHDMI 2",
        "dumpsys wifi | grep -o -E 'cname=[^ ]*|^Wi-Fi is [a-z]+'": "Wi-Fi is enabled\ncname=com.android.tv.settings",
    }


class TvStateBackupTests(unittest.TestCase):
    """Verifies safe TV state capture, immutable local storage, and setupwraith-safe recovery."""

    def test_capture_records_complete_native_state_and_always_disconnects(self) -> None:
        adb = FakeAdbExecutor(_sample_native_responses())
        mgr = TvStateBackupManager(adb)

        snapshot = mgr.capture("192.168.1.50")

        self.assertEqual("192.168.1.50:5555", snapshot.target)
        self.assertEqual("Xiaomi", snapshot.device["manufacturer"])
        self.assertEqual("14", snapshot.device["android_release"])
        self.assertEqual(
            "com.google.android.apps.tv.launcherx/.home.HomeActivity",
            snapshot.home["resolved_home_activity"],
        )
        self.assertIn("com.google.android.apps.tv.launcherx", snapshot.packages["enabled"])
        self.assertIn("com.vendor.already.disabled", snapshot.packages["disabled"])
        self.assertFalse(snapshot.packages["soartv_installed"])
        self.assertFalse(snapshot.already_running_soartv_home)
        self.assertEqual("com.google.android.backdrop/.Backdrop", snapshot.screensaver["components"])
        self.assertEqual(["com.android.tv.settings"], snapshot.diagnostics["wifi_owners"])
        self.assertEqual(["192.168.1.50:5555"], adb.connected_targets)
        self.assertEqual(["192.168.1.50:5555"], adb.disconnected_targets)

    def test_capture_disconnects_adb_even_when_command_fails(self) -> None:
        adb = FakeAdbExecutor(_sample_native_responses(), fail_on_substring="pm list packages -e")
        mgr = TvStateBackupManager(adb)

        with self.assertRaises(AdbCommandError):
            mgr.capture("192.168.1.50")

        self.assertEqual(["192.168.1.50:5555"], adb.disconnected_targets)

    def test_save_backup_refuses_to_overwrite_existing_initial_snapshot_without_force(self) -> None:
        adb = FakeAdbExecutor(_sample_native_responses())
        mgr = TvStateBackupManager(adb)
        snapshot = mgr.capture("192.168.1.50")

        with tempfile.TemporaryDirectory() as tmp:
            backup_dir = Path(tmp) / "tv-backups"
            first_path = mgr.save_backup(snapshot, backup_dir=backup_dir)
            self.assertTrue(first_path.is_file())
            self.assertIn("initial-native-state.json", first_path.name)

            # Companion human-readable restore notes file is also written
            notes_path = first_path.with_name(first_path.stem.replace("-initial-native-state", "-restore-notes") + ".txt")
            self.assertTrue(notes_path.is_file())

            # Second save without force MUST fail to protect pristine pre-SoarTV state
            with self.assertRaises(FileExistsError):
                mgr.save_backup(snapshot, backup_dir=backup_dir, force=False)

            # Explicit force=True is allowed
            forced_path = mgr.save_backup(snapshot, backup_dir=backup_dir, force=True)
            self.assertEqual(first_path, forced_path)

            loaded = mgr.load_backup(first_path)
            self.assertEqual(snapshot.home["resolved_home_activity"], loaded.home["resolved_home_activity"])
            self.assertEqual(snapshot.packages["enabled"], loaded.packages["enabled"])

    def test_capture_flags_when_soartv_is_already_active_home(self) -> None:
        resp = _sample_native_responses()
        resp["cmd role get-role-holders --user 0 android.app.role.HOME"] = "io.soartv"
        resp[
            "cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME"
        ] = "io.soartv/.HomeActivity"
        resp["pm list packages io.soartv"] = "package:io.soartv"

        mgr = TvStateBackupManager(FakeAdbExecutor(resp))
        snapshot = mgr.capture("192.168.1.50")

        self.assertTrue(snapshot.packages["soartv_installed"])
        self.assertTrue(snapshot.already_running_soartv_home)

    def test_restore_plan_enforces_launcherx_before_setupwraith_and_preserves_native_disabled(self) -> None:
        adb = FakeAdbExecutor(_sample_native_responses())
        mgr = TvStateBackupManager(adb)
        snapshot = mgr.capture("192.168.1.50")

        # Simulate TV currently in SoarTV mode where launcherx, setupwraith, backdrop,
        # ytm, and the pre-existing natively disabled package are all disabled.
        currently_disabled = {
            "com.google.android.apps.tv.launcherx",
            GOOGLE_SETUP_PKG,
            "com.google.android.backdrop",
            "com.google.android.youtube.tvmusic",
            "com.vendor.already.disabled",
        }

        plan = mgr.build_restore_plan(snapshot, currently_disabled=currently_disabled, uninstall_soartv=False)

        # 1. Natively disabled package MUST NEVER be re-enabled
        self.assertFalse(any("com.vendor.already.disabled" in cmd for cmd in plan))

        # 2. launcherx MUST be re-enabled AND set as HOME strictly BEFORE setupwraith is re-enabled
        idx_enable_launcherx = plan.index("pm enable com.google.android.apps.tv.launcherx")
        idx_set_home = plan.index(
            "cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity"
        )
        idx_enable_setupwraith = plan.index(f"pm enable {GOOGLE_SETUP_PKG}")
        self.assertLess(idx_enable_launcherx, idx_set_home)
        self.assertLess(idx_set_home, idx_enable_setupwraith)

        # 3. Screensaver, accessibility, notification listeners, and global record are restored
        self.assertIn("settings put secure screensaver_components 'com.google.android.backdrop/.Backdrop'", plan)
        self.assertIn("settings delete secure enabled_accessibility_services", plan)
        self.assertIn("settings put secure accessibility_enabled '0'", plan)
        self.assertIn("cmd notification disallow_listener io.soartv/io.soartv.NowPlaying", plan)
        self.assertIn(
            "settings put secure enabled_notification_listeners 'com.google.android.tv.remote.service/.Listener'",
            plan,
        )
        self.assertIn("settings delete global soartv_disabled", plan)
        self.assertEqual("am start -a android.intent.action.MAIN -c android.intent.category.HOME", plan[-1])

    def test_restore_dry_run_makes_zero_state_mutations_and_disconnects(self) -> None:
        resp = _sample_native_responses()
        adb_capture = FakeAdbExecutor(resp)
        mgr_capture = TvStateBackupManager(adb_capture)
        snapshot = mgr_capture.capture("192.168.1.50")

        with tempfile.TemporaryDirectory() as tmp:
            backup_path = mgr_capture.save_backup(snapshot, backup_dir=Path(tmp))

            restore_resp = {
                "pm list packages -d": (
                    "package:com.google.android.apps.tv.launcherx\n"
                    f"package:{GOOGLE_SETUP_PKG}\n"
                )
            }
            adb_restore = FakeAdbExecutor(restore_resp)
            mgr_restore = TvStateBackupManager(adb_restore)

            result = mgr_restore.restore("192.168.1.50", backup_path, dry_run=True, uninstall_soartv=True)

            self.assertTrue(result.dry_run)
            self.assertGreater(len(result.planned_commands), 5)
            # Only read-only `pm list packages -d` ran during dry_run
            self.assertEqual(["pm list packages -d"], adb_restore.shell_calls)
            self.assertEqual(["192.168.1.50:5555"], adb_restore.disconnected_targets)

    def test_restore_executes_plan_and_optional_uninstall_after_home_restored(self) -> None:
        resp = _sample_native_responses()
        mgr_cap = TvStateBackupManager(FakeAdbExecutor(resp))
        snapshot = mgr_cap.capture("192.168.1.50")

        with tempfile.TemporaryDirectory() as tmp:
            backup_path = mgr_cap.save_backup(snapshot, backup_dir=Path(tmp))
            restore_adb = FakeAdbExecutor(
                {
                    "pm list packages -d": (
                        "package:com.google.android.apps.tv.launcherx\n"
                        f"package:{GOOGLE_SETUP_PKG}\n"
                    )
                }
            )
            mgr_res = TvStateBackupManager(restore_adb)
            result = mgr_res.restore("192.168.1.50", backup_path, dry_run=False, uninstall_soartv=True)

            self.assertFalse(result.dry_run)
            idx_home = restore_adb.shell_calls.index(
                "cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity"
            )
            idx_uninstall = restore_adb.shell_calls.index("__uninstall__ io.soartv")
            self.assertLess(idx_home, idx_uninstall)
            self.assertEqual(["192.168.1.50:5555"], restore_adb.disconnected_targets)

    def test_unsafe_shell_characters_in_backup_are_sanitized(self) -> None:
        raw_snapshot = TvNativeSnapshot(
            schema_version=1,
            captured_at_utc="2026-01-01T00:00:00Z",
            target="192.168.1.50:5555",
            device={"manufacturer": "Test", "model": "TV"},
            home={
                "role_holders": "com.bad;rm -rf /",
                "resolved_home_activity": "com.bad;rm -rf /.Home",
                "home_activities": [],
                "jm_home_prop": "bad;prop",
            },
            packages={
                "enabled": ["com.safe.pkg", "com.evil;reboot"],
                "disabled": [],
                "soartv_installed": False,
                "soartv_disabled_record": "",
            },
            screensaver={
                "components": "com.safe/.Dream;echo pwn",
                "default_component": "",
                "enabled": "1",
                "activate_on_sleep": "",
                "activate_on_dock": "",
                "screen_off_timeout": "300000",
            },
            services={
                "enabled_accessibility_services": "com.safe/.Svc;whoami",
                "accessibility_enabled": "0",
                "enabled_notification_listeners": "",
            },
            diagnostics={"tv_inputs": [], "wifi_owners": []},
            already_running_soartv_home=False,
        )

        mgr = TvStateBackupManager(FakeAdbExecutor())
        plan = mgr.build_restore_plan(raw_snapshot, currently_disabled={"com.safe.pkg", "com.evil;reboot"})

        self.assertIn("pm enable com.safe.pkg", plan)
        self.assertFalse(any(";" in cmd for cmd in plan))
        self.assertFalse(any("reboot" in cmd for cmd in plan))


if __name__ == "__main__":
    unittest.main()
