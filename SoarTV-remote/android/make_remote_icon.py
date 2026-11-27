"""Generates the crisp Liquid Glass Adaptive Icon for SoarTV-Remote.apk matching SoarTV's official icon.png.

Usage: python SoarTV-remote/android/make_remote_icon.py
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "res")
NODPI = os.path.join(RES, "drawable-nodpi")

# Official SoarTV Liquid Glass gradient (identical to SoarTV-launcher/tools/make_art.py)
STOPS = [
    (0.0, (56, 189, 248)),   # Sky Cyan
    (0.5, (99, 102, 241)),   # Royal Indigo
    (1.0, (168, 85, 247)),   # Luminous Purple
]


def make_gradient(size: int, angle: float = 35.0) -> Image.Image:
    # Render at 1/2 scale for fast smooth interpolation, then bicubic upscale
    s = max(128, size // 2)
    img = Image.new("RGB", (s, s))
    px = img.load()
    a = math.radians(angle)
    dx, dy = math.cos(a), math.sin(a)
    lo = min(0.0, s * dx, s * dy, s * dx + s * dy)
    hi = max(0.0, s * dx, s * dy, s * dx + s * dy)
    span = hi - lo
    for y in range(s):
        for x in range(s):
            raw_t = ((x * dx + y * dy) - lo) / span
            # Stretch full cyan->indigo->purple spectrum across the visible 72% Adaptive Icon viewport
            t = max(0.0, min(1.0, (raw_t - 0.16) / 0.68))
            for i in range(len(STOPS) - 1):
                t0, c0 = STOPS[i]
                t1, c1 = STOPS[i + 1]
                if t <= t1 or i == len(STOPS) - 2:
                    f = max(0.0, min(1.0, (t - t0) / (t1 - t0)))
                    px[x, y] = tuple(int(round(c0[k] + (c1[k] - c0[k]) * f)) for k in range(3))
                    break
    return img.resize((size, size), Image.Resampling.BICUBIC).convert("RGBA")


def draw_sparkle(draw: ImageDraw.ImageDraw, cx: float, cy: float, r: float, fill: tuple) -> None:
    pts = []
    for i in range(8):
        ang = math.pi / 2 * (i // 2) + (0.0 if i % 2 == 0 else math.pi / 4)
        rad = r if i % 2 == 0 else r * 0.28
        pts.append((cx + rad * math.cos(ang - math.pi / 2), cy + rad * math.sin(ang - math.pi / 2)))
    draw.polygon(pts, fill=fill)


def render_foreground(size: int = 432) -> Image.Image:
    """Renders a sleek, Apple-TV-inspired pure-white remote glyph centered with generous breathing room inside the Adaptive Icon safe zone."""
    s = size * 4  # 4x supersampling for razor-sharp antialiased curves
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    white = (255, 255, 255, 255)
    frosted_fill = (255, 255, 255, 36)
    soft_white = (255, 255, 255, 232)

    # Balanced vertical remote proportions with generous breathing margin inside both One UI squircle & Pixel circle
    rx0, ry0 = s * 0.370, s * 0.275
    rx1, ry1 = s * 0.630, s * 0.725
    corner_r = int(s * 0.076)
    stroke_w = int(s * 0.032)

    # Subtle frosted glass interior + crisp pure-white silhouette outline
    d.rounded_rectangle((rx0, ry0, rx1, ry1), radius=corner_r, fill=frosted_fill, outline=white, width=stroke_w)

    # Sculpted circular D-Pad / Clickpad ring in upper half
    cx = s * 0.50
    ring_cy = s * 0.425
    ring_r = s * 0.068
    ring_w = int(s * 0.022)
    d.ellipse(
        (cx - ring_r, ring_cy - ring_r, cx + ring_r, ring_cy + ring_r),
        outline=soft_white,
        width=ring_w,
    )
    # Pure white center OK orb
    ok_r = s * 0.025
    d.ellipse((cx - ok_r, ring_cy - ok_r, cx + ok_r, ring_cy + ok_r), fill=white)

    # Twin clean thumb-orb dots (Back + Home)
    dot_y = s * 0.562
    dot_r = s * 0.020
    dot_dx = s * 0.046
    d.ellipse((cx - dot_dx - dot_r, dot_y - dot_r, cx - dot_dx + dot_r, dot_y + dot_r), fill=white)
    d.ellipse((cx + dot_dx - dot_r, dot_y - dot_r, cx + dot_dx + dot_r, dot_y + dot_r), fill=white)

    # Minimalist volume pill bar
    bar_w = s * 0.118
    bar_h = s * 0.024
    bar_y = s * 0.636
    d.rounded_rectangle(
        (cx - bar_w / 2, bar_y - bar_h / 2, cx + bar_w / 2, bar_y + bar_h / 2),
        radius=int(bar_h / 2),
        fill=white,
    )

    # Signature SoarTV 4-point sparkle tucked cleanly inside safe zone beside upper-right of remote
    draw_sparkle(d, s * 0.690, s * 0.315, s * 0.042, white)

    # Soft tinted ambient shadow beneath glyph (same technique as SoarTV-launcher/tools/make_art.py)
    shadow = layer.copy().filter(ImageFilter.GaussianBlur(radius=s * 0.018))
    tinted_shadow = Image.new("RGBA", (s, s), (45, 22, 92, 0))
    tinted_shadow.putalpha(shadow.getchannel("A").point(lambda v: int(v * 0.28)))

    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.alpha_composite(tinted_shadow, (0, int(s * 0.014)))
    out.alpha_composite(layer)
    return out.resize((size, size), Image.Resampling.LANCZOS)


def main() -> None:
    os.makedirs(NODPI, exist_ok=True)
    bg = make_gradient(432)
    fg = render_foreground(432)

    bg.save(os.path.join(NODPI, "ic_launcher_background.png"), format="PNG", optimize=True)
    fg.save(os.path.join(NODPI, "ic_launcher_foreground.png"), format="PNG", optimize=True)

    # Legacy rounded-squircle fallback for API 24-25 (192x192)
    combined = Image.alpha_composite(bg, fg)
    s = 432
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.23), fill=255)
    legacy = combined.copy()
    legacy.putalpha(mask)
    legacy.resize((192, 192), Image.Resampling.LANCZOS).save(
        os.path.join(NODPI, "ic_launcher.png"), format="PNG", optimize=True
    )

    # Optional preview sheet in scratch if requested via env var
    preview_path = os.environ.get("PREVIEW_OUT", "")
    if preview_path:
        # Show One UI squircle + Pixel circle + side-by-side comparison with TV icon
        sheet = Image.new("RGBA", (960, 360), (14, 16, 36, 255))
        # Crop adaptive icon center 72% (how Android launchers actually crop 108dp -> 72dp visible viewport)
        m = int(432 * (18 / 108.0))
        vis = combined.crop((m, m, 432 - m, 432 - m)).resize((220, 220), Image.Resampling.LANCZOS)

        # 1. Samsung One UI Squircle mask
        sq_mask = Image.new("L", (220, 220), 0)
        ImageDraw.Draw(sq_mask).rounded_rectangle((0, 0, 219, 219), radius=74, fill=255)
        sq = vis.copy()
        sq.putalpha(sq_mask)
        sheet.alpha_composite(sq, (90, 70))

        # 2. Circle mask
        ci_mask = Image.new("L", (220, 220), 0)
        ImageDraw.Draw(ci_mask).ellipse((0, 0, 219, 219), fill=255)
        ci = vis.copy()
        ci.putalpha(ci_mask)
        sheet.alpha_composite(ci, (370, 70))

        # 3. Official SoarTV TV icon.png beside it for visual family check
        tv_icon_path = os.path.normpath(os.path.join(HERE, "../../SoarTV-launcher/app/res/drawable-nodpi/icon.png"))
        if os.path.isfile(tv_icon_path):
            tv_ic = Image.open(tv_icon_path).convert("RGBA").resize((220, 220), Image.Resampling.LANCZOS)
            sheet.alpha_composite(tv_ic, (650, 70))

        sheet.save(preview_path, format="PNG")
    print("Generated SoarTV-Remote Adaptive Icon PNGs successfully.")


if __name__ == "__main__":
    main()
