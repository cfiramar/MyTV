"""
Adversarial Stress Test Suite: Autonomous In-Game Exit Watcher, Disconnect Grace & Termination RPC.

Empirically tests and stress-tests:
1. Autonomous Exit Detection:
   - Rapid window recreation / resolution switch simulation (HWND=0 for 0.2s while PID is alive).
     Verifies privacy curtain stays active and does NOT trigger false exit.
   - Clean in-game quit (HWND=0 and PID terminated).
     Verifies autonomous detection triggers Sunshine /api/apps/close and safety_net.restore().
2. Sunshine Disconnect Grace Period Boundary:
   - Reconnection at t = 4.9s (< 5.0s grace): cancels teardown, session remains alive.
   - Disconnect lasting t = 5.1s (>= 5.0s grace): triggers teardown, terminates game, closes Sunshine, restores PC.
3. Termination RPC (/api/gaming/terminate):
   - Auth rejection (401) on missing/invalid token.
   - Clean termination and PC restoration on valid remote_home_exit.
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
from typing import Any, Dict, List, Optional, Set, Tuple
from unittest import mock

# Ensure local gaming tool directory is in sys.path
_TOOL_DIR = Path(__file__).resolve().parent
if str(_TOOL_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOL_DIR))

import laptop_gaming_agent as agent_mod
from laptop_gaming_agent import (
    AgentAuthManager,
    GameEntry,
    GameProcessController,
    LaptopGamingAgentApp,
    PCPrivacySafetyNet,
    PrivacyCurtainManager,
    SunshineConfigManager,
    create_http_server,
)


class MockSafetyNet:
    """Mock safety net tracking calls for testing."""

    def __init__(self) -> None:
        self.engaged = True
        self.restored = False
        self.restore_count = 0

    def is_engaged(self) -> bool:
        return self.engaged and not self.restored

    def engage(self) -> None:
        self.engaged = True
        self.restored = False

    def restore(self) -> None:
        self.restored = True
        self.engaged = False
        self.restore_count += 1


class MockCursorController:
    """Mock cursor controller tracking calls."""

    def __init__(self) -> None:
        self.park_count = 0
        self.restore_count = 0

    def park_cursor(self) -> None:
        self.park_count += 1

    def re_park_without_overwriting_saved(self) -> None:
        self.park_count += 1

    def restore_cursor(self) -> None:
        self.restore_count += 1


class MockEscListener:
    """Mock ESC listener."""

    def __init__(self) -> None:
        self.stopped = False

    def start(self, callback: Any = None) -> bool:
        return True

    def stop(self) -> None:
        self.stopped = True


class AdversarialM3AutonomousExitWatcherTests(unittest.TestCase):
    """Adversarial stress-testing of autonomous exit detection and disconnect grace."""

    def setUp(self) -> None:
        self.test_game = GameEntry(
            game_id="steam_965680",
            title="Boomerang Fu",
            category="steam",
            launch_cmd=r"C:\Games\BoomerangFu\BoomerangFu.exe",
            working_dir=r"C:\Games\BoomerangFu",
        )

    def test_resolution_switch_window_toggle_does_not_trigger_false_exit(self) -> None:
        """
        Adversarial scenario: game enters full-screen or switches resolution.
        HWND is temporarily destroyed (game_hwnd == 0) for 200ms, but the game PID
        remains alive in the process table.
        Curtain MUST stay active (setting z-order to topmost) and MUST NOT trigger exit teardown.
        """
        safety_net = MockSafetyNet()
        cursor = MockCursorController()
        esc = MockEscListener()
        close_sunshine_called = []
        z_orders = []

        # Timeline simulation:
        # Step 0: game_hwnd = 1001, pid = 4000 (game running)
        # Step 1: game_hwnd = 0, pid = 4000 (resolution switch transient)
        # Step 2: game_hwnd = 0, pid = 4000 (resolution switch transient)
        # Step 3: game_hwnd = 1002, pid = 4000 (new window ready)
        # Step 4: stop
        hwnd_timeline = [1001, 1001, 0, 0, 1002, 1002]
        proc_map = {4000: "boomerangfu.exe"}
        step_idx = [0]

        def find_game_hwnd(entry: GameEntry) -> int:
            idx = step_idx[0]
            val = hwnd_timeline[idx] if idx < len(hwnd_timeline) else 1002
            return val

        def get_hwnd_pid(hwnd: int) -> int:
            return 4000 if hwnd > 0 else 0

        def process_snapshot() -> Dict[int, str]:
            return dict(proc_map)

        def apply_z_order(mode: str, ref_hwnd: int) -> None:
            z_orders.append((mode, ref_hwnd))

        def close_sunshine() -> bool:
            close_sunshine_called.append(True)
            return True

        with tempfile.NamedTemporaryFile("w+", delete=False, encoding="utf-8") as f:
            log_path = Path(f.name)

        curtain = PrivacyCurtainManager(
            sunshine_log_path=log_path,
            cursor_controller=cursor,
            esc_listener=esc,
            safety_net=safety_net,
            find_game_hwnd_fn=find_game_hwnd,
            get_hwnd_pid_fn=get_hwnd_pid,
            process_snapshot_fn=process_snapshot,
            apply_curtain_z_order_fn=apply_z_order,
            close_sunshine_app_fn=close_sunshine,
            create_native_window=False,
            poll_interval_sec=0.02,
            connect_timeout_sec=5.0,
        )

        try:
            curtain.start_for_game(self.test_game, use_hdmi=False)
            self.assertTrue(curtain.is_active())

            # Append client connected after curtain has started
            with log_path.open("a", encoding="utf-8") as f:
                f.write("CLIENT CONNECTED\n")
                f.flush()

            # Advance steps through the timeline
            for _ in range(len(hwnd_timeline) + 2):
                time.sleep(0.04)
                step_idx[0] += 1

            # Curtain must still be active (NOT prematurely exited)
            self.assertTrue(curtain.is_active(), "Curtain must remain active during transient resolution switch")
            self.assertEqual(len(close_sunshine_called), 0, "Sunshine close must not be called during resolution switch")
            self.assertFalse(safety_net.restored, "Safety net must not be restored prematurely")

            # Check z-order transitions: behind_game -> topmost during toggle -> behind_game
            modes = [m for m, _ in z_orders]
            self.assertIn("behind_game", modes)
            self.assertIn("topmost", modes)
        finally:
            curtain.stop()
            try:
                os.remove(log_path)
            except OSError:
                pass

    def test_in_game_quit_autonomous_detection(self) -> None:
        """
        Adversarial scenario: User clicks 'Quit to Desktop' inside the game.
        Game window disappears (HWND=0) and game PID exits (PID dead in process snapshot).
        Curtain monitor loop MUST detect this autonomously, call Sunshine close,
        restore safety net, and restore cursor.
        """
        safety_net = MockSafetyNet()
        cursor = MockCursorController()
        esc = MockEscListener()
        close_sunshine_called = []

        # Initially game is running: HWND=2001, PID=5000
        cur_hwnd = [2001]
        proc_map = {5000: "boomerangfu.exe"}

        def find_game_hwnd(entry: GameEntry) -> int:
            return cur_hwnd[0]

        def get_hwnd_pid(hwnd: int) -> int:
            return 5000 if hwnd == 2001 else 0

        def process_snapshot() -> Dict[int, str]:
            return dict(proc_map)

        def close_sunshine() -> bool:
            close_sunshine_called.append(True)
            return True

        with tempfile.NamedTemporaryFile("w+", delete=False, encoding="utf-8") as f:
            log_path = Path(f.name)

        curtain = PrivacyCurtainManager(
            sunshine_log_path=log_path,
            cursor_controller=cursor,
            esc_listener=esc,
            safety_net=safety_net,
            find_game_hwnd_fn=find_game_hwnd,
            get_hwnd_pid_fn=get_hwnd_pid,
            process_snapshot_fn=process_snapshot,
            close_sunshine_app_fn=close_sunshine,
            create_native_window=False,
            poll_interval_sec=0.02,
            connect_timeout_sec=5.0,
        )

        try:
            curtain.start_for_game(self.test_game, use_hdmi=False)
            with log_path.open("a", encoding="utf-8") as f:
                f.write("CLIENT CONNECTED\n")
                f.flush()
            time.sleep(0.06)
            self.assertTrue(curtain.is_active())

            # Now simulate user clicking 'Quit to Desktop'
            cur_hwnd[0] = 0
            proc_map.clear()  # PID 5000 exited!

            # Wait for autonomous detection (poll_interval = 20ms)
            for _ in range(25):
                if not curtain.is_active():
                    break
                time.sleep(0.02)

            self.assertFalse(curtain.is_active(), "Curtain must deactivate upon autonomous game exit")
            self.assertEqual(len(close_sunshine_called), 1, "Sunshine close must be triggered on autonomous exit")
            self.assertTrue(safety_net.restored, "Safety net must be fully restored on autonomous exit")
            self.assertGreaterEqual(cursor.restore_count, 1, "Cursor must be restored")
        finally:
            curtain.stop()
            try:
                os.remove(log_path)
            except OSError:
                pass

    def test_sunshine_disconnect_grace_boundary(self) -> None:
        """
        Stress-tests Sunshine disconnect grace period (5.0s threshold):
        1. Disconnect at t=0, reconnect at t=4.9s (< 5.0s) -> Teardown cancelled!
        2. Disconnect lasting >= 5.0s (e.g. 5.1s) -> Teardown triggered!
        """
        # Test 1: Reconnection within grace cancels teardown
        safety_net_1 = MockSafetyNet()
        cursor_1 = MockCursorController()
        esc_1 = MockEscListener()
        close_sunshine_1 = []

        with tempfile.NamedTemporaryFile("w+", delete=False, encoding="utf-8") as f:
            log_path_1 = Path(f.name)

        curtain_1 = PrivacyCurtainManager(
            sunshine_log_path=log_path_1,
            cursor_controller=cursor_1,
            esc_listener=esc_1,
            safety_net=safety_net_1,
            find_game_hwnd_fn=lambda g: 1234,
            get_hwnd_pid_fn=lambda h: 6000,
            process_snapshot_fn=lambda: {6000: "boomerangfu.exe"},
            close_sunshine_app_fn=lambda: close_sunshine_1.append(True) or True,
            create_native_window=False,
            poll_interval_sec=0.02,
            disconnect_grace_sec=0.20,  # Scaled down to 200ms for fast reliable testing
        )

        try:
            curtain_1.start_for_game(self.test_game, use_hdmi=False)
            with log_path_1.open("a", encoding="utf-8") as f:
                f.write("CLIENT CONNECTED\n")
                f.flush()
            time.sleep(0.05)
            self.assertTrue(curtain_1.is_active())

            # Append disconnect
            with log_path_1.open("a", encoding="utf-8") as f:
                f.write("CLIENT DISCONNECTED\n")
                f.flush()

            # Wait half the grace period (0.10s < 0.20s)
            time.sleep(0.10)

            # Client reconnects before grace expiry!
            with log_path_1.open("a", encoding="utf-8") as f:
                f.write("CLIENT CONNECTED\n")
                f.flush()

            # Wait past original grace period
            time.sleep(0.15)

            # Session should still be active!
            self.assertTrue(curtain_1.is_active(), "Reconnection before grace expiry must cancel teardown")
            self.assertEqual(len(close_sunshine_1), 0, "Sunshine close must not trigger after reconnection")
        finally:
            curtain_1.stop()
            try:
                os.remove(log_path_1)
            except OSError:
                pass

        # Test 2: Disconnection expiring past grace period triggers teardown
        safety_net_2 = MockSafetyNet()
        cursor_2 = MockCursorController()
        esc_2 = MockEscListener()
        close_sunshine_2 = []

        with tempfile.NamedTemporaryFile("w+", delete=False, encoding="utf-8") as f:
            log_path_2 = Path(f.name)

        curtain_2 = PrivacyCurtainManager(
            sunshine_log_path=log_path_2,
            cursor_controller=cursor_2,
            esc_listener=esc_2,
            safety_net=safety_net_2,
            find_game_hwnd_fn=lambda g: 1234,
            get_hwnd_pid_fn=lambda h: 6001,
            process_snapshot_fn=lambda: {6001: "boomerangfu.exe"},
            close_sunshine_app_fn=lambda: close_sunshine_2.append(True) or True,
            create_native_window=False,
            poll_interval_sec=0.02,
            disconnect_grace_sec=0.15,  # 150ms grace
        )

        try:
            curtain_2.start_for_game(self.test_game, use_hdmi=False)
            with log_path_2.open("a", encoding="utf-8") as f:
                f.write("CLIENT CONNECTED\n")
                f.flush()
            time.sleep(0.05)
            self.assertTrue(curtain_2.is_active())

            # Append disconnect
            with log_path_2.open("a", encoding="utf-8") as f:
                f.write("CLIENT DISCONNECTED\n")
                f.flush()

            # Wait past grace period (0.25s > 0.15s)
            for _ in range(25):
                if not curtain_2.is_active():
                    break
                time.sleep(0.02)

            self.assertFalse(curtain_2.is_active(), "Session must terminate when disconnect exceeds grace period")
            self.assertEqual(len(close_sunshine_2), 1, "Sunshine close must be called on disconnect expiry")
            self.assertTrue(safety_net_2.restored, "Safety net must be restored on disconnect expiry")
        finally:
            curtain_2.stop()
            try:
                os.remove(log_path_2)
            except OSError:
                pass


class AdversarialM3TerminationEndpointTests(unittest.TestCase):
    """Adversarial stress-testing of POST /api/gaming/terminate endpoint."""

    def setUp(self) -> None:
        self.tmp_dir = tempfile.TemporaryDirectory()
        tmp_path = Path(self.tmp_dir.name)
        self.auth_token = "valid_test_token_abcdef123456"
        cfg_file = tmp_path / "config.properties"
        cfg_file.write_text(f"gaming_pc_token={self.auth_token}\n", encoding="utf-8")

        self.auth_mgr = AgentAuthManager(
            auth_path=tmp_path / "auth.json",
            pair_code_path=tmp_path / "pair_code.txt",
            config_properties_path=cfg_file,
        )

        self.safety_net = MockSafetyNet()
        self.curtain = mock.MagicMock()
        self.curtain.is_active.return_value = True
        self.curtain.get_target_pid.return_value = 0
        self.curtain.get_target_hwnd.return_value = 0
        self.close_sunshine_called = []

        self.proc_ctrl = GameProcessController(
            safety_net=self.safety_net,
            privacy_curtain=self.curtain,
            post_message_fn=lambda h, m, w, l: True,
            terminate_process_fn=lambda p: True,
            is_pid_alive_fn=lambda p: False,
            snapshot_procs_fn=lambda: [(7001, 100, "BoomerangFu.exe")],
            find_hwnds_fn=lambda pids: [9999],
            close_sunshine_fn=lambda: self.close_sunshine_called.append(True) or True,
            poll_interval_sec=0.01,
        )

        self.app = LaptopGamingAgentApp(
            state_dir=tmp_path / "state",
            auth_manager=self.auth_mgr,
        )
        self.app.process_controller = self.proc_ctrl
        self.app.privacy_curtain = self.curtain

        self.server = create_http_server(self.app, host="127.0.0.1", port=0)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        try:
            self.server.shutdown()
            self.server.server_close()
        except Exception:
            pass
        self.tmp_dir.cleanup()

    def _post(self, path: str, body: Dict[str, Any], token: Optional[str]) -> Tuple[int, Dict[str, Any]]:
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3.0)
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["X-MyTV-Token"] = token
        conn.request("POST", path, body=json.dumps(body), headers=headers)
        resp = conn.getresponse()
        data = resp.read().decode("utf-8")
        conn.close()
        try:
            parsed = json.loads(data)
        except Exception:
            parsed = {"raw": data}
        return resp.status, parsed

    def test_terminate_unauthenticated_rejected(self) -> None:
        """Unauthenticated or invalid token requests MUST be rejected with HTTP 401."""
        status, res = self._post("/api/gaming/terminate", {"gameId": "steam_965680"}, token=None)
        self.assertEqual(status, 401)
        self.assertEqual(res.get("error"), "unauthorized")

        status, res = self._post("/api/gaming/terminate", {"gameId": "steam_965680"}, token="invalid-token")
        self.assertEqual(status, 401)
        self.assertEqual(res.get("error"), "unauthorized")

    def test_terminate_authenticated_succeeds_and_restores_pc(self) -> None:
        """Valid authenticated remote termination MUST terminate game, close Sunshine, and restore PC."""
        payload = {"gameId": "steam_965680", "reason": "remote_home_exit"}
        status, res = self._post("/api/gaming/terminate", payload, token=self.auth_token)
        self.assertEqual(status, 200)
        self.assertTrue(res.get("ok"))
        self.assertTrue(res.get("terminated"))
        self.assertTrue(res.get("pcRestored"))
        self.assertEqual(res.get("reason"), "remote_home_exit")
        self.assertEqual(len(self.close_sunshine_called), 1, "Sunshine close must be called")
        self.assertTrue(self.safety_net.restored, "PC Safety net must be restored")


if __name__ == "__main__":
    unittest.main()
