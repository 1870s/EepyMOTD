"""Draws the bundled eepy server icons (64x64 PNG) in the violet/blue EepyTab palette.

Run from the repository root:  python tools/make_icons.py
Requires Pillow. Icons are drawn at 8x size and downscaled for clean edges.
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

OUT = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "icons"
S = 512  # working size
FINAL = 64


def hex_rgb(value):
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def background(top_left, bottom_right):
    """Diagonal gradient in a rounded square."""
    a, b = hex_rgb(top_left), hex_rgb(bottom_right)
    grad = Image.new("RGB", (S, S))
    px = grad.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = lerp(a, b, (x + y) / (2 * (S - 1)))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, S - 1, S - 1), radius=S // 5, fill=255)
    icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    icon.paste(grad, (0, 0), mask)
    return icon, mask


def glow(icon, center, radius, color, strength):
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse(
        (center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius),
        fill=hex_rgb(color) + (strength,))
    layer = layer.filter(ImageFilter.GaussianBlur(radius / 2.2))
    return Image.alpha_composite(icon, layer)


def moon(icon, center, radius, color, cut_offset):
    """Crescent: a disc with a second disc cut out."""
    layer = Image.new("L", (S, S), 0)
    draw = ImageDraw.Draw(layer)
    cx, cy = center
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=255)
    ox, oy = cut_offset
    draw.ellipse((cx - radius + ox, cy - radius + oy, cx + radius + ox, cy + radius + oy), fill=0)
    fill = Image.new("RGBA", (S, S), hex_rgb(color) + (255,))
    icon.paste(fill, (0, 0), layer)
    return icon


def sparkle(draw, center, size, color):
    """Four-pointed star like the ✦ used in the MOTD."""
    cx, cy = center
    w = size * 0.28
    points = [(cx, cy - size), (cx + w, cy - w), (cx + size, cy), (cx + w, cy + w),
              (cx, cy + size), (cx - w, cy + w), (cx - size, cy), (cx - w, cy - w)]
    draw.polygon(points, fill=hex_rgb(color))


def finish(icon, mask, name):
    icon.putalpha(Image.composite(icon.getchannel("A"), Image.new("L", (S, S), 0), mask))
    OUT.mkdir(parents=True, exist_ok=True)
    icon.resize((FINAL, FINAL), Image.LANCZOS).save(OUT / f"{name}.png", optimize=True)
    print("wrote", OUT / f"{name}.png")


def base_night(top_left, bottom_right, moon_color="#F3EEFF", glow_color="#B98CFF"):
    icon, mask = background(top_left, bottom_right)
    icon = glow(icon, (248, 250), 150, glow_color, 120)
    icon = moon(icon, (250, 256), 132, moon_color, (78, -58))
    draw = ImageDraw.Draw(icon)
    sparkle(draw, (372, 150), 46, "#F3EEFF")
    sparkle(draw, (410, 292), 24, "#C9A6FF")
    sparkle(draw, (318, 392), 18, "#8DB6FF")
    return icon, mask, draw


def default_icon():
    icon, mask, _ = base_night("#5B3FA8", "#2C4E9E")
    finish(icon, mask, "eepy")


def maintenance_icon():
    icon, mask, draw = base_night("#4A4466", "#2F3550", moon_color="#DCD3F5", glow_color="#6F6890")
    # amber badge with an exclamation mark, bottom right
    cx, cy, r = 396, 396, 84
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=hex_rgb("#FFB86B"))
    draw.rounded_rectangle((cx - 13, cy - 52, cx + 13, cy + 16), radius=12, fill=hex_rgb("#3A2A1A"))
    draw.ellipse((cx - 14, cy + 30, cx + 14, cy + 58), fill=hex_rgb("#3A2A1A"))
    finish(icon, mask, "eepy-maintenance")


def winter_icon():
    icon, mask, draw = base_night("#4B57B8", "#2A6DAE", glow_color="#BFE3FF")
    # soft snow drifts at the bottom
    draw.ellipse((-80, 420, 300, 640), fill=hex_rgb("#EAF4FF"))
    draw.ellipse((200, 440, 620, 660), fill=hex_rgb("#DDEBFF"))
    for x, y, r in [(110, 110, 10), (180, 70, 7), (90, 330, 8), (450, 90, 9), (470, 210, 6), (140, 220, 6)]:
        draw.ellipse((x - r, y - r, x + r, y + r), fill=hex_rgb("#FFFFFF"))
    finish(icon, mask, "eepy-winter")


def halloween_icon():
    icon, mask, draw = base_night("#5A2E8C", "#B5541E", moon_color="#FFD8A8", glow_color="#FF9A4D")
    # tiny bats
    for cx, cy, k in [(140, 120, 1.0), (420, 410, 0.8)]:
        w, h = 60 * k, 24 * k
        draw.polygon([(cx - w, cy), (cx - w * 0.45, cy - h), (cx - w * 0.2, cy - h * 0.2), (cx, cy - h * 0.7),
                      (cx + w * 0.2, cy - h * 0.2), (cx + w * 0.45, cy - h), (cx + w, cy), (cx, cy + h * 0.6)],
                     fill=hex_rgb("#22132E"))
    finish(icon, mask, "eepy-halloween")


if __name__ == "__main__":
    default_icon()
    maintenance_icon()
    winter_icon()
    halloween_icon()
