#!/usr/bin/env bash
# validate_grid_utm_step.sh
# Usage: ./validate_grid_utm_step.sh LAT0 LON0 LAT1 LON1 [STEP_M=1]
# Requires: geod, cs2cs (both from PROJ); awk

set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 ]]; then
  echo "Usage: $0 LAT0 LON0 LAT1 LON1 [STEP_M]" >&2
  exit 1
fi

for cmd in geod cs2cs awk; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "Error: '$cmd' not found in PATH." >&2
    exit 2
  }
done

LAT0="$1"; LON0="$2"; LAT1="$3"; LON1="$4"
STEP="${5:-1}"   # meters

# Helpers (bash/awk)
norm360() { awk -v x="$1" 'BEGIN{y=x%360; if(y<0)y+=360; printf("%.12f", y)}'; }

# 1) TRUE azimuth origin->target (deg)
AZ_TRUE=$(echo "$LAT0 $LON0  $LAT1 $LON1" \
  | geod -I +ellps=WGS84 -f "%.12f" \
  | awk '{a=$1; if(a<0)a+=360; printf("%.12f", a)}')

# 2) Forward step from origin along TRUE azimuth by STEP meters → (LATs, LONs)
read LATs LONs _ < <(echo "$LAT0 $LON0 $AZ_TRUE $STEP" \
  | geod +ellps=WGS84 -f "%.12f")

# 3) Compute UTM zone with Norway/Svalbard exceptions (origin-based)
ZONE=$(awk -v lon="$LON0" -v lat="$LAT0" 'function floor(x){return int(x)-(x<0 && x!=int(x))}
  BEGIN{
    z=floor((lon+180)/6)+1;
    if(lat>=56 && lat<64 && lon>=3 && lon<12) z=32;         # Norway
    if(lat>=72 && lat<84){                                  # Svalbard
      if(lon>=0  && lon<9 ) z=31; else if(lon>=9  && lon<21) z=33;
      else if(lon>=21 && lon<33) z=35; else if(lon>=33 && lon<42) z=37;
    }
    if(z<1) z=1; if(z>60) z=60;
    print z;
  }')

HEMI=$(awk -v lat="$LAT0" 'BEGIN{print (lat>=0) ? "+north" : "+south"}')

# 4) Project origin and step to UTM (meters)
read E0 N0 _ < <(echo "$LON0 $LAT0" \
  | cs2cs +proj=longlat +datum=WGS84 +to +proj=utm +zone="$ZONE" $HEMI +datum=WGS84 +units=m +no_defs)
read Es Ns _ < <(echo "$LONs $LATs" \
  | cs2cs +proj=longlat +datum=WGS84 +to +proj=utm +zone="$ZONE" $HEMI +datum=WGS84 +units=m +no_defs)

# 5) Grid azimuth = atan2(ΔE, ΔN) in degrees, normalized to [0,360)
GRID=$(awk -v e0="$E0" -v n0="$N0" -v es="$Es" -v ns="$Ns" \
  'BEGIN{de=es-e0; dn=ns-n0; a=atan2(de,dn)*180/atan2(0,-1); if(a<0)a+=360; printf("%.12f", a)}')

cat <<EOF
Inputs:
  Origin:  lat=$LAT0  lon=$LON0
  Target:  lat=$LAT1  lon=$LON1
  Step:    $STEP m

Results (degrees):
  True azimuth:     $AZ_TRUE
  Grid azimuth:     $GRID

UTM context:
  Zone:             $ZONE
  Hemisphere:       $( [[ "$HEMI" == "+north" ]] && echo "N" || echo "S" )
EOF
