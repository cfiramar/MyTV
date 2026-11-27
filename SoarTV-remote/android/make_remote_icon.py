"""Generates the unified SoarTV Adaptive Icon & PWA icons for SoarTV-Remote.apk and mobile browsers.

Why:
    SoarTV Remote intentionally uses the exact same official Liquid Glass soaring-bird + `tv`
    icon as the SoarTV TV Launcher (`assets/icons/soartv_lockup_transparent.png` on the seamless
    connected Twilight Aurora mesh). Delegating to `SoarTvArtGenerator` guarantees the TV app
    and Companion Remote never drift out of sync across teams or releases.

Usage:
    python SoarTV-remote/android/make_remote_icon.py
"""
from __future__ import annotations

import os
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOLS_DIR = os.path.join(REPO_ROOT, "SoarTV-launcher", "tools")
if TOOLS_DIR not in sys.path:
    sys.path.insert(0, TOOLS_DIR)

from make_art import SoarTvArtGenerator  # noqa: E402


def main() -> None:
    """Regenerates SoarTV-Remote Android Adaptive Icon and PWA PNG assets."""
    generator = SoarTvArtGenerator(REPO_ROOT)
    generator.generate_remote_assets()
    print("Generated unified SoarTV-Remote Adaptive Icon & PWA PNGs successfully.")


if __name__ == "__main__":
    main()
