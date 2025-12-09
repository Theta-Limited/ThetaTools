#!/usr/bin/python3

# BulkDownloader.py
# Copyright 2024 Theta Informatics LLC
# Apache 2.0 license
#
# Given a lat,lon coordinates that make a large bounding
# box, bulk download slightly-overlapping DTM tiles of particular type
# pass in upper-left, lower-right coordinates, DTM type, verbose flag
# read OPENATHENA_API_KEY from env, work with a OA Core RESTful API
# server to fetch the tiles
# --bounds 12.35 41.8 12.65 42  left bottom right top

# option for dry-run for printing out what script *would* do
# check API access to Core API server first to establish connectivity
# check admin api access to core api server to establish permission

# for calculating tiles, don't clip the edge tiles so that they all stay inside
# our bounding box.  Instead, all tiles same size and they may spill over the bounding
# box slightly

# testing
# ft sill bounding box is: -98.756447 34.632163 -98.270302 34.72838
# HI island including some water: -158.293488 21.244838 -157.618927 21.705921

# still need to add support for specifying dataset type and how to
# handle fallbacks?

import math
import argparse
from dataclasses import dataclass
import json
import os
from urllib.parse import urlparse
from urllib.request import urlopen
from urllib.error import URLError, HTTPError
import sys

EARTH_RADIUS_KM = 6371.0
# Approximate length of 1 degree of latitude in km
KM_PER_DEG_LAT = 111.32

# ---- Defaults ----
DEFAULT_TILE_SIZE_M = 10_000.0  # 10 km tiles by default

# ---- ASCII box layout ----
LINE_WIDTH = 79       # assume ~80-char terminal
PLUS_LEFT_COL = 10
PLUS_RIGHT_COL = LINE_WIDTH - 10
OPENATHENA_API_KEY = "needApiKey"
VERBOSE=False

# LEFT  = west-most longitude  (lon_min)
# RIGHT = east-most longitude  (lon_max)
# BOTTOM = south-most latitude (lat_min)
# TOP    = north-most latitude (lat_max)

@dataclass
class Tile:
    south: float
    west: float
    north: float
    east: float
    width_m: float
    height_m: float
    row: int
    col: int
    center_lat: float
    center_lon: float

# ------------------------------------------------------------------------    
# make an API call to given URL and then return result code
# ignoring any returned body for now

from urllib.request import urlopen, Request
from urllib.error import URLError, HTTPError

def get_url_status_code(
    url: str,
    method: str = "GET",
    timeout: float = 15.0,
    data: bytes | None = None,
    headers: dict | None = None,
) -> int:
    """
    Make a basic HTTP(S) request to `url` with the given method and
    return a status code.

    Args:
        url: Full URL to call.
        method: HTTP method, e.g. "GET", "POST", "PUT", "DELETE".
        timeout: Timeout in seconds.
        data: Optional request body as bytes (e.g., b'{}' for JSON).
        headers: Optional dict of HTTP headers.

    Returns:
        - On success: the HTTP status code (e.g., 200, 301, 404, 500, ...).
        - On HTTPError: the HTTP error code (4xx, 5xx).
        - On network errors / DNS / timeout: -1
        - On unexpected exceptions: -2
    """
    if headers is None:
        headers = {}

    method = method.upper().strip()

    # Build Request with explicit method (Python 3.3+ supports this)
    req = Request(url, data=data, headers=headers, method=method)

    try:
        with urlopen(req, timeout=timeout) as resp:
            return resp.getcode()
    except HTTPError as e:
        # Server responded but with an HTTP error (4xx/5xx)
        return e.code
    except URLError:
        # DNS failure, refused connection, timeout, etc.
        return -1
    except Exception:
        # Anything else unexpected
        return -2

# ------------------------------------------------------------------------

# ------------------------------------------------------------------------
# make tiles with spillover; that is, if they exceed our bounding box just
# slightly, don't worry about it.  Tiles are uniformly sized
# order of tile creation is: bottom,left upwards and rightwards

def make_tiles_with_spillover(lon_min: float,
               lat_min: float,
               lon_max: float,
               lat_max: float,
               tile_size_m: float,
               overlap_m: float):
    """
    Create full-size tiles that may extend beyond the bounding box.
    All tiles have equal size (minus floating-point delta).

    Args:
        lon_min: left / west-most longitude (degrees).
        lat_min: bottom / south-most latitude (degrees).
        lon_max: right / east-most longitude (degrees).
        lat_max: top / north-most latitude (degrees).
        tile_size_m: nominal tile width/height in meters (square tiles).
        overlap_m: overlap between neighboring tiles in meters.

    Behavior:
        - All tiles have the same angular size (derived from tile_size_m).
        - Neighboring tiles' *starts* are separated by
          (tile_size_m - overlap_m) in both directions.
        - Tiles are NOT clipped to the bounds; outer tiles may extend beyond
          the user-specified bbox.
        - We generate enough rows/cols so that the grid fully covers the bbox.
    """
    if overlap_m < 0:
        raise ValueError("overlap_m must be >= 0")
    if overlap_m >= tile_size_m:
        raise ValueError("overlap_m must be < tile_size_m")

    # Use mid-latitude of the bbox for lon->km conversion
    center_lat = (lat_min + lat_max) / 2.0

    km_per_deg_lon = km_per_deg_lon_at_lat(center_lat)
    deg_per_km_lat = 1.0 / KM_PER_DEG_LAT
    deg_per_km_lon = 1.0 / km_per_deg_lon if km_per_deg_lon != 0 else 0.0

    # Convert sizes from meters to km
    tile_size_km = tile_size_m / 1000.0
    overlap_km = overlap_m / 1000.0

    # Tile dimensions in degrees (fixed for all tiles)
    tile_height_deg = tile_size_km * deg_per_km_lat
    tile_width_deg  = tile_size_km * deg_per_km_lon

    # Step between tile starts in degrees (tile size minus overlap)
    step_km      = tile_size_km - overlap_km
    step_lat_deg = step_km * deg_per_km_lat
    step_lon_deg = step_km * deg_per_km_lon

    if step_lat_deg <= 0 or step_lon_deg <= 0:
        raise ValueError("Tile step must be positive (check overlap vs tile size).")

    tiles = []

    # How many rows/cols do we need to cover the bbox at least once?
    delta_lat = lat_max - lat_min
    delta_lon = lon_max - lon_min

    # Small epsilon to avoid floating point edge weirdness
    eps = 1e-12

    n_rows = max(1, math.ceil((delta_lat - eps) / step_lat_deg))
    n_cols = max(1, math.ceil((delta_lon - eps) / step_lon_deg))

    for row in range(n_rows):
        cur_south = lat_min + row * step_lat_deg
        cur_north = cur_south + tile_height_deg

        # Height is constant in degrees, so constant in km (approx)
        height_km = tile_height_deg * KM_PER_DEG_LAT
        height_m  = height_km * 1000.0

        for col in range(n_cols):
            cur_west = lon_min + col * step_lon_deg
            cur_east = cur_west + tile_width_deg

            # Width is constant in degrees, so constant in km at center_lat
            width_km = tile_width_deg * km_per_deg_lon
            width_m  = width_km * 1000.0

            center_lat_tile = (cur_south + cur_north) / 2.0
            center_lon_tile = (cur_west + cur_east) / 2.0

            tiles.append(Tile(
                south=cur_south,
                west=cur_west,
                north=cur_north,
                east=cur_east,
                width_m=width_m,
                height_m=height_m,
                row=row,
                col=col,
                center_lat=center_lat_tile,
                center_lon=center_lon_tile,
            ))

    # NOTE: we still return the original requested bbox,
    # not the extended grid extent.
    return tiles, (lat_min, lon_min, lat_max, lon_max)

# ------------------------------------------------------------------------
# calculate and make the tiles given the bounding box
# make tiles w/o spillover
# order of tile creation is: bottom,left upwards and rightwards

def make_tiles_without_spillover(
        lon_min: float,
        lat_min: float,
        lon_max: float,
        lat_max: float,
        tile_size_m: float,
        overlap_m: float):
    """
    Create tiles that are clipped at the provided bounding box edges.
    Tiles at the north/east edge may be smaller than tile_size_m

    Args:
        lon_min: left / west-most longitude (degrees).
        lat_min: bottom / south-most latitude (degrees).
        lon_max: right / east-most longitude (degrees).
        lat_max: top / north-most latitude (degrees).
        tile_size_m: nominal tile width/height in meters.
        overlap_m: overlap between neighboring tiles in meters.

    Tiles are nominally tile_size_m x tile_size_m in meters.
    Neighboring tiles overlap by overlap_m in both directions
    (so the *step* between tile starts is tile_size_m - overlap_m).

    Edges may be smaller if the bounding box edge cuts a tile.
    """
    if overlap_m < 0:
        raise ValueError("overlap_m must be >= 0")
    if overlap_m >= tile_size_m:
        raise ValueError("overlap_m must be < tile_size_m")

    # Use mid-latitude for lon->km conversion
    center_lat = (lat_min + lat_max) / 2.0

    km_per_deg_lon = km_per_deg_lon_at_lat(center_lat)
    deg_per_km_lat = 1.0 / KM_PER_DEG_LAT
    deg_per_km_lon = 1.0 / km_per_deg_lon if km_per_deg_lon != 0 else 0.0

    # Convert sizes from meters to km
    tile_size_km = tile_size_m / 1000.0
    overlap_km = overlap_m / 1000.0

    # Tile dimensions in degrees
    tile_height_deg = tile_size_km * deg_per_km_lat
    tile_width_deg = tile_size_km * deg_per_km_lon

    # Step between tile starts in degrees (tile size minus overlap)
    step_km = tile_size_km - overlap_km
    step_lat_deg = step_km * deg_per_km_lat
    step_lon_deg = step_km * deg_per_km_lon

    tiles = []

    row = 0
    cur_south = lat_min
    eps = 1e-12

    while cur_south < lat_max - eps:
        ideal_north = cur_south + tile_height_deg
        tile_north = min(ideal_north, lat_max)

        height_km = (tile_north - cur_south) * KM_PER_DEG_LAT
        height_m = height_km * 1000.0

        col = 0
        cur_west = lon_min
        while cur_west < lon_max - eps:
            ideal_east = cur_west + tile_width_deg
            tile_east = min(ideal_east, lon_max)

            width_km = (tile_east - cur_west) * km_per_deg_lon
            width_m = width_km * 1000.0

            # calculate center
            tile_center_lat = (cur_south + tile_north) / 2.0
            tile_center_lon = (cur_west + tile_east) / 2.0
            

            tiles.append(Tile(
                south=cur_south,
                west=cur_west,
                north=tile_north,
                east=tile_east,
                width_m=width_m,
                height_m=height_m,
                row=row,
                col=col,
                center_lat=tile_center_lat,
                center_lon=tile_center_lon,
            ))

            cur_west += step_lon_deg
            col += 1

        cur_south += step_lat_deg
        row += 1

    # Keep return format the same: (tiles, (lat_min, lon_min, lat_max, lon_max))
    return tiles, (lat_min, lon_min, lat_max, lon_max)

# ------------------------------------------------------------------------
# helper functions

def km_per_deg_lon_at_lat(lat_deg: float) -> float:
    """
    Approximate kilometers per degree of longitude at a given latitude.
    """
    lat_rad = math.radians(lat_deg)
    return KM_PER_DEG_LAT * math.cos(lat_rad)

def parse_bool(value: str) -> bool:
    value = value.lower().strip()
    if value in ("true", "1", "yes", "y", "on"):
        return True
    if value in ("false", "0", "no", "n", "off"):
        return False
    raise argparse.ArgumentTypeError(
        f"Invalid boolean value '{value}'. Expected true/false."
    )

def fmt_coord(lat, lon):
    return f"{lat:.6f},{lon:.6f}"


def place_label(line_chars, label, target_comma_col):
    """
    Place `label` into `line_chars` so that the comma in the label
    sits at column `target_comma_col` (as closely as possible).
    """
    comma_idx = label.find(',')
    if comma_idx == -1:
        # Fallback: pretend comma is in the middle
        comma_idx = len(label) // 2

    start = target_comma_col - comma_idx
    if start < 0:
        start = 0
    if start + len(label) > LINE_WIDTH:
        start = max(0, LINE_WIDTH - len(label))

    for i, ch in enumerate(label):
        col = start + i
        if 0 <= col < LINE_WIDTH:
            line_chars[col] = ch


def print_ascii_box(lat_min, lon_min, lat_max, lon_max,
                    center_lat, center_lon):
    """
    Print an ASCII box with corner '+' characters and put the
    corner coordinates on lines above/below so that each '+'
    is vertically aligned with the comma in the corresponding
    'lat,lon' label.

    Uses only space characters for padding (no tabs), and includes
    a couple of interior lines so the box isn't too squished.
    """
    nw = (lat_max, lon_min)
    ne = (lat_max, lon_max)
    sw = (lat_min, lon_min)
    se = (lat_min, lon_max)
    c  = (center_lat, center_lon)

    nw_label = fmt_coord(*nw)
    ne_label = fmt_coord(*ne)
    sw_label = fmt_coord(*sw)
    se_label = fmt_coord(*se)
    c_label  = fmt_coord(*c)

    # Top coordinate labels line (NW and NE)
    top_label_line = [' '] * LINE_WIDTH
    place_label(top_label_line, nw_label, PLUS_LEFT_COL)
    place_label(top_label_line, ne_label, PLUS_RIGHT_COL)
    print("".join(top_label_line).rstrip())

    # Top border line
    top_border = [' '] * LINE_WIDTH
    top_border[PLUS_LEFT_COL] = '+'
    for col in range(PLUS_LEFT_COL + 1, PLUS_RIGHT_COL):
        top_border[col] = '-'
    top_border[PLUS_RIGHT_COL] = '+'
    print("".join(top_border).rstrip())

    # Helper: blank interior line with vertical borders
    def print_blank_line():
        line = [' '] * LINE_WIDTH
        line[PLUS_LEFT_COL] = '|'
        line[PLUS_RIGHT_COL] = '|'
        print("".join(line).rstrip())

    # two blank interior line above center
    print_blank_line()
    print_blank_line()    

    # Middle line with center label roughly centered in the box
    middle_line = [' '] * LINE_WIDTH
    middle_line[PLUS_LEFT_COL] = '|'
    middle_line[PLUS_RIGHT_COL] = '|'

    c_start = (PLUS_LEFT_COL + PLUS_RIGHT_COL) // 2 - len(c_label) // 2
    if c_start < PLUS_LEFT_COL + 1:
        c_start = PLUS_LEFT_COL + 1
    if c_start + len(c_label) >= PLUS_RIGHT_COL:
        c_start = max(PLUS_LEFT_COL + 1, PLUS_RIGHT_COL - 1 - len(c_label))

    for i, ch in enumerate(c_label):
        col = c_start + i
        if PLUS_LEFT_COL < col < PLUS_RIGHT_COL:
            middle_line[col] = ch

    print("".join(middle_line).rstrip())

    # Two blank interior line below center
    print_blank_line()
    print_blank_line()    

    # Bottom border line
    bottom_border = [' '] * LINE_WIDTH
    bottom_border[PLUS_LEFT_COL] = '+'
    for col in range(PLUS_LEFT_COL + 1, PLUS_RIGHT_COL):
        bottom_border[col] = '-'
    bottom_border[PLUS_RIGHT_COL] = '+'
    print("".join(bottom_border).rstrip())

    # Bottom coordinate labels line (SW and SE)
    bottom_label_line = [' '] * LINE_WIDTH
    place_label(bottom_label_line, sw_label, PLUS_LEFT_COL)
    place_label(bottom_label_line, se_label, PLUS_RIGHT_COL)
    print("".join(bottom_label_line).rstrip())

# ------------------------------------------------------------------------
# print summary of the tiles we will/would download

def print_summary(tiles, bbox, tile_size_m: float,
                  overlap_m: float,
                  dry_run: bool,
                  center_lat: float,
                  center_lon: float,
                  server_url: str | None):

    global verbose

    lat_min, lon_min, lat_max, lon_max = bbox
    num_tiles = len(tiles)
    num_rows = max((t.row for t in tiles), default=-1) + 1
    num_cols = max((t.col for t in tiles), default=-1) + 1

    height_km = (lat_max - lat_min) * KM_PER_DEG_LAT
    km_per_deg_lon = km_per_deg_lon_at_lat(center_lat)
    width_km = (lon_max - lon_min) * km_per_deg_lon
    area_km2 = width_km * height_km

    print("Bounding box:")
    print(
        "  lat_min, lon_min, lat_max, lon_max: "
        f"{lat_min:.6f}, {lon_min:.6f}, {lat_max:.6f}, {lon_max:.6f}"
    )
    print(
        "Box size: "
        f"width {width_km:,.3f} km, "
        f"height {height_km:,.3f} km"
    )
    print(f"Approx area: {area_km2:,.3f} square km")

    print(f"Requested tile size: {tile_size_m:,.1f} m")
    print(f"Requested overlap:   {overlap_m:,.1f} m")
    print(
        f"Tiles (rows x cols): {num_rows:,} x {num_cols:,} "
        f"(total {num_tiles:,})"
    )

    if tiles:
        min_width = min(t.width_m for t in tiles)
        max_width = max(t.width_m for t in tiles)
        min_height = min(t.height_m for t in tiles)
        max_height = max(t.height_m for t in tiles)

        print(f"Tile width  (m): min {min_width:,.1f}, max {max_width:,.1f}")
        print(f"Tile height (m): min {min_height:,.1f}, max {max_height:,.1f}")
    else:
        print("No tiles generated (check your inputs).")

    if server_url:
        print(f"Server URL: {server_url}")
    else:
        print("Server URL: (none)")

    if verbose:
        print_ascii_box(lat_min, lon_min, lat_max, lon_max,
                        center_lat, center_lon)

# ------------------------------------------------------------------------
# write out a json file containing parameters and all the tiels

def write_json(tiles, bbox,
               center_lat: float,
               center_lon: float,
               tile_size_m: float,
               overlap_m: float,
               server_url: str | None,
               uniform: bool,
               filename: str = "bulkdownload.json"):
    lat_min, lon_min, lat_max, lon_max = bbox

    data = {
        "center": {
            "lat": center_lat,
            "lon": center_lon,
        },
        "tile_size_m": tile_size_m,
        "overlap_m": overlap_m,
        "server_url": server_url,
        "uniform": uniform,
        "bbox": {
            "lat_min": lat_min,
            "lon_min": lon_min,
            "lat_max": lat_max,
            "lon_max": lon_max,
        },
        "tiles": [
            {
                "row": t.row,
                "col": t.col,
                "south": t.south,
                "west": t.west,
                "north": t.north,
                "east": t.east,
                "width_m": t.width_m,
                "height_m": t.height_m,
                "tile_center_lat": t.center_lat,
                "tile_center_lon": t.center_lon,
            }
            for t in tiles
        ],
    }

    with open(filename, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)

    print(f"\nWrote {len(tiles)} tiles to JSON file: {filename}")


# ------------------------------------------------------------------------

def validate_server_url_or_exit(url: str, timeout: float = 15.0) -> str:
    """
    Basic sanity check for the server URL *and* a live HTTP check.

    - Strips whitespace.
    - Requires scheme http or https.
    - Requires a non-empty host.
    - Normalizes by stripping a trailing slash.
    - Issues a GET request to the URL with a short timeout using urllib.
      * Accepts 2xx and 3xx status codes as "OK".
    - Exits the program with an error message if invalid or unreachable.

    Returns the normalized URL string.
    """
    if url is None:
        print("ERROR: server URL is missing.", file=sys.stderr)
        sys.exit(1)

    url = url.strip()
    if not url:
        print("ERROR: server URL is empty.", file=sys.stderr)
        sys.exit(1)

    parsed = urlparse(url)

    if parsed.scheme not in ("http", "https"):
        print(
            "ERROR: server URL must start with http:// or https:// "
            f"(got scheme '{parsed.scheme or 'None'}').",
            file=sys.stderr,
        )
        sys.exit(1)

    if not parsed.netloc:
        print(
            "ERROR: server URL is missing a host "
            "(expected something like https://example.com or "
            "https://example.com/api).",
            file=sys.stderr,
        )
        sys.exit(1)

    # Normalize: strip trailing slash so later joins are easier
    normalized = url.rstrip("/")

    urlstr = normalized + "/api/v1/openathena/up?" + OPENATHENA_API_KEY

    status = get_url_status_code(urlstr,method="GET", timeout=timeout)

    if status == -1:
        print(
            f"ERROR: failed to contact server URL '{normalized}' "
            "(network/DNS/timeout error).",
            file=sys.stderr,
        )
        sys.exit(1)
    elif status == -2:
        print(
            f"ERROR: unexpected error contacting server URL '{normalized}'.",
            file=sys.stderr,
        )
        sys.exit(1)
    elif not (200 <= status < 400):
        print(
            "ERROR: server URL responded with unexpected status code "
            f"{status} (expected 2xx or 3xx).",
            file=sys.stderr,
        )
        sys.exit(1)
    
    if verbose:
        print(f"{normalized} is up")


    # now test to make sure we have admin permissions?
    # don't need to; regular uers can perform POST to request a new Dem be downloaded

    return normalized

# ------------------------------------------------------------------------
# ------------------------------------------------------------------------

def main():
    global OPENATHENA_API_KEY
    global verbose
    
    parser = argparse.ArgumentParser(
        description="Generate a grid of tiles over a square bounding box "
                    "around a center latitude/longitude."
    )
    parser.add_argument("-v", "--verbose",action="store_true",
                        help="Enable verbose output.")

    parser.add_argument(
        "--bounds",
        type=float,
        nargs=4,
        metavar=("LEFT", "BOTTOM", "RIGHT", "TOP"),
        required=True,
        help=(
            "Bounding box as LEFT BOTTOM RIGHT TOP "
            "(lon_min lat_min lon_max lat_max). "
            "LEFT = west-most lon, RIGHT = east-most lon, "
            "BOTTOM = south-most lat, TOP = north-most lat."
        ),
    )
    
    parser.add_argument(
        "--tile-size-m",
        type=float,
        default=DEFAULT_TILE_SIZE_M,
        help=f"Edge length of each tile in METERS (default: {DEFAULT_TILE_SIZE_M:.0f})."
    )
    parser.add_argument(
        "--overlap-m",
        type=float,
        default=0.0,
        help="Overlap between neighboring tiles in METERS (default: 0)."
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print only a summary of the grid (no per-tile listing)."
    )
    parser.add_argument(
        "--json-output",
        action="store_true",
        help="Also write tile data to bulkdownload.json."
    )
    parser.add_argument(
        "--server-url",
        type=str,
        required=True,
        help="Base URL for OpenAthenaCore API server."
    )
    parser.add_argument(
        "--uniform",
        type=parse_bool,
        default=True,  
        help="Whether to use uniform-size tiles that spill past the bounding box "
        "(true) or clipped tiles (false).",
    )
    
    args = parser.parse_args()
    if args.verbose:
        verbose = True
    else:
        verbose = False

    left, bottom, right, top = args.bounds
    lon_min, lat_min, lon_max, lat_max = left, bottom, right, top

    center_lat = (lat_min + lat_max) / 2.0
    center_lon = (lon_min + lon_max) / 2.0

    if args.uniform:
        tiles, bbox = make_tiles_with_spillover(
            lon_min=lon_min,
            lat_min=lat_min,
            lon_max=lon_max,
            lat_max=lat_max,
            tile_size_m=args.tile_size_m,
            overlap_m=args.overlap_m,
        )
    else:
        tiles, bbox = make_tiles_without_spillover(
            lon_min=lon_min,
            lat_min=lat_min,
            lon_max=lon_max,
            lat_max=lat_max,
            tile_size_m=args.tile_size_m,
            overlap_m=args.overlap_m,
        )

    # Always print summary
    print_summary(
        tiles,
        bbox,
        tile_size_m=args.tile_size_m,
        overlap_m=args.overlap_m,
        dry_run=args.dry_run,
        center_lat=center_lat,
        center_lon=center_lon,
        server_url=args.server_url,
    )

    # JSON output if requested
    if args.json_output:
        write_json(
            tiles,
            bbox,
            center_lat=center_lat,
            center_lon=center_lon,
            tile_size_m=args.tile_size_m,
            overlap_m=args.overlap_m,
            server_url=args.server_url,
            uniform=args.uniform,
            filename="bulkdownload.json",
        )

    # pull OA API key from environment variable
    if "OPENATHENA_API_KEY" in os.environ:
        OPENATHENA_API_KEY=os.getenv("OPENATHENA_API_KEY")

    if verbose:
        print(f"Using {OPENATHENA_API_KEY} to communicate with {args.server_url}")
    
    args.server_url = validate_server_url_or_exit(args.server_url)

    # now, loop through the tiles and print out (if verbose)
    # tile info; if dry-run, show what we would post
    # if not dry-run, do the actual post

    for tile in tiles:
        if verbose:
            print(
                f"Tile: r{tile.row} c{tile.col}: "
                f"lat: [{tile.south:.6f}, {tile.north:.6f}], "
                f"lon: [{tile.west:.6f}, {tile.east:.6f}], "
                f"center: ({tile.center_lat:.6f},{tile.center_lon:.6f}), "
                f"size: ≈ {tile.width_m:.1f} m x {tile.height_m:.1f} m"
            )
        urlstr = f"{args.server_url}" + f"/api/v1/openathena/dem?lat={tile.center_lat:.6f}&lon={tile.center_lon:.6f}&len={tile.width_m:.0f}&apikey=" + OPENATHENA_API_KEY
        if args.dry_run == False:
            if verbose:
                print(f"     Going to invoke API to download tile {tile.row}x{tile.col}")
                print(f"     url: {urlstr}")
            status = get_url_status_code(urlstr,method="POST",timeout=25)
            if verbose:
                print(f"     POST returned {status}")
    
# ------------------------------------------------------------------------
# ------------------------------------------------------------------------

if __name__ == "__main__":
    main()
