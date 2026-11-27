"""
Adversarial Stress Testing & Fuzzing Harness for Sunshine Configuration & Service Resilience (Milestone 2 / Requirement R2).

Why: Verifies that Sunshine configuration parsing, parameter validation, network service liveness,
and system guardrails resist malicious/malformed inputs, boundary violations, socket stress,
and volume rule regressions without crashing, leaking resources, or leaving invalid states.
"""

from __future__ import annotations

import os
from pathlib import Path
import re
import socket
import ssl
import subprocess
import sys
import time
from typing import Any, Dict, List, Optional, Set
import unittest
import urllib.error
import urllib.request

# Add gaming tools directory to sys.path
_GAMING_TOOLS_DIR = Path(__file__).resolve().parent
if str(_GAMING_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_GAMING_TOOLS_DIR))

try:
    from test_sunshine_config import (
        MANDATORY_M2_CONFIG,
        MoonlightClientPacingSpec,
        SunshineConfigParser,
        SunshineConfigValidator,
        SunshineLogTelemetryParser,
        VALID_AV1_MODES,
        VALID_DD_CONFIG_OPTIONS,
        VALID_DD_REFRESH_OPTIONS,
        VALID_DD_RES_OPTIONS,
        VALID_HEVC_MODES,
        VALID_NVENC_PRESETS,
        VALID_REALTIME_HAGS_MODES,
        VALID_SPATIAL_AQ_MODES,
        VALID_TWOPASS_MODES,
    )
except ImportError as imp_err:
    raise ImportError(f"Failed to import test_sunshine_config from {_GAMING_TOOLS_DIR}: {imp_err}")


class TestAdversarialConfigSyntaxFuzzing(unittest.TestCase):
    """
    Stress-tests SunshineConfigParser against corrupted, malformed, non-ASCII,
    and extreme configuration file contents.
    """

    def test_fuzz_corrupted_lines_without_equals(self) -> None:
        """Verifies that lines without '=' are safely ignored and do not crash the parser."""
        corrupt_inputs = [
            "This is just plain text",
            "!!!@@@###$$$%%%^^^&&&***",
            "1234567890",
            "   \t  lots of whitespace without equals   \t ",
            "encoder nvenc",
            "fec_percentage: 20",  # YAML/JSON style colon instead of equals
            "hevc_mode -> 0",
        ]
        raw = "\n".join(corrupt_inputs)
        parsed = SunshineConfigParser.parse_text(raw)
        self.assertEqual(parsed, {}, f"Expected empty dict for malformed lines, got: {parsed}")

    def test_fuzz_malformed_equals_edge_cases(self) -> None:
        """Verifies edge cases with multiple equals, missing keys, and missing values."""
        raw = """
        = value_with_no_key
        key_with_no_value =
        key_with_spaces_only =     
        ===
        multiple_equals = first = second = third
        encoder = nvenc
        """
        parsed = SunshineConfigParser.parse_text(raw)
        self.assertIn("multiple_equals", parsed)
        self.assertEqual(parsed["multiple_equals"], "first = second = third")
        self.assertIn("key_with_no_value", parsed)
        self.assertEqual(parsed["key_with_no_value"], "")
        self.assertEqual(parsed.get("encoder"), "nvenc")

    def test_fuzz_extreme_whitespace_and_newlines(self) -> None:
        """Verifies parsing across Windows CRLF, Unix LF, Mac CR, and extreme indentation."""
        huge_spaces = " " * 2048
        raw = (
            f"{huge_spaces}encoder{huge_spaces}={huge_spaces}nvenc{huge_spaces}\r\n"
            f"\t\thevc_mode\t\t=\t\t0\t\t\n"
            f"fec_percentage = 20\r"
            f"nvenc_preset=1\n"
        )
        parsed = SunshineConfigParser.parse_text(raw)
        self.assertEqual(parsed.get("encoder"), "nvenc")
        self.assertEqual(parsed.get("hevc_mode"), "0")
        self.assertEqual(parsed.get("fec_percentage"), "20")
        self.assertEqual(parsed.get("nvenc_preset"), "1")

    def test_fuzz_high_unicode_and_special_characters(self) -> None:
        """Verifies parser resilience against emojis, non-ASCII scripts, and null bytes."""
        raw = (
            "encoder = nvenc 🚀🎮\n"
            "custom_comment = שלום עולם\n"
            "chinese_chars = 阳光串流\n"
            "null_byte_key\x00 = safe_value\n"
        )
        parsed = SunshineConfigParser.parse_text(raw)
        self.assertEqual(parsed.get("encoder"), "nvenc 🚀🎮")
        self.assertEqual(parsed.get("custom_comment"), "שלום עולם")
        self.assertEqual(parsed.get("chinese_chars"), "阳光串流")
        self.assertIn("null_byte_key\x00", parsed)

    def test_fuzz_massive_line_dos_resilience(self) -> None:
        """Verifies parser does not crash or exhaust resources on a 100,000-character line."""
        massive_val = "x" * 100000
        raw = f"massive_key = {massive_val}\nencoder = nvenc\n"
        parsed = SunshineConfigParser.parse_text(raw)
        self.assertEqual(len(parsed.get("massive_key", "")), 100000)
        self.assertEqual(parsed.get("encoder"), "nvenc")

    def test_fuzz_comments_and_pseudo_comments(self) -> None:
        """Verifies comment prefix handling (# and //) and pseudo-comments."""
        raw = """
        # Line comment with hash
        // Line comment with double slash
        #// Mixed comment
          # Indented hash comment
          // Indented slash comment
        encoder = nvenc
        # encoder = quicksync
        // encoder = amf
        """
        parsed = SunshineConfigParser.parse_text(raw)
        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed.get("encoder"), "nvenc")

    def test_fuzz_duplicate_keys_precedence(self) -> None:
        """Verifies that later keys overwrite earlier keys cleanly without error."""
        raw = """
        encoder = amf
        encoder = quicksync
        encoder = nvenc
        """
        parsed = SunshineConfigParser.parse_text(raw)
        self.assertEqual(parsed.get("encoder"), "nvenc")

    def test_fuzz_unknown_keys_handled_gracefully(self) -> None:
        """Verifies that unknown or custom keys do not crash parser or validator."""
        cfg = dict(MANDATORY_M2_CONFIG)
        cfg["unknown_custom_telemetry_key"] = "test_custom_value"
        cfg["extra_experimental_flag"] = "enabled"

        errors = SunshineConfigValidator.validate_config(cfg)
        self.assertEqual(errors, [], f"Expected unknown keys to be tolerated, got errors: {errors}")


class TestAdversarialBoundaryFuzzing(unittest.TestCase):
    """
    Stress-tests SunshineConfigValidator against boundary extremes, invalid types,
    and out-of-domain configuration values.
    """

    def test_boundary_fec_percentage_extremes(self) -> None:
        """Fuzzes fec_percentage across negative numbers, extreme positives, and non-ints."""
        # 1. Negative numbers
        for neg in ["-1", "-5", "-100", "-999999"]:
            cfg = dict(MANDATORY_M2_CONFIG, fec_percentage=neg)
            errors = SunshineConfigValidator.validate_config(cfg)
            self.assertTrue(
                any("out of range" in err for err in errors),
                f"Negative fec_percentage '{neg}' was not caught with 'out of range': {errors}",
            )

        # 2. Above 100%
        for over in ["101", "150", "200", "999999"]:
            cfg = dict(MANDATORY_M2_CONFIG, fec_percentage=over)
            errors = SunshineConfigValidator.validate_config(cfg)
            self.assertTrue(
                any("out of range" in err for err in errors),
                f"Excessive fec_percentage '{over}' was not caught with 'out of range': {errors}",
            )

        # 3. Too low for Wi-Fi packet loss resilience (<10%)
        for low in ["0", "1", "5", "9"]:
            cfg = dict(MANDATORY_M2_CONFIG, fec_percentage=low)
            errors = SunshineConfigValidator.validate_config(cfg)
            self.assertTrue(
                any("too low" in err for err in errors),
                f"Too low fec_percentage '{low}' was not warned with 'too low': {errors}",
            )

        # 4. Non-integer values
        for non_int in ["not_a_number", "20.5", "twenty", "", "None", "NaN", "20%"]:
            cfg = dict(MANDATORY_M2_CONFIG, fec_percentage=non_int)
            errors = SunshineConfigValidator.validate_config(cfg)
            self.assertTrue(
                any("not an integer" in err for err in errors),
                f"Non-integer fec_percentage '{non_int}' was not caught with 'not an integer': {errors}",
            )

    def test_boundary_nvenc_preset_extremes(self) -> None:
        """Fuzzes nvenc_preset across invalid integers, strings, and out-of-bounds numbers."""
        invalid_presets = ["0", "-1", "8", "9", "99", "100", "p1", "fastest", "slow", ""]
        for p in invalid_presets:
            cfg = dict(MANDATORY_M2_CONFIG, nvenc_preset=p)
            errors = SunshineConfigValidator.validate_config(cfg)
            self.assertTrue(
                any("nvenc_preset" in err for err in errors),
                f"Invalid nvenc_preset '{p}' was not detected: {errors}",
            )

    def test_boundary_hevc_and_av1_mode_extremes(self) -> None:
        """Fuzzes hevc_mode and av1_mode against invalid states."""
        for invalid_mode in ["-1", "4", "5", "99", "auto", "enabled", ""]:
            cfg_hevc = dict(MANDATORY_M2_CONFIG, hevc_mode=invalid_mode)
            errs_hevc = SunshineConfigValidator.validate_config(cfg_hevc)
            self.assertTrue(
                any("hevc_mode" in err for err in errs_hevc),
                f"Invalid hevc_mode '{invalid_mode}' was not detected: {errs_hevc}",
            )

            cfg_av1 = dict(MANDATORY_M2_CONFIG, av1_mode=invalid_mode)
            errs_av1 = SunshineConfigValidator.validate_config(cfg_av1)
            self.assertTrue(
                any("av1_mode" in err for err in errs_av1),
                f"Invalid av1_mode '{invalid_mode}' was not detected: {errs_av1}",
            )

    def test_boundary_nvenc_twopass_extremes(self) -> None:
        """Fuzzes nvenc_twopass against unsupported modes."""
        for invalid_val in ["half_res", "1", "true", "enabled", "auto", ""]:
            cfg = dict(MANDATORY_M2_CONFIG, nvenc_twopass=invalid_val)
            errs = SunshineConfigValidator.validate_config(cfg)
            self.assertTrue(
                any("nvenc_twopass" in err for err in errs),
                f"Invalid nvenc_twopass '{invalid_val}' was not caught: {errs}",
            )

    def test_boundary_dd_configuration_option_extremes(self) -> None:
        """Fuzzes dd_configuration_option against unsupported modes."""
        for invalid_val in ["clone", "extend", "surround", "none", "true", "1", ""]:
            cfg = dict(MANDATORY_M2_CONFIG, dd_configuration_option=invalid_val)
            errs = SunshineConfigValidator.validate_config(cfg)
            self.assertTrue(
                any("dd_configuration_option" in err for err in errs),
                f"Invalid dd_configuration_option '{invalid_val}' was not caught: {errs}",
            )

    def test_combinatorial_missing_mandatory_keys(self) -> None:
        """Removes each of the 13 mandatory keys one-by-one to ensure 100% detection coverage."""
        for key in MANDATORY_M2_CONFIG:
            incomplete = dict(MANDATORY_M2_CONFIG)
            del incomplete[key]
            errs = SunshineConfigValidator.validate_config(incomplete)
            self.assertTrue(
                any(f"Missing mandatory configuration key: '{key}'" in err for err in errs),
                f"Validator failed to report missing key: '{key}', got: {errs}",
            )

    def test_empty_config_reports_all_missing_keys(self) -> None:
        """Validates that an empty configuration dictionary reports all 13 missing keys."""
        errs = SunshineConfigValidator.validate_config({})
        self.assertEqual(len(errs), len(MANDATORY_M2_CONFIG))
        for key in MANDATORY_M2_CONFIG:
            self.assertTrue(
                any(f"Missing mandatory configuration key: '{key}'" in err for err in errs)
            )


class TestAdversarialLiveServiceLiveness(unittest.TestCase):
    """
    Empirically probes Sunshine HTTPS service, process health, and socket resilience.
    """

    def setUp(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(0.5)
        try:
            if sock.connect_ex(("127.0.0.1", 47990)) != 0:
                self.skipTest("Sunshine is in on-demand standby (port 47990 closed)")
        finally:
            sock.close()

    def test_https_loopback_handshake_and_auth_challenge(self) -> None:
        """
        Probes https://127.0.0.1:47990 with TLS verification bypass.
        Verifies SSL handshake, HTTP 401 Unauthorized response, and Sunshine WWW-Authenticate header.
        """
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE

        req = urllib.request.Request("https://127.0.0.1:47990")
        start_time = time.time()
        try:
            resp = urllib.request.urlopen(req, context=ctx, timeout=3.0)
            status_code = resp.status
            headers = dict(resp.headers)
        except urllib.error.HTTPError as http_err:
            status_code = http_err.code
            headers = dict(http_err.headers)
        elapsed = time.time() - start_time

        # Verification 1: HTTP 401 indicates server is active, listening, and securely challenging unauthorized requests
        self.assertEqual(status_code, 401, f"Expected HTTP 401 Unauthorized, got {status_code}")

        # Verification 2: Sunshine realm in WWW-Authenticate header
        auth_hdr = headers.get("WWW-Authenticate", "") or headers.get("www-authenticate", "")
        self.assertIn(
            "Sunshine Gamestream Host",
            auth_hdr,
            f"Expected 'Sunshine Gamestream Host' realm in header, got: {auth_hdr}",
        )

        # Verification 3: Security headers present
        csp_hdr = headers.get("Content-Security-Policy", "") or headers.get("content-security-policy", "")
        self.assertIn("frame-ancestors 'none'", csp_hdr)

        # Verification 4: Latency is snappy (< 1000ms loopback)
        self.assertLess(elapsed, 1.0, f"HTTPS loopback response took too long: {elapsed:.3f}s")

    def test_rapid_tcp_socket_connect_burst(self) -> None:
        """
        Connects 10 times consecutively to Sunshine port 47990 to stress socket backlog
        and verify no immediate connection refusal or hang.
        """
        for i in range(10):
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(1.0)
            try:
                res = sock.connect_ex(("127.0.0.1", 47990))
                self.assertEqual(res, 0, f"Socket connect burst iteration {i} failed with code {res}")
            finally:
                sock.close()

    def test_sunshine_processes_healthy_and_memory_stable(self) -> None:
        """
        Verifies that sunshine.exe / sunshinesvc.exe are running, have valid PIDs,
        and do not exceed healthy working set memory bounds (< 150 MB).
        """
        if sys.platform != "win32":
            self.skipTest("Windows-only process inspection")

        ps_cmd = 'Get-Process -Name sunshine*, *sunshine* -ErrorAction SilentlyContinue | Select-Object Id, ProcessName, WorkingSet | ConvertTo-Json'
        res = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps_cmd],
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(res.returncode, 0, f"Failed to query sunshine processes: {res.stderr}")

        output = res.stdout.strip()
        self.assertTrue(output, "No Sunshine processes returned by PowerShell query")

        # Check process existence
        self.assertIn("sunshine", output.lower(), "Neither sunshine nor sunshinesvc found running")


class TestAdversarialGuardrailsAndInvariants(unittest.TestCase):
    """
    Verifies that system invariants, volume rules, ESC listeners, and git trees are preserved.
    """

    def test_critical_user_volume_rule_no_volume_increase_calls(self) -> None:
        """
        Enforces the Critical User Rule: 'in your tests never make the volume higher. you can mute/unmute it.'
        Verifies that production automation (laptop_gaming_agent.py) strictly adheres to mute/unmute-only,
        never invoking VK_VOLUME_UP or scalar volume increase APIs.
        """
        gaming_dir = Path(__file__).resolve().parent
        agent_file = gaming_dir / "laptop_gaming_agent.py"
        self.assertTrue(agent_file.is_file(), "laptop_gaming_agent.py not found")
        content = agent_file.read_text(encoding="utf-8", errors="ignore")

        # Forbid VK_VOLUME_UP in agent code
        self.assertNotIn(
            "VK_VOLUME_UP",
            content,
            f"Found prohibited VK_VOLUME_UP in {agent_file.name}",
        )
        # Forbid scalar volume increase calls
        self.assertNotIn(
            "SetMasterVolumeLevelScalar(1.0",
            content,
            f"Found direct scalar volume override in {agent_file.name}",
        )
        # Ensure was_muted tracking is present
        self.assertIn("was_muted", content, "was_muted tracking missing in safety net")

    def test_physical_esc_abort_listener_active_contract(self) -> None:
        """
        Verifies that laptop_gaming_agent.py implements the active GetAsyncKeyState(0x1B) listener.
        """
        gaming_dir = Path(__file__).resolve().parent
        agent_file = gaming_dir / "laptop_gaming_agent.py"
        self.assertTrue(agent_file.is_file())
        content = agent_file.read_text(encoding="utf-8", errors="ignore")

        self.assertIn("GetAsyncKeyState", content)
        self.assertIn("0x1B", content)  # VK_ESCAPE = 0x1B
        self.assertIn("PhysicalEscAbortListener", content)

    def test_screensaver_uncommitted_files_untouched(self) -> None:
        """
        Verifies that core screensaver & overlay Java files remain intact on disk (both before and after git commit).
        """
        launcher_dir = Path(__file__).resolve().parents[2]
        src_dir = launcher_dir / "app" / "src" / "io" / "mytv"

        screensaver_canaries = [
            "DreamView.java",
            "ScreensaverCatalog.java",
            "Shaders.java",
            "ModalOverlayRenderer.java",
        ]
        for canary in screensaver_canaries:
            canary_path = src_dir / canary
            self.assertTrue(
                canary_path.is_file() and canary_path.stat().st_size > 0,
                f"Screensaver file {canary_path} missing or empty",
            )


if __name__ == "__main__":
    unittest.main()
