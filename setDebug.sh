#!/bin/bash

# Set OACore debug value

# pull in API key from env variable
# check to make sure we have all the args we need

if (( $# != 3)); then
    echo "Usage: setDebug host port true|false"
    exit
fi

HOST="$1"
PORT="$2"
BOOL="$3"

API_KEY=$OPENATHENA_API_KEY

URL="http://$HOST:$PORT/api/v1/openathena/admin/debug?apikey=$API_KEY"

curl_cmd="curl -X PUT -H \"Content-Type: application/json\" -d '{\"value\":\"$BOOL\"}' \"$URL\""

echo "Executing command: $curl_cmd"

output=$(eval $curl_cmd)
echo "$output" | jq 'to_entries | sort_by(.key) | from_entries'
echo
