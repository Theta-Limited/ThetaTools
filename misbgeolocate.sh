#!/bin/bash

# take a MISB json image metadata file and geolocate via OpenAthenaCore
# REST server

# pull in API key from env variable
API_KEY=$OPENATHENA_API_KEY
ENDPOINT="locationmisb"

if [[ "$1" == "-simple" ]]; then
    ENDPOINT="locationmisbsimple"
    shift
fi

JSON_FILE="$1"
HOST="$2"
PORT="$3"
URL="http://$HOST:$PORT/api/v1/openathena/$ENDPOINT?apikey=$API_KEY"

# check to make sure we have all the args we need
if (( $# != 3)); then
    echo "Usage: misbgeolocate.sh [-simple] image-json-file host port"
    exit
fi

CURL_CMD="curl -s -X POST -H 'Content-Type: application/json' -d @${JSON_FILE} '$URL' "

echo $CURL_CMD

output=$(eval $CURL_CMD)
exitcode=$?

echo $output
echo

exit $exitcode

