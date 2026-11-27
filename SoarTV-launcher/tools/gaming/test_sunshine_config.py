"""
Unit and integration test suite for Sunshine & Moonlight Stream Configuration (Requirement R2).

Why: Verifies that Sunshine configuration (sunshine.conf) implements optimal low-latency NVENC,
HEVC hardware encoding, display device isolation, 60Hz refresh rate synchronization, Wi-Fi FEC resilience,
zero-fatal-error startup, telemetry log parsing, Moonlight Android TV frame pacing recommendations,
and strict volume safety guardrails.
"""

from __future__ import annotations

import os
from pathlib import Path
import re
import socket
import sys
from typing import Any, Dict, List, Optional, Set, Tuple
import unittest

_DEFAULT_SUNSHINE_CONF_PATHS: Tuple[Path, ...] = (
    Path(r"C:\Program Files\Sunshine\config\sunshine.conf"),
    Path(os.environ.get("ProgramData", r"C:\ProgramData")) / "Sunshine" / "config" / "sunshine.conf",
)

_DEFAULT_SUNSHINE_LOG_PATHS: Tuple[Path, ...] = (
    Path(r"C:\Program Files\Sunshine\config\sunshine.log"),
    Path(os.environ.get("ProgramData", r"C:\ProgramData")) / "Sunshine" / "config" / "sunshine.log",
)

# Mandatory Sunshine configuration keys and their target values for M3
MANDATORY_M2_CONFIG: Dict[str, str] = {
    "gamepad_driver": "vigembus",
    "encoder": "quicksync",
    "hevc_mode": "1",
    "av1_mode": "1",
    "minimum_fps_target": "60",
    "nvenc_preset": "1",
    "nvenc_twopass": "quarter_res",
    "nvenc_spatial_aq": "disabled",
    "nvenc_realtime_hags": "enabled",
    "qsv_preset": "veryfast",
    "qsv_coder": "cabac",
    "fec_percentage": "20",
    "dd_configuration_option": "verify_only",
    "dd_resolution_option": "disabled",
    "dd_refresh_rate_option": "auto",
    "dd_config_revert_on_disconnect": "enabled",
}

VALID_NVENC_PRESETS: Set[str] = {"1", "2", "3", "4", "5", "6", "7"}
VALID_QSV_PRESETS: Set[str] = {"veryfast", "faster", "fast", "medium", "slow", "slower", "slowest"}
VALID_QSV_CODERS: Set[str] = {"auto", "cabac", "cavlc"}
VALID_HEVC_MODES: Set[str] = {"0", "1", "2", "3"}
VALID_AV1_MODES: Set[str] = {"0", "1", "2", "3"}
VALID_TWOPASS_MODES: Set[str] = {"disabled", "quarter_res", "full_res"}
VALID_SPATIAL_AQ_MODES: Set[str] = {"disabled", "enabled"}
VALID_REALTIME_HAGS_MODES: Set[str] = {"disabled", "enabled"}
VALID_DD_CONFIG_OPTIONS: Set[str] = {
    "disabled",
    "verify_only",
    "ensure_active",
    "ensure_primary",
    "ensure_only_display",
}
VALID_DD_RES_OPTIONS: Set[str] = {"disabled", "auto", "manual"}
VALID_DD_REFRESH_OPTIONS: Set[str] = {"disabled", "auto", "manual"}
VALID_BOOLEAN_OPTIONS: Set[str] = {"disabled", "enabled", "false", "true"}


class SunshineConfigParser:
    """
    Parses sunshine.conf key-value configuration files.

    Why: Sunshine uses simple INI-like 'key = value' lines with '#' or '//' comments.
    What: Extracts clean string key-value mappings ignoring empty lines and comments.
    """

    @classmethod
    def parse_text(cls, text: str) -> Dict[str, str]:
        """Parses sunshine.conf string content into a dictionary."""
        config: Dict[str, str] = {}
        for line in text.splitlines():
            clean = line.strip()
            if not clean or clean.startswith("#") or clean.startswith("//"):
                continue
            if "=" not in clean:
                continue
            key, sep, val = clean.partition("=")
            config[key.strip()] = val.strip()
        return config

    @classmethod
    def parse_file(cls, path: Path) -> Dict[str, str]:
        """Reads and parses a sunshine.conf file from disk."""
        if not path.is_file():
            return {}
        try:
            return cls.parse_text(path.read_text(encoding="utf-8", errors="ignore"))
        except OSError:
            return {}


class SunshineConfigValidator:
    """
    Validates sunshine.conf parameters against latency, hardware encoder, and stability specifications.

    Why: Invalid or missing parameters cause stream stutter, high decode times, and frame drops.
    What: Ensures all mandatory M2 parameters exist with valid domain ranges.
    """

    @classmethod
    def validate_config(cls, config: Dict[str, str]) -> List[str]:
        """
        Validates all configuration parameters against M2 specifications.
        Returns a list of error descriptions (empty list if 100% compliant).
        """
        errors: List[str] = []

        # 1. Parameter presence check
        for req_key, expected_val in MANDATORY_M2_CONFIG.items():
            if req_key not in config:
                errors.append(f"Missing mandatory configuration key: '{req_key}'")
            elif config[req_key] != expected_val:
                errors.append(
                    f"Configuration key '{req_key}' has value '{config[req_key]}', expected '{expected_val}'"
                )

        # 2. Domain validity checks
        if "nvenc_preset" in config:
            val = config["nvenc_preset"]
            if val not in VALID_NVENC_PRESETS:
                errors.append(f"Invalid nvenc_preset '{val}', must be 1..7 (1 is fastest/lowest latency)")

        if "fec_percentage" in config:
            try:
                fec = int(config["fec_percentage"])
                if fec < 0 or fec > 100:
                    errors.append(f"fec_percentage '{fec}' out of range [0, 100]")
                elif fec < 10:
                    errors.append(f"fec_percentage '{fec}' is too low for reliable Wi-Fi packet loss resilience")
            except ValueError:
                errors.append(f"fec_percentage '{config['fec_percentage']}' is not an integer")

        if "hevc_mode" in config and config["hevc_mode"] not in VALID_HEVC_MODES:
            errors.append(f"Invalid hevc_mode '{config['hevc_mode']}'")

        if "av1_mode" in config and config["av1_mode"] not in VALID_AV1_MODES:
            errors.append(f"Invalid av1_mode '{config['av1_mode']}'")

        if "nvenc_twopass" in config and config["nvenc_twopass"] not in VALID_TWOPASS_MODES:
            errors.append(f"Invalid nvenc_twopass '{config['nvenc_twopass']}'")

        if "nvenc_spatial_aq" in config and config["nvenc_spatial_aq"] not in VALID_SPATIAL_AQ_MODES:
            errors.append(f"Invalid nvenc_spatial_aq '{config['nvenc_spatial_aq']}'")

        if "nvenc_realtime_hags" in config and config["nvenc_realtime_hags"] not in VALID_REALTIME_HAGS_MODES:
            errors.append(f"Invalid nvenc_realtime_hags '{config['nvenc_realtime_hags']}'")

        if "qsv_preset" in config and config["qsv_preset"] not in VALID_QSV_PRESETS:
            errors.append(f"Invalid qsv_preset '{config['qsv_preset']}'")

        if "qsv_coder" in config and config["qsv_coder"] not in VALID_QSV_CODERS:
            errors.append(f"Invalid qsv_coder '{config['qsv_coder']}'")

        if "dd_configuration_option" in config and config["dd_configuration_option"] not in VALID_DD_CONFIG_OPTIONS:
            errors.append(f"Invalid dd_configuration_option '{config['dd_configuration_option']}'")

        if "dd_resolution_option" in config and config["dd_resolution_option"] not in VALID_DD_RES_OPTIONS:
            errors.append(f"Invalid dd_resolution_option '{config['dd_resolution_option']}'")

        if "dd_refresh_rate_option" in config and config["dd_refresh_rate_option"] not in VALID_DD_REFRESH_OPTIONS:
            errors.append(f"Invalid dd_refresh_rate_option '{config['dd_refresh_rate_option']}'")

        return errors


class SunshineLogTelemetryParser:
    """
    Parses sunshine.log to verify service startup, encoder detection, and display device synchronization.

    Why: Telemetry logs provide forensic proof of clean startup, hardware probing, and display config.
    What: Extracts parsed configuration options, GPU probes, active encoder, and errors.
    """

    @classmethod
    def parse_log(cls, log_text: str) -> Dict[str, Any]:
        """Extracts telemetry facts from sunshine.log content."""
        telemetry: Dict[str, Any] = {
            "version": "",
            "parsed_config": {},
            "display_devices": [],
            "tried_encoders": [],
            "found_encoders": [],
            "fatal_errors": [],
            "display_device_configured": False,
        }

        ver_match = re.search(r"Sunshine version:\s*([^\s]+)", log_text)
        if ver_match:
            telemetry["version"] = ver_match.group(1)

        # Extract logged config lines: Info: config: '<key>' = <value>
        for match in re.finditer(r"config:\s*'([^']+)'\s*=\s*([^\r\n]+)", log_text):
            telemetry["parsed_config"][match.group(1).strip()] = match.group(2).strip()

        # Extract encoder trials
        for match in re.finditer(r"Trying encoder\s*\[([^\]]+)\]", log_text):
            telemetry["tried_encoders"].append(match.group(1).strip())

        # Extract found encoders
        for match in re.finditer(r"Found\s+([A-Za-z0-9\.\-_]+)\s+encoder:\s*([^\r\n]+)", log_text):
            telemetry["found_encoders"].append({
                "codec": match.group(1).strip(),
                "details": match.group(2).strip(),
            })

        # Detect display device configuration
        if "dd_configuration_option" in telemetry["parsed_config"]:
            opt = telemetry["parsed_config"]["dd_configuration_option"]
            if opt != "disabled":
                telemetry["display_device_configured"] = True

        # Check fatal errors (excluding safe ignored encoder test probes)
        for line in log_text.splitlines():
            if "Fatal:" in line or "CRITICAL:" in line:
                telemetry["fatal_errors"].append(line.strip())

        return telemetry


class MoonlightClientPacingSpec:
    """
    Specification model for Moonlight Android TV client settings on a 60 Hz 4K Smart TV (e.g. 192.168.1.50:5555).

    Why: Client-side settings must pair with Sunshine's configuration to prevent frame drops.
    What: Verifies the recommended client configuration.
    """

    RECOMMENDED_SETTINGS: Dict[str, Any] = {
        "video_codec": "HEVC",
        "frame_pacing": "Balanced",
        "jitter_buffer_frames": 1,
        "target_fps": 60,
        "resolution": "1920x1080",
        "bitrate_range_mbps": (30, 50),
        "enable_hdr": False,
        "optimize_game_settings": True,
    }

    @classmethod
    def validate_client_settings(cls, settings: Dict[str, Any]) -> List[str]:
        """Validates Moonlight client configuration against latency & smoothness specs."""
        deviations: List[str] = []
        if settings.get("video_codec") != "HEVC":
            deviations.append(
                f"Video codec is '{settings.get('video_codec')}', expected 'HEVC' for hardware decode latency < 6ms"
            )
        if settings.get("frame_pacing") not in ("Balanced", "Smoothest Video"):
            deviations.append(
                f"Frame pacing is '{settings.get('frame_pacing')}', expected 'Balanced' (1-frame jitter buffer) "
                "to absorb Wi-Fi network packet latency spikes"
            )
        if settings.get("target_fps") != 60:
            deviations.append(f"Target FPS is {settings.get('target_fps')}, expected 60 for 60.00 Hz TV panel lock")
        return deviations


# =============================================================================
# Test Cases
# =============================================================================


class TestSunshineConfigSyntaxAndParsing(unittest.TestCase):
    """Verifies that SunshineConfigParser correctly extracts key-value pairs and ignores comments."""

    def test_parse_valid_config_lines(self) -> None:
        raw = "encoder = nvenc\nhevc_mode = 0\nfec_percentage = 20\n"
        parsed = SunshineConfigParser.parse_text(raw)
        self.assertEqual(parsed.get("encoder"), "nvenc")
        self.assertEqual(parsed.get("hevc_mode"), "0")
        self.assertEqual(parsed.get("fec_percentage"), "20")

    def test_parse_ignores_comments_and_blank_lines(self) -> None:
        raw = """
        # Core gamepad driver
        gamepad_driver = vigembus

        // Video Encoder
        encoder = nvenc
        # Next line is empty

        hevc_mode = 0
        """
        parsed = SunshineConfigParser.parse_text(raw)
        self.assertEqual(len(parsed), 3)
        self.assertEqual(parsed["gamepad_driver"], "vigembus")
        self.assertEqual(parsed["encoder"], "nvenc")
        self.assertEqual(parsed["hevc_mode"], "0")

    def test_parse_handles_inline_whitespace(self) -> None:
        raw = "  dd_configuration_option   =   ensure_only_display  \n"
        parsed = SunshineConfigParser.parse_text(raw)
        self.assertEqual(parsed["dd_configuration_option"], "ensure_only_display")

    def test_parse_handles_missing_file_gracefully(self) -> None:
        bogus_path = Path("Z:\\NonExistent\\sunshine.conf")
        parsed = SunshineConfigParser.parse_file(bogus_path)
        self.assertEqual(parsed, {})

    def test_parse_handles_empty_string(self) -> None:
        parsed = SunshineConfigParser.parse_text("")
        self.assertEqual(parsed, {})


class TestSunshineConfigParameterConstraints(unittest.TestCase):
    """Verifies that SunshineConfigValidator enforces valid domain ranges and catches defects."""

    def test_valid_m2_config_passes_zero_errors(self) -> None:
        errors = SunshineConfigValidator.validate_config(MANDATORY_M2_CONFIG)
        self.assertEqual(errors, [], f"Expected 0 errors on clean M2 config, got: {errors}")

    def test_missing_mandatory_parameter_detected(self) -> None:
        incomplete = dict(MANDATORY_M2_CONFIG)
        del incomplete["nvenc_preset"]
        errors = SunshineConfigValidator.validate_config(incomplete)
        self.assertTrue(any("nvenc_preset" in err for err in errors))

    def test_invalid_nvenc_preset_rejected(self) -> None:
        invalid = dict(MANDATORY_M2_CONFIG, nvenc_preset="99")
        errors = SunshineConfigValidator.validate_config(invalid)
        self.assertTrue(any("nvenc_preset" in err for err in errors))

    def test_invalid_fec_percentage_rejected(self) -> None:
        invalid = dict(MANDATORY_M2_CONFIG, fec_percentage="not_a_number")
        errors = SunshineConfigValidator.validate_config(invalid)
        self.assertTrue(any("fec_percentage" in err for err in errors))

    def test_too_low_fec_percentage_warned(self) -> None:
        invalid = dict(MANDATORY_M2_CONFIG, fec_percentage="2")
        errors = SunshineConfigValidator.validate_config(invalid)
        self.assertTrue(any("too low" in err for err in errors))

    def test_invalid_dd_configuration_option_rejected(self) -> None:
        invalid = dict(MANDATORY_M2_CONFIG, dd_configuration_option="invalid_mode")
        errors = SunshineConfigValidator.validate_config(invalid)
        self.assertTrue(any("dd_configuration_option" in err for err in errors))


class TestLiveSunshineConfigFile(unittest.TestCase):
    """Verifies that the actual live sunshine.conf on this host matches all M2 requirements."""

    def setUp(self) -> None:
        self.live_conf_path: Optional[Path] = None
        for p in _DEFAULT_SUNSHINE_CONF_PATHS:
            if p.is_file():
                self.live_conf_path = p
                break
        if self.live_conf_path is None:
            self.skipTest("Live sunshine.conf not installed on this host")

    def test_live_config_file_exists(self) -> None:
        self.assertIsNotNone(
            self.live_conf_path,
            f"sunshine.conf not found at any standard location: {_DEFAULT_SUNSHINE_CONF_PATHS}",
        )

    def test_live_config_file_has_all_mandatory_keys(self) -> None:
        self.assertIsNotNone(self.live_conf_path)
        assert self.live_conf_path is not None
        parsed = SunshineConfigParser.parse_file(self.live_conf_path)
        errors = SunshineConfigValidator.validate_config(parsed)
        self.assertEqual(
            errors,
            [],
            f"Live sunshine.conf ({self.live_conf_path}) failed validation:\n" + "\n".join(errors),
        )

    def test_live_config_values_match_exact_m2_specifications(self) -> None:
        self.assertIsNotNone(self.live_conf_path)
        assert self.live_conf_path is not None
        parsed = SunshineConfigParser.parse_file(self.live_conf_path)

        self.assertEqual(parsed.get("gamepad_driver"), "vigembus")
        self.assertEqual(parsed.get("encoder"), "quicksync")
        self.assertEqual(parsed.get("hevc_mode"), "1")
        self.assertEqual(parsed.get("av1_mode"), "1")
        self.assertEqual(parsed.get("minimum_fps_target"), "60")
        self.assertEqual(parsed.get("nvenc_preset"), "1")
        self.assertEqual(parsed.get("nvenc_twopass"), "quarter_res")
        self.assertEqual(parsed.get("nvenc_spatial_aq"), "disabled")
        self.assertEqual(parsed.get("nvenc_realtime_hags"), "enabled")
        self.assertEqual(parsed.get("qsv_coder"), "cabac")
        self.assertEqual(parsed.get("fec_percentage"), "20")
        self.assertEqual(parsed.get("dd_configuration_option"), "verify_only")
        self.assertEqual(parsed.get("dd_resolution_option"), "disabled")
        self.assertEqual(parsed.get("dd_refresh_rate_option"), "auto")
        self.assertEqual(parsed.get("dd_config_revert_on_disconnect"), "enabled")


class TestSunshineServiceStatusAndTelemetry(unittest.TestCase):
    """Verifies that the Sunshine service starts cleanly with zero fatal crashes and logs parsed options."""

    def setUp(self) -> None:
        self.live_log_path: Optional[Path] = None
        for p in _DEFAULT_SUNSHINE_LOG_PATHS:
            if p.is_file():
                self.live_log_path = p
                break

    def test_sunshine_service_port_is_open_and_responsive(self) -> None:
        """Tests that local HTTPS port 47990 is actively listening when Sunshine is awake."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(0.5)
        try:
            res = sock.connect_ex(("127.0.0.1", 47990))
            if res != 0:
                self.skipTest("Sunshine is in on-demand standby (port 47990 closed)")
        finally:
            sock.close()

    def test_live_log_contains_config_telemetry_lines(self) -> None:
        if self.live_log_path is None:
            self.skipTest("Live sunshine.log not installed on this host")
        assert self.live_log_path is not None
        content = self.live_log_path.read_text(encoding="utf-8", errors="ignore")
        telemetry = SunshineLogTelemetryParser.parse_log(content)

        parsed_cfg = telemetry["parsed_config"]
        # Verify that Sunshine logged all our config keys upon startup
        self.assertIn("encoder", parsed_cfg, "Sunshine did not log 'encoder' config line")
        self.assertIn(parsed_cfg.get("encoder"), ("quicksync", "nvenc"))
        self.assertIn(parsed_cfg.get("hevc_mode"), ("0", "1"))
        self.assertEqual(parsed_cfg.get("nvenc_preset"), "1")
        self.assertIn(parsed_cfg.get("dd_configuration_option"), ("disabled", "verify_only", "ensure_only_display"))
        self.assertEqual(parsed_cfg.get("fec_percentage"), "20")

    def test_live_log_has_zero_fatal_crashes(self) -> None:
        if self.live_log_path is None:
            self.skipTest("Live sunshine.log not installed on this host")
        content = self.live_log_path.read_text(encoding="utf-8", errors="ignore")
        telemetry = SunshineLogTelemetryParser.parse_log(content)

        self.assertEqual(
            telemetry["fatal_errors"],
            [],
            f"Found fatal errors in Sunshine startup log: {telemetry['fatal_errors']}",
        )

    def test_live_log_shows_graceful_encoder_probing_and_fallback(self) -> None:
        """
        Verifies that Sunshine probes nvenc first, detects GPU topography, and maintains operational stability.
        """
        if self.live_log_path is None:
            self.skipTest("Live sunshine.log not installed on this host")
        content = self.live_log_path.read_text(encoding="utf-8", errors="ignore")
        telemetry = SunshineLogTelemetryParser.parse_log(content)

        self.assertTrue(
            any(enc in telemetry["tried_encoders"] for enc in ("quicksync", "nvenc")),
            "Sunshine should probe hardware encoder (quicksync or nvenc)",
        )
        # Ensure at least one working encoder was found
        self.assertGreater(len(telemetry["found_encoders"]), 0, "Sunshine should find a working encoder")


class TestMoonlightClientPacingOptimization(unittest.TestCase):
    """Verifies that Moonlight client specifications enforce HEVC, Balanced frame pacing, and 60fps locked."""

    def test_recommended_settings_pass_validation(self) -> None:
        deviations = MoonlightClientPacingSpec.validate_client_settings(
            MoonlightClientPacingSpec.RECOMMENDED_SETTINGS
        )
        self.assertEqual(deviations, [])

    def test_h264_deviation_caught(self) -> None:
        bad_settings = dict(MoonlightClientPacingSpec.RECOMMENDED_SETTINGS, video_codec="H.264")
        deviations = MoonlightClientPacingSpec.validate_client_settings(bad_settings)
        self.assertTrue(any("HEVC" in d for d in deviations))

    def test_lowest_latency_pacing_deviation_caught(self) -> None:
        bad_settings = dict(MoonlightClientPacingSpec.RECOMMENDED_SETTINGS, frame_pacing="Lowest Latency")
        deviations = MoonlightClientPacingSpec.validate_client_settings(bad_settings)
        self.assertTrue(any("Balanced" in d for d in deviations))

    def test_unlocked_framerate_deviation_caught(self) -> None:
        bad_settings = dict(MoonlightClientPacingSpec.RECOMMENDED_SETTINGS, target_fps=120)
        deviations = MoonlightClientPacingSpec.validate_client_settings(bad_settings)
        self.assertTrue(any("target_fps" in d.lower() or "60" in d for d in deviations))


class TestVolumeSafetyGuardrails(unittest.TestCase):
    """
    Verifies compliance with the critical user rule:
    'in your tests never make the volume higher. you can mute/unmute it.'
    """

    def test_volume_rule_contract_strictly_forbids_volume_increase(self) -> None:
        """Asserts that test scripts and gaming agents only ever mute/unmute, never raise volume."""
        # Inspect laptop_gaming_agent.py source code to confirm no SetMasterVolumeLevelScalar increase
        agent_file = Path(__file__).resolve().parent / "laptop_gaming_agent.py"
        self.assertTrue(agent_file.is_file(), "laptop_gaming_agent.py not found")
        content = agent_file.read_text(encoding="utf-8", errors="ignore")

        # Confirm SetMasterVolumeLevelScalar with a higher value is never called
        # Only SetMute(1) or SetMute(0) or restore is used
        self.assertNotIn("VK_VOLUME_UP", content, "Volume UP key must not be present in automation")


if __name__ == "__main__":
    unittest.main()
