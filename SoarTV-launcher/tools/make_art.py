"""Generates all official SoarTV Launcher, SoarTV Remote, and canonical assets/icons/ artwork.

Why:
    Keeps Android TV / Google TV, Fire TV, LG webOS, Samsung Tizen, the Companion Phone
    Remote (Android Adaptive Icon + iOS/Web PWA), and GitHub READMEs 100% visually unified
    from vector-rendered canonical masters in `assets/icons/` (`soartv_lockup_transparent.png`,
    `soartv_bird_transparent.png`, `soartv_bird.svg`) over SoarTV's seamless connected
    edge-to-edge Twilight Aurora gradient.

Usage:
    python3 SoarTV-launcher/tools/make_art.py
"""
from __future__ import annotations

import math
import os
from dataclasses import dataclass
from typing import Tuple

from PIL import Image, ImageDraw

RGBFloat = Tuple[float, float, float]


@dataclass(frozen=True)
class TwilightAuroraPalette:
    """Exact connected Twilight Aurora color tokens matching HomeView.sampleUnifiedAuroraColor."""

    top_left: RGBFloat = (64.0, 30.0, 104.0)
    top_right: RGBFloat = (10.0, 86.0, 146.0)
    bottom_left: RGBFloat = (34.0, 24.0, 62.0)
    bottom_right: RGBFloat = (18.0, 86.0, 90.0)
    violet: RGBFloat = (109.0, 40.0, 217.0)
    cyan: RGBFloat = (2.0, 132.0, 199.0)
    teal: RGBFloat = (13.0, 148.0, 136.0)
    magenta: RGBFloat = (157.0, 23.0, 77.0)
    plum: RGBFloat = (74.0, 35.0, 112.0)
    dark_scale: float = 0.80


class SoarTvArtGenerator:
    """Renders unified SoarTV brand icons and wide TV banners for all supported platforms.

    What:
        Generates connected edge-to-edge Twilight Aurora canvases (zero dark seams between
        colors, zero corner vignette) and composites the vector-rendered trimmed transparent
        Liquid Glass soaring-bird + `tv` lockup (or standalone bird mark) at exact platform
        safe-zone proportions.
    """

    MASTER_ICON_SIZE_PX: int = 1024
    # Because trimmed masters include soft drop-shadow/glow bleed (~12% total horizontal pad),
    # target ratios represent the trimmed RGBA bounding width relative to the canvas width:
    SQUARE_LOCKUP_WIDTH_RATIO: float = 0.68        # ~58% crisp subject width — bold & circle-safe
    BANNER_LOCKUP_WIDTH_RATIO: float = 0.56        # ~48% crisp subject width — Apple TV wide proportion
    BIRD_ONLY_WIDTH_RATIO: float = 0.68            # ~58% crisp bird width centered on 1:1 Aurora
    ADAPTIVE_CANVAS_PX: int = 432                  # 108dp @ xxxhdpi (4x)
    ADAPTIVE_LOCKUP_WIDTH_PX: int = 222            # ~65% visible subject width inside 72dp (288px) viewport
    ADAPTIVE_VIEWPORT_INSET: float = 0.14
    ADAPTIVE_VIEWPORT_SPAN: float = 0.72
    LEGACY_CORNER_RADIUS_RATIO: float = 0.23

    TV_BANNER_SIZE: Tuple[int, int] = (1280, 720)
    TV_ICON_SIZE: int = 512
    README_ICON_SIZE: int = 512
    LEGACY_ICON_SIZE: int = 192
    WEBOS_ICON_SIZE: int = 80
    WEBOS_LARGE_ICON_SIZE: int = 130
    TIZEN_ICON_SIZE: int = 512
    PWA_SIZES: Tuple[Tuple[str, int], ...] = (
        ("icon-512.png", 512),
        ("icon-192.png", 192),
        ("apple-touch-icon.png", 180),
        ("favicon.png", 64),
    )

    def __init__(self, repo_root: str, palette: TwilightAuroraPalette | None = None) -> None:
        self.repo_root = os.path.abspath(repo_root)
        self.palette = palette or TwilightAuroraPalette()
        self.icons_dir = os.path.join(self.repo_root, "assets", "icons")
        self.lockup_master_path = os.path.join(self.icons_dir, "soartv_lockup_transparent.png")
        self.bird_master_path = os.path.join(self.icons_dir, "soartv_bird_transparent.png")
        self._lockup_master: Image.Image | None = None
        self._bird_master: Image.Image | None = None

    def load_lockup_master(self) -> Image.Image:
        """Loads and caches the trimmed transparent RGBA SoarTV soaring-bird + `tv` lockup."""
        if self._lockup_master is None:
            if not os.path.isfile(self.lockup_master_path):
                raise FileNotFoundError(
                    f"Missing canonical SoarTV lockup master at {self.lockup_master_path}"
                )
            self._lockup_master = Image.open(self.lockup_master_path).convert("RGBA")
        return self._lockup_master

    def load_bird_master(self) -> Image.Image:
        """Loads and caches the trimmed transparent RGBA standalone bird mark (no `tv` text)."""
        if self._bird_master is None:
            if not os.path.isfile(self.bird_master_path):
                raise FileNotFoundError(
                    f"Missing canonical SoarTV bird-only master at {self.bird_master_path}"
                )
            self._bird_master = Image.open(self.bird_master_path).convert("RGBA")
        return self._bird_master

    @staticmethod
    def _mix_rgb(c0: RGBFloat, c1: RGBFloat, t: float) -> RGBFloat:
        f = 0.0 if t < 0.0 else 1.0 if t > 1.0 else t
        return (
            c0[0] + (c1[0] - c0[0]) * f,
            c0[1] + (c1[1] - c0[1]) * f,
            c0[2] + (c1[2] - c0[2]) * f,
        )

    def sample_aurora(self, u: float, v: float, adaptive_stretch: bool = False) -> Tuple[int, int, int]:
        """Evaluates the seamless connected Twilight Aurora RGB color at normalized (u, v) in [0, 1]."""
        p = self.palette
        if adaptive_stretch:
            u = max(0.0, min(1.0, (u - self.ADAPTIVE_VIEWPORT_INSET) / self.ADAPTIVE_VIEWPORT_SPAN))
            v = max(0.0, min(1.0, (v - self.ADAPTIVE_VIEWPORT_INSET) / self.ADAPTIVE_VIEWPORT_SPAN))

        top_row = self._mix_rgb(p.top_left, p.top_right, u)
        bot_row = self._mix_rgb(p.bottom_left, p.bottom_right, u)
        rgb = self._mix_rgb(top_row, bot_row, v)

        # Flowing S-curve diagonal aurora bridge connecting violet -> ocean sapphire -> petrol teal
        wave_center = 0.54 - 0.30 * u + 0.075 * math.sin(u * 4.6 + 0.5)
        dy_rib = (v - wave_center) / 0.42
        ribbon = math.exp(-(dy_rib * dy_rib))
        rib_col = self._mix_rgb(p.violet, p.cyan, u * 1.05 - 0.02)
        rgb = self._mix_rgb(rgb, rib_col, ribbon * 0.34)

        # Broad overlapping corner/edge blooms reaching cleanly to all four corners (zero vignette)
        orb_a_col = self._mix_rgb(p.violet, p.magenta, 0.28)
        w_a = math.exp(-(((u - 0.08) * 1.05) ** 2 + (v - 0.14) ** 2) / 0.36) * 0.42
        rgb = self._mix_rgb(rgb, orb_a_col, w_a)

        w_b = math.exp(-(((u - 0.90) * 1.05) ** 2 + (v - 0.10) ** 2) / 0.40) * 0.44
        rgb = self._mix_rgb(rgb, p.cyan, w_b)

        w_c = math.exp(-(((u - 0.86) * 1.05) ** 2 + (v - 0.90) ** 2) / 0.42) * 0.42
        rgb = self._mix_rgb(rgb, p.teal, w_c)

        w_d = math.exp(-(((u - 0.10) * 1.05) ** 2 + (v - 0.92) ** 2) / 0.42) * 0.30
        rgb = self._mix_rgb(rgb, p.plum, w_d)

        s = p.dark_scale
        return (
            max(0, min(255, int(round(rgb[0] * s)))),
            max(0, min(255, int(round(rgb[1] * s)))),
            max(0, min(255, int(round(rgb[2] * s)))),
        )

    def render_aurora(self, width: int, height: int, adaptive_stretch: bool = False) -> Image.Image:
        """Generates a smooth edge-to-edge Twilight Aurora RGB image at (width, height)."""
        mesh_max = 256
        if width >= height:
            rw = min(width, mesh_max)
            rh = max(64, int(round(rw * height / float(width))))
        else:
            rh = min(height, mesh_max)
            rw = max(64, int(round(rh * width / float(height))))

        mesh = Image.new("RGB", (rw, rh))
        px = mesh.load()
        for y in range(rh):
            v = (y + 0.5) / rh
            for x in range(rw):
                u = (x + 0.5) / rw
                px[x, y] = self.sample_aurora(u, v, adaptive_stretch=adaptive_stretch)
        return mesh.resize((width, height), Image.Resampling.BICUBIC)

    @staticmethod
    def _composite_centered(background: Image.Image, fg_rgba: Image.Image, target_width_px: int) -> Image.Image:
        """Scales trimmed transparent `fg_rgba` proportionally to `target_width_px` and centers over `background`."""
        scale = target_width_px / float(fg_rgba.width)
        new_w = max(1, int(round(fg_rgba.width * scale)))
        new_h = max(1, int(round(fg_rgba.height * scale)))
        fg_scaled = fg_rgba.resize((new_w, new_h), Image.Resampling.LANCZOS)

        canvas = background.convert("RGBA").copy()
        layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        ox = (canvas.width - new_w) // 2
        oy = (canvas.height - new_h) // 2
        layer.paste(fg_scaled, (ox, oy))
        return Image.alpha_composite(canvas, layer)

    def render_square_icon(self, size: int) -> Image.Image:
        """Renders a full-bleed 1:1 SoarTV icon safe for Google TV circles, webOS, Tizen & iOS."""
        base = self.render_aurora(self.MASTER_ICON_SIZE_PX, self.MASTER_ICON_SIZE_PX)
        target_w = int(round(self.MASTER_ICON_SIZE_PX * self.SQUARE_LOCKUP_WIDTH_RATIO))
        master_sq = self._composite_centered(base, self.load_lockup_master(), target_w).convert("RGB")
        if size == self.MASTER_ICON_SIZE_PX:
            return master_sq
        return master_sq.resize((size, size), Image.Resampling.LANCZOS)

    def render_tv_banner(self, width: int = 1280, height: int = 720) -> Image.Image:
        """Renders the native 16:9 Android TV / Google TV / Fire TV wide banner."""
        base = self.render_aurora(width, height)
        target_w = int(round(width * self.BANNER_LOCKUP_WIDTH_RATIO))
        return self._composite_centered(base, self.load_lockup_master(), target_w).convert("RGB")

    def render_bird_icon(self, size: int = 1024) -> Image.Image:
        """Renders the standalone bird-only icon (without `tv` text) on connected Twilight Aurora."""
        base = self.render_aurora(size, size)
        target_w = int(round(size * self.BIRD_ONLY_WIDTH_RATIO))
        return self._composite_centered(base, self.load_bird_master(), target_w).convert("RGB")

    def render_rounded_icon(self, size: int = 512) -> Image.Image:
        """Renders a 2x-supersampled rounded-squircle RGBA icon for GitHub READMEs & docs."""
        super_sz = size * 2
        sq = self.render_square_icon(super_sz).convert("RGBA")
        mask = Image.new("L", (super_sz, super_sz), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            (0, 0, super_sz - 1, super_sz - 1),
            radius=int(round(super_sz * self.LEGACY_CORNER_RADIUS_RATIO)),
            fill=255,
        )
        sq.putalpha(mask)
        return sq.resize((size, size), Image.Resampling.LANCZOS)

    def render_adaptive_layers(self) -> Tuple[Image.Image, Image.Image, Image.Image]:
        """Renders Android Adaptive Icon (background 432px, transparent foreground 432px, legacy 192px)."""
        sz = self.ADAPTIVE_CANVAS_PX
        bg = self.render_aurora(sz, sz, adaptive_stretch=True).convert("RGB")
        empty = Image.new("RGBA", (sz, sz), (0, 0, 0, 0))
        fg = self._composite_centered(empty, self.load_lockup_master(), self.ADAPTIVE_LOCKUP_WIDTH_PX)

        legacy = self.render_rounded_icon(self.LEGACY_ICON_SIZE)
        return bg, fg, legacy

    def generate_canonical_assets(self) -> None:
        """Writes all canonical master icon & banner images into `assets/icons/`."""
        os.makedirs(self.icons_dir, exist_ok=True)
        bg, fg, _ = self.render_adaptive_layers()
        self.render_square_icon(self.MASTER_ICON_SIZE_PX).save(
            os.path.join(self.icons_dir, "soartv_icon.png"), format="PNG", optimize=True
        )
        self.render_rounded_icon(self.README_ICON_SIZE).save(
            os.path.join(self.icons_dir, "soartv_icon_rounded.png"), format="PNG", optimize=True
        )
        self.render_tv_banner(*self.TV_BANNER_SIZE).save(
            os.path.join(self.icons_dir, "soartv_banner_16x9.png"), format="PNG", optimize=True
        )
        self.render_bird_icon(self.MASTER_ICON_SIZE_PX).save(
            os.path.join(self.icons_dir, "soartv_bird_icon.png"), format="PNG", optimize=True
        )
        bg.save(os.path.join(self.icons_dir, "adaptive_background.png"), format="PNG", optimize=True)
        fg.save(os.path.join(self.icons_dir, "adaptive_foreground.png"), format="PNG", optimize=True)

    def generate_launcher_assets(self) -> None:
        """Writes all TV launcher icons & banners (Android TV, Google TV, webOS, Tizen)."""
        launcher_dir = os.path.join(self.repo_root, "SoarTV-launcher")
        res_nodpi = os.path.join(launcher_dir, "app", "res", "drawable-nodpi")
        os.makedirs(res_nodpi, exist_ok=True)

        bg, fg, _ = self.render_adaptive_layers()
        self.render_square_icon(self.TV_ICON_SIZE).save(
            os.path.join(res_nodpi, "icon.png"), format="PNG", optimize=True
        )
        bg.save(os.path.join(res_nodpi, "icon_background.png"), format="PNG", optimize=True)
        fg.save(os.path.join(res_nodpi, "icon_foreground.png"), format="PNG", optimize=True)
        self.render_tv_banner(*self.TV_BANNER_SIZE).save(
            os.path.join(res_nodpi, "banner.png"), format="PNG", optimize=True
        )

        webos_dir = os.path.join(launcher_dir, "webos")
        if os.path.isdir(webos_dir):
            self.render_square_icon(self.WEBOS_ICON_SIZE).save(
                os.path.join(webos_dir, "icon.png"), format="PNG", optimize=True
            )
            self.render_square_icon(self.WEBOS_LARGE_ICON_SIZE).save(
                os.path.join(webos_dir, "largeIcon.png"), format="PNG", optimize=True
            )

        tizen_dir = os.path.join(launcher_dir, "tizen")
        if os.path.isdir(tizen_dir):
            self.render_square_icon(self.TIZEN_ICON_SIZE).save(
                os.path.join(tizen_dir, "icon.png"), format="PNG", optimize=True
            )

    def generate_remote_assets(self) -> None:
        """Writes all Companion Remote Android Adaptive Icon and PWA/iOS icons (matching TV app)."""
        remote_dir = os.path.join(self.repo_root, "SoarTV-remote")
        android_nodpi = os.path.join(remote_dir, "android", "res", "drawable-nodpi")
        os.makedirs(android_nodpi, exist_ok=True)

        bg, fg, legacy = self.render_adaptive_layers()
        bg.save(os.path.join(android_nodpi, "ic_launcher_background.png"), format="PNG", optimize=True)
        fg.save(os.path.join(android_nodpi, "ic_launcher_foreground.png"), format="PNG", optimize=True)
        legacy.save(os.path.join(android_nodpi, "ic_launcher.png"), format="PNG", optimize=True)

        public_dir = os.path.join(remote_dir, "public")
        launcher_remote_dir = os.path.join(self.repo_root, "SoarTV-launcher", "app", "assets", "remote")
        for out_dir in (public_dir, launcher_remote_dir):
            if os.path.isdir(out_dir):
                for filename, size in self.PWA_SIZES:
                    self.render_square_icon(size).save(
                        os.path.join(out_dir, filename), format="PNG", optimize=True
                    )

    def generate_all(self) -> None:
        """Generates all canonical assets/icons/, TV Launcher, and Companion Remote icons."""
        self.generate_canonical_assets()
        self.generate_launcher_assets()
        self.generate_remote_assets()


def main() -> None:
    repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    generator = SoarTvArtGenerator(repo_root)
    generator.generate_all()
    print("Generated unified vector-sharp SoarTV icons in assets/icons/, Launcher & Remote.")


if __name__ == "__main__":
    main()
