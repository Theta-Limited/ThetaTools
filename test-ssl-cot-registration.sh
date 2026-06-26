#!/bin/bash
set -euo pipefail

# HOST=34.133.221.26
HOST=34.123.208.252
PORT=8089

CLIENT_KEYSTORE=client-keystore.p12
CLIENT_KEYSTORE_PASSWORD='client-keystore-password'

# This is the password you want on the extracted private key PEM.
# It may or may not be the same as your Java client key password.
CLIENT_KEY_PASSWORD='client-private-key-password'

TRUSTSTORE=penguin-truststore-root.p12
TRUSTSTORE_PASSWORD='atakatak'
CLIENT_KEYSTORE=penguin-openathena.p12
CLIENT_KEYSTORE_PASSWORD='atakatak'
CLIENT_KEY_PASSWORD='atakatak'

VERIFY_NAME=""

usage() {
  echo "Usage: $0 [--verify-name hostname-or-ip]"
  echo
  echo "Examples:"
  echo "  $0"
  echo "  $0 --verify-name 34.133.221.26"
  echo "  $0 --verify-name opentakserver"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --verify-name)
      if [[ $# -lt 2 ]]; then
        echo "Error: --verify-name requires an argument" >&2
        usage >&2
        exit 2
      fi
      VERIFY_NAME="$2"
      shift 2
      ;;

    -h|--help)
      usage
      exit 0
      ;;

    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

iso_now_utc() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

iso_plus_five_minutes_utc() {
  if date -u -d '+5 minutes' +"%Y-%m-%dT%H:%M:%SZ" >/dev/null 2>&1; then
    date -u -d '+5 minutes' +"%Y-%m-%dT%H:%M:%SZ"
  else
    # macOS/BSD date fallback
    date -u -v+5M +"%Y-%m-%dT%H:%M:%SZ"
  fi
}

rm -f client-cert.pem client-key.pem trust-ca.pem penguin-server-cert.pem

openssl pkcs12 \
  -legacy \
  -in "$CLIENT_KEYSTORE" \
  -clcerts \
  -nokeys \
  -out client-cert.pem \
  -passin "pass:$CLIENT_KEYSTORE_PASSWORD"

openssl pkcs12 \
  -legacy \
  -in "$CLIENT_KEYSTORE" \
  -nocerts \
  -out client-key.pem \
  -passin "pass:$CLIENT_KEYSTORE_PASSWORD" \
  -passout "pass:$CLIENT_KEY_PASSWORD"

openssl pkcs12 \
  -legacy \
  -in "$TRUSTSTORE" \
  -nokeys \
  -out trust-ca.pem \
  -passin "pass:$TRUSTSTORE_PASSWORD"

openssl s_client \
  -connect "$HOST:$PORT" \
  -servername "$HOST" \
  -CAfile trust-ca.pem \
  -verify_return_error \
  </dev/null 2>/dev/null \
| openssl x509 \
  -outform PEM \
  -out penguin-server-cert.pem

VERIFY_ARGS=()

if [[ -n "$VERIFY_NAME" ]]; then
  if [[ "$VERIFY_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    VERIFY_ARGS=(-verify_ip "$VERIFY_NAME")
  else
    VERIFY_ARGS=(-verify_hostname "$VERIFY_NAME")
  fi

  echo "TLS name verification enabled for: $VERIFY_NAME" >&2
else
  echo "TLS certificate chain verification enabled; hostname/IP verification disabled" >&2
fi

NOW_UTC="$(iso_now_utc)"
STALE_UTC="$(iso_plus_five_minutes_utc)"
IMAGE_TIME="2018-03-15T19:23:14Z"

# Stable OpenTAKServer sender/EUD identity. This should not change per target.
REG_UID="OpenAthena-November01"
REG_CALLSIGN="HarryTuttle"
#REG_CALLSIGN="OpenAthena-November01"
#REG_GROUP="__ANON__"
# REG_GROUP="__Orange__"
REG_GROUP="__Yellow__"

# Drone/source platform location from the target CoT's OpenAthena calculation info.
DRONE_LAT="48.31847449918336"
DRONE_LON="14.248001279105155"
DRONE_HAE="505.58830601759547"

# TARGET_UID="OpenAthena-HarryTuttle-1185"
TARGET_UID="OpenAthena-HNovember01"
TARGET_CALLSIGN="HarryTuttle"

# Registration/presence CoT. This establishes the stable sender/EUD before the target CoT.
REGISTRATION_MESSAGE="$(cat <<EOF_REG
<?xml version="1.0" encoding="UTF-8" standalone="no"?><event version="2.0" uid="${REG_UID}" type="a-f-G-U-C" how="m-g" time="${NOW_UTC}" start="${NOW_UTC}" stale="${STALE_UTC}" access="Undefined"><point lat="${DRONE_LAT}" lon="${DRONE_LON}" hae="${DRONE_HAE}" ce="9999999.0" le="9999999.0"/><detail><takv os="Java" version="test-script" device="OpenAthenaCore" platform="OpenAthena"/><contact callsign="${REG_CALLSIGN}"/><uid Droid="${REG_UID}"/><precisionlocation altsrc="GPS" geopointsrc="GPS"/><__group name="${REG_GROUP}" role="Team Member"/><status battery="100"/><track course="0.0" speed="0.0"/></detail></event>
EOF_REG
)"

# Target CoT. start/stale are refreshed; time remains the original image capture time.
# No <link> element is added here.
MESSAGE="$(cat <<EOF_MSG
<?xml version="1.0" encoding="UTF-8" standalone="no"?><event how="h-c" stale="${STALE_UTC}" start="${NOW_UTC}" time="${IMAGE_TIME}" type="a-p-G" uid="${TARGET_UID}" version="2.0"><point ce="10.17210452302702" hae="440.51049020254504" lat="48.31720814374078" le="5.9" lon="14.249464092019835"/><detail><precisionlocation altsrc="DTED2" geopointsrc="GPS"/><contact callsign="${TARGET_CALLSIGN}"/><openAthenaCalculationInfo EGM96Offset="45.690357747098254" azimuthOffsetUserCorrection="0.0" cameraRollAngleDeg="-6.119539261" cameraSlantAngleDeg="3.130533218" cottype="a-p-G" cotuid="${TARGET_UID}" dataset="EUDTM" demFilename="DEM_LatLon_48.228396_14.1129_48.408394_14.38358.eudtm" digitalZoomRatio="1.0" droneElevationHAE="505.58830601759547" droneLatitude="48.31847449918336" droneLongitude="14.248001279105155" elevationDataModel="Continental Europe Digital Terrain Model 30m" f_x="1307.142988065971" f_y="1307.142988065971" finalTheta="20.140722904370936" focalLength="1.8300001180412324" focalLength35="6.0" geoidOffset="45.690357747098254" gimbalPitchDegree="3.130533218" gimbalYawDegree="147.263885498" gtype="EUDTM" imageFilename="Bebop2_20180315152314+0100.jpg" imageHeight="3320.0" imageSelectedProportionX="0.4572192615366237" imageSelectedProportionY="0.6935160427807486" imageWidth="4096.0" isCameraModelRecognized="true" lensType="fisheye" make="parrot" model="bebop 2" pitchOffsetDegSelectedPoint="-17.010189686370936" raySlantAngleDeg="20.140722904370936" slantRange="189.31171242411614" terrainAltUnderDroneHAE="460.6447170180089" yawOffsetDegSelectedPoint="-4.794025281470469"/><remarks>${TARGET_UID} generated by OpenAthenaCore from sUAS data</remarks></detail></event>
EOF_MSG
)"

echo "Registration CoT:" >&2
echo "$REGISTRATION_MESSAGE" >&2
echo >&2
echo "Target CoT:" >&2
echo "$MESSAGE" >&2
echo >&2
echo "Sending registration CoT followed by target CoT on one TLS connection to $HOST:$PORT" >&2

# Use printf, not echo, so we do not add extra newline delimiters.
# Send registration first, then target, over one TLS connection.
{
  printf '%s' "$REGISTRATION_MESSAGE"
  printf '%s' "$MESSAGE"
} | openssl s_client \
  -connect "$HOST:$PORT" \
  -servername "$HOST" \
  "${VERIFY_ARGS[@]}" \
  -CAfile trust-ca.pem \
  -cert client-cert.pem \
  -key client-key.pem \
  -pass "pass:$CLIENT_KEY_PASSWORD" \
  -showcerts \
  -quiet \
  -no_ign_eof \
  -state \
  -msg \
  -no_ticket \
  -verify_return_error
