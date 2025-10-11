#!/bin/bash

# look up an altitude lat,lon and optionally specify a GeoTiffDataType

# pull in API key from env variable
API_KEY=$OPENATHENA_API_KEY

if [[ $# -ne 4 && $# -ne 5 ]]; then
    echo "Usage: lookupAtltidue.sh host port lat lon {gtype}"
    exit 1
fi

HOST="$1"
PORT="$2"
LAT="$3"
LON="$4"
GTYPE=""
URL="https://$HOST:$PORT/api/v1/openathena/dem/alt?lat=$LAT&lon=$LON"

if [[ $# -eq 5 ]]; then
    GTYPE="$5"
    URL+="&gtype=$GTYPE"
fi
URL+="&apikey=$API_KEY"

echo "Going to post $URL"

# CURL_CMD="curl -s -X POST '$URL' "
CURL_CMD="curl --connect-timeout 2 -X POST '$URL' "

output=$(eval $CURL_CMD)
exitcode=$?

echo $output
echo
exit $exitcode
