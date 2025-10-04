#!/usr/bin/env bash
# validate_grid_true_minus_gamma.sh
# Usage: ./validate_grid_true_minus_gamma.sh LAT0 LON0 LAT1 LON1
# Requires: geod (from PROJ), GeoConvert (from GeographicLib)

set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 LAT0 LON0 LAT1 LON1" >&2
  exit 1
fi

for cmd in geod GeoConvert awk; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "Error: '$cmd' not found in PATH." >&2
    exit 2
  }
done

LAT0="$1"; LON0="$2"; LAT1="$3"; LON1="$4"

# TRUE azimuth (deg, 0..360)
AZ_TRUE=$(echo "$LAT0 $LON0  $LAT1 $LON1" \
  | geod -I +ellps=WGS84 -f "%.12f" \
  | awk '{a=$1; if(a<0)a+=360; printf("%.12f", a)}')

# Meridian convergence γ at origin (deg, clockwise TRUE→GRID)
GAMMA=$(echo "$LAT0 $LON0" \
  | GeoConvert -c \
  | awk '{printf("%.12f",$1)}')

# GRID = TRUE − γ, normalized to [0,360)
GRID=$(awk -v t="$AZ_TRUE" -v g="$GAMMA" 'BEGIN{
  a=t-g; a=fmod(a,360); if(a<0)a+=360; printf("%.12f", a)
  } function fmod(x,y){return x-y*int(x/y)}')

cat <<EOF
Inputs:
  Origin:  lat=$LAT0  lon=$LON0
  Target:  lat=$LAT1  lon=$LON1

Results (degrees):
  True azimuth:     $AZ_TRUE
  Convergence γ:    $GAMMA
  Grid azimuth:     $GRID
EOF
