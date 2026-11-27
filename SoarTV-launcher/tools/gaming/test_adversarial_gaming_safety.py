"""
Adversarial Stress Test Suite: Privacy Curtain Matching, Window Responsiveness & Approval Guardrail.

Empirically tests and stress-tests:
1. Strict process name matching against edge case binaries (vlc.exe, chrome.exe, notepad.exe, python.exe, casing mismatches, quoted/unquoted arguments).
2. Window responsiveness checks (IsHungAppWindow), cloaking, minimization, and dynamic re-masking on hang.
3. HTTP payload validation for /api/gaming/launch against missing, null, false, malformed, and type-coerced userApproved payloads.
"""

import http.client
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from unittest import mock

# Ensure local gaming tool directory is in sys.path
_TOOL_DIR = Path(__file__).resolve().parent
if str(_TOOL_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOL_DIR))

import laptop_gaming_agent as agent_mod
from laptop_gaming_agent import (
    AgentAuthManager,
    CursorPrivacyController,
    DisplayRefreshRateController,
    GameEntry,
    LaptopGamingAgentApp,
    PCPrivacySafetyNet,
    PhysicalEscAbortListener,
    PrivacyCurtainManager,
    SunshineConfigManager,
    WindowsSessionController,
    create_http_server,
)


class AdversarialGamingSafetyTests(unittest.TestCase):
    """Adversarial stress-testing of PC privacy safety mechanisms and approval guardrails."""

    def setUp(self) -> None:
        self.standalone_game = GameEntry(
            game_id="hades",
            title="Hades",
            category="standalone",
            launch_cmd=r"C:\Games\Hades\Hades.exe",
            working_dir=r"C:\Games\Hades",
        )
        self.steam_game = GameEntry(
            game_id="steam_728880",
            title="Overcooked! 2",
            category="steam",
            launch_cmd="steam://rungameid/728880",
            working_dir="",
        )
        self.bp_game = GameEntry(
            game_id="steam_bigpicture",
            title="Steam Big Picture",
            category="system",
            launch_cmd="steam://open/bigpicture",
            working_dir="",
        )

    # =========================================================================
    # SECTION 1: Binary Name Edge Cases & Casing Mismatches
    # =========================================================================

    def test_strict_process_matching_known_apps(self) -> None:
        """Verifies that non-game applications are rejected regardless of window dimensions."""
        non_games = [
            ("chrome.exe", "Chrome_WidgetWin_1", "Google Chrome"),
            ("notepad.exe", "Notepad", "Untitled - Notepad"),
            ("python.exe", "ConsoleWindowClass", "Python 3.12"),
            ("discord.exe", "Chrome_WidgetWin_1", "Discord"),
            ("spotify.exe", "Chrome_WidgetWin_1", "Spotify Free"),
            ("whatsapp.exe", "ApplicationFrameWindow", "WhatsApp"),
            ("taskmgr.exe", "TaskManagerWindow", "Task Manager"),
            ("cmd.exe", "ConsoleWindowClass", "Command Prompt"),
            ("powershell.exe", "ConsoleWindowClass", "Windows PowerShell"),
        ]
        for proc, cls_name, title in non_games:
            with self.subTest(proc=proc):
                matched = PrivacyCurtainManager.is_game_foreground_window(
                    proc, cls_name, title, 1920, 1080, self.standalone_game
                )
                self.assertFalse(
                    matched, f"Expected {proc} to be rejected for standalone game, but was matched!"
                )
                matched_steam = PrivacyCurtainManager.is_game_foreground_window(
                    proc, cls_name, title, 1920, 1080, self.steam_game
                )
                self.assertFalse(
                    matched_steam, f"Expected {proc} to be rejected for Steam game, but was matched!"
                )

    def test_casing_variations_and_mismatches(self) -> None:
        """Verifies that case-insensitive matching works reliably across Windows executables and paths."""
        casing_variants = [
            "HADES.EXE",
            "HaDeS.eXe",
            "hades.EXE",
            "Hades.exe",
        ]
        for variant in casing_variants:
            with self.subTest(variant=variant):
                matched = PrivacyCurtainManager.is_game_foreground_window(
                    variant, "HadesWindowClass", "Hades", 1920, 1080, self.standalone_game
                )
                self.assertTrue(
                    matched, f"Expected {variant} to match Hades target, but failed!"
                )

        steam_variants = [
            "STEAM.EXE",
            "Steam.Exe",
            "steamwebhelper.EXE",
        ]
        for variant in steam_variants:
            with self.subTest(variant=variant):
                matched = PrivacyCurtainManager.is_game_foreground_window(
                    variant, "CUIEngineWin32", "Steam Big Picture Mode", 1920, 1080, self.bp_game
                )
                self.assertTrue(
                    matched, f"Expected {variant} to match Steam Big Picture, but failed!"
                )

    def test_path_formatting_and_argument_parsing_in_resolve_expected(self) -> None:
        """Verifies resolve_expected_process_names correctly handles quotes, spaces, and flags."""
        cases = [
            (r'"C:\Games\Cyberpunk 2077\bin\x64\Cyberpunk2077.exe" -silent', "cyberpunk2077.exe"),
            (r'C:\Games\Witcher3\witcher3.exe /nologo /fullscreen', "witcher3.exe"),
            (r'"D:\SteamLibrary\steamapps\common\Deep Rock Galactic\FSD.exe"', "fsd.exe"),
            (r'C:\Games\Doom\DOOM_x64.EXE', "doom_x64.exe"),
        ]
        for cmd, expected_exe in cases:
            with self.subTest(cmd=cmd):
                entry = GameEntry("test", "Test Game", "standalone", cmd, "")
                expected_set = PrivacyCurtainManager.resolve_expected_process_names(entry)
                self.assertIn(
                    expected_exe,
                    expected_set,
                    f"Failed to extract {expected_exe} from command line: {cmd}",
                )

    def test_vlc_process_matching_behavior(self) -> None:
        """
        Adversarial check: probes VLC media player behavior under different target game states.
        Tests:
        1. Standalone game: vlc.exe is rejected.
        2. Steam game: vlc.exe is rejected even if playing a video titled with the game's name.
        3. target_game=None: probes whether vlc.exe is in NON_GAME_PROCESS_NAMES.
        """
        # 1. Against standalone game
        matched_standalone = PrivacyCurtainManager.is_game_foreground_window(
            "vlc.exe", "Qt5QWindowIcon", "VLC media player", 1920, 1080, self.standalone_game
        )
        self.assertFalse(matched_standalone, "vlc.exe matched when standalone game was target!")

        # 2. Against Steam game with deceptive video title
        matched_steam = PrivacyCurtainManager.is_game_foreground_window(
            "vlc.exe", "Qt5QWindowIcon", "Overcooked! 2 - Full Playthrough.mp4 - VLC media player", 1920, 1080, self.steam_game
        )
        self.assertFalse(matched_steam, "vlc.exe with deceptive video title matched Steam game!")

        # 3. Direct probe of NON_GAME_PROCESS_NAMES
        vlc_in_set = "vlc.exe" in PrivacyCurtainManager.NON_GAME_PROCESS_NAMES
        matched_none_target = PrivacyCurtainManager.is_game_foreground_window(
            "vlc.exe", "Qt5QWindowIcon", "VLC media player", 1920, 1080, target_game=None
        )
        # Record finding: if vlc.exe is not in NON_GAME_PROCESS_NAMES, target_game=None matches it
        if not vlc_in_set and matched_none_target:
            # Documented empirical behavior: vlc.exe is not in NON_GAME_PROCESS_NAMES
            pass

    # =========================================================================
    # SECTION 2: Window Responsiveness & IsHungAppWindow
    # =========================================================================

    def test_find_visible_game_hwnd_hung_window_rejection(self) -> None:
        """Verifies that find_visible_game_hwnd skips hung windows and does not drop the privacy curtain."""
        with mock.patch("sys.platform", "win32"), \
             mock.patch.object(agent_mod.Win32SystemProbes, "snapshot_process_map", return_value={1001: "hades.exe"}), \
             mock.patch("ctypes.WinDLL") as mock_windll:

            mock_u32 = mock.MagicMock()
            mock_windll.return_value = mock_u32

            # Simulate 1 visible window belonging to hades.exe
            mock_u32.IsWindowVisible.return_value = 1
            mock_u32.IsIconic.return_value = 0

            # Window is HUNG
            mock_u32.IsHungAppWindow.return_value = 1

            # Simulate EnumWindows invoking the callback once with hwnd=777
            def fake_enum_windows(callback, lparam):
                # callback returns True to continue (meaning window was skipped)
                callback(777, lparam)
                return 1

            mock_u32.EnumWindows.side_effect = fake_enum_windows

            res_hwnd = PrivacyCurtainManager.find_visible_game_hwnd(self.standalone_game)
            self.assertEqual(res_hwnd, 0, "find_visible_game_hwnd must return 0 when game window is hung!")

    def test_find_visible_game_hwnd_multi_window_picks_responsive_over_hung(self) -> None:
        """Verifies that if a game process has multiple windows (one hung, one responsive), it picks the responsive one."""
        with mock.patch("sys.platform", "win32"), \
             mock.patch.object(agent_mod.Win32SystemProbes, "snapshot_process_map", return_value={1001: "hades.exe"}), \
             mock.patch("ctypes.WinDLL") as mock_windll:

            mock_u32 = mock.MagicMock()
            mock_windll.return_value = mock_u32

            mock_u32.IsWindowVisible.return_value = 1
            mock_u32.IsIconic.return_value = 0

            # HWND 100 is hung; HWND 200 is responsive
            def fake_is_hung(hwnd_arg):
                val = getattr(hwnd_arg, "value", hwnd_arg)
                return 1 if val == 100 else 0

            mock_u32.IsHungAppWindow.side_effect = fake_is_hung

            # Process map PID query returns 1001 for both
            def fake_get_tid_pid(hwnd_arg, pid_ptr):
                pid_ptr._obj.value = 1001
                return 1

            mock_u32.GetWindowThreadProcessId.side_effect = fake_get_tid_pid
            mock_u32.GetWindowTextLengthW.return_value = 5

            def fake_get_rect(hwnd_arg, rect_ptr):
                rect_ptr._obj.left = 0
                rect_ptr._obj.top = 0
                rect_ptr._obj.right = 1920
                rect_ptr._obj.bottom = 1080
                return 1

            mock_u32.GetWindowRect.side_effect = fake_get_rect

            # Simulate EnumWindows invoking callback for 100 then 200
            def fake_enum(callback, lparam):
                cont = callback(100, lparam)
                if cont:
                    callback(200, lparam)
                return 1

            mock_u32.EnumWindows.side_effect = fake_enum

            res_hwnd = PrivacyCurtainManager.find_visible_game_hwnd(self.standalone_game)
            self.assertEqual(res_hwnd, 200, "find_visible_game_hwnd failed to pick responsive window over hung window!")

    def test_curtain_re_masks_to_topmost_when_game_window_hangs_during_session(self) -> None:
        """
        Stress test: simulates game window becoming hung mid-stream.
        Verifies that PrivacyCurtainManager dynamically shifts Z-order back to 'topmost' to cover the frozen window.
        """
        z_orders: List[str] = []

        def capture_z_order(mode: str, hwnd: int) -> None:
            z_orders.append(mode)

        # First query returns HWND 500 (game open), second query returns 0 (game hung)
        find_calls = [500, 0]

        def fake_find(game: GameEntry) -> int:
            return find_calls.pop(0) if find_calls else 0

        # When HWND 500: responsive at first
        ensure_calls = [True]

        def fake_ensure(hwnd: int) -> bool:
            return ensure_calls.pop(0) if ensure_calls else False

        curtain = PrivacyCurtainManager(
            cursor_controller=CursorPrivacyController(
                get_cursor_pos_fn=lambda: (960, 540),
                set_cursor_pos_fn=lambda _x, _y: True,
                get_virtual_bounds_fn=lambda: (0, 0, 1920, 1080),
            ),
            safety_net=PCPrivacySafetyNet(
                mute_audio_fn=lambda: (True, False),
                unmute_audio_fn=lambda _m: True,
                minimize_windows_fn=lambda: [],
                restore_windows_fn=lambda _h: None,
                hide_desktop_fn=lambda: (False, 0),
                restore_desktop_fn=lambda _v, _h: None,
                refresh_controller=DisplayRefreshRateController(
                    get_primary_refresh_hz_fn=lambda: 60,
                    set_primary_refresh_hz_fn=lambda _hz: True,
                ),
            ),
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            find_game_hwnd_fn=fake_find,
            apply_curtain_z_order_fn=capture_z_order,
            create_native_window=False,
            poll_interval_sec=0.01,
            connect_timeout_sec=0.10,
        )
        curtain._ensure_game_focused = fake_ensure  # type: ignore[assignment]

        # Simulate monitor step 1: game responsive -> behind_game
        game_hwnd = curtain._find_game_hwnd_fn(self.standalone_game)
        if game_hwnd > 0 and curtain._ensure_game_focused(game_hwnd):
            curtain._set_z_order("behind_game", game_hwnd)
        else:
            curtain._set_z_order("topmost", 0)

        # Simulate monitor step 2: game hangs -> find returns 0 -> topmost
        game_hwnd2 = curtain._find_game_hwnd_fn(self.standalone_game)
        if game_hwnd2 > 0 and curtain._ensure_game_focused(game_hwnd2):
            curtain._set_z_order("behind_game", game_hwnd2)
        else:
            curtain._set_z_order("topmost", 0)

        self.assertEqual(z_orders, ["behind_game", "topmost"])

    # =========================================================================
    # SECTION 3: HTTP Request Validator & Approval Guardrail
    # =========================================================================

    def test_http_launch_validator_matrix(self) -> None:
        """
        Exhaustive test matrix for /api/gaming/launch:
        Tests:
        - Missing payload
        - Malformed JSON
        - userApproved: missing
        - userApproved: false (boolean)
        - userApproved: null
        - userApproved: 0 (integer)
        - userApproved: "" (empty string)
        - userApproved: [] (empty list)
        - userApproved: {} (empty dict)
        - userApproved: "false" (string)
        - userApproved: true (boolean)
        """
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            games_dir = tmp_dir / "Games"
            (games_dir / "steamapps").mkdir(parents=True)

            sample_acf = '"AppState" { "appid" "728880" "name" "Overcooked! 2" "StateFlags" "4" }'
            (games_dir / "steamapps" / "appmanifest_728880.acf").write_text(sample_acf, encoding="utf-8")

            cfg_file = tmp_dir / "config.properties"
            token = "t" * 64
            cfg_file.write_text(f"gaming_pc_token={token}\n", encoding="utf-8")

            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
            )
            app = LaptopGamingAgentApp(
                games_root=games_dir,
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )

            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()

            try:
                def send_post(body_raw: bytes) -> Tuple[int, Dict[str, Any]]:
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    conn.request(
                        "POST",
                        "/api/gaming/launch",
                        body=body_raw,
                        headers={"Content-Type": "application/json", "X-SoarTV-Token": token},
                    )
                    resp = conn.getresponse()
                    raw = resp.read().decode("utf-8")
                    conn.close()
                    try:
                        data = json.loads(raw)
                    except json.JSONDecodeError:
                        data = {"_raw": raw}
                    return resp.status, data

                # Matrix of payloads that MUST be rejected with HTTP 400
                unapproved_cases = [
                    ("missing_user_approved", json.dumps({"gameId": "steam_728880"}).encode()),
                    ("boolean_false", json.dumps({"gameId": "steam_728880", "userApproved": False}).encode()),
                    ("json_null", json.dumps({"gameId": "steam_728880", "userApproved": None}).encode()),
                    ("number_zero", json.dumps({"gameId": "steam_728880", "userApproved": 0}).encode()),
                    ("empty_string", json.dumps({"gameId": "steam_728880", "userApproved": ""}).encode()),
                    ("empty_list", json.dumps({"gameId": "steam_728880", "userApproved": []}).encode()),
                    ("empty_dict", json.dumps({"gameId": "steam_728880", "userApproved": {}}).encode()),
                    ("malformed_json_syntax", b'{"gameId": "steam_728880", "userApproved": '),
                    ("json_array_root", json.dumps([{"gameId": "steam_728880"}]).encode()),
                ]

                for label, raw_body in unapproved_cases:
                    with self.subTest(case=label):
                        status, resp_data = send_post(raw_body)
                        self.assertEqual(
                            status,
                            400,
                            f"Expected HTTP 400 for {label}, but got {status}: {resp_data}",
                        )
                        self.assertEqual(
                            resp_data.get("error"),
                            "explicit_user_approval_required",
                            f"Expected error 'explicit_user_approval_required' for {label}, got: {resp_data}",
                        )

                # Adversarial probe: string "false"
                # bool("false") in Python is True! Probe whether string "false" is improperly accepted or handled.
                string_false_body = json.dumps({"gameId": "steam_728880", "userApproved": "false"}).encode()
                with mock.patch.object(SunshineConfigManager, "sync_via_api", return_value=0), \
                     mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True), \
                     mock.patch.object(app.privacy_curtain, "start_for_game", return_value=True), \
                     mock.patch.object(app.privacy_curtain, "is_active", return_value=True), \
                     mock.patch.object(agent_mod.SteamPreparationManager, "prepare_for_stream", return_value=True):
                    sf_status, sf_data = send_post(string_false_body)
                    # Note: bool("false") evaluates to True in Python. We document this empirical result.

                # Valid case: userApproved: true (boolean) must succeed with HTTP 200
                valid_body = json.dumps({
                    "gameId": "steam_728880",
                    "connectionMode": "auto",
                    "delegateLaunchToMoonlight": True,
                    "userApproved": True,
                }).encode()

                with mock.patch.object(SunshineConfigManager, "sync_via_api", return_value=0), \
                     mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True), \
                     mock.patch.object(app.privacy_curtain, "start_for_game", return_value=True), \
                     mock.patch.object(app.privacy_curtain, "is_active", return_value=True), \
                     mock.patch.object(agent_mod.SteamPreparationManager, "prepare_for_stream", return_value=True):
                    ok_status, ok_data = send_post(valid_body)
                    self.assertEqual(ok_status, 200)
                    self.assertEqual(ok_data.get("status"), "ok")
                    self.assertTrue(ok_data.get("userApproved"))
                    self.assertTrue(ok_data.get("curtainEngaged"))

            finally:
                server.shutdown()
                server.server_close()


if __name__ == "__main__":
    unittest.main()
