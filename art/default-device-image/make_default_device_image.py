#!/usr/bin/env python3
"""Make the assets for the default device image.

This script opens the master board drawing and finds the background
colour from the border. It crops the drawing to the board with a
margin. Then it maps the luma values to a 24-colour ramp and writes
one palette PNG for each Compose density.

Run this script from the repository root:

    python3 art/default-device-image/make_default_device_image.py

Use --source or --out-dir to set other paths.

The constant DEFAULT_DEVICE_IMAGE_BACKGROUND in
core/common/src/commonMain/kotlin/com/snapshotpb/configuration/common/composable/DeviceBadge.kt
must equal the background colour that this script prints. A JVM test
checks this value.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import statistics

from PIL import Image, ImageStat

# Repository root found from the path of this script.
REPO_ROOT = Path(__file__).resolve().parents[2]
# Default path of the master drawing.
DEFAULT_SOURCE = REPO_ROOT / "art/default-device-image/snapshotpb-view.png"
# Default output folder for Compose resources.
DEFAULT_OUT_DIR = REPO_ROOT / "core/resources/src/commonMain/composeResources"

# Width of the outer border in pixels for the background sample.
BORDER_PX = 16
# Luma offset above the background luma that marks the board drawing.
DRAWING_THRESHOLD = 40
# Margin fraction of the larger box side to add on each side.
MARGIN_FRACTION = 0.06
# Output height in pixels for the xxxhdpi asset.
XXXHDPI_HEIGHT = 1096
# Number of colours in the output palette ramp.
LEVELS = 24
# Luma margin above the background luma that maps to index 0.
NOISE_FLOOR = 3
# Percentile of the luma histogram of the crop that selects the line luma.
LINE_PERCENTILE = 99.99
# Output file name in each density folder.
FILE_NAME = "default-device-image.png"
# Compose density names and their scale factors.
DENSITIES: tuple[tuple[str, float], ...] = (
    ("mdpi", 1.0),
    ("hdpi", 1.5),
    ("xhdpi", 2.0),
    ("xxhdpi", 3.0),
    ("xxxhdpi", 4.0),
)


def load_rgb(path: Path) -> Image.Image:
    """Open the image at path and return an RGB copy."""
    with Image.open(path) as raw:
        return raw.convert("RGB")


def background_colour(img: Image.Image) -> tuple[int, int, int]:
    """Return the median RGB colour of the outer border."""
    width, height = img.size
    border = min(BORDER_PX, width, height)
    if 2 * border >= width or 2 * border >= height:
        strips = [img]
    else:
        strips = [
            img.crop((0, 0, width, border)),
            img.crop((0, height - border, width, height)),
            img.crop((0, border, border, height - border)),
            img.crop((width - border, border, width, height - border)),
        ]
    channels: tuple[list[int], list[int], list[int]] = ([], [], [])
    for strip in strips:
        raw = strip.tobytes()
        channels[0].extend(raw[0::3])
        channels[1].extend(raw[1::3])
        channels[2].extend(raw[2::3])
    return (
        round(statistics.median(channels[0])),
        round(statistics.median(channels[1])),
        round(statistics.median(channels[2])),
    )


def luma(rgb: tuple[int, int, int]) -> int:
    """Return the Pillow luma value of one RGB colour."""
    pixel = Image.new("RGB", (1, 1), rgb).convert("L")
    value = pixel.getpixel((0, 0))
    assert isinstance(value, int)
    return value


def drawing_bounds(gray: Image.Image, bg_luma: int) -> tuple[int, int, int, int]:
    """Return the pixel box with luma above bg_luma plus DRAWING_THRESHOLD."""
    cutoff = bg_luma + DRAWING_THRESHOLD
    mask = gray.point(lambda value: 255 if value > cutoff else 0)
    bbox = mask.getbbox()
    if bbox is None:
        raise SystemExit(
            f"No pixel has luma above {cutoff} (background luma {bg_luma})."
        )
    return bbox


def crop_box(
    bounds: tuple[int, int, int, int],
    margin_fraction: float,
) -> tuple[int, int, int, int]:
    """Add a margin of margin_fraction times the larger side on each side."""
    left, top, right, bottom = bounds
    width = right - left
    height = bottom - top
    margin = round(margin_fraction * max(width, height))
    return (left - margin, top - margin, right + margin, bottom + margin)


def crop_with_fill(
    img: Image.Image,
    box: tuple[int, int, int, int],
    fill: int | tuple[int, int, int],
) -> Image.Image:
    """Crop box from img and fill any area outside img with fill."""
    left, top, right, bottom = box
    out = Image.new(img.mode, (right - left, bottom - top), fill)
    src_left = max(0, left)
    src_top = max(0, top)
    src_right = min(img.width, right)
    src_bottom = min(img.height, bottom)
    if src_left < src_right and src_top < src_bottom:
        region = img.crop((src_left, src_top, src_right, src_bottom))
        out.paste(region, (src_left - left, src_top - top))
    return out


def line_colour(
    rgb_crop: Image.Image,
    gray_crop: Image.Image,
) -> tuple[tuple[int, int, int], int]:
    """Find the line luma at LINE_PERCENTILE and the mean RGB colour there."""
    histogram = gray_crop.histogram()
    total = sum(histogram)
    target = total * LINE_PERCENTILE / 100.0
    count = 0
    line_luma = 255
    for value, freq in enumerate(histogram):
        count += freq
        if count >= target:
            line_luma = value
            break
    mask = gray_crop.point(lambda value: 255 if value >= line_luma else 0)
    stat = ImageStat.Stat(rgb_crop, mask)
    colour = (
        round(stat.mean[0]),
        round(stat.mean[1]),
        round(stat.mean[2]),
    )
    return (colour, line_luma)


def ramp_palette(
    bg: tuple[int, int, int],
    line: tuple[int, int, int],
    levels: int,
) -> list[int]:
    """Return a flat RGB palette ramp from bg to line."""
    palette: list[int] = []
    for index in range(levels):
        for channel in range(3):
            value = round(bg[channel] + (line[channel] - bg[channel]) * index / (levels - 1))
            palette.append(value)
    return palette


def ramp_lut(bg_luma: int, line_luma: int, levels: int) -> list[int]:
    """Return a 256-entry lookup table from luma to palette index."""
    span = line_luma - bg_luma - NOISE_FLOOR
    if span <= 0:
        raise SystemExit(
            f"Line luma {line_luma} is not above background luma {bg_luma} plus noise floor {NOISE_FLOOR}."
        )
    lut: list[int] = []
    for value in range(256):
        scaled = (value - bg_luma - NOISE_FLOOR) / span
        clamped = min(1.0, max(0.0, scaled))
        lut.append(round(clamped * (levels - 1)))
    return lut


def render(
    gray_crop: Image.Image,
    size: tuple[int, int],
    lut: list[int],
    palette: list[int],
) -> Image.Image:
    """Scale gray_crop to size, apply lut, and return a palette image."""
    resized = gray_crop.resize(size, Image.LANCZOS)
    indexed = resized.point(lut)
    out = Image.frombytes("P", size, indexed.tobytes())
    out.putpalette(palette)
    return out


def parse_args() -> argparse.Namespace:
    """Parse command-line options for the script."""
    parser = argparse.ArgumentParser(
        description="Make the palette PNG assets for the default device image."
    )
    parser.add_argument(
        "--source",
        type=Path,
        default=DEFAULT_SOURCE,
        help="Path to the master PNG drawing.",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=DEFAULT_OUT_DIR,
        help="Output composeResources folder.",
    )
    return parser.parse_args()


def display_path(path: Path) -> str:
    """Return path relative to REPO_ROOT when possible."""
    resolved = path.resolve()
    try:
        return str(resolved.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def main() -> None:
    """Make all density PNG files and print their sizes and background colour."""
    args = parse_args()
    rgb = load_rgb(args.source)
    bg = background_colour(rgb)
    bg_luma = luma(bg)
    gray = rgb.convert("L")

    bounds = drawing_bounds(gray, bg_luma)
    box = crop_box(bounds, MARGIN_FRACTION)
    rgb_crop = crop_with_fill(rgb, box, bg)
    gray_crop = crop_with_fill(gray, box, bg_luma)

    line, line_luma = line_colour(rgb_crop, gray_crop)
    palette = ramp_palette(bg, line, LEVELS)
    lut = ramp_lut(bg_luma, line_luma, LEVELS)

    crop_width, crop_height = gray_crop.size
    for name, density in DENSITIES:
        height = round(XXXHDPI_HEIGHT * density / 4)
        width = round(crop_width * height / crop_height)
        image = render(gray_crop, (width, height), lut, palette)
        dest = args.out_dir / f"drawable-{name}" / FILE_NAME
        dest.parent.mkdir(parents=True, exist_ok=True)
        image.save(dest, format="PNG", optimize=True)
        byte_size = dest.stat().st_size
        print(f"{display_path(dest)}  {width}x{height}  {byte_size} bytes")

    print(
        f"DEFAULT_DEVICE_IMAGE_BACKGROUND = Color(0xFF{bg[0]:02X}{bg[1]:02X}{bg[2]:02X})"
    )


if __name__ == "__main__":
    main()
