#!/bin/bash

# get the terrain alt of lat,lon from REST API server

# pull in API key from env variable
API_KEY=$OPENATHENA_API_KEY

LAT="$1"
LON="$2"
HOST="$3"
PORT="$4"

# check to make sure we have all the args we need
if (( $# != 4)); then
    echo "Usage: terrainAlt.sh lat lon host port"
    exit
fi

URL="http://$HOST:$PORT/api/v1/openathena/dem/alt?lat={$LAT}&lon={$LON}&apikey=$API_KEY"

CURL_CMD="curl -s -X POST -H 'Content-Type: application/json' '$URL' "

output=$(eval $CURL_CMD)
echo $output
echo
