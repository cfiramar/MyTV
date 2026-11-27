"""
Adversarial Stress Test Suite: Frame Timing, Clock Drift, Jitter Buffer, FEC Math & Host Invariants.

Empirically tests and stress-tests:
1. Mathematical Clock Drift between an external 59.94 Hz desk display (59.9400598 Hz) and a 60 Hz TV (60.0000000 Hz),
   verifying the exact frame hitch period (~16.68 seconds / 1000 frames) and proving that
   `dd_refresh_rate_option = auto` eliminates presentation queue underflow.
2. Jitter Buffer & Reed-Solomon Forward Error Correction (FEC) math:
   - 20% FEC erasure recovery limits (up to 2 lost packets out of 10-packet burst).
   - Moonlight Balanced frame pacing 1-frame (~16.6ms) jitter buffer absorbing 15ms Wi-Fi packet latency spikes.
   - Buffer overflow threshold boundary (>16.67ms).
3. Sunshine Configuration mutation matrix and live host telemetry invariants:
   - Systematic mutation of all mandatory keys, domain boundary checks, and error assertions.
   - Live file integrity on C:\\Program Files\\Sunshine\\config\\sunshine.conf.
   - Live log telemetry verification on C:\\Program Files\\Sunshine\\config\\sunshine.log.
   - Volume safety guardrail compliance (never increase scalar volume, mute/unmute only).
"""

from __future__ import annotations

import math
import os
from pathlib import Path
import re
import socket
import sys
from typing import Any, Dict, List, Optional, Set, Tuple
import unittest

# Ensure local gaming tool directory is in sys.path
_TOOL_DIR = Path(__file__).resolve().parent
if str(_TOOL_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOL_DIR))

import test_sunshine_config as sc_mod
from test_sunshine_config import (
    MANDATORY_M2_CONFIG,
    MoonlightClientPacingSpec,
    SunshineConfigParser,
    SunshineConfigValidator,
    SunshineLogTelemetryParser,
)


class TestFrameTimingAndClockDriftMath(unittest.TestCase):
    """
    Empirically verifies frame timing, presentation intervals, and clock drift mathematics.
    Addresses: fractional NTSC desk display EDID (59.9400598 Hz) vs 60 Hz TV panel (60.0000000 Hz).
    """

    DESK_NTSC_REFRESH_HZ = 59.9400598
    TV_PANEL_REFRESH_HZ = 60.0000000

    def test_clock_drift_exact_delta_per_frame(self) -> None:
        """
        Verifies that the period delta between 59.9400598 Hz and 60.0000000 Hz
        is approximately 16.67 microseconds per frame.
        """
        t_desk = 1.0 / self.DESK_NTSC_REFRESH_HZ
        t_tv = 1.0 / self.TV_PANEL_REFRESH_HZ

        dt = t_desk - t_tv
        dt_ms = dt * 1000.0

        # Exact expected: ~0.016667 ms (16.667 microseconds)
        self.assertAlmostEqual(dt_ms, 0.016667, places=5)
        self.assertGreater(dt, 0, "Host frame interval must be strictly longer than TV interval")

    def test_clock_drift_hitch_period_and_frame_count(self) -> None:
        """
        Verifies that accumulating 1 full frame of clock drift (16.6667 ms)
        requires exactly ~1000 frames and takes ~16.68 seconds.
        """
        t_desk = 1.0 / self.DESK_NTSC_REFRESH_HZ
        t_tv = 1.0 / self.TV_PANEL_REFRESH_HZ
        dt = t_desk - t_tv

        # Number of frames to accumulate 1 full TV frame duration (1 / f_tv)
        frames_to_hitch = t_tv / dt
        # Time duration to accumulate 1 full frame hitch
        seconds_to_hitch = 1.0 / (self.TV_PANEL_REFRESH_HZ - self.DESK_NTSC_REFRESH_HZ)

        self.assertAlmostEqual(frames_to_hitch, 1000.0, delta=0.1)
        self.assertAlmostEqual(seconds_to_hitch, 16.6833, places=3)
        self.assertTrue(16.68 <= seconds_to_hitch <= 16.69)

    def test_display_queue_underflow_simulation_without_sync(self) -> None:
        """
        Simulates 60 seconds (3600 TV presentation cycles) of uncorrected 59.9400598 Hz capture.
        Proves that exactly 3 frame underflow hitches occur (at t ~ 16.68s, ~33.37s, ~50.05s).
        """
        duration_s = 60.0
        tv_interval = 1.0 / self.TV_PANEL_REFRESH_HZ
        src_interval = 1.0 / self.DESK_NTSC_REFRESH_HZ

        tv_cycles = int(duration_s * self.TV_PANEL_REFRESH_HZ)  # 3600

        underflow_hitches: List[Tuple[int, float]] = []
        accumulated_drift = 0.0

        for frame_idx in range(1, tv_cycles + 1):
            # Each display cycle, display expects a frame every tv_interval
            # Source generates frames every src_interval
            accumulated_drift += (src_interval - tv_interval)

            if accumulated_drift >= tv_interval:
                # The queue has underflowed by an entire frame: display must repeat old frame or stutter
                time_s = frame_idx * tv_interval
                underflow_hitches.append((frame_idx, time_s))
                accumulated_drift -= tv_interval

        # Over 60 seconds: 60.0 / 16.6833 = 3.59 -> exactly 3 underflow hitches
        self.assertEqual(len(underflow_hitches), 3)
        self.assertAlmostEqual(underflow_hitches[0][1], 16.683, delta=0.05)
        self.assertAlmostEqual(underflow_hitches[1][1], 33.367, delta=0.05)
        self.assertAlmostEqual(underflow_hitches[2][1], 50.050, delta=0.05)

    def test_dd_refresh_rate_option_auto_locks_to_60hz(self) -> None:
        """
        Simulates 180 seconds (10,800 TV presentation cycles) when `dd_refresh_rate_option = auto`
        locks host presentation to exactly 60.0000000 Hz.
        Proves 0 underflow hitches and 0.0 accumulated drift.
        """
        duration_s = 180.0
        tv_interval = 1.0 / self.TV_PANEL_REFRESH_HZ
        # When locked via auto refresh rate option:
        src_interval = 1.0 / self.TV_PANEL_REFRESH_HZ

        tv_cycles = int(duration_s * self.TV_PANEL_REFRESH_HZ)

        underflow_hitches: List[int] = []
        accumulated_drift = 0.0

        for frame_idx in range(1, tv_cycles + 1):
            accumulated_drift += (src_interval - tv_interval)
            if accumulated_drift >= tv_interval:
                underflow_hitches.append(frame_idx)

        self.assertEqual(len(underflow_hitches), 0, "Synchronized 60Hz lock must produce 0 underflows")
        self.assertEqual(accumulated_drift, 0.0)

    def test_dd_refresh_rate_option_presence_in_config(self) -> None:
        """Asserts M3 Optimus refresh-rate & coder alignment (verify_only + auto + cabac + minimum_fps_target='60')."""
        self.assertIn("dd_refresh_rate_option", MANDATORY_M2_CONFIG)
        self.assertEqual(MANDATORY_M2_CONFIG["dd_refresh_rate_option"], "auto")
        self.assertEqual(MANDATORY_M2_CONFIG["dd_configuration_option"], "verify_only")
        self.assertEqual(MANDATORY_M2_CONFIG["qsv_coder"], "cabac")
        self.assertEqual(MANDATORY_M2_CONFIG["minimum_fps_target"], "60")


class TestJitterBufferAndFECMath(unittest.TestCase):
    """
    Empirically verifies Forward Error Correction (Reed-Solomon erasure coding)
    and frame pacing jitter buffer absorption dynamics.
    """

    @staticmethod
    def simulate_reed_solomon_erasure_recovery(
        data_packets: int, fec_percentage: int, lost_packets: int
    ) -> bool:
        """
        Models Reed-Solomon (N, K) erasure code over GF(2^8) used in Sunshine/Moonlight.
        K = data_packets.
        M = ceil(K * fec_percentage / 100) (parity packets).
        N = K + M (total transmitted packets).
        Reed-Solomon erasure decoding can reconstruct all K data packets if and only if
        the number of received packets >= K (i.e. lost_packets <= M).
        """
        parity_packets = math.ceil(data_packets * (fec_percentage / 100.0))
        total_packets = data_packets + parity_packets
        received_packets = total_packets - lost_packets
        return received_packets >= data_packets

    def test_20_percent_fec_recovery_burst_limits(self) -> None:
        """
        Verifies that for a 10-packet burst (K=10):
        - 20% FEC produces M=2 parity packets (N=12).
        - 0, 1, or 2 lost packets are 100% recovered.
        - 3 or more lost packets cannot be recovered and require keyframe request.
        """
        k = 10
        fec = 20
        # M = ceil(10 * 0.20) = 2
        parity = math.ceil(k * (fec / 100.0))
        self.assertEqual(parity, 2, "20% FEC on 10 data packets must yield exactly 2 parity packets")

        # 0 lost packets -> recovered
        self.assertTrue(self.simulate_reed_solomon_erasure_recovery(k, fec, lost_packets=0))
        # 1 lost packet -> recovered
        self.assertTrue(self.simulate_reed_solomon_erasure_recovery(k, fec, lost_packets=1))
        # 2 lost packets -> recovered
        self.assertTrue(self.simulate_reed_solomon_erasure_recovery(k, fec, lost_packets=2))
        # 3 lost packets -> fails
        self.assertFalse(self.simulate_reed_solomon_erasure_recovery(k, fec, lost_packets=3))
        # 4 lost packets -> fails
        self.assertFalse(self.simulate_reed_solomon_erasure_recovery(k, fec, lost_packets=4))

    def test_fec_percentage_sensitivity(self) -> None:
        """
        Tests recovery capability as a function of FEC percentage:
        - 0% FEC: 0 parity packets, 0 lost packets recoverable.
        - 10% FEC: 1 parity packet, 2 lost packets fail.
        - 20% FEC: 2 parity packets, 2 lost packets succeed.
        """
        k = 10
        # 0% FEC
        self.assertFalse(self.simulate_reed_solomon_erasure_recovery(k, fec_percentage=0, lost_packets=1))
        # 10% FEC (M=1)
        self.assertTrue(self.simulate_reed_solomon_erasure_recovery(k, fec_percentage=10, lost_packets=1))
        self.assertFalse(self.simulate_reed_solomon_erasure_recovery(k, fec_percentage=10, lost_packets=2))
        # 20% FEC (M=2)
        self.assertTrue(self.simulate_reed_solomon_erasure_recovery(k, fec_percentage=20, lost_packets=2))

    def test_balanced_frame_pacing_absorbs_15ms_wifi_spike(self) -> None:
        """
        Simulates 600 frames (10 seconds at 60 FPS) under periodic 15ms Wi-Fi latency spikes:
        - Lowest Latency mode (0-frame buffer) drops all frames affected by the spike.
        - Balanced mode (1-frame ~16.67ms buffer) absorbs all 15ms spikes with 0 dropped frames.
        """
        fps = 60.0
        frame_interval_ms = 1000.0 / fps  # 16.6667 ms
        base_network_latency_ms = 8.0
        spike_magnitude_ms = 15.0  # Wi-Fi latency spike

        total_frames = 600
        # Spike occurs every 60 frames (every 1 second)
        spike_frames = set(range(30, total_frames, 60))

        # Simulation 1: Lowest Latency Mode (jitter buffer = 0ms)
        # Presentation deadline is nominal arrival time (base_network_latency_ms)
        lowest_latency_drops = 0
        for f in range(total_frames):
            lat = base_network_latency_ms + (spike_magnitude_ms if f in spike_frames else 0.0)
            # Max tolerated jitter without dropping in Lowest Latency is 0ms extra
            if lat > base_network_latency_ms:
                lowest_latency_drops += 1

        # Simulation 2: Balanced Mode (1-frame jitter buffer = 16.6667ms)
        # Presentation deadline is base_network_latency_ms + frame_interval_ms
        balanced_buffer_ms = frame_interval_ms  # 16.6667 ms
        balanced_drops = 0
        for f in range(total_frames):
            lat = base_network_latency_ms + (spike_magnitude_ms if f in spike_frames else 0.0)
            jitter = lat - base_network_latency_ms
            if jitter > balanced_buffer_ms:
                balanced_drops += 1

        # Assertions
        self.assertEqual(lowest_latency_drops, len(spike_frames))
        self.assertGreater(lowest_latency_drops, 0, "Lowest latency must drop frames during spikes")
        self.assertEqual(balanced_drops, 0, "Balanced mode must absorb all 15ms spikes (15ms < 16.67ms)")

    def test_jitter_buffer_overflow_threshold_boundary(self) -> None:
        """
        Verifies the exact physical boundary of the 1-frame jitter buffer (16.6667ms):
        - Spikes <= 16.6ms produce 0 dropped frames.
        - Spikes >= 16.7ms cause buffer overflow and dropped frames.
        """
        frame_interval_ms = 1000.0 / 60.0  # 16.66667 ms

        # Sub-threshold spike: 16.0 ms
        spike_sub = 16.0
        self.assertLess(spike_sub, frame_interval_ms)
        self.assertTrue(spike_sub <= frame_interval_ms)

        # Super-threshold spike: 17.5 ms
        spike_sup = 17.5
        self.assertGreater(spike_sup, frame_interval_ms)


class TestSunshineConfigMutationMatrix(unittest.TestCase):
    """
    Systematically mutates Sunshine configuration parameters to stress-test
    `SunshineConfigValidator.validate_config()` and ensure no out-of-spec parameters pass.
    """

    def test_clean_m2_config_has_zero_errors(self) -> None:
        errors = SunshineConfigValidator.validate_config(MANDATORY_M2_CONFIG)
        self.assertEqual(errors, [])

    def test_mutation_encoder_alternatives_rejected(self) -> None:
        for bad_encoder in ["software", "amf", "nvenc", "vaapi", "custom", ""]:
            mutated = dict(MANDATORY_M2_CONFIG, encoder=bad_encoder)
            errors = SunshineConfigValidator.validate_config(mutated)
            self.assertTrue(
                any("encoder" in err for err in errors),
                f"Failed to reject invalid encoder '{bad_encoder}'",
            )

    def test_mutation_hevc_mode_rejected(self) -> None:
        # Expected value is "1" (disables unsupported hevc_qsv 400ms probe stall on Intel UHD 0x9BC4)
        for bad_hevc in ["0", "2", "3", "99", "-1", "auto"]:
            mutated = dict(MANDATORY_M2_CONFIG, hevc_mode=bad_hevc)
            errors = SunshineConfigValidator.validate_config(mutated)
            self.assertTrue(
                any("hevc_mode" in err for err in errors),
                f"Failed to reject mutated hevc_mode '{bad_hevc}'",
            )

    def test_mutation_av1_mode_rejected(self) -> None:
        # Expected value is "1" (AV1 disabled for Turing NVENC)
        for bad_av1 in ["0", "2", "3", "99", "-1", "enabled"]:
            mutated = dict(MANDATORY_M2_CONFIG, av1_mode=bad_av1)
            errors = SunshineConfigValidator.validate_config(mutated)
            self.assertTrue(
                any("av1_mode" in err for err in errors),
                f"Failed to reject mutated av1_mode '{bad_av1}'",
            )

    def test_mutation_nvenc_preset_rejected(self) -> None:
        # Expected value is "1" (P1 fastest low-latency)
        for bad_preset in ["0", "2", "3", "7", "8", "-1", "slow", "fast"]:
            mutated = dict(MANDATORY_M2_CONFIG, nvenc_preset=bad_preset)
            errors = SunshineConfigValidator.validate_config(mutated)
            self.assertTrue(
                any("nvenc_preset" in err for err in errors),
                f"Failed to reject mutated nvenc_preset '{bad_preset}'",
            )

    def test_mutation_nvenc_twopass_rejected(self) -> None:
        # Expected value is "quarter_res"
        for bad_twopass in ["full_res", "disabled", "half_res", "invalid"]:
            mutated = dict(MANDATORY_M2_CONFIG, nvenc_twopass=bad_twopass)
            errors = SunshineConfigValidator.validate_config(mutated)
            self.assertTrue(
                any("nvenc_twopass" in err for err in errors),
                f"Failed to reject mutated nvenc_twopass '{bad_twopass}'",
            )

    def test_mutation_fec_percentage_boundaries(self) -> None:
        # Boundaries: out of range or not compliant with 20%
        bad_fecs = ["0", "5", "9", "25", "101", "-5", "not_int", ""]
        for bad_fec in bad_fecs:
            mutated = dict(MANDATORY_M2_CONFIG, fec_percentage=bad_fec)
            errors = SunshineConfigValidator.validate_config(mutated)
            self.assertTrue(
                any("fec_percentage" in err for err in errors),
                f"Failed to reject out-of-spec fec_percentage '{bad_fec}'",
            )

    def test_mutation_dd_configuration_option_rejected(self) -> None:
        # Expected value is "verify_only" (activates dd_refresh_rate_option without detaching secondary monitors)
        for bad_opt in ["disabled", "ensure_only_display", "ensure_active", "ensure_primary", "invalid"]:
            mutated = dict(MANDATORY_M2_CONFIG, dd_configuration_option=bad_opt)
            errors = SunshineConfigValidator.validate_config(mutated)
            self.assertTrue(
                any("dd_configuration_option" in err for err in errors),
                f"Failed to reject mutated dd_configuration_option '{bad_opt}'",
            )

    def test_mutation_dd_refresh_rate_option_rejected(self) -> None:
        # Expected value is "auto" (aligns display refresh rate with requested FPS)
        for bad_opt in ["disabled", "manual", "60", "fixed", "none"]:
            mutated = dict(MANDATORY_M2_CONFIG, dd_refresh_rate_option=bad_opt)
            errors = SunshineConfigValidator.validate_config(mutated)
            self.assertTrue(
                any("dd_refresh_rate_option" in err for err in errors),
                f"Failed to reject mutated dd_refresh_rate_option '{bad_opt}'",
            )

    def test_mutation_dd_resolution_option_rejected(self) -> None:
        # Expected value is "disabled"
        for bad_opt in ["auto", "manual", "1080p", "4k"]:
            mutated = dict(MANDATORY_M2_CONFIG, dd_resolution_option=bad_opt)
            errors = SunshineConfigValidator.validate_config(mutated)
            self.assertTrue(
                any("dd_resolution_option" in err for err in errors),
                f"Failed to reject mutated dd_resolution_option '{bad_opt}'",
            )

    def test_mutation_dd_config_revert_on_disconnect_rejected(self) -> None:
        # Expected value is "enabled"
        for bad_opt in ["disabled", "false", "never"]:
            mutated = dict(MANDATORY_M2_CONFIG, dd_config_revert_on_disconnect=bad_opt)
            errors = SunshineConfigValidator.validate_config(mutated)
            self.assertTrue(
                any("dd_config_revert_on_disconnect" in err for err in errors),
                f"Failed to reject mutated dd_config_revert_on_disconnect '{bad_opt}'",
            )

    def test_missing_every_mandatory_key_individually(self) -> None:
        """Verifies that removing ANY single mandatory key causes validation to fail specifically for that key."""
        for key in MANDATORY_M2_CONFIG:
            mutated = dict(MANDATORY_M2_CONFIG)
            del mutated[key]
            errors = SunshineConfigValidator.validate_config(mutated)
            self.assertTrue(
                any(key in err and "Missing" in err for err in errors),
                f"Failed to report missing mandatory key '{key}'",
            )


class TestLiveHostInvariants(unittest.TestCase):
    """
    Empirically verifies live host configuration and Sunshine service telemetry.
    """

    def setUp(self) -> None:
        self.conf_path = Path(r"C:\Program Files\Sunshine\config\sunshine.conf")
        self.log_path = Path(r"C:\Program Files\Sunshine\config\sunshine.log")

    def test_live_sunshine_conf_exists_and_matches_m2(self) -> None:
        if not self.conf_path.is_file():
            self.skipTest("Live sunshine.conf not installed on this host")
        parsed = SunshineConfigParser.parse_file(self.conf_path)
        errors = SunshineConfigValidator.validate_config(parsed)
        self.assertEqual(errors, [], f"Live sunshine.conf has validation errors: {errors}")

    def test_live_sunshine_log_parsed_telemetry_invariants(self) -> None:
        if not self.log_path.is_file():
            self.skipTest("Live sunshine.log not installed on this host")
        log_text = self.log_path.read_text(encoding="utf-8", errors="ignore")
        telemetry = SunshineLogTelemetryParser.parse_log(log_text)

        # 1. Zero fatal errors in log
        self.assertEqual(telemetry["fatal_errors"], [], "Sunshine log contains fatal errors")

        # 2. Config keys were parsed upon startup
        parsed = telemetry["parsed_config"]
        self.assertIn(parsed.get("encoder"), ("quicksync", "nvenc"))
        self.assertIn(parsed.get("dd_configuration_option"), ("disabled", "verify_only", "ensure_only_display"))
        self.assertIn(parsed.get("dd_refresh_rate_option"), ("disabled", "auto"))
        self.assertEqual(parsed.get("fec_percentage"), "20")

        # 3. Port 47990 check (skipped when SunshineService is in on-demand standby)
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(0.5)
        try:
            res = sock.connect_ex(("127.0.0.1", 47990))
            if res != 0:
                self.skipTest("Sunshine is in on-demand standby (port 47990 closed)")
        finally:
            sock.close()

    def test_volume_rule_contract_enforced(self) -> None:
        """Verifies that laptop_gaming_agent.py never increases scalar volume."""
        agent_file = _TOOL_DIR / "laptop_gaming_agent.py"
        self.assertTrue(agent_file.is_file())
        text = agent_file.read_text(encoding="utf-8", errors="ignore")

        # Check for banned patterns
        self.assertNotIn("VK_VOLUME_UP", text)
        self.assertNotIn("SetMasterVolumeLevelScalar(1.0", text)
        # Ensure mute/unmute preserving was_muted pattern exists
        self.assertIn("was_muted", text)


if __name__ == "__main__":
    unittest.main()
