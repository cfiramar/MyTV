"""Unit tests verifying unified SoarTV Launcher & Remote icon/banner generation and wiring."""
from __future__ import annotations

import json
import os
import sys
import unittest

from PIL import Image

TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(TOOLS_DIR))
if TOOLS_DIR not in sys.path:
    sys.path.insert(0, TOOLS_DIR)

from make_art import SoarTvArtGenerator  # noqa: E402


class SoarTvArtGeneratorTests(unittest.TestCase):
    """Validates generated icon dimensions, connected aurora edges, and Remote PWA wiring."""

    def setUp(self) -> None:
        self.generator = SoarTvArtGenerator(REPO_ROOT)

    def test_aurora_corners_stay_colored_without_dark_vignette(self) -> None:
        """All 4 corners and center must remain richly colored (zero black vignette or dark seams)."""
        for u, v in ((0.0, 0.0), (1.0, 0.0), (0.0, 1.0), (1.0, 1.0), (0.5, 0.5)):
            r, g, b = self.generator.sample_aurora(u, v)
            self.assertGreater(max(r, g, b), 45, f"Corner/point ({u}, {v}) too dark: {(r, g, b)}")

    def test_launcher_icons_and_banner_exist_with_exact_dimensions(self) -> None:
        """Android TV, Google TV Adaptive, LG webOS, and Samsung Tizen assets match target specs."""
        expected = {
            "SoarTV-launcher/app/res/drawable-nodpi/icon.png": (512, 512),
            "SoarTV-launcher/app/res/drawable-nodpi/icon_background.png": (432, 432),
            "SoarTV-launcher/app/res/drawable-nodpi/icon_foreground.png": (432, 432),
            "SoarTV-launcher/app/res/drawable-nodpi/banner.png": (1280, 720),
            "SoarTV-launcher/webos/icon.png": (80, 80),
            "SoarTV-launcher/webos/largeIcon.png": (130, 130),
            "SoarTV-launcher/tizen/icon.png": (512, 512),
        }
        for rel, size in expected.items():
            full = os.path.join(REPO_ROOT, rel)
            self.assertTrue(os.path.isfile(full), f"Missing asset: {rel}")
            with Image.open(full) as im:
                self.assertEqual(im.size, size, f"Unexpected size for {rel}: {im.size}")

        fg_path = os.path.join(REPO_ROOT, "SoarTV-launcher/app/res/drawable-nodpi/icon_foreground.png")
        with Image.open(fg_path) as fg:
            self.assertEqual(fg.mode, "RGBA")
            self.assertEqual(fg.getpixel((0, 0))[3], 0, "Adaptive foreground corners must be transparent")

    def test_remote_adaptive_and_pwa_icons_match_launcher_assets(self) -> None:
        """SoarTV Remote uses the exact unified icon across Android APK and mobile PWA."""
        expected = {
            "SoarTV-remote/android/res/drawable-nodpi/ic_launcher_background.png": (432, 432),
            "SoarTV-remote/android/res/drawable-nodpi/ic_launcher_foreground.png": (432, 432),
            "SoarTV-remote/android/res/drawable-nodpi/ic_launcher.png": (192, 192),
            "SoarTV-remote/public/icon-512.png": (512, 512),
            "SoarTV-remote/public/icon-192.png": (192, 192),
            "SoarTV-remote/public/apple-touch-icon.png": (180, 180),
            "SoarTV-remote/public/favicon.png": (64, 64),
            "SoarTV-launcher/app/assets/remote/icon-512.png": (512, 512),
            "SoarTV-launcher/app/assets/remote/icon-192.png": (192, 192),
            "SoarTV-launcher/app/assets/remote/apple-touch-icon.png": (180, 180),
            "SoarTV-launcher/app/assets/remote/favicon.png": (64, 64),
        }
        for rel, size in expected.items():
            full = os.path.join(REPO_ROOT, rel)
            self.assertTrue(os.path.isfile(full), f"Missing remote asset: {rel}")
            with Image.open(full) as im:
                self.assertEqual(im.size, size, f"Unexpected size for {rel}: {im.size}")

        # Verify manifest.json and index.html wire the new PNG icons cleanly
        manifest_path = os.path.join(REPO_ROOT, "SoarTV-remote/public/manifest.json")
        with open(manifest_path, encoding="utf-8") as f:
            manifest = json.load(f)
        srcs = {entry["src"] for entry in manifest.get("icons", [])}
        self.assertIn("/icon-192.png", srcs)
        self.assertIn("/icon-512.png", srcs)

        html_path = os.path.join(REPO_ROOT, "SoarTV-remote/public/index.html")
        with open(html_path, encoding="utf-8") as f:
            html = f.read()
        self.assertIn('href="/apple-touch-icon.png"', html)
        self.assertIn('href="/favicon.png"', html)
        self.assertNotIn("data:image/svg+xml", html[:1200])

    def test_canonical_assets_icons_and_readme_wiring(self) -> None:
        """All canonical icons live in assets/icons/ (trimmed transparent marks + bird SVG + README inline icon)."""
        bird_svg = os.path.join(REPO_ROOT, "assets/icons/soartv_bird.svg")
        bird_transparent = os.path.join(REPO_ROOT, "assets/icons/soartv_bird_transparent.png")
        bird_icon = os.path.join(REPO_ROOT, "assets/icons/soartv_bird_icon.png")
        lockup_transparent = os.path.join(REPO_ROOT, "assets/icons/soartv_lockup_transparent.png")
        readme_icon = os.path.join(REPO_ROOT, "assets/icons/soartv_icon_rounded.png")
        main_icon = os.path.join(REPO_ROOT, "assets/icons/soartv_icon.png")
        banner = os.path.join(REPO_ROOT, "assets/icons/soartv_banner_16x9.png")

        self.assertTrue(os.path.isfile(bird_svg))
        with open(bird_svg, encoding="utf-8") as f:
            self.assertIn("<path", f.read())

        with Image.open(bird_transparent) as im:
            self.assertEqual(im.mode, "RGBA")
            self.assertGreater(im.width, 600)
            self.assertNotEqual(im.width, im.height, "Bird transparent mark should be tightly trimmed")

        with Image.open(lockup_transparent) as im:
            self.assertEqual(im.mode, "RGBA")
            self.assertGreater(im.width, im.height * 1.8, "Lockup transparent mark must be trimmed wide (non-square)")

        with Image.open(bird_icon) as im:
            self.assertEqual(im.size, (1024, 1024))
            self.assertEqual(im.mode, "RGB")

        with Image.open(main_icon) as im:
            self.assertEqual(im.size, (1024, 1024))

        with Image.open(banner) as im:
            self.assertEqual(im.size, (1280, 720))

        with Image.open(readme_icon) as im:
            self.assertEqual(im.size, (512, 512))
            self.assertEqual(im.mode, "RGBA")
            self.assertEqual(im.getpixel((0, 0))[3], 0)

        readme_path = os.path.join(REPO_ROOT, "README.md")
        with open(readme_path, encoding="utf-8") as f:
            first_line = f.readline()
        self.assertIn('src="assets/icons/soartv_icon_rounded.png"', first_line)
        self.assertIn("SoarTV", first_line)


if __name__ == "__main__":
    unittest.main()
