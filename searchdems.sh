#!/bin/bash

# search dem cache
# REST server

# pull in API key from env variable
API_KEY=$OPENATHENA_API_KEY

HOST="$1"
PORT="$2"
LAT="$3"
LON="$4"
DATASET="$5"

# check to make sure we have all the args we need
if (( $# != 5)); then
    echo "Usage: searchdems.sh host port lat lon dataset"
    exit
fi

URL="http://$HOST:$PORT/api/v1/openathena/demlist?lat=$LAT&lon=$LON&dataset=$DATASET&apikey=$API_KEY"

CURL_CMD="curl -s $URL"

echo "Command is: $CURL_CMD"

output=$(curl -s "$URL")
exitcode=$?

echo $output
echo

exit $exitcode
