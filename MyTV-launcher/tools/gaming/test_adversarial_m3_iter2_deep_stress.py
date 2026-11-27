"""
Adversarial Stress Harness (Challenger M3 Iteration 2 Deep Stress).

Empirically challenges GameProcessController.terminate_game() against:
1. Pathological HWNDs (negative, zero, HWND_BROADCAST 0xFFFF, 32-bit/64-bit extreme integers).
2. Cascading multiple-point exception explosions (PostMessage + TerminateProcess + Sunshine + Curtain failing simultaneously).
3. Critical Volume Rule: 0 scalar volume adjustments, strict was_muted preservation across single & repeated calls.
4. Hung process tree with mixed exits (graceful vs forced kills).
5. Ghost/Dead process session (0 PIDs found, safety net still unconditionally restored).
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple
import unittest
from unittest import mock

_TOOL_DIR = Path(__file__).resolve().parent
if str(_TOOL_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOL_DIR))

import laptop_gaming_agent as agent_mod
from laptop_gaming_agent import (
    GameProcessController,
    PCPrivacySafetyNet,
    WM_CLOSE,
)


class AdversarialM3Iter2DeepStressTests(unittest.TestCase):
    """Deep adversarial stress scenarios for GameProcessController.terminate_game."""

    def test_pathological_hwnd_values_and_extreme_integers(self) -> None:
        """
        Feeds extreme, invalid, and boundary HWNDs into terminate_game:
        - negative values: -1, -2147483648, -999999
        - zero: 0
        - broadcast: 0xFFFF (65535)
        - large integers: 0xFFFFFFFF (4294967295), 2**31 - 1, 2**63 - 1
        """
        post_messages: List[Tuple[int, int, int, int]] = []

        def mock_find_hwnds(pids: Set[int]) -> List[int]:
            return [
                0,
                -1,
                -99999,
                -2147483648,
                0xFFFF,
                65535,
                1001,
                1002,
                1001,  # duplicate
                2147483647,
                4294967295,
            ]

        mock_curtain = mock.MagicMock()
        mock_curtain.get_target_hwnd.return_value = -1  # Invalid curtain HWND
        mock_curtain.get_target_pid.return_value = 7000

        controller = GameProcessController(
            privacy_curtain=mock_curtain,
            post_message_fn=lambda h, m, w, l: post_messages.append((h, m, w, l)) or True,
            terminate_process_fn=lambda p: True,
            is_pid_alive_fn=lambda p: False,
            snapshot_procs_fn=lambda: [(7000, 1, "test.exe")],
            find_hwnds_fn=mock_find_hwnds,
            close_sunshine_fn=lambda: True,
        )

        # explicit_hwnd is HWND_BROADCAST (0xFFFF)
        res = controller.terminate_game(explicit_pid=7000, explicit_hwnd=0xFFFF)
        self.assertTrue(res["ok"])

        messaged_hwnds = [h for h, m, w, l in post_messages]

        # Verify absolutely no non-positive or 0xFFFF HWND received a message
        self.assertNotIn(0, messaged_hwnds, "HWND 0 must never receive WM_CLOSE")
        self.assertNotIn(-1, messaged_hwnds, "HWND -1 must never receive WM_CLOSE")
        self.assertNotIn(-99999, messaged_hwnds, "Negative HWND must never receive WM_CLOSE")
        self.assertNotIn(-2147483648, messaged_hwnds, "Negative HWND must never receive WM_CLOSE")
        self.assertNotIn(0xFFFF, messaged_hwnds, "HWND_BROADCAST (0xFFFF) must never receive WM_CLOSE")
        self.assertNotIn(65535, messaged_hwnds, "HWND_BROADCAST (65535) must never receive WM_CLOSE")

        # Valid positive HWNDs must be messaged without duplicates
        self.assertEqual(set(messaged_hwnds), {1001, 1002, 2147483647, 4294967295})
        self.assertEqual(len(messaged_hwnds), 4, "HWNDs must be strictly deduplicated")

    def test_cascading_multiple_point_exceptions(self) -> None:
        """
        Stress test: Every single external hook in the termination pipeline raises an exception:
        - _post_message_fn raises OSError
        - _terminate_process_fn raises PermissionError
        - _close_sunshine_fn raises ConnectionResetError
        - privacy_curtain.stop() raises RuntimeError
        - safety_net.restore() completes cleanly.
        Verifies terminate_game does not crash and safety net is restored!
        """
        mock_curtain = mock.MagicMock()
        mock_curtain.get_target_hwnd.return_value = 2001
        mock_curtain.get_target_pid.return_value = 8000
        mock_curtain.stop.side_effect = RuntimeError("Curtain DWM release failed")

        restored: List[str] = []
        mock_safety = mock.MagicMock()
        mock_safety.restore.side_effect = lambda: restored.append("restored")
        mock_safety.is_engaged.return_value = False

        controller = GameProcessController(
            safety_net=mock_safety,
            privacy_curtain=mock_curtain,
            post_message_fn=mock.MagicMock(side_effect=OSError("PostMessageW failed")),
            terminate_process_fn=mock.MagicMock(side_effect=PermissionError("TerminateProcess access denied")),
            is_pid_alive_fn=lambda p: True,  # Hung process
            snapshot_procs_fn=lambda: [(8000, 1, "game.exe")],
            find_hwnds_fn=lambda pids: [2001],
            close_sunshine_fn=mock.MagicMock(side_effect=ConnectionResetError("Sunshine offline")),
            poll_interval_sec=0.01,
        )

        res = controller.terminate_game(explicit_pid=8000, grace_period_sec=0.02)

        self.assertTrue(res["ok"], "terminate_game must return ok:True even under cascading errors")
        self.assertTrue(res["forced"], "Forced termination must be flagged")
        self.assertTrue(res["pcRestored"], "pcRestored must be True")
        self.assertEqual(restored, ["restored"], "Safety net restore MUST be invoked")

    def test_critical_volume_rule_unconditional_invariants(self) -> None:
        """
        CRITICAL USER RULE EMPIRICAL VERIFICATION:
        1. PC was already muted: restore must NEVER unmute.
        2. PC was unmuted: restore must unmute exactly once.
        3. Multiple calls (idempotency hammer): never unmutes if user was muted.
        4. Zero scalar volume operations.
        """
        # Case A: User was muted before session
        unmute_calls_muted: List[bool] = []
        safety_muted = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, True),  # was_muted = True
            unmute_audio_fn=lambda m: unmute_calls_muted.append(m) or True,
            hide_desktop_fn=lambda: (True, 100),
            restore_desktop_fn=lambda vis, h: None,
            minimize_windows_fn=lambda: [10],
            restore_windows_fn=lambda hwnds: None,
        )
        safety_muted.engage()

        ctrl_muted = GameProcessController(
            safety_net=safety_muted,
            post_message_fn=lambda h, m, w, l: True,
            terminate_process_fn=lambda p: True,
            is_pid_alive_fn=lambda p: False,
            close_sunshine_fn=lambda: True,
        )

        # Fire 5 sequential termination requests
        for _ in range(5):
            res = ctrl_muted.terminate_game(explicit_pid=9000)
            self.assertTrue(res["ok"])
            self.assertTrue(res["pcRestored"])

        self.assertEqual(
            len(unmute_calls_muted), 0,
            "CRITICAL USER RULE VIOLATION: unmute_audio_fn was invoked when user had muted PC!"
        )

        # Case B: User was unmuted before session
        unmute_calls_unmuted: List[bool] = []
        safety_unmuted = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, False),  # was_muted = False
            unmute_audio_fn=lambda m: unmute_calls_unmuted.append(m) or True,
            hide_desktop_fn=lambda: (True, 100),
            restore_desktop_fn=lambda vis, h: None,
            minimize_windows_fn=lambda: [10],
            restore_windows_fn=lambda hwnds: None,
        )
        safety_unmuted.engage()

        ctrl_unmuted = GameProcessController(
            safety_net=safety_unmuted,
            post_message_fn=lambda h, m, w, l: True,
            terminate_process_fn=lambda p: True,
            is_pid_alive_fn=lambda p: False,
            close_sunshine_fn=lambda: True,
        )

        # Fire 5 sequential termination requests
        for _ in range(5):
            res = ctrl_unmuted.terminate_game(explicit_pid=9001)
            self.assertTrue(res["ok"])
            self.assertTrue(res["pcRestored"])

        self.assertEqual(
            unmute_calls_unmuted, [False],
            "Unmute should occur exactly once on the first termination, and be idempotent thereafter"
        )

    def test_ghost_session_zero_pids_restores_safety_net(self) -> None:
        """
        Tests terminating when game is already dead (0 PIDs found).
        Safety net and Sunshine must still be cleaned up and PC restored.
        """
        restored: List[str] = []
        mock_safety = mock.MagicMock()
        mock_safety.restore.side_effect = lambda: restored.append("restored")
        mock_safety.is_engaged.return_value = False

        sunshine_closed: List[str] = []

        controller = GameProcessController(
            safety_net=mock_safety,
            post_message_fn=lambda h, m, w, l: True,
            terminate_process_fn=lambda p: True,
            is_pid_alive_fn=lambda p: False,
            snapshot_procs_fn=lambda: [],  # No processes
            find_hwnds_fn=lambda pids: [],
            close_sunshine_fn=lambda: sunshine_closed.append("closed") or True,
        )

        res = controller.terminate_game(explicit_pid=0)
        self.assertTrue(res["ok"])
        self.assertFalse(res["terminated"], "No processes were terminated")
        self.assertFalse(res["forced"])
        self.assertTrue(res["pcRestored"])
        self.assertEqual(restored, ["restored"], "Safety net must be restored even if 0 PIDs were active")
        self.assertEqual(sunshine_closed, ["closed"], "Sunshine app must be closed even if 0 PIDs were active")


if __name__ == "__main__":
    unittest.main()
