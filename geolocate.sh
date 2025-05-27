#!/bin/bash

# take a json image metadata file and geolocate via OpenAthenaCore
# REST server

# pull in API key from env variable
API_KEY=$OPENATHENA_API_KEY

JSON_FILE="$1"
HOST="$2"
PORT="$3"

# check to make sure we have all the args we need
if (( $# != 3)); then
    echo "Usage: geolocate.sh image-json-file host port"
    exit
fi

URL="http://$HOST:$PORT/api/v1/openathena/locationsimple?apikey=$API_KEY"

CURL_CMD="curl -s -X POST -H 'Content-Type: application/json' -d @${JSON_FILE} '$URL' "

output=$(eval $CURL_CMD)
exitcode=$?

echo $output
echo

exit $exitcode

