#!/bin/bash

# nonsensical query
# REST server

# pull in API key from env variable
API_KEY=$OPENATHENA_API_KEY

HOST="$1"
PORT="$2"

# check to make sure we have all the args we need
if (( $# != 2)); then
    echo "Usage: nonsensequery.sh host port"
    exit
fi

URL="http://$HOST:$PORT/api/v1/openathena/foobar?apikey=$API_KEY"

CURL_CMD="curl -s $URL"

echo "Command is: $CURL_CMD"

output=$(curl -s "$URL")
exitcode=$?

echo $output
echo

exit $exitcode
