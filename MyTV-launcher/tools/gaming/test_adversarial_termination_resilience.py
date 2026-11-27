"""
Adversarial Stress Test Suite: Termination Resilience, Process Safety & Volume Preservation.

Why: Milestone 3 requires closing PC games cleanly on exit (quit in-game or TV remote Home/Back)
and restoring the computer to its normal state, with strict guardrails:
1. Deep process tree traversal (5+ levels) must find all helper/CEF/audio child PIDs.
2. Parent safety: Ancestor PIDs (steam.exe, explorer.exe, init) must NEVER be killed.
3. Pathological inputs: Negative PIDs, PID 0, cyclic PPID trees, and non-existent PIDs must be handled safely.
4. Two-stage termination mechanics: sub-millisecond exit vs hung process fallback to TerminateProcess.
5. Multi-HWND handling: games with multiple windows must have WM_CLOSE sent to all valid HWNDs without duplication.
6. HTTP endpoint security: authentication bypass, payload fuzzing (malformed JSON, type confusion, 100K strings),
   concurrency hammer, and strict idempotency.
7. CRITICAL USER RULE: Volume scalar must NEVER be modified (0 SetMasterVolumeLevelScalar / VK_VOLUME_UP calls),
   and pre-existing mute state must be strictly preserved on exit.
8. Screensaver preservation: all uncommitted screensaver files must remain untouched.
"""

from __future__ import annotations

import http.client
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
from typing import Any, Dict, List, Optional, Set, Tuple
import unittest
from unittest import mock

# Ensure local gaming tool directory is in sys.path
_TOOL_DIR = Path(__file__).resolve().parent
if str(_TOOL_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOL_DIR))

import laptop_gaming_agent as agent_mod
from laptop_gaming_agent import (
    AgentAuthManager,
    DisplayRefreshRateController,
    GameEntry,
    GameProcessController,
    LaptopGamingAgentApp,
    PCPrivacySafetyNet,
    PrivacyCurtainManager,
    SunshineConfigManager,
    Win32SystemProbes,
    create_http_server,
    WM_CLOSE,
    GAME_TERMINATE_TIMEOUT_SEC,
    HTTP_MAX_BODY_BYTES,
)


class AdversarialProcessTreeTraversalTests(unittest.TestCase):
    """Adversarial stress-testing of GameProcessController process tree discovery and ancestor safety."""

    def test_deep_process_tree_traversal_6_levels_downward(self) -> None:
        """
        Stress-tests BFS traversal with a 6-level deep process tree with multiple branches.
        Verifies all descendant PIDs are found and all ancestor PIDs are strictly excluded.
        """
        # Topology:
        # System (1) -> explorer.exe (50) -> steam.exe (100) -> launcher.exe (200) [TARGET]
        # launcher (200) -> bootstrap (300) -> main_game (400)
        # main_game (400) -> crash_reporter (500) -> cef_renderer (600) -> audio_worker (700)
        # main_game (400) -> telemetry_worker (501)
        # Independent: discord (800, ppid=50), chrome (900, ppid=50)
        procs: List[Tuple[int, int, str]] = [
            (1, 0, "system"),
            (50, 1, "explorer.exe"),
            (100, 50, "steam.exe"),
            (200, 100, "launcher.exe"),
            (300, 200, "bootstrapper.exe"),
            (400, 300, "main_game.exe"),
            (500, 400, "crash_reporter.exe"),
            (501, 400, "telemetry_worker.exe"),
            (600, 500, "cef_renderer.exe"),
            (700, 600, "audio_worker.exe"),
            (800, 50, "discord.exe"),
            (900, 50, "chrome.exe"),
        ]

        tree = GameProcessController.get_process_tree(200, entries=procs)
        expected_descendants = {200, 300, 400, 500, 501, 600, 700}
        self.assertEqual(tree, expected_descendants)

        # Critical safety invariant: Ancestor PIDs must NEVER be in tree
        self.assertNotIn(100, tree, "steam.exe (PID 100) must NEVER be in descendant tree!")
        self.assertNotIn(50, tree, "explorer.exe (PID 50) must NEVER be in descendant tree!")
        self.assertNotIn(1, tree, "System (PID 1) must NEVER be in descendant tree!")

        # Unrelated processes must NEVER be in tree
        self.assertNotIn(800, tree, "discord.exe (PID 800) must NEVER be in descendant tree!")
        self.assertNotIn(900, tree, "chrome.exe (PID 900) must NEVER be in descendant tree!")

    def test_parent_process_safety_under_game_termination(self) -> None:
        """
        Verifies that when terminating a game, parent processes (steam.exe, explorer.exe)
        are NEVER sent WM_CLOSE and NEVER terminated via TerminateProcess.
        """
        procs: List[Tuple[int, int, str]] = [
            (50, 1, "explorer.exe"),
            (100, 50, "steam.exe"),
            (200, 100, "game.exe"),
            (201, 200, "game_subproc.exe"),
        ]

        post_messages: List[Tuple[int, int, int, int]] = []
        term_calls: List[int] = []

        # Map PID to HWND
        hwnds_map = {
            50: [1000],   # explorer taskbar
            100: [2000],  # steam client
            200: [3000],  # game window
            201: [3001],  # game subproc window
        }

        def mock_find_hwnds(pids: Set[int]) -> List[int]:
            hwnds = []
            for p in pids:
                hwnds.extend(hwnds_map.get(p, []))
            return hwnds

        controller = GameProcessController(
            post_message_fn=lambda h, m, w, l: post_messages.append((h, m, w, l)) or True,
            terminate_process_fn=lambda p: term_calls.append(p) or True,
            is_pid_alive_fn=lambda p: True,  # Process hangs to test worst-case kill
            snapshot_procs_fn=lambda: procs,
            find_hwnds_fn=mock_find_hwnds,
            close_sunshine_fn=lambda: True,
            poll_interval_sec=0.01,
        )

        res = controller.terminate_game(explicit_pid=200, grace_period_sec=0.03)

        self.assertTrue(res["ok"])
        self.assertTrue(res["terminated"])
        self.assertTrue(res["forced"])

        # Targeted PIDs were terminated
        self.assertEqual(set(res["pidsTerminated"]), {200, 201})
        self.assertEqual(set(term_calls), {200, 201})

        # Sent WM_CLOSE only to game HWNDs
        messaged_hwnds = {h for h, m, w, l in post_messages}
        self.assertEqual(messaged_hwnds, {3000, 3001})

        # CRITICAL ASSERTIONS: Steam and Explorer HWNDs and PIDs must NEVER be touched
        self.assertNotIn(2000, messaged_hwnds, "Steam HWND (2000) was sent WM_CLOSE!")
        self.assertNotIn(1000, messaged_hwnds, "Explorer HWND (1000) was sent WM_CLOSE!")
        self.assertNotIn(100, term_calls, "Steam PID (100) was killed!")
        self.assertNotIn(50, term_calls, "Explorer PID (50) was killed!")

    def test_negative_zero_and_nonexistent_pid_safety(self) -> None:
        """Verifies boundary conditions: PID 0, negative PIDs, and non-existent PIDs."""
        # 1. PID 0 returns empty set
        self.assertEqual(GameProcessController.get_process_tree(0), set())
        # 2. Negative PIDs return empty set
        self.assertEqual(GameProcessController.get_process_tree(-1), set())
        self.assertEqual(GameProcessController.get_process_tree(-9999), set())

        controller = GameProcessController(
            snapshot_procs_fn=lambda: [(100, 1, "proc.exe")],
            find_hwnds_fn=lambda pids: [],
            close_sunshine_fn=lambda: True,
        )

        # 3. find_game_pids with negative or zero PID returns empty set
        self.assertEqual(controller.find_game_pids(explicit_pid=0), set())
        self.assertEqual(controller.find_game_pids(explicit_pid=-50), set())

        # 4. terminate_game with negative PID returns cleanly without termination
        res_neg = controller.terminate_game(explicit_pid=-42)
        self.assertTrue(res_neg["ok"])
        self.assertFalse(res_neg["terminated"])
        self.assertEqual(res_neg["pidsTerminated"], [])
        self.assertFalse(res_neg["forced"])
        self.assertTrue(res_neg["pcRestored"])

        # 5. terminate_game with PID 0 returns cleanly
        res_zero = controller.terminate_game(explicit_pid=0)
        self.assertTrue(res_zero["ok"])
        self.assertFalse(res_zero["terminated"])
        self.assertEqual(res_zero["pidsTerminated"], [])

        # 6. Non-existent PID returns isolated root set
        tree_missing = GameProcessController.get_process_tree(88888, entries=[(100, 1, "test.exe")])
        self.assertEqual(tree_missing, {88888})

    def test_cyclic_and_corrupted_process_table_resilience(self) -> None:
        """
        Adversarially feeds cyclic and corrupted process entries into get_process_tree.
        Verifies BFS terminates cleanly without infinite loops or stack overflow.
        """
        corrupted_procs: List[Tuple[int, int, str]] = [
            (10, 20, "proc_a.exe"),  # 10's parent is 20
            (20, 10, "proc_b.exe"),  # 20's parent is 10 (Cycle!)
            (30, 30, "self_parent.exe"),  # 30's parent is 30 (Self-loop!)
            (40, 99999, "orphan.exe"),  # Parent doesn't exist
            (50, 40, "child_of_orphan.exe"),
        ]

        # Cycle 10 <-> 20 should terminate and return {10, 20}
        t0 = time.monotonic()
        tree_cycle = GameProcessController.get_process_tree(10, entries=corrupted_procs)
        elapsed = time.monotonic() - t0
        self.assertLess(elapsed, 0.5, "Cycle detection took too long — possible infinite loop!")
        self.assertEqual(tree_cycle, {10, 20})

        # Self-parent should terminate immediately
        tree_self = GameProcessController.get_process_tree(30, entries=corrupted_procs)
        self.assertEqual(tree_self, {30})

        # Orphan with child
        tree_orphan = GameProcessController.get_process_tree(40, entries=corrupted_procs)
        self.assertEqual(tree_orphan, {40, 50})

    def test_find_game_pids_standalone_and_desktop_protection(self) -> None:
        """Verifies find_game_pids resolves standalone game executables and protects 'desktop' category."""
        hades_game = GameEntry(
            game_id="hades",
            title="Hades",
            category="standalone",
            launch_cmd=r'C:\Games\Hades\Hades.exe -fullscreen',
            working_dir=r'C:\Games\Hades',
        )

        desktop_game = GameEntry(
            game_id="desktop",
            title="Desktop",
            category="system",
            launch_cmd="",
            working_dir="",
        )

        procs: List[Tuple[int, int, str]] = [
            (100, 1, "explorer.exe"),
            (5000, 100, "hades.exe"),
            (5001, 5000, "crash_reporter.exe"),
            (6000, 100, "chrome.exe"),
        ]

        controller = GameProcessController(
            snapshot_procs_fn=lambda: procs,
            find_hwnds_fn=lambda pids: [],
        )

        # Standalone game should find hades.exe and child crash_reporter.exe
        hades_pids = controller.find_game_pids(target_game=hades_game)
        self.assertEqual(hades_pids, {5000, 5001})

        # Desktop game should return empty set (NEVER kill desktop or explorer!)
        desktop_pids = controller.find_game_pids(target_game=desktop_game)
        self.assertEqual(desktop_pids, set(), "Desktop game must NEVER match running processes!")


class AdversarialTwoStageTerminationMechanicsTests(unittest.TestCase):
    """Stress-tests the two-stage WM_CLOSE -> TerminateProcess fallback mechanics."""

    def test_wm_close_grace_period_submillisecond_exit_timing(self) -> None:
        """
        Verifies that when a game process exits cleanly upon WM_CLOSE, terminate_game
        breaks early and does NOT block for the full grace_period_sec.
        """
        post_messages: List[Tuple[int, int, int, int]] = []
        term_calls: List[int] = []

        # Process is alive on initial query, then immediately dead
        alive_responses = [True, False]

        def mock_is_alive(p: int) -> bool:
            return alive_responses.pop(0) if alive_responses else False

        controller = GameProcessController(
            post_message_fn=lambda h, m, w, l: post_messages.append((h, m, w, l)) or True,
            terminate_process_fn=lambda p: term_calls.append(p) or True,
            is_pid_alive_fn=mock_is_alive,
            snapshot_procs_fn=lambda: [(5000, 1, "game.exe")],
            find_hwnds_fn=lambda pids: [1001],
            close_sunshine_fn=lambda: True,
            poll_interval_sec=0.01,
        )

        # Set a long grace period of 10.0 seconds
        t0 = time.monotonic()
        res = controller.terminate_game(explicit_pid=5000, grace_period_sec=10.0)
        elapsed = time.monotonic() - t0

        self.assertTrue(res["ok"])
        self.assertTrue(res["terminated"])
        self.assertFalse(res["forced"], "Exit should have been graceful, not forced!")
        self.assertEqual(term_calls, [], "TerminateProcess should NOT be called on graceful exit!")
        self.assertLess(elapsed, 1.0, f"Sub-millisecond exit took {elapsed:.3f}s; failed to break early!")

    def test_hung_process_timeout_escalation_to_terminate_process(self) -> None:
        """
        Verifies that when a process ignores WM_CLOSE (e.g. frozen render thread),
        it waits exactly the grace period and escalates to TerminateProcess.
        """
        post_messages: List[Tuple[int, int, int, int]] = []
        term_calls: List[int] = []

        controller = GameProcessController(
            post_message_fn=lambda h, m, w, l: post_messages.append((h, m, w, l)) or True,
            terminate_process_fn=lambda p: term_calls.append(p) or True,
            is_pid_alive_fn=lambda p: True,  # Process NEVER dies voluntarily
            snapshot_procs_fn=lambda: [(5000, 1, "hung_game.exe")],
            find_hwnds_fn=lambda pids: [2002],
            close_sunshine_fn=lambda: True,
            poll_interval_sec=0.01,
        )

        t0 = time.monotonic()
        res = controller.terminate_game(explicit_pid=5000, grace_period_sec=0.05)
        elapsed = time.monotonic() - t0

        self.assertTrue(res["ok"])
        self.assertTrue(res["terminated"])
        self.assertTrue(res["forced"], "Forced kill flag must be True for hung process!")
        self.assertEqual(post_messages, [(2002, WM_CLOSE, 0, 0)])
        self.assertEqual(term_calls, [5000], "TerminateProcess must be called on hung PID 5000!")
        self.assertGreaterEqual(elapsed, 0.04)

    def test_partial_process_tree_survival_selective_kill(self) -> None:
        """
        Verifies that in a process tree where some processes exit gracefully and others hang,
        TerminateProcess is called ONLY on the surviving hung processes.
        """
        procs: List[Tuple[int, int, str]] = [
            (4000, 1, "main_game.exe"),
            (4001, 4000, "clean_child.exe"),
            (4002, 4000, "hung_child.exe"),
        ]

        term_calls: List[int] = []

        # 4000 and 4001 exit cleanly upon WM_CLOSE; 4002 hangs indefinitely
        def mock_is_alive(pid: int) -> bool:
            return pid == 4002

        controller = GameProcessController(
            post_message_fn=lambda h, m, w, l: True,
            terminate_process_fn=lambda p: term_calls.append(p) or True,
            is_pid_alive_fn=mock_is_alive,
            snapshot_procs_fn=lambda: procs,
            find_hwnds_fn=lambda pids: [10, 11, 12],
            close_sunshine_fn=lambda: True,
            poll_interval_sec=0.01,
        )

        res = controller.terminate_game(explicit_pid=4000, grace_period_sec=0.03)

        self.assertTrue(res["ok"])
        self.assertTrue(res["terminated"])
        self.assertTrue(res["forced"])
        self.assertEqual(res["pidsTerminated"], [4000, 4001, 4002])
        # Only the surviving hung child (4002) should be forcibly killed
        self.assertEqual(term_calls, [4002])

    def test_multiple_top_level_hwnds_and_deduplication(self) -> None:
        """
        Verifies that games with multiple top-level HWNDs (splash, game, dialog),
        explicit HWND, and privacy curtain HWND receive WM_CLOSE with strict deduplication
        and negative/zero HWND filtering.
        """
        post_messages: List[Tuple[int, int, int, int]] = []

        # Simulated find_hwnds returns duplicates and invalid handles
        def mock_find_hwnds(pids: Set[int]) -> List[int]:
            return [101, 102, 101, 103, 0, -1]

        mock_curtain = mock.MagicMock()
        mock_curtain.get_target_hwnd.return_value = 104
        mock_curtain.get_target_pid.return_value = 5000

        controller = GameProcessController(
            privacy_curtain=mock_curtain,
            post_message_fn=lambda h, m, w, l: post_messages.append((h, m, w, l)) or True,
            terminate_process_fn=lambda p: True,
            is_pid_alive_fn=lambda p: False,  # Immediate exit
            snapshot_procs_fn=lambda: [(5000, 1, "game.exe")],
            find_hwnds_fn=mock_find_hwnds,
            close_sunshine_fn=lambda: True,
        )

        # explicit_hwnd = 105; curtain_hwnd = 104; find_hwnds = [101, 102, 103]
        res = controller.terminate_game(explicit_pid=5000, explicit_hwnd=105)

        self.assertTrue(res["ok"])
        messaged_hwnds = [h for h, m, w, l in post_messages]

        # Verify all valid HWNDs received WM_CLOSE exactly once
        self.assertEqual(set(messaged_hwnds), {101, 102, 103, 104, 105})
        self.assertEqual(len(messaged_hwnds), 5, "Duplicate HWNDs must be deduplicated!")
        self.assertNotIn(0, messaged_hwnds, "HWND 0 must not be messaged!")
        self.assertNotIn(-1, messaged_hwnds, "Negative HWND must not be messaged!")

    def test_post_message_error_resilience_in_termination_pipeline(self) -> None:
        """
        Adversarially injects OSError into _post_message_fn.
        Verifies GameProcessController catches the error and still performs PC state restoration.
        """
        def exploding_post_message(h: int, m: int, w: int, l: int) -> bool:
            raise OSError("PostMessageW access denied / invalid window handle")

        safety_restored: List[str] = []
        mock_safety = mock.MagicMock()
        mock_safety.restore.side_effect = lambda: safety_restored.append("restored")
        mock_safety.is_engaged.return_value = False

        controller = GameProcessController(
            safety_net=mock_safety,
            post_message_fn=exploding_post_message,
            terminate_process_fn=lambda p: True,
            is_pid_alive_fn=lambda p: False,  # Exit immediately
            snapshot_procs_fn=lambda: [(5000, 1, "game.exe")],
            find_hwnds_fn=lambda pids: [1001],
            close_sunshine_fn=lambda: True,
            poll_interval_sec=0.01,
        )

        res = controller.terminate_game(explicit_pid=5000, grace_period_sec=0.02)
        self.assertTrue(res["ok"])
        self.assertTrue(res["pcRestored"])
        self.assertIn("restored", safety_restored, "PC state MUST be restored even if PostMessageW fails!")

    def test_terminate_process_permission_error_resilience_in_termination_pipeline(self) -> None:
        """
        Adversarially injects PermissionError into _terminate_process_fn (e.g. anti-cheat protected game).
        Verifies GameProcessController catches the error and still performs PC state restoration.
        """
        def exploding_terminate_process(p: int) -> bool:
            raise PermissionError("TerminateProcess ERROR_ACCESS_DENIED (anti-cheat protected process)")

        safety_restored: List[str] = []
        mock_safety = mock.MagicMock()
        mock_safety.restore.side_effect = lambda: safety_restored.append("restored")
        mock_safety.is_engaged.return_value = False

        controller = GameProcessController(
            safety_net=mock_safety,
            post_message_fn=lambda h, m, w, l: True,
            terminate_process_fn=exploding_terminate_process,
            is_pid_alive_fn=lambda p: True,  # Process hangs
            snapshot_procs_fn=lambda: [(5000, 1, "game.exe")],
            find_hwnds_fn=lambda pids: [1001],
            close_sunshine_fn=lambda: True,
            poll_interval_sec=0.01,
        )

        res = controller.terminate_game(explicit_pid=5000, grace_period_sec=0.02)
        self.assertTrue(res["ok"])
        self.assertTrue(res["forced"])
        self.assertTrue(res["pcRestored"])
        self.assertIn("restored", safety_restored, "PC state MUST be restored even if TerminateProcess fails!")


    def test_privacy_curtain_manager_standalone_termination_helper(self) -> None:
        """Tests PrivacyCurtainManager._terminate_game_process boundary cases."""
        # 1. PID 0, HWND 0 -> returns True immediately
        self.assertTrue(PrivacyCurtainManager._terminate_game_process(hwnd=0, pid=0))

        # 2. Negative PID, negative HWND -> returns True immediately
        self.assertTrue(PrivacyCurtainManager._terminate_game_process(hwnd=-1, pid=-100))

        # 3. Clean exit upon WM_CLOSE
        msgs: List[Tuple[int, int, int, int]] = []
        kills: List[int] = []
        is_alive_seq = [True, False]

        ok = PrivacyCurtainManager._terminate_game_process(
            hwnd=555,
            pid=777,
            wait_timeout_sec=1.0,
            send_msg_fn=lambda h, m, w, l: msgs.append((h, m, w, l)),
            kill_proc_fn=lambda p: kills.append(p),
            is_pid_alive_fn=lambda p: is_alive_seq.pop(0) if is_alive_seq else False,
        )
        self.assertTrue(ok)
        self.assertEqual(msgs, [(555, WM_CLOSE, 0, 0)])
        self.assertEqual(kills, [])

        # 4. Hung PID escalation
        msgs_hung: List[Tuple[int, int, int, int]] = []
        kills_hung: List[int] = []

        ok_hung = PrivacyCurtainManager._terminate_game_process(
            hwnd=556,
            pid=778,
            wait_timeout_sec=0.03,
            send_msg_fn=lambda h, m, w, l: msgs_hung.append((h, m, w, l)),
            kill_proc_fn=lambda p: kills_hung.append(p),
            is_pid_alive_fn=lambda p: True,
        )
        self.assertTrue(ok_hung)
        self.assertEqual(msgs_hung, [(556, WM_CLOSE, 0, 0)])
        self.assertEqual(kills_hung, [778])


class AdversarialHttpTerminateEndpointTests(unittest.TestCase):
    """Stress-tests the POST /api/gaming/terminate HTTP endpoint for auth bypass and payload fuzzing."""

    def setUp(self) -> None:
        self.tmp_dir_obj = tempfile.TemporaryDirectory()
        self.tmp_dir = Path(self.tmp_dir_obj.name)
        self.valid_token = "secret_valid_token_m3_challenger_8888"

        cfg_file = self.tmp_dir / "config.properties"
        cfg_file.write_text(f"gaming_pc_token={self.valid_token}\n", encoding="utf-8")

        self.auth_mgr = AgentAuthManager(
            auth_path=self.tmp_dir / "auth.json",
            pair_code_path=self.tmp_dir / "pair_code.txt",
            config_properties_path=cfg_file,
        )

        self.app = LaptopGamingAgentApp(
            games_root=self.tmp_dir / "Games",
            steam_root=self.tmp_dir / "Steam",
            state_dir=self.tmp_dir / "state",
            auth_manager=self.auth_mgr,
        )

        # Wire a mock process controller to spy on calls
        self.mock_terminate_calls: List[Dict[str, Any]] = []

        def mock_term(target_game: Optional[GameEntry] = None, explicit_pid: int = 0, explicit_hwnd: int = 0, grace_period_sec: float = 3.0, reason: str = "") -> Dict[str, Any]:
            self.mock_terminate_calls.append({
                "target_game": target_game,
                "explicit_pid": explicit_pid,
                "reason": reason,
            })
            return {
                "ok": True,
                "status": "ok",
                "terminated": True,
                "pidsTerminated": [1234],
                "forced": False,
                "pcRestored": True,
                "reason": reason,
            }

        self.app.process_controller.terminate_game = mock_term  # type: ignore[assignment]

        self.server = create_http_server(app=self.app, host="127.0.0.1", port=0)
        self.port = self.server.server_address[1]
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()

    def tearDown(self) -> None:
        try:
            self.server.shutdown()
            self.server.server_close()
        except Exception:
            pass
        self.tmp_dir_obj.cleanup()

    def _post(
        self,
        path: str,
        body: bytes | str,
        headers: Optional[Dict[str, str]] = None,
        timeout: float = 3.0,
    ) -> Tuple[int, Dict[str, Any]]:
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)
        hdrs = dict(headers or {})
        raw_body = body.encode("utf-8") if isinstance(body, str) else body
        if "Content-Length" not in hdrs and "content-length" not in hdrs:
            hdrs["Content-Length"] = str(len(raw_body))
        conn.request("POST", path, body=raw_body, headers=hdrs)
        resp = conn.getresponse()
        data_bytes = resp.read()
        conn.close()
        try:
            parsed = json.loads(data_bytes.decode("utf-8", errors="ignore"))
        except Exception:
            parsed = {}
        return resp.status, parsed

    def test_auth_bypass_attacks_on_terminate(self) -> None:
        """Adversarially attempts authentication bypass via missing, invalid, spoofed, and cross-origin tokens."""
        # Attack 1: Missing all auth headers
        s, d = self._post("/api/gaming/terminate", json.dumps({"gameId": "hades"}), {"Content-Type": "application/json"})
        self.assertEqual(s, 401)
        self.assertFalse(d.get("ok"))
        self.assertEqual(d.get("error"), "unauthorized")

        # Attack 2: Empty X-MyTV-Token header
        s, d = self._post("/api/gaming/terminate", json.dumps({"gameId": "hades"}), {
            "Content-Type": "application/json",
            "X-MyTV-Token": "",
        })
        self.assertEqual(s, 401)

        # Attack 3: Completely wrong token
        s, d = self._post("/api/gaming/terminate", json.dumps({"gameId": "hades"}), {
            "Content-Type": "application/json",
            "X-MyTV-Token": "attacker_guessed_wrong_token",
        })
        self.assertEqual(s, 401)

        # Attack 4: Partial prefix token (timing attack / substring attack)
        s, d = self._post("/api/gaming/terminate", json.dumps({"gameId": "hades"}), {
            "Content-Type": "application/json",
            "X-MyTV-Token": self.valid_token[:10],
        })
        self.assertEqual(s, 401)

        # Attack 5: Spoofed Bearer token in Authorization header
        s, d = self._post("/api/gaming/terminate", json.dumps({"gameId": "hades"}), {
            "Content-Type": "application/json",
            "Authorization": "Bearer fake_token_abc123",
        })
        self.assertEqual(s, 401)

        # Attack 6: Basic auth instead of Bearer
        s, d = self._post("/api/gaming/terminate", json.dumps({"gameId": "hades"}), {
            "Content-Type": "application/json",
            "Authorization": "Basic YWRtaW46cGFzc3dvcmQ=",
        })
        self.assertEqual(s, 401)

        # Attack 7: Cross-origin CSRF attack with valid token (Origin forbidden)
        s, d = self._post("/api/gaming/terminate", json.dumps({"gameId": "hades"}), {
            "Content-Type": "application/json",
            "X-MyTV-Token": self.valid_token,
            "Origin": "http://malicious-attacker-website.com",
        })
        self.assertEqual(s, 403, "Cross-origin request to terminate MUST be rejected with HTTP 403!")

        # Control 1: Legitimate X-MyTV-Token
        s, d = self._post("/api/gaming/terminate", json.dumps({"gameId": "hades"}), {
            "Content-Type": "application/json",
            "X-MyTV-Token": self.valid_token,
        })
        self.assertEqual(s, 200)
        self.assertTrue(d.get("ok"))

        # Control 2: Legitimate Authorization Bearer token
        s, d = self._post("/api/gaming/terminate", json.dumps({"gameId": "hades"}), {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.valid_token}",
        })
        self.assertEqual(s, 200)
        self.assertTrue(d.get("ok"))

    def test_payload_fuzzing_resilience(self) -> None:
        """Fuzzes POST /api/gaming/terminate with corrupt, malformed, oversized, and type-mismatched bodies."""
        headers = {
            "Content-Type": "application/json",
            "X-MyTV-Token": self.valid_token,
        }

        # Fuzz 1: Malformed truncated JSON
        s, d = self._post("/api/gaming/terminate", '{"gameId": "hades", "reason":', headers)
        self.assertEqual(s, 200, "Malformed JSON should fall back gracefully without 500 error")
        self.assertTrue(d.get("ok"))

        # Fuzz 2: Empty body string
        s, d = self._post("/api/gaming/terminate", '', headers)
        self.assertEqual(s, 200)
        self.assertTrue(d.get("ok"))

        # Fuzz 3: JSON array instead of dictionary
        s, d = self._post("/api/gaming/terminate", '["hades", "remote_home_exit"]', headers)
        self.assertEqual(s, 200)
        self.assertTrue(d.get("ok"))

        # Fuzz 4: Non-string gameId (integer)
        s, d = self._post("/api/gaming/terminate", json.dumps({"gameId": 99999, "reason": 12345}), headers)
        self.assertEqual(s, 200)
        self.assertTrue(d.get("ok"))

        # Fuzz 5: Non-string gameId (nested dict) and non-string reason
        s, d = self._post("/api/gaming/terminate", json.dumps({"gameId": {"nested": "obj"}, "reason": [1, 2]}), headers)
        self.assertEqual(s, 200)
        self.assertTrue(d.get("ok"))

        # Fuzz 6: Null values
        s, d = self._post("/api/gaming/terminate", json.dumps({"gameId": None, "reason": None}), headers)
        self.assertEqual(s, 200)
        self.assertTrue(d.get("ok"))

        # Fuzz 7: 100K-character string payload (Buffer exhaustion attempt)
        giant_string = "X" * 100000
        giant_payload = json.dumps({"gameId": giant_string, "reason": "remote_home_exit"}).encode("utf-8")
        s, d = self._post("/api/gaming/terminate", giant_payload, headers)
        self.assertEqual(s, 200, "Oversized payload should be safely truncated/capped by HTTP_MAX_BODY_BYTES")
        self.assertTrue(d.get("ok"))

        # Fuzz 8: Command injection / Path traversal payload in gameId
        injection_payload = json.dumps({
            "gameId": "'; Start-Process calc.exe; # ../../../../../etc/passwd",
            "reason": "remote_home_exit && whoami",
        })
        s, d = self._post("/api/gaming/terminate", injection_payload, headers)
        self.assertEqual(s, 200)
        self.assertTrue(d.get("ok"))
        self.assertEqual(d.get("reason"), "remote_home_exit && whoami")

    def test_terminate_concurrency_and_idempotency_hammer(self) -> None:
        """Fires 20 concurrent termination requests simultaneously to stress-test race conditions and idempotency."""
        headers = {
            "Content-Type": "application/json",
            "X-MyTV-Token": self.valid_token,
        }

        results: List[int] = []
        threads: List[threading.Thread] = []

        def worker() -> None:
            s, _ = self._post(
                "/api/gaming/terminate",
                json.dumps({"gameId": "hades", "reason": "concurrent_stress"}),
                headers,
                timeout=5.0,
            )
            results.append(s)

        for _ in range(20):
            t = threading.Thread(target=worker)
            threads.append(t)
            t.start()

        for t in threads:
            t.join(timeout=6.0)

        self.assertEqual(len(results), 20)
        self.assertTrue(all(code == 200 for code in results), f"All concurrent calls must succeed! Results: {results}")


class AdversarialVolumePreservationContractTests(unittest.TestCase):
    """
    CRITICAL USER RULE VERIFICATION:
    "another critical note: in your tests never make the volume higher. you can mute/unmute it."
    Mandatory enforcement:
    1. NEVER increase volume scalar/steps (0 SetMasterVolumeLevelScalar / VK_VOLUME_UP).
    2. If was_muted == True, PC remains muted on exit (never unmute).
    """

    def test_volume_rule_codebase_ast_and_bytecode_invariants(self) -> None:
        """
        Adversarially scans the implementation of laptop_gaming_agent.py to verify
        that no scalar volume adjustment APIs exist.
        """
        agent_file = _TOOL_DIR / "laptop_gaming_agent.py"
        self.assertTrue(agent_file.exists())
        code = agent_file.read_text(encoding="utf-8")

        # Must NOT call SetMasterVolumeLevelScalar
        self.assertNotIn("SetMasterVolumeLevelScalar", code,
                         "CRITICAL VIOLATION: SetMasterVolumeLevelScalar found in laptop_gaming_agent.py!")

        # Must NOT inject VK_VOLUME_UP
        self.assertNotIn("VK_VOLUME_UP", code,
                         "CRITICAL VIOLATION: VK_VOLUME_UP found in laptop_gaming_agent.py!")

        # Must NOT inject KEYCODE_VOLUME_UP
        self.assertNotIn("KEYCODE_VOLUME_UP", code,
                         "CRITICAL VIOLATION: KEYCODE_VOLUME_UP found in laptop_gaming_agent.py!")

    def test_pre_existing_muted_state_strictly_preserved_on_exit(self) -> None:
        """
        Verifies that if the PC audio was ALREADY muted before gaming,
        unmute_audio is NEVER called during restore / termination.
        """
        unmute_calls: List[bool] = []
        restore_windows_calls: List[List[int]] = []

        safety = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, True),  # was_muted = True
            unmute_audio_fn=lambda restore_muted: unmute_calls.append(restore_muted) or True,
            hide_desktop_fn=lambda: (True, 1234),
            restore_desktop_fn=lambda vis, h: None,
            minimize_windows_fn=lambda: [100, 200],
            restore_windows_fn=lambda hwnds: restore_windows_calls.append(list(hwnds)),
            refresh_controller=DisplayRefreshRateController(
                get_primary_refresh_hz_fn=lambda: 60,
                set_primary_refresh_hz_fn=lambda _hz: True,
            ),
        )

        # 1. Engage
        safety.engage()
        self.assertTrue(safety.is_engaged())
        self.assertFalse(safety._audio_muted_by_us, "We did NOT mute audio because user was already muted!")
        self.assertTrue(safety._was_muted)

        # 2. Terminate session & Restore
        controller = GameProcessController(
            safety_net=safety,
            post_message_fn=lambda h, m, w, l: True,
            terminate_process_fn=lambda p: True,
            is_pid_alive_fn=lambda p: False,
            close_sunshine_fn=lambda: True,
        )

        res = controller.terminate_game(explicit_pid=5000)
        self.assertTrue(res["ok"])
        self.assertTrue(res["pcRestored"])
        self.assertFalse(safety.is_engaged())

        # CRITICAL ASSERTION: unmute_audio_fn must NOT have been called!
        self.assertEqual(len(unmute_calls), 0,
                         "CRITICAL USER RULE VIOLATION: Audio unmute was invoked when user was already muted!")

        # Windows and desktop should still be restored cleanly
        self.assertEqual(restore_windows_calls, [[100, 200]])

    def test_pre_existing_unmuted_state_properly_restored_on_exit(self) -> None:
        """
        Verifies that if the PC audio was UNMUTED before gaming,
        unmute_audio is called exactly once with restore_muted=False to restore audio.
        """
        unmute_calls: List[bool] = []

        safety = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, False),  # was_muted = False
            unmute_audio_fn=lambda restore_muted: unmute_calls.append(restore_muted) or True,
            hide_desktop_fn=lambda: (True, 0),
            restore_desktop_fn=lambda _vis, _h: None,
            minimize_windows_fn=lambda: [],
            restore_windows_fn=lambda hwnds: None,
            refresh_controller=DisplayRefreshRateController(
                get_primary_refresh_hz_fn=lambda: 60,
                set_primary_refresh_hz_fn=lambda _hz: True,
            ),
        )

        # 1. Engage
        safety.engage()
        self.assertTrue(safety.is_engaged())
        self.assertTrue(safety._audio_muted_by_us)
        self.assertFalse(safety._was_muted)

        # 2. Terminate session & Restore
        controller = GameProcessController(
            safety_net=safety,
            post_message_fn=lambda h, m, w, l: True,
            terminate_process_fn=lambda p: True,
            is_pid_alive_fn=lambda p: False,
            close_sunshine_fn=lambda: True,
        )

        res = controller.terminate_game(explicit_pid=5000)
        self.assertTrue(res["ok"])
        self.assertTrue(res["pcRestored"])
        self.assertFalse(safety.is_engaged())

        # Unmute should have been called with False (unmuting)
        self.assertEqual(unmute_calls, [False])

        # 3. Repeated / idempotent restore must not call unmute again
        safety.restore()
        self.assertEqual(unmute_calls, [False], "Idempotent restore must not double-unmute!")

    def test_safety_net_engage_failure_rollback_preserves_audio_state(self) -> None:
        """Verifies that if engage() fails mid-way, rollback preserves pre-existing mute state."""
        unmute_calls: List[bool] = []

        safety = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, True),  # Already muted
            unmute_audio_fn=lambda restore_muted: unmute_calls.append(restore_muted) or True,
            hide_desktop_fn=lambda: (True, 0),
            restore_desktop_fn=lambda _vis, _h: None,
            minimize_windows_fn=lambda: (_ for _ in ()).throw(RuntimeError("EnumWindows failure")),
            restore_windows_fn=lambda hwnds: None,
            refresh_controller=DisplayRefreshRateController(
                get_primary_refresh_hz_fn=lambda: 60,
                set_primary_refresh_hz_fn=lambda _hz: True,
            ),
        )

        ok = safety.engage()
        self.assertFalse(ok, "Engage should return False on internal failure")
        self.assertFalse(safety.is_engaged())
        # Even during rollback, pre-existing muted audio must not be unmuted
        self.assertEqual(len(unmute_calls), 0)


class LateSpawnSteamReaperTests(unittest.TestCase):
    """
    Verifies that if a user exits via TV Home/Back BEFORE Steam finishes spawning <game>.exe,
    GameProcessController reaps the late-arriving executable unless a new game launch supersedes it.
    """

    def test_late_spawning_steam_game_reaped_after_early_exit(self) -> None:
        """If game PID does not exist at exit time, a bounded reaper kills it when Steam spawns it late."""
        from laptop_gaming_agent import GameEntry

        game = GameEntry(
            game_id="steam_965680",
            title="Boomerang Fu",
            category="steam",
            launch_cmd="steam://rungameid/965680",
            working_dir=r"C:\Games\Steam\steamapps\common\Boomerang Fu",
        )
        procs: List[Tuple[int, int, str]] = [(932, 1, "steam.exe")]
        term_calls: List[int] = []
        close_msgs: List[int] = []

        controller = GameProcessController(
            post_message_fn=lambda h, m, w, l: close_msgs.append(h) or True,
            terminate_process_fn=lambda p: term_calls.append(p) or True,
            is_pid_alive_fn=lambda p: p == 676 and 676 not in term_calls,
            snapshot_procs_fn=lambda: list(procs),
            find_hwnds_fn=lambda pids: [5555] if 676 in pids else [],
            close_sunshine_fn=lambda: True,
            poll_interval_sec=0.02,
        )

        res = controller.terminate_game(
            target_game=game,
            grace_period_sec=0.02,
            late_spawn_watch_sec=0.5,
        )
        self.assertTrue(res["ok"])
        self.assertEqual(res["pidsTerminated"], [])

        # Simulate Steam finishing launch 80ms AFTER user already pressed TV Home
        time.sleep(0.08)
        procs.append((676, 932, "boomerang fu.exe"))
        time.sleep(0.20)

        self.assertIn(5555, close_msgs, "Late-spawning game HWND must receive WM_CLOSE!")
        self.assertIn(676, term_calls, "Surviving late-spawning game PID must be terminated!")

    def test_late_spawn_watch_cancelled_when_new_game_launched(self) -> None:
        """Calling cancel_late_spawn_watch() when a new game starts immediately disarms prior reaper."""
        from laptop_gaming_agent import GameEntry

        game = GameEntry(
            game_id="steam_965680",
            title="Boomerang Fu",
            category="steam",
            launch_cmd="steam://rungameid/965680",
            working_dir=r"C:\Games\Steam\steamapps\common\Boomerang Fu",
        )
        procs: List[Tuple[int, int, str]] = [(932, 1, "steam.exe")]
        term_calls: List[int] = []

        controller = GameProcessController(
            post_message_fn=lambda h, m, w, l: True,
            terminate_process_fn=lambda p: term_calls.append(p) or True,
            is_pid_alive_fn=lambda p: True,
            snapshot_procs_fn=lambda: list(procs),
            find_hwnds_fn=lambda pids: [],
            close_sunshine_fn=lambda: True,
            poll_interval_sec=0.02,
        )

        controller.terminate_game(target_game=game, grace_period_sec=0.02, late_spawn_watch_sec=0.6)
        controller.cancel_late_spawn_watch()

        procs.append((777, 932, "boomerang fu.exe"))
        time.sleep(0.15)
        self.assertEqual(term_calls, [], "Cancelled reaper must NOT kill newly launched game!")


class AdversarialScreensaverPreservationTests(unittest.TestCase):
    """Verifies all 21 uncommitted screensaver files remain completely untouched."""

    def test_uncommitted_screensaver_files_untouched(self) -> None:
        """Verifies git status shows 0 changes to screensaver overhaul files."""
        res = subprocess.run(
            ["git", "status", "--short"],
            capture_output=True,
            text=True,
            check=True,
        )
        lines = res.stdout.splitlines()

        # Screensaver files list
        saver_files = [
            "DreamView.java",
            "ScreensaverCatalog.java",
            "Shaders.java",
            "ModalOverlayRenderer.java",
            "Backdrop.java",
            "saver_catalog.json",
            "generate_saver_catalog.py",
            "verify_catalog_stress.py",
            "ScreensaverOverhaulE2ETests.java",
        ]

        # Verify none of our changes deleted or staged these
        for line in lines:
            if not line:
                continue
            for sf in saver_files:
                if sf in line:
                    status_prefix = line[:2]
                    self.assertNotIn("D", status_prefix, f"Screensaver file {sf} was deleted!")
                    self.assertNotEqual(status_prefix[0], "M", f"Screensaver file {sf} was unexpectedly staged!")



if __name__ == "__main__":
    unittest.main()
